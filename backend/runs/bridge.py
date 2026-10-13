#!/usr/bin/env python3
"""
Jeeves Frida sidecar.

The backend (FridaBridge.java) launches the explicitly configured runner Python with `bridge.py` and speaks newline-delimited JSON
over stdio:
  request   {"id": <n>, "op": "<name>", ...params}
  response  {"id": <n>, "ok": true, ...result}  |  {"id": <n>, "ok": false, "error": "..."}
  event     {"event": "hit"|"device"|"session"|"log", ...}   (no id — async, from Frida threads)

It wraps adb (device discovery, app list, owned installed-server sessions) and the frida package
(instrumentation), multiplexing N device sessions. Frida has no JVM host binding, so this Python process
is the controlling side; the in-target work lives in _agent.js. The operator selects a server matching the device architecture.

Standalone-drivable for poking:  echo '{"id":1,"op":"ping"}' | venv/bin/python bridge.py
"""

import json
import os
import re
import shlex
import subprocess
import sys
import threading
import time

import frida

from lifecycle import Lifecycle

from dalvik import parse_method_signature

HERE = os.path.dirname(os.path.abspath(__file__))
AGENT_JS = os.path.join(HERE, "_agent.js")
NATIVE_AGENT_JS = os.path.join(HERE, "_native_agent.js")   # native breakpoints (decision 2026-09-10)


def _is_native(h):
    """A hook spec is native when it carries native:true (or a module + no Java signature)."""
    return bool(h.get("native")) or ("module" in h and "signature" not in h)


def _native_spec(h):
    """The {hookId, module, export?, offset?, buildId?} the native agent's addNativeHook wants."""
    spec = {"hookId": h["hookId"], "module": h["module"]}
    if h.get("export") not in (None, ""):
        spec["export"] = h["export"]
    if h.get("offset") is not None:
        spec["offset"] = h["offset"]
    if h.get("buildId") not in (None, ""):
        spec["buildId"] = h["buildId"]     # agent refuses to bind if the loaded module's build-id differs
    return spec


def _native_arm_note(session_id, spec, res):
    """Surface a build-id refusal/unverified from the IMMEDIATE add path (already-loaded module); the
    deferred watchDlopen path emits its own native-mismatch/native-unverified from inside the agent."""
    if not isinstance(res, dict):
        return
    if res.get("mismatch"):
        emit("native", session=session_id, kind="native-mismatch", hookId=spec.get("hookId"),
             module=spec.get("module"), expected=res.get("expected"), actual=res.get("actual"))
    elif res.get("unverified"):
        emit("native", session=session_id, kind="native-unverified", hookId=spec.get("hookId"),
             module=spec.get("module"))


def _ensure_native_script(sess, session, session_id):
    """Lazily load _native_agent.js as a SECOND script on the session (native hooks live there, not
    in the minified Java agent). Its hits ride the same message channel the Java agent uses."""
    if sess.get("native_script") is not None:
        return sess["native_script"]
    ns = session.create_script(open(NATIVE_AGENT_JS, "r", encoding="utf-8").read())
    sess["native_script"] = ns          # track before load() so detach can unload even if load throws
    try:
        ns.on("message", _on_message_factory(session_id))
        ns.load()
    except Exception:
        sess["native_script"] = None    # unusable — clear the slot so a later call retries cleanly (no dupes)
        try: ns.unload()
        except Exception: pass
        raise
    return ns
ADB = os.environ.get("ADB") or os.environ.get("WORKBENCH_ADB") or next(   # same overrides as DeviceManager
    (p for p in (os.path.expanduser("~/Library/Android/sdk/platform-tools/adb"),   # Android Studio, macOS
                 os.path.expanduser("~/Android/Sdk/platform-tools/adb"),           # Android Studio, Linux
                 os.path.join(os.environ.get("ANDROID_HOME") or "/nonexistent", "platform-tools", "adb"),
                 os.path.join(os.environ.get("ANDROID_SDK_ROOT") or "/nonexistent", "platform-tools", "adb"))
     if os.path.isfile(p)),
    os.path.expanduser("~/Library/Android/sdk/platform-tools/adb"))
if not os.path.exists(ADB):
    ADB = "adb"  # fall back to PATH
FRIDA_VER = frida.__version__
# A local operator installs and selects the matching server. Jeeves never pushes
# a vendored executable or changes Android security/freezer/ADB settings.
SERVER_PORT = int(os.environ.get("JEEVES_FRIDA_PORT", "27183"))
_STATE = os.environ.get("WORKBENCH_RUNS_STATE") or os.path.join(
    os.environ.get("WORKBENCH_HOME") or os.path.expanduser("~/.local/state/jeeves"), "runs", "ownership")
