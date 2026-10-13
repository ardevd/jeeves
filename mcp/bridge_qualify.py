#!/usr/bin/env python3
"""Qualify the jeeves MCP bridge over its real transport (stdio JSON-RPC) against a room behind an edge.

    mcp/bridge_qualify.py --url <edge url> --token-file <student token file> --state <edge state dir>
                          [--bad-token-file <file>] [--multiline-token-file <file>] [--missing-token-file <path>]
                          [--fake-catalogue-url <url>] [--tls-url <https edge url> --ca-file <cert.pem>]
                          [--out receipt.json]

Scenarios (each a PASS/FAIL line; the receipt lists them):
  1. initialize is answered BEFORE any HTTP request leaves; an unknown protocol version gets the newest one
  2. tools/list: the room's catalogue — in a course room the classroom's subset, never the host's devices or
     autonomous missions — plus jeeves_status; schemas closed; listChanged announced
  3. tools/call symbols → a result and a `provenance:` item (target hash, revision, manifest match, truncated said)
  4. tools/call code → a result; an unknown tool → JSON-RPC -32602; the room refuses an action outside the
     classroom's allowed ones in the student's words
  5. the result bound: a bridge with WORKBENCH_MCP_MAX_CHARS=1500 cuts and says so (truncated_by bridge)
  6. cancellation: notifications/cancelled drops the pending result (no result frame for that id)
  7. locked (the edge's 404 denial page): a running bridge's call → kind locked naming both causes (not open /
     token not current); a fresh bridge lists only jeeves_status and says `locked` with the reconnect step
  8. a bad token against the REAL edge's behaviour (404 page) → kind locked, the token named; with the edge's
     X-WB-Denied: token header → kind unauthorized
  9. no token file → kind unauthorized BEFORE any HTTP; a multi-line token file → unauthorized, nothing sent,
     nothing of it printed
 10. the room away (503): kind unavailable, retry wording; no retry storm from tools/list or jeeves_status
 11. an incompatible catalogue (a fake room): kind catalogue, "update the course kit"
 12. protocol robustness: a batch line and a scalar → -32600 and the bridge lives on; string arguments → -32602
 13. a redirect from the edge → kind endpoint, ONE request, the bearer never re-sent elsewhere
 14. TLS: an https edge with the class CA works; without the CA the bridge refuses before any request arrives
 15. the call bound: a slow edge against WORKBENCH_MCP_CALL_TIMEOUT=1 → kind unavailable, "did not answer within"
 16. the room opens while a client waits: notifications/tools/list_changed arrives, the next tools/list is full
 17. the token value appears nowhere in any bridge's stdout/stderr
 18. --doctor prints one JSON report (no protocol frame in it)
The harness never calls a model: it is the protocol and the bridge's behaviour, not a client's loop.
"""
import argparse, json, os, subprocess, sys, threading, time
from pathlib import Path

HERE = Path(__file__).resolve().parent
BRIDGE = HERE / "workbench_mcp.py"
checks = []
BRIDGES = []


def check(ok, label, detail=None):
    checks.append({"ok": bool(ok), "label": label})
    print(("PASS " if ok else "FAIL ") + label, flush=True)
    if not ok and detail is not None:
        print("     judged: " + str(detail)[:1200].replace("\n", " ⏎ "), flush=True)


class Bridge:
    def __init__(self, env, label):
        self.label = label
        self.p = subprocess.Popen([sys.executable, str(BRIDGE)], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, bufsize=1, env=env)
        self.frames = []
        self.err = []
        self.lock = threading.Lock()
        threading.Thread(target=self._pump, daemon=True).start()
        threading.Thread(target=self._pump_err, daemon=True).start()
        BRIDGES.append(self)

    def _pump(self):
        for line in self.p.stdout:
            line = line.strip()
            if not line:
                continue
            try:
                with self.lock:
                    self.frames.append(json.loads(line))
            except Exception:
                pass

    def _pump_err(self):
        for line in self.p.stderr:
            self.err.append(line.rstrip("\n"))

    def send_raw(self, text):
        try:
            self.p.stdin.write(text + "\n"); self.p.stdin.flush()
        except (BrokenPipeError, ValueError):
            pass

    def send(self, obj):
        self.send_raw(json.dumps(obj))

    def wait(self, rid, timeout=30):
        deadline = time.time() + timeout
        while time.time() < deadline:
            with self.lock:
                for f in self.frames:
                    if f.get("id") == rid:
                        return f
            time.sleep(0.05)
        return None

    def wait_notification(self, method, timeout=30):
        deadline = time.time() + timeout
        while time.time() < deadline:
            with self.lock:
                for f in self.frames:
                    if f.get("method") == method:
                        return f
            time.sleep(0.1)
        return None

    def rpc(self, rid, method, params=None, timeout=30):
        self.send({"jsonrpc": "2.0", "id": rid, "method": method, "params": params or {}})
        return self.wait(rid, timeout)

    def notify(self, method, params=None):
        self.send({"jsonrpc": "2.0", "method": method, "params": params or {}})

    def alive(self):
        return self.p.poll() is None

    def close(self):
        try:
            self.p.stdin.close()
        except Exception:
            pass
        try:
            self.p.wait(timeout=5)
        except Exception:
            self.p.kill()