_lifecycle = Lifecycle(ADB, FRIDA_VER, _STATE)

_out_lock = threading.Lock()
_sessions = {}          # sessionId -> {device, package, pid, session, script, hooks:{hookId:signature}}
_remote_devs = {}       # serial -> cached frida remote Device (reaches our server through the forward)
_seq = 0

_lifecycle_lock = threading.RLock()

# ── stdio ────────────────────────────────────────────────────────────────────────────────────────
def _write(obj):
    with _out_lock:
        sys.stdout.write(json.dumps(obj) + "\n")
        sys.stdout.flush()


def emit(event, **kw):
    kw["event"] = event
    _write(kw)


def respond(req_id, ok, **kw):
    kw["id"] = req_id
    kw["ok"] = ok
    _write(kw)


def log(*a):
    print("[frida-bridge]", *a, file=sys.stderr, flush=True)


def _sid():
    global _seq
    _seq += 1
    return "fs%d" % _seq


# ── adb helpers ──────────────────────────────────────────────────────────────────────────────────
def adb(*args, serial=None, timeout=20):
    cmd = [ADB]
    if serial:
        cmd += ["-s", serial]
    cmd += list(args)
    # stdin=DEVNULL: a remote command that reads stdin (e.g. a su that drops to an interactive shell)
    # gets EOF instead of blocking until `timeout` — a real hang we hit on a KernelSU device.
    p = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, stdin=subprocess.DEVNULL)
    return p.returncode, p.stdout, p.stderr


def _owned(serial):
    row = _lifecycle.owned.get(serial)
    if row is None:
        raise RuntimeError("No owned device session; explicitly provision or import external evidence")
    _lifecycle.verify(row)
    return row


def _reset_remote(serial):
    _remote_devs.pop(serial, None)
    row = _lifecycle.owned.get(serial)
    if row and row.get("forward"):
        try:
            frida.get_device_manager().remove_remote_device("127.0.0.1:%d" % row["forward"])
        except Exception:
            pass


def _frida_device(serial):
    row = _owned(serial)
    dev = _remote_devs.get(serial)
    if dev is None:
        dev = frida.get_device_manager().add_remote_device("127.0.0.1:%d" % row["forward"])
        _lifecycle.verify(row)  # a replaced forward/listener must not become a foreign attachment
        _remote_devs[serial] = dev
    return dev


def adb_serials():
    rc, out, _ = adb("devices")
    serials = []
    for line in out.splitlines()[1:]:
        line = line.strip()
        if not line or "\t" not in line:
            continue
        serial, state = line.split("\t", 1)
        if state.strip() == "device":
            serials.append(serial)
    return serials


def getprop(serial, prop):
    rc, out, _ = adb("shell", "getprop", prop, serial=serial)
    return out.strip() if rc == 0 else ""


def device_info(serial):
    abi = getprop(serial, "ro.product.cpu.abi")
    info = {
        "serial": serial,
        "model": getprop(serial, "ro.product.model") or getprop(serial, "ro.product.device"),
        "android": getprop(serial, "ro.build.version.release"),
        "sdk": getprop(serial, "ro.build.version.sdk"),
        "abi": abi,
        "arm64": abi == "arm64-v8a",
        "emulator": getprop(serial, "ro.kernel.qemu") == "1" or serial.startswith("emulator-"),
        "adbdRoot": adb("shell", "id -u", serial=serial)[1].strip() == "0",
    }
    info["fridaServer"] = frida_server_status(serial)
    return info


def frida_server_status(serial):
    row = _lifecycle.owned.get(serial)
    if row is None:
        return {"running": False, "version": None, "ownership": "unclaimed",
                "note": "Foreign servers are not adopted; explicit setup is required"}
    try:
        _lifecycle.verify(row)
        return {"running": True, "version": row["server"]["version"], "ownership": "verified-owned"}
    except Exception:
        return {"running": False, "version": None, "ownership": "unknown", "cleanup": row["cleanup"]}


# ── ops ──────────────────────────────────────────────────────────────────────────────────────────
def op_ping(_):
    return {"pong": True, "frida": FRIDA_VER, "serverProvisioning": "explicit-installed-path-only"}


def op_enumerate_devices(_):
    devices = []
    for serial in adb_serials():
        try:
            devices.append(device_info(serial))
        except Exception as e:
            devices.append({"serial": serial, "error": str(e)})
    return {"devices": devices}