def text_of(frame):
    try:
        return "\n".join(c.get("text", "") for c in frame["result"]["content"] if c.get("type") == "text")
    except Exception:
        return json.dumps(frame)


def error_kind(frame):
    try:
        t = text_of(frame)
        js = t[t.rindex("{"):]
        return json.loads(js).get("kind")
    except Exception:
        return None


def status_of(frame):
    try:
        return json.loads(text_of(frame))
    except Exception:
        return {}


def requests_logged(state):
    f = Path(state) / "requests.log"
    if not f.is_file():
        return []
    return [json.loads(l) for l in f.read_text().splitlines() if l.strip()]


def init(b, rid=1, version="2024-11-05", name="q"):
    return b.rpc(rid, "initialize", {"protocolVersion": version, "capabilities": {}, "clientInfo": {"name": name, "version": "1"}}, timeout=10)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", required=True); ap.add_argument("--token-file", required=True); ap.add_argument("--state", required=True)
    ap.add_argument("--bad-token-file"); ap.add_argument("--multiline-token-file"); ap.add_argument("--missing-token-file")
    ap.add_argument("--fake-catalogue-url"); ap.add_argument("--tls-url"); ap.add_argument("--ca-file"); ap.add_argument("--tls-state")
    ap.add_argument("--out", default="bridge-qualify.json")
    ap.add_argument("--symbol", default="Lm76/a;", help="a class of the room's target to decompile (the fixture apk's Lm76/a;)")
    a = ap.parse_args()
    state = Path(a.state)
    token = Path(a.token_file).read_text().strip()
    base_env = dict(os.environ, WORKBENCH_URL=a.url, WORKBENCH_BEARER_FILE=a.token_file, WORKBENCH_ACTOR="llm:qualify")
    base_env.pop("WORKBENCH_BEARER", None); base_env.pop("WORKBENCH_TARGET", None)
    for f in ("locked", "down", "redirect", "slow", "denied-header", "legacy401"):
        (state / f).unlink(missing_ok=True)
    (state / "requests.log").unlink(missing_ok=True)

    # 1. initialize before any HTTP; protocol negotiation
    b = Bridge(base_env, "main")
    i1 = init(b, 1, "2025-06-18", "bridge-qualify")
    ver = (i1 or {}).get("result", {}).get("serverInfo", {}).get("version")
    check(i1 is not None and "result" in i1 and i1["result"].get("protocolVersion") == "2025-06-18" and i1["result"]["serverInfo"]["name"] == "jeeves",
          "initialize answered (protocol negotiated to the client's 2025-06-18, serverInfo jeeves %s)" % ver)
    check(len(requests_logged(state)) == 0, "no HTTP request left the bridge before initialize was answered (edge log empty)")
    check(i1 is not None and i1["result"].get("capabilities", {}).get("tools", {}).get("listChanged") is True, "the bridge announces tools.listChanged (the room's opening is announced)")
    b.notify("notifications/initialized")
    bx = Bridge(base_env, "oldclient")
    ix = init(bx, 1, "1999-01-01", "ancient")
    check(ix is not None and ix["result"].get("protocolVersion") == "2025-06-18", "an unknown protocol version gets the NEWEST this bridge speaks (2025-06-18), not the oldest")
    bx.close()
    # 2. tools/list
    tl = b.rpc(2, "tools/list", timeout=30)
    tools = {t["name"]: t for t in (tl or {}).get("result", {}).get("tools", [])}
    check("jeeves_status" in tools and "symbols" in tools and "code" in tools and "xrefs" in tools and "search" in tools and "annotate" in tools,
          "tools/list: the facade's evidence tools and jeeves_status (%d tools)" % len(tools))
    check("devices" not in tools and "mission" not in tools and "runs" not in tools and "llm" not in tools and "review" not in tools and "map_edit" not in tools,
          "the classroom's subset: no devices, missions, runs, llm, review or map editing in a room")
    check(all(t["inputSchema"].get("additionalProperties") is False for n, t in tools.items() if n != "jeeves_status"), "every tool schema is closed")
    tgt_enum = tools.get("target", {}).get("inputSchema", {}).get("properties", {}).get("action", {}).get("enum")
    check(tgt_enum == ["list", "info", "changes", "save", "save_status", "switch"], "the target tool's action enum is the room's allowed list: %s" % tgt_enum)
    logged = requests_logged(state)
    check(any("ops.tools" in r["path"] for r in logged), "the catalogue was fetched on tools/list (lazily)")
    # 3. symbols with provenance
    sym = b.rpc(3, "tools/call", {"name": "symbols", "arguments": {"query": "a", "limit": 3}}, timeout=60)
    stext = text_of(sym) if sym else ""
    check(sym is not None and sym.get("result", {}).get("isError") is False and "provenance:" in stext and '"artifact_sha256"' in stext and '"project_revision"' in stext
          and '"manifest_match"' in stext and '"truncated": true' in stext and '"truncated_by": "room"' in stext and '"origin": "external-assistant"' in stext and '"account_id"' not in stext,
          "tools/call symbols (limit 3 of thousands) → a result with a provenance item (target hash, revision, manifest match, truncated by the room said, no account id)", stext)
    # 4. code + unknown + an action the room refuses
    code = b.rpc(4, "tools/call", {"name": "code", "arguments": {"symbol": a.symbol, "window": "all"}}, timeout=120)
    ctext = text_of(code) if code else ""
    check(code is not None and ("isError" in code.get("result", {})) and len(ctext) > 50, "tools/call code answered (%d chars, isError=%s)" % (len(ctext), code.get("result", {}).get("isError") if code else None))
    unk = b.rpc(5, "tools/call", {"name": "frobnicate", "arguments": {}}, timeout=30)
    check(unk is not None and unk.get("error", {}).get("code") == -32602 and "Unknown tool" in unk.get("error", {}).get("message", ""), "an unknown tool → JSON-RPC -32602 'Unknown tool' (the spec's shape)")
    dev = b.rpc(6, "tools/call", {"name": "devices", "arguments": {"action": "list"}}, timeout=30)
    check(dev is not None and dev.get("error", {}).get("code") == -32602, "a tool outside the subset (devices) is not in the room's list → -32602")
    opn = b.rpc(60, "tools/call", {"name": "target", "arguments": {"action": "open", "path": "/x.apk"}}, timeout=30)
    otext = text_of(opn) if opn else ""
    check(opn is not None and opn["result"].get("isError") is True and "not available to your assistant" in otext and "course=tool-subset" in otext and "facade" not in otext.lower() and "grant" not in otext.lower(),
          "the room refuses an action outside the allowed ones (target open) in the student's words, detail course=tool-subset")
    exp = b.rpc(61, "tools/call", {"name": "jeeves_export", "arguments": {"symbol": a.symbol, "scope": "class"}}, timeout=60)
    etext = text_of(exp) if exp else ""
    check(exp is not None and exp["result"].get("isError") is False and '"artifact_id"' in etext and '"sha256"' in etext and '"workspace_generation"' in etext,
          "jeeves_export (course mode) → the room's bounded immutable export: artifact id, sha256, size, owner generation", etext[:300])
    # 6. cancellation
    b.send({"jsonrpc": "2.0", "id": 7, "method": "tools/call", "params": {"name": "search", "arguments": {"query": "zz", "limit": 5}}})
    b.notify("notifications/cancelled", {"requestId": 7})
    got7 = b.wait(7, timeout=20)
    check(got7 is None, "notifications/cancelled: the pending result is dropped (no frame for id 7 in 20 s)" + ("" if got7 is None else " — a frame arrived"))
    # 12. protocol robustness on the main bridge
    b.send_raw('[{"jsonrpc":"2.0","id":901,"method":"ping"}]')
    b.send_raw('42')
    time.sleep(0.5)
    errs = [f for f in b.frames if f.get("id") is None and f.get("error", {}).get("code") == -32600]
    pong = b.rpc(902, "ping", timeout=10)
    check(len(errs) >= 2 and pong is not None and "result" in pong and b.alive(), "a batch line and a scalar → -32600 (id null) ×%d, and the bridge still answers ping" % len(errs))
    sarg = b.rpc(903, "tools/call", {"name": "symbols", "arguments": "not-an-object"}, timeout=10)
    check(sarg is not None and sarg.get("error", {}).get("code") == -32602, "string arguments → -32602, the call thread lives")
    # 7. locked: a running bridge's call; then a fresh bridge
    (state / "locked").write_text("1")
    locked_call = b.rpc(8, "tools/call", {"name": "symbols", "arguments": {"query": "a"}}, timeout=30)
    ltext = text_of(locked_call) if locked_call else ""
    check(locked_call is not None and locked_call["result"].get("isError") is True and error_kind(locked_call) == "locked" and "token" in ltext and "Today" in ltext and "not open yet" in ltext,
          "locked (the edge's 404 denial page) on a running bridge → kind locked naming BOTH causes (not open yet / token not current) and the Today page")
    b2 = Bridge(base_env, "locked")
    i2 = init(b2, 1, "2024-11-05", "q2")
    tl2 = b2.rpc(2, "tools/list", timeout=30)
    names2 = [t["name"] for t in (tl2 or {}).get("result", {}).get("tools", [])]
    st2 = b2.rpc(3, "tools/call", {"name": "jeeves_status", "arguments": {}}, timeout=30)
    s2 = status_of(st2)
    check(i2 is not None and names2 == ["jeeves_status"] and s2.get("state") == "locked" and "new assistant session" in s2.get("reconnect", "").lower(),
          "a fresh bridge on a locked room: initialize ok, tools/list = jeeves_status only, status 'locked' with the reconnect instruction")
    b2.close()
    (state / "locked").unlink()
    # 8. a bad token against the real edge's behaviour, then with the X-WB-Denied header
    if a.bad_token_file:
        b3 = Bridge(dict(base_env, WORKBENCH_BEARER_FILE=a.bad_token_file), "badtoken")
        init(b3, 1, "2024-11-05", "q3")
        s3 = status_of(b3.rpc(2, "tools/call", {"name": "jeeves_status", "arguments": {}}, timeout=30))
        check(s3.get("state") == "locked" and "token" in s3.get("problem", "") and "Today" in s3.get("next", "") + s3.get("problem", ""),
              "a bad token against the real edge (its 404 page, no distinction) → 'locked' that names the token and the Today page")
        b3.close()
        (state / "denied-header").write_text("token")
        b3b = Bridge(dict(base_env, WORKBENCH_BEARER_FILE=a.bad_token_file), "badtoken-header")
        init(b3b, 1, "2024-11-05", "q3b")
        s3b = status_of(b3b.rpc(2, "tools/call", {"name": "jeeves_status", "arguments": {}}, timeout=30))
        check(s3b.get("state") == "unauthorized" and "token" in s3b.get("problem", ""), "the same with the edge's X-WB-Denied: token header → 'unauthorized' (the header decides)")
        b3b.close()
        (state / "denied-header").unlink()
    # 9. no token file; a multi-line token file
    if a.missing_token_file:
        before = len(requests_logged(state))
        b9 = Bridge(dict(base_env, WORKBENCH_BEARER_FILE=a.missing_token_file), "notoken")
        init(b9, 1, "2024-11-05", "q9")
        s9 = status_of(b9.rpc(2, "tools/call", {"name": "jeeves_status", "arguments": {}}, timeout=30))
        check(s9.get("state") == "unauthorized" and "missing" in s9.get("problem", "") and s9.get("token_file", {}).get("usable") is False and len(requests_logged(state)) == before,
              "no token file → 'unauthorized' (the file is missing) with NO HTTP request; the report says the file is not usable")
        b9.close()
    if a.multiline_token_file:
        before = len(requests_logged(state))
        first = Path(a.multiline_token_file).read_text().splitlines()[0].strip()
        b10 = Bridge(dict(base_env, WORKBENCH_BEARER_FILE=a.multiline_token_file), "multiline")
        init(b10, 1, "2024-11-05", "q10")
        s10 = status_of(b10.rpc(2, "tools/call", {"name": "jeeves_status", "arguments": {}}, timeout=30))
        b10.close()
        leak10 = any(first in l for l in b10.err) or any(first in json.dumps(f) for f in b10.frames)
        check(s10.get("state") == "unauthorized" and "does not hold a token" in s10.get("problem", "") and len(requests_logged(state)) == before and not leak10 and b10.p.returncode in (0, None, -15),
              "a multi-line token file → 'unauthorized', nothing sent, nothing of it printed, the bridge alive")
    # 10. the room away
    (state / "down").write_text("1")
    b4 = Bridge(base_env, "down")
    init(b4, 1, "2024-11-05", "q4")
    s4 = status_of(b4.rpc(2, "tools/call", {"name": "jeeves_status", "arguments": {}}, timeout=30))
    check(s4.get("state") == "unavailable" and "retry" in s4.get("next", "").lower() and "kept" not in s4.get("next", "").lower(), "the room away (503 page) → status 'unavailable' with retry wording (no promise about the room's state)")
    before = len([r for r in requests_logged(state) if r["verdict"] == "503 down"])
    for i in range(3):
        b4.rpc(10 + i, "tools/list", timeout=10)
    for i in range(3):
        b4.rpc(20 + i, "tools/call", {"name": "jeeves_status", "arguments": {}}, timeout=10)
    after = len([r for r in requests_logged(state) if r["verdict"] == "503 down"])
    check(after - before <= 1, "no retry storm: three tools/list and three jeeves_status on a dead room make at most one more attempt (%d)" % (after - before))
    b4.close()
    (state / "down").unlink()
    # 11. incompatible catalogue
    if a.fake_catalogue_url:
        b5 = Bridge(dict(base_env, WORKBENCH_URL=a.fake_catalogue_url), "fake")
        init(b5, 1, "2024-11-05", "q5")
        s5 = status_of(b5.rpc(2, "tools/call", {"name": "jeeves_status", "arguments": {}}, timeout=30))
        check(s5.get("state") == "catalogue" and "course kit" in s5.get("next", ""), "an incompatible catalogue → status 'catalogue' with 'update the course kit'")
        b5.close()
    # 5. the result bound
    b6 = Bridge(dict(base_env, WORKBENCH_MCP_MAX_CHARS="2000"), "bound")
    init(b6, 1, "2024-11-05", "q6")
    b6.rpc(2, "tools/list", timeout=30)
    big = b6.rpc(3, "tools/call", {"name": "strings", "arguments": {"limit": 400}}, timeout=120)
    btext = text_of(big) if big else ""
    check(big is not None and "result cut at 2000" in btext and ('"truncated_by": "bridge"' in btext or '"truncated_by": "room,bridge"' in btext) and '"truncated": true' in btext,
          "the result bound cuts a long result (strings, limit 400) and declares it — truncated true, truncated_by names the bridge — %d chars shown" % len(btext), btext[-600:])
    b6.close()
    # 13. a redirect from the edge
    (state / "redirect").write_text("1")
    before = len(requests_logged(state))
    b7 = Bridge(base_env, "redirect")
    init(b7, 1, "2024-11-05", "q7")
    s7 = status_of(b7.rpc(2, "tools/call", {"name": "jeeves_status", "arguments": {}}, timeout=30))
    b7.close()
    after_rows = requests_logged(state)[before:]
    check(s7.get("state") == "endpoint" and "redirect" in s7.get("problem", "") and len(after_rows) == 1 and after_rows[0]["verdict"] == "302 redirect",
          "a redirect from the edge → 'endpoint' (redirect refused), exactly one request, the bearer not re-sent anywhere")
    (state / "redirect").unlink()
    # 14. TLS
    if a.tls_url and a.ca_file:
        tls_state = Path(a.tls_state) if a.tls_state else state
        (tls_state / "requests.log").unlink(missing_ok=True)
        b8 = Bridge(dict(base_env, WORKBENCH_URL=a.tls_url, WORKBENCH_MCP_CA_FILE=a.ca_file), "tls")
        init(b8, 1, "2024-11-05", "q8")
        tl8 = b8.rpc(2, "tools/list", timeout=30)
        n8 = len((tl8 or {}).get("result", {}).get("tools", []))
        check(n8 > 1, "an https edge with the class CA file: tools/list works over TLS (%d tools)" % n8)
        b8.close()
        before = len(requests_logged(tls_state))
        env9 = dict(base_env, WORKBENCH_URL=a.tls_url); env9.pop("WORKBENCH_MCP_CA_FILE", None)
        b9t = Bridge(env9, "tls-noca")
        init(b9t, 1, "2024-11-05", "q9t")
        s9t = status_of(b9t.rpc(2, "tools/call", {"name": "jeeves_status", "arguments": {}}, timeout=30))
        b9t.close()
        check(s9t.get("state") == "endpoint" and "certificate" in s9t.get("problem", "") and "Install Certificates" in s9t.get("next", "") and len(requests_logged(tls_state)) == before,
              "the same edge without the CA: 'endpoint' (certificate not trusted, the fix named) and NO request reached the edge")
        bnc = Bridge(dict(base_env, WORKBENCH_MCP_CA_FILE="/nonexistent/ca.pem"), "badca")
        init(bnc, 1, "2024-11-05", "qnc")
        snc = status_of(bnc.rpc(2, "tools/call", {"name": "jeeves_status", "arguments": {}}, timeout=30))
        bnc.close()
        check(snc.get("state") == "endpoint" and "CA bundle" in snc.get("problem", ""), "a CA file that does not exist → 'endpoint' at call time, not a crash")
    # 15. the call bound against a slow edge
    (state / "slow").write_text("1")
    b11 = Bridge(dict(base_env, WORKBENCH_MCP_CALL_TIMEOUT="1"), "slow")
    init(b11, 1, "2024-11-05", "q11")
    b11.rpc(2, "tools/list", timeout=30)   # the catalogue has its own 10 s bound: it loads through the 3 s delay
    s11 = b11.rpc(3, "tools/call", {"name": "symbols", "arguments": {"query": "a", "limit": 1}}, timeout=30)
    t11 = text_of(s11) if s11 else ""
    (state / "slow").unlink()
    b11.close()
    check(s11 is not None and "did not answer within 1 s" in t11 and "narrow" in t11 and error_kind(s11) == "unavailable", "a slow edge against WORKBENCH_MCP_CALL_TIMEOUT=1: a tool call → kind unavailable, 'did not answer within 1 s', the narrowing hint", t11)
    # 16. the room opens while a client waits
    (state / "locked").write_text("1")
    b12 = Bridge(dict(base_env, WORKBENCH_MCP_BG_RETRY="2"), "opens")
    init(b12, 1, "2025-06-18", "q12")
    tl12 = b12.rpc(2, "tools/list", timeout=30)
    only = [t["name"] for t in (tl12 or {}).get("result", {}).get("tools", [])] == ["jeeves_status"]
    (state / "locked").unlink()
    note = b12.wait_notification("notifications/tools/list_changed", timeout=12)
    tl12b = b12.rpc(3, "tools/list", timeout=30)
    n12 = len((tl12b or {}).get("result", {}).get("tools", []))
    b12.close()
    check(only and note is not None and n12 > 1, "a bridge that listed while locked announces notifications/tools/list_changed once the room opens; the next tools/list is full (%d)" % n12)
    # 18. --doctor prints ONE JSON document (no protocol frame leaks into the report)
    doc = subprocess.run([sys.executable, str(BRIDGE), "--doctor"], env=base_env, capture_output=True, text=True, timeout=60)
    try:
        drep = json.loads(doc.stdout)
    except Exception:
        drep = None
    check(doc.returncode == 0 and isinstance(drep, dict) and drep.get("state") == "connected" and drep.get("token_file", {}).get("usable") is True,
          "--doctor: one JSON report on stdout (state connected, token file usable), exit 0", doc.stdout[:300])
    # 17. no token leakage, anywhere
    b.close()
    leak = any(token in l for br in BRIDGES for l in br.err) or any(token in json.dumps(f) for br in BRIDGES for f in br.frames)
    check(not leak, "the token value appears nowhere in any bridge's stderr or results (%d bridges)" % len(BRIDGES))
    ok = all(c["ok"] for c in checks)
    receipt = {"schema": "jeeves-bridge-qualify-v1", "at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "bridge": str(BRIDGE), "bridge_version": ver, "python": sys.version.split()[0],
               "edge": a.url, "tls_edge": a.tls_url, "checks": checks, "passed": sum(c["ok"] for c in checks), "failed": sum(not c["ok"] for c in checks),
               "note": "protocol and bridge behaviour over the real stdio transport against a room behind a stand-in edge that answers like the real one (auth failures = the 404 denial page); no model, no client loop — client handshakes are a separate receipt"}
    Path(a.out).write_text(json.dumps(receipt, indent=1))
    print(("BRIDGE QUALIFY PASSED" if ok else "BRIDGE QUALIFY FAILED") + ": %d/%d" % (receipt["passed"], len(checks)))
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