def op_list_apps(params):
    serial = params["device"]
    third_party = params.get("thirdParty", True)
    args = ["shell", "pm", "list", "packages"]
    if third_party:
        args.append("-3")
    rc, out, err = adb(*args, serial=serial)
    if rc != 0:
        raise RuntimeError("pm list failed: " + err)
    apps = [{"package": ln.split(":", 1)[1].strip()} for ln in out.splitlines() if ln.startswith("package:")]
    return {"apps": apps, "count": len(apps)}


def op_provision(params):
    serial = params["device"]
    try:
        row = _lifecycle.provision(serial, params.get("serverPath"), params.get("ownershipConfirmed"),
                                   params.get("serverPort", SERVER_PORT))
    except Exception as error:
        row = _lifecycle.owned.get(serial)
        if row:
            result = _lifecycle.cleanup(row)
            receipt = str(_lifecycle.directory / (row['controller_id'] + '.json'))
            raise RuntimeError("Provision failed; cleanup=" + result['cleanup'] + "; ownership receipt=" + receipt) from error
        pending=[]
        if _lifecycle.directory.exists():
            for path in _lifecycle.directory.glob('*.json'):
                try:
                    saved=json.loads(path.read_text())
                    if saved.get('device')==serial and saved.get('cleanup')!='complete': pending.append(str(path))
                except (OSError,ValueError): pass
        if pending: raise RuntimeError(str(error)+"; retained ownership receipts="+json.dumps(pending[:10])) from error
        raise
    return {"provisioned": True, "ownership": row}


def op_cleanup(params):
    serial = params["device"]
    detached = []
    for sid, sess in list(_sessions.items()):
        if sess["device"] == serial:
            detached.append(op_detach({"session": sid}))
    _reset_remote(serial)
    row = _lifecycle.owned.get(serial)
    result = _lifecycle.cleanup(row) if row else _lifecycle.last_cleanup.get(serial,{"cleanup": "not-applicable"})
    if any(item.get("cleanup") != "complete" for item in detached):
        result = dict(result)
        result["cleanup"] = "unknown"
        result["cleanup_results"] = list(result.get("cleanup_results", [])) + [{"resource":"instrumentation-session","state":"unknown"}]
    return {"cleanup": result, "sessions": detached}


def _on_message_factory(session_id):
    def on_message(message, data):
        if message.get("type") == "send":
            payload = message.get("payload") or {}
            ptype = payload.get("type")
            if ptype == "hit":
                emit("hit", session=session_id, **{k: v for k, v in payload.items() if k != "type"})
            elif ptype == "hitcount":     # rate-cap heartbeat for a hot hook (dropped full snapshots)
                emit("hitcount", session=session_id, **{k: v for k, v in payload.items() if k != "type"})
            elif ptype and ptype.startswith("native-"):   # armed / mismatch / unverified / armfail (native bp)
                emit("native", session=session_id, kind=ptype, **{k: v for k, v in payload.items() if k != "type"})
            elif ptype in ("hiterr", "ready"):
                emit("agent", session=session_id, **payload)
        elif message.get("type") == "error":
            emit("agent_error", session=session_id, error=message.get("description"),
                 stack=message.get("stack"))
    return on_message


def _resolve_spec(hook_id, signature, statics=False):
    m = parse_method_signature(signature)
    return {
        "hookId": hook_id,
        "className": m["className"],
        "fridaMethod": m["fridaMethod"],
        "paramTypes": m["paramTypes"],
        "isStaticInit": m["isStaticInit"],
        "statics": bool(statics),        # opt-in per BP: also snapshot the class's static fields on a hit
    }


def _running_pid(dev, package, serial=None):
    """The pid of a running app by PACKAGE id (frida's attach(name) matches the process *name*, not the
    package — so we resolve via enumerate_applications). FALLBACK: `pidof` on the device — frida's
    enumerate_applications can miss packages entirely (seen on an Android 16 emulator with a GMS module
    where frida listed 34 of ~200 packages), while pidof is authoritative. 0/None if not running."""
    try:
        for app in dev.enumerate_applications():
            if app.identifier == package and app.pid:
                return app.pid
    except Exception:
        pass
    if serial:
        try:
            rc, out, _ = adb("shell", "pidof", package, serial=serial, timeout=8)
            if rc == 0 and out.strip().isdigit():
                return int(out.strip())
        except Exception:
            pass
    return None


def op_attach(params):
    serial = params["device"]
    package = params["package"]
    spawn = params.get("spawn", False)
    hooks = params.get("hooks", [])         # [{hookId, signature}]
    if params.get("coldBroadcast"):
        raise RuntimeError("Automatic force-stop/broadcast was retired; perform explicit student actions and mark them")
    if any(s["device"] == serial for s in _sessions.values()):
        raise RuntimeError("This device already has an owned controller session; stop it before attaching again")
    row = _owned(serial)
    phone = _lifecycle.verify(row)
    dev = _frida_device(serial)
    pid = _running_pid(dev, package, serial)
    if spawn:
        if pid:
            raise RuntimeError("App already running; explicit attach is required, no force-stop is performed")
        pid = dev.spawn([package])
    elif not pid:
        raise RuntimeError("Selected app is not running; start it explicitly, no automatic spawn or replay")
    process_identity = phone.identity(pid)
    observations = None if spawn else phone.foreground(package, pid)
    try:
        session = dev.attach(pid)
    except Exception:
        if spawn:
            try: dev.resume(pid)
            except Exception: pass
        raise

    session_id = _sid()

    def _on_detached(reason, crash=None, _sid=session_id):
        # The frida session died underneath us (process exit, kill, server restart). Mark it dead on the
        # event channel and drop it from the registry so later ops (add_hook/detach/sessions) don't treat
        # it as live. Only emit when the session was still registered — op_detach pops it BEFORE calling
        # session.detach(), so an orderly detach is announced there (once), not here.
        s = _sessions.pop(_sid, None)
        if s is not None:
            emit("session", session=_sid, state="dead", reason=str(reason), completion="partial", cleanup="unknown")

    session.on("detached", _on_detached)
    script = None
    try:
        with open(AGENT_JS, "r", encoding="utf-8") as f:
            script = session.create_script(f.read())
        script.on("message", _on_message_factory(session_id))
        script.load()
    except Exception:
        if script is not None:
            try: script.unload()
            except Exception: pass
        try: session.detach()
        except Exception: pass
        if spawn:
            try: dev.resume(pid)
            except Exception: pass
        raise

    # Install the requested hooks. On a fresh SPAWN the app classloader isn't up yet, so the agent DEFERS
    # each hook (frida-java-bridge flushes the install when ActivityThread.handleBindApplication runs — i.e.
    # right after the resume() below) and returns {deferred:true} with no overloads yet. That used to come
    # back as None and crash here on res.get('overloads') — the F4 spawn bug. `or {}` is belt-and-suspenders.
    sess = {"device": serial, "package": package, "pid": pid, "session": session,
            "script": script, "native_script": None, "hooks": {},
            "processIdentity": process_identity, "observations": observations}
    installed = []
    for h in hooks:
        try:
            if _is_native(h):                          # native BP → the native agent (2nd script)
                ns = _ensure_native_script(sess, session, session_id)
                res = ns.exports_sync.add_native_hook(_native_spec(h)) or {}
                _native_arm_note(session_id, h, res)
                ok = res.get("ok", True)
                rec = {"hookId": h["hookId"], "ok": bool(ok), "native": True,
                       "addr": res.get("addr"), "deferred": bool(res.get("deferred"))}
                if ok is False:
                    rec["error"] = res.get("reason")
                installed.append(rec)
                sess["hooks"][h["hookId"]] = "native:" + h["module"] + "!" + \
                    (h.get("export") or ("+" + str(h.get("offset"))))
            else:
                spec = _resolve_spec(h["hookId"], h["signature"], h.get("statics"))
                res = script.exports_sync.add_hook(spec) or {}
                installed.append({"hookId": h["hookId"], "ok": True,
                                  "overloads": res.get("overloads"), "deferred": bool(res.get("deferred"))})
                sess["hooks"][h["hookId"]] = h["signature"]
        except Exception as e:
            installed.append({"hookId": h["hookId"], "ok": False, "error": str(e)})

    if spawn:
        try:
            dev.resume(pid)             # let the app start → the deferred hooks arm as its classloader comes up
        except Exception as e:
            # A failed resume leaves the app suspended forever — do NOT report success. Tear down the
            # attach (script first, then session; the detached handler is a no-op here since the session
            # was never registered) and fail the op loudly.
            try:
                script.unload()
            except Exception:
                pass
            try:
                session.detach()
            except Exception:
                pass
            raise RuntimeError("resume(pid=%d) failed — app left suspended; attach aborted: %s" % (pid, e))

    _sessions[session_id] = sess
    emit("session", session=session_id, device=serial, package=package, state="live")
    return {"session": session_id, "pid": pid, "spawned": spawn, "hooks": installed,
            "processIdentity": process_identity, "observations": observations, "ownership": row}


def op_add_hook(params):
    s = _sessions[params["session"]]
    phone = _lifecycle.verify(_owned(s["device"]))
    if phone.identity(s["pid"]) != s["processIdentity"]:
        raise RuntimeError("Application process identity changed; no hook change performed")
    if _is_native(params):
        ns = _ensure_native_script(s, s["session"], params["session"])
        res = ns.exports_sync.add_native_hook(_native_spec(params)) or {}
        _native_arm_note(params["session"], params, res)
        s["hooks"][params["hookId"]] = "native:" + params["module"] + "!" + \
            (params.get("export") or ("+" + str(params.get("offset"))))
        return {"hookId": params["hookId"], "native": True, "addr": res.get("addr"),
                "deferred": bool(res.get("deferred")), "ok": bool(res.get("ok", True))}
    spec = _resolve_spec(params["hookId"], params["signature"], params.get("statics"))
    res = s["script"].exports_sync.add_hook(spec)
    s["hooks"][params["hookId"]] = params["signature"]
    return {"hookId": params["hookId"], "overloads": res.get("overloads")}


def op_remove_hook(params):
    s = _sessions[params["session"]]
    phone = _lifecycle.verify(_owned(s["device"]))
    if phone.identity(s["pid"]) != s["processIdentity"]:
        raise RuntimeError("Application process identity changed; no hook change performed")
    hid = params["hookId"]
    sig = s["hooks"].get(hid, "")
    if isinstance(sig, str) and sig.startswith("native:") and s.get("native_script"):
        res = s["native_script"].exports_sync.remove_native_hook(hid)
    else:
        res = s["script"].exports_sync.remove_hook(hid)
    s["hooks"].pop(hid, None)
    return {"removed": res.get("removed", False)}


def op_detach(params):
    sid = params["session"]
    s = _sessions.pop(sid, None)
    if not s:
        return {"detached": False}
    failures = []
    for resource in (s.get("native_script"), s.get("script"), s.get("session")):
        if resource is None:
            continue
        try:
            resource.detach() if resource is s.get("session") else resource.unload()
        except Exception as error:
            failures.append(type(error).__name__)
    cleanup = "unknown" if failures else "complete"
    emit("session", session=sid, device=s["device"], package=s["package"], state="dead", cleanup=cleanup)
    return {"detached": not failures, "cleanup": cleanup, "errors": failures}


def op_sessions(_):
    return {"sessions": [{"session": sid, "device": s["device"], "package": s["package"],
                          "pid": s["pid"], "hooks": list(s["hooks"].keys())}
                         for sid, s in _sessions.items()]}


def op_appversion(params):
    """The installed version of a package on the device (for the F2 version-skew guard): compares against
    the loaded project's manifest versionCode. installed:false when the target app isn't on the device."""
    serial = params["device"]
    package = params["package"]
    rc, out, _ = adb("shell", "dumpsys", "package", package, serial=serial)
    vc = re.search(r"versionCode=(\d+)", out or "")
    vn = re.search(r"versionName=(\S+)", out or "")
    return {"package": package, "installed": vc is not None,
            "versionCode": vc.group(1) if vc else None,
            "versionName": vn.group(1) if vn else None}


OPS = {
    "ping": op_ping,
    "enumerate_devices": op_enumerate_devices,
    "list_apps": op_list_apps,
    "appversion": op_appversion,
    "provision": op_provision,
    "cleanup": op_cleanup,
    "attach": op_attach,
    "add_hook": op_add_hook,
    "remove_hook": op_remove_hook,
    "detach": op_detach,
    "sessions": op_sessions,
}


def handle(msg):
    req_id = msg.get("id")
    op = msg.get("op")
    fn = OPS.get(op)
    if fn is None:
        respond(req_id, False, error="unknown op: %s" % op)
        return
    try:
        if op in ("provision", "attach", "detach", "cleanup"):
            with _lifecycle_lock:
                result = fn(msg) or {}
        else:
            result = fn(msg) or {}
        respond(req_id, True, **result)
    except Exception as e:
        respond(req_id, False, error="%s: %s" % (type(e).__name__, e))


def main():
    log("up — frida", FRIDA_VER, "| explicit local ownership required")
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            msg = json.loads(line)
        except Exception as e:
            log("parse error:", e)
            continue
        handle(msg)
    for serial in list(_lifecycle.owned):
        try:
            result = op_cleanup({"device": serial})
            emit("cleanup", device=serial, **result)
        except Exception as error:
            emit("cleanup", device=serial, cleanup={"cleanup":"unknown", "error":type(error).__name__})
    log("stdin closed — exiting")


if __name__ == "__main__":
    main()
