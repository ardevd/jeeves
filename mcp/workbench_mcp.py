#!/usr/bin/env python3
"""
jeeves MCP bridge — the workbench's tool facade over stdio (newline-delimited JSON-RPC 2.0).

Runs beside an assistant (Kimi Code, OpenCode, Claude Code, Codex) and calls the workbench's HTTP
API: on the owner's laptop that is http://127.0.0.1:7800; in the class it is the student's assigned
HTTPS room through the class server's edge. Standard library only (no dependency to install),
Python 3.9+. Version: see BRIDGE_VERSION.

Protocol: initialize / notifications/initialized / tools/list / tools/call / ping, plus empty
resources/list and prompts/list for clients that ask, and notifications/cancelled. initialize is
answered AT ONCE — the catalogue (the backend's `ops.tools`: the facade of dev.workbench.llm.ToolFacade,
in a course room the classroom's subset) is fetched lazily, on the first tools/list, with a bounded
timeout, and re-asked at most every RETRY_S seconds while it fails (no startup storm when the room is
locked or away). Until it is known, tools/list answers ONE tool, `jeeves_status`, which says what is
wrong and what to do (locked room, token not accepted, room away, incompatible catalogue). While a
client has asked and the room is not there, a background retry every BG_RETRY_S announces the room's
opening with notifications/tools/list_changed (clients that listen refresh; the others need a new
session). A locked room is the server's gate; nothing here bypasses it.

Each tools/call runs on its own thread (at most MAX_CONCURRENT_CALLS at once): the loop keeps reading
stdin, a second call does not wait behind a long one, and a client's notifications/cancelled drops the
pending result (the room's op itself runs to its own bound — the bridge cannot abort it over HTTP).
Results are JSON text, bounded by MAX_RESULT_CHARS with a declared cut; errors are structured
(isError + {kind, message, hint, code}).

Env:
  WORKBENCH_URL          the backend base URL (default http://127.0.0.1:7800). Anything but loopback
                         must be https:// (the class room is reached through the edge's TLS). The URL is
                         the configured, trusted endpoint: no user:password, no query, and redirects are
                         NOT followed (a bearer never crosses an unexpected redirect).
  WORKBENCH_BEARER_FILE  the room token file (course mode) — read at every call, so a rotation needs no
                         restart; one line of printable characters; owner-only permissions are warned
                         about. A configured file that is missing, empty or not a token stops the call
                         BEFORE any HTTP. WORKBENCH_BEARER (a value in env) counts only without a file.
  WORKBENCH_ACTOR        actor tag (default llm:mcp; the operator's own assistant may use human:<name>)
  WORKBENCH_TARGET       standalone: the target every call is pinned to — a target id (t2) or the
                         artifact's exact file name among the open targets, exact matches only (an
                         ambiguous or unknown pin is an error, never "the first match"). In a course room
                         the ROOM pins its course target and this is ignored.
  WORKBENCH_MCP_MAX_CHARS    the result bound (default 80000 ≈ 20k tokens: under Claude Code's default
                             MCP output cap, so a long result is cut and declared, not rejected)
  WORKBENCH_MCP_CALL_TIMEOUT the per-call bound in seconds (default 75 — the class edge's read timeout)
  WORKBENCH_MCP_CA_FILE      a CA bundle for the room's TLS when this Python's store is not enough

Token material never appears in logs, error texts or results.

--selftest [--write]: list tools, assert the facade shape, call workspace / map / project (m7IT).
--doctor: one connection report (installation, endpoint, token file, room state) as JSON, exit 0/1.
"""

import http.client
import ipaddress
import json
import os
import re
import socket
import ssl
import stat
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

BRIDGE_VERSION = "0.9.2"
BASE = os.environ.get("WORKBENCH_URL", "http://127.0.0.1:7800").rstrip("/")
ACTOR = os.environ.get("WORKBENCH_ACTOR", "llm:mcp")
COURSE_MODE = bool(os.environ.get("WORKBENCH_BEARER_FILE"))   # a token file configured = the class: the room pins its target
TARGET = (os.environ.get("WORKBENCH_TARGET") or None) if not COURSE_MODE else None
PINNED = bool(TARGET)          # a configured pin never moves; a session pin follows the client's opens
PROTOCOL_VERSIONS = ("2025-06-18", "2025-03-26", "2024-11-05")   # newest first
PROTOCOL_VERSION = PROTOCOL_VERSIONS[0]
SERVER_INFO = {"name": "jeeves", "version": BRIDGE_VERSION}


def _int_env(name, default, lo, hi):
    try:
        return max(lo, min(hi, int(os.environ.get(name, default))))
    except (TypeError, ValueError):
        return default


MAX_RESULT_CHARS = _int_env("WORKBENCH_MCP_MAX_CHARS", 80_000, 2_000, 2_000_000)
MAX_BODY_BYTES = 16 << 20      # a room answer larger than this is refused unread, not materialized
CATALOGUE_TIMEOUT_S = 10
CALL_TIMEOUT_S = _int_env("WORKBENCH_MCP_CALL_TIMEOUT", 75, 1, 3600)
RETRY_S = 5.0                  # the catalogue is not re-asked more often than this while it fails (status included)
BG_RETRY_S = _int_env("WORKBENCH_MCP_BG_RETRY", 30, 2, 600)
MAX_CONCURRENT_CALLS = 4
REQUIRED_TOOLS = ("symbols", "code", "xrefs", "search")   # a catalogue without these is not the facade this bridge knows
TOKEN_RE = re.compile(r"[\x21-\x7e]{8,4096}")

TOKEN_HINT = ("install the current Jeeves token from your course home (the Today page shows it) into the kit's token file — "
              "the kit's README says how; then start a new assistant session")
LOCKED_HINT = ("wait for the instructor's go — the classroom (slides, materials, messages) stays available; when Jeeves opens, "
               "this bridge announces it to your assistant, and a new assistant session always shows the tools")
DENIED_PAGE = ("the class server did not let this connection into the room: Jeeves is not open yet — or the Jeeves token on this "
               "computer is not the current one (your Today page shows the token)")
DENIED_HINT = ("if the instructor says Jeeves is open, install the current token from your Today page and start a new assistant "
               "session; otherwise wait for the go — the classroom stays available")
NOT_YOURS = "this room is no longer yours: it was reset or reassigned, or your account is paused"
NOT_YOURS_HINT = "open your course home (Today) for your current room and connect from there"
TLS_HINT = ("set WORKBENCH_MCP_CA_FILE in the kit's room file to the class CA bundle (the kit says which), or — a python.org Python "
            "on macOS — run 'Install Certificates.command' from its Python folder once")
UNREACHABLE_HINT = "is the class server up and are you on its network? retry in a minute; the token is never sent until a connection exists"


def log(*a):
    print("[jeeves-mcp]", *a, file=sys.stderr, flush=True)


# ── endpoint and credential ───────────────────────────────────────────────────

def is_loopback(host):
    if host == "localhost":
        return True
    try:
        return ipaddress.ip_address(host.strip("[]")).is_loopback
    except ValueError:
        return False


def endpoint_problem():
    """Why BASE is not a usable, trusted endpoint; None when it is."""
    try:
        u = urllib.parse.urlsplit(BASE)
        host = u.hostname
        u.port
    except ValueError as e:
        return "WORKBENCH_URL is not a usable URL (%s)" % e.__class__.__name__
    if u.scheme not in ("http", "https"):
        return "WORKBENCH_URL must start with https:// (http:// only for this computer's own workbench)"
    if not host:
        return "WORKBENCH_URL has no host"
    if u.username is not None or u.password is not None:
        return "WORKBENCH_URL must not carry a user name or password"
    if u.query or u.fragment:
        return "WORKBENCH_URL must be the room's address only — no ?query or #fragment"
    if u.scheme == "http" and not is_loopback(host):
        return "WORKBENCH_URL is http:// to another computer — the class room is reached over https:// only (a token must not travel in clear)"
    return None


class RoomError(Exception):
    """A classified failure to reach or be served by the room: kind, a message for a person, a hint."""
    KINDS = ("locked", "unauthorized", "fenced", "unavailable", "endpoint", "catalogue", "refused", "op")

    def __init__(self, kind, message, hint="", code=None):
        super().__init__(message)
        self.kind = kind
        self.message = message
        self.hint = hint
        self.code = code

    def as_dict(self):
        d = {"kind": self.kind, "message": self.message}
        if self.hint:
            d["hint"] = self.hint
        if self.code:
            d["code"] = self.code
        return d


def bearer():
    """The room token (course mode): the FILE, re-read every call — a rotation is a file write. The
    environment's WORKBENCH_BEARER counts only when no file is configured. None standalone; a configured
    file that is missing, empty or not a token raises RoomError('unauthorized') BEFORE any HTTP."""
    path = os.environ.get("WORKBENCH_BEARER_FILE")
    if not path:
        tok = (os.environ.get("WORKBENCH_BEARER") or "").strip()
        return tok if TOKEN_RE.fullmatch(tok) else None
    try:
        st = os.stat(path)
    except OSError:
        raise RoomError("unauthorized", "no Jeeves token is installed on this computer (the token file is missing)", TOKEN_HINT, "E_SCOPE")
    if stat.S_IMODE(st.st_mode) & 0o077 and not getattr(bearer, "_warned", False):
        bearer._warned = True
        log("warning: the token file is readable by others — chmod 600 it")
    try:
        with open(path, "rb") as f:
            raw = f.read(8192)
    except OSError:
        raise RoomError("unauthorized", "the Jeeves token file cannot be read", TOKEN_HINT, "E_SCOPE")
    tok = raw.decode("utf-8-sig", "replace").strip()
    if not tok:
        raise RoomError("unauthorized", "the Jeeves token file is empty", TOKEN_HINT, "E_SCOPE")
    if not TOKEN_RE.fullmatch(tok):
        raise RoomError("unauthorized", "the Jeeves token file does not hold a token (one line of printable characters) — nothing was sent", TOKEN_HINT, "E_SCOPE")
    return tok


def token_state():
    """The doctor's view of the token file: configured, present, usable — never its value."""
    path = os.environ.get("WORKBENCH_BEARER_FILE")
    st = {"configured": bool(path), "path": path}
    if path:
        st["present"] = os.path.isfile(path)
        try:
            bearer()
            st["usable"] = True
        except RoomError as e:
            st["usable"] = False
            st["problem"] = e.message
    return st


class NoRedirect(urllib.request.HTTPRedirectHandler):
    """A redirect from the configured endpoint is refused: the bearer never follows one."""
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise urllib.error.HTTPError(req.full_url, code, "redirect refused", headers, fp)


_OPENER = None
_OPENER_PROBLEM = None


def opener():
    """The one urllib opener: TLS validated by the default store (or WORKBENCH_MCP_CA_FILE), a Python
    without root certificates falls back to certifi when it is installed, redirects refused. A bad CA
    configuration is an endpoint error at call time, never a crash at import."""
    global _OPENER, _OPENER_PROBLEM
    if _OPENER_PROBLEM:
        raise RoomError("endpoint", _OPENER_PROBLEM, "fix WORKBENCH_MCP_CA_FILE in the kit's room file")
    if _OPENER is not None:
        return _OPENER
    ca = os.environ.get("WORKBENCH_MCP_CA_FILE")
    try:
        if ca:
            if not os.path.isfile(ca):
                raise FileNotFoundError(ca)
            ctx = ssl.create_default_context(cafile=ca)
        else:
            ctx = ssl.create_default_context()
            if ctx.cert_store_stats().get("x509_ca", 0) == 0:
                try:
                    import certifi
                    ctx.load_verify_locations(cafile=certifi.where())
                except Exception:
                    pass
    except Exception as e:
        _OPENER_PROBLEM = "the CA bundle named by WORKBENCH_MCP_CA_FILE cannot be used (%s)" % e.__class__.__name__
        raise RoomError("endpoint", _OPENER_PROBLEM, "fix WORKBENCH_MCP_CA_FILE in the kit's room file")
    _OPENER = urllib.request.build_opener(NoRedirect(), urllib.request.HTTPSHandler(context=ctx))
    return _OPENER


def classify_http(code, body_text, op, headers=None):
    """An HTTP failure from the endpoint, read for what a student can do about it. Never the token.
    The real class edge answers every auth failure with its 404 denial page (no JSON, no status
    distinction) — so a 404 page says both possibilities; an X-WB-Denied header, when the server adds
    it, names the one that applies. The room's own refusals carry a machine-readable `detail`."""
    body = body_text[:4000] if body_text else ""
    err = None
    try:
        parsed = json.loads(body)
        if isinstance(parsed, dict) and isinstance(parsed.get("error"), dict):
            err = parsed["error"]
    except Exception:
        err = None
    msg = str((err or {}).get("message", "")) if err else ""
    detail = str((err or {}).get("detail", "")) if err else ""
    ecode = (err or {}).get("code") if err else None
    low = (msg + " " + body).lower()
    if 300 <= code < 400:
        return RoomError("endpoint", "the address answered with a redirect (HTTP %d) — this bridge talks to the configured room address only and never carries the token elsewhere" % code,
                         "check WORKBENCH_URL in the kit's room file: it must be the room's own https address", "E_REDIRECT")
    denied = ((headers.get("X-WB-Denied") if headers else None) or "").strip()
    if denied:
        d = denied.lower()
        if "token" in d or "auth" in d or "credential" in d:
            return RoomError("unauthorized", "the class server did not accept the Jeeves token on this computer (%s)" % denied, TOKEN_HINT, "E_SCOPE")
        if any(w in d for w in ("generation", "revoked", "suspend", "reassigned", "reset", "fenced")):
            return RoomError("fenced", NOT_YOURS + " (%s)" % denied, NOT_YOURS_HINT, "E_SCOPE")
        if "budget" in d or "rate" in d:
            return RoomError("unavailable", "the class server is holding this connection back for a moment (%s)" % denied, "wait a minute and retry", "E_BUSY")
        return RoomError("locked", "the class server has not opened Jeeves for you (%s)" % denied, LOCKED_HINT, "E_SCOPE")
    if code == 401:
        return RoomError("unauthorized", "the Jeeves token on this computer is not accepted (expired, rotated or not the current one)", TOKEN_HINT, "E_SCOPE")
    if code == 403:
        if detail.startswith("course=closed") or "not open yet" in low or "closed jeeves" in low or "released" in low:
            return RoomError("locked", msg or "Jeeves is not open for students yet — the instructor opens it from the classroom", LOCKED_HINT, "E_SCOPE")
        if detail.startswith("course=fenced") or detail.startswith("course=suspended") or any(w in low for w in ("reset or given", "another account", "earlier generation", "suspended")):
            return RoomError("fenced", NOT_YOURS, NOT_YOURS_HINT, "E_SCOPE")
        return RoomError("refused", msg or "the room refused this request (403)", str((err or {}).get("hint", "") or "") if err else "", ecode or "E_SCOPE")
    if code == 404:
        if err:
            return RoomError("refused", msg or "not found", str((err or {}).get("hint", "") or ""), ecode)
        return RoomError("locked", DENIED_PAGE, DENIED_HINT, "E_NOTFOUND")
    if code == 429:
        return RoomError("unavailable", "the class server asks this connection to slow down (HTTP 429)", "wait a minute and retry", "E_BUSY")
    if code in (502, 503, 504):
        return RoomError("unavailable", "the room is not answering right now (HTTP %d)" % code,
                         "the class server or the room is starting or restarting — retry in a minute", "E_BUSY")
    if err:
        return RoomError("op", "%s: %s" % (ecode or "E_?", msg or "?"), str((err or {}).get("hint", "") or ""), ecode)
    return RoomError("refused", "HTTP %d from %s" % (code, op), "", "E_HTTP")


def call_api(op, params=None, actor=ACTOR, timeout=CALL_TIMEOUT_S):
    """POST /api/<op> {params, actor} → the data payload; raises RoomError (classified)."""
    ep = endpoint_problem()
    if ep:
        raise RoomError("endpoint", ep, "fix WORKBENCH_URL in the kit's room file (~/.config/jeeves/room.env)")
    tok = bearer()
    body = json.dumps({"params": params or {}, "actor": actor}).encode()
    headers = {"Content-Type": "application/json", "User-Agent": "jeeves-mcp/" + BRIDGE_VERSION}
    if tok:
        headers["Authorization"] = "Bearer " + tok
    req = urllib.request.Request(BASE + "/api/" + op, data=body, headers=headers, method="POST")
    try:
        with opener().open(req, timeout=timeout) as r:
            raw = r.read(MAX_BODY_BYTES + 1)
    except urllib.error.HTTPError as e:
        try:
            text = e.read(65536).decode("utf-8", "replace")
        except Exception:
            text = ""
        raise classify_http(e.code, text, op, e.headers)
    except urllib.error.URLError as e:
        reason = e.reason
        if isinstance(reason, ssl.SSLError):
            why = getattr(reason, "reason", None) or reason.__class__.__name__
            raise RoomError("endpoint", "the room's certificate is not trusted by this Python (%s)" % why, TLS_HINT, "E_TLS")
        if isinstance(reason, (TimeoutError, socket.timeout)):
            raise RoomError("unavailable", "the room did not answer within %d s" % timeout, timeout_hint(timeout), "E_BUSY")
        raise RoomError("unavailable", "cannot reach the room (%s)" % (reason.__class__.__name__ if not isinstance(reason, str) else "no route"), UNREACHABLE_HINT, "E_BUSY")
    except (TimeoutError, socket.timeout):
        raise RoomError("unavailable", "the room did not answer within %d s" % timeout, timeout_hint(timeout), "E_BUSY")
    except (http.client.HTTPException, ValueError) as e:
        raise RoomError("unavailable", "the connection to the room broke (%s)" % e.__class__.__name__, "retry; if it repeats, the room may be restarting", "E_BUSY")
    except OSError as e:
        raise RoomError("unavailable", "cannot reach the room (%s)" % e.__class__.__name__, UNREACHABLE_HINT, "E_BUSY")
    if len(raw) > MAX_BODY_BYTES:
        raise RoomError("op", "the room's answer is larger than %d MiB — ask with a limit or a narrower window" % (MAX_BODY_BYTES >> 20), "", "E_TOOLARGE")
    text = raw.decode("utf-8", "replace")
    try:
        resp = json.loads(text)
    except Exception:
        raise RoomError("endpoint", "the endpoint answered something that is not the room's JSON", "is WORKBENCH_URL the room's API address (not a web page)?", "E_HTTP")
    if not isinstance(resp, dict) or not resp.get("ok"):
        err = (resp or {}).get("error") or {} if isinstance(resp, dict) else {}
        text = "%s: %s (%s)" % (err.get("code", "E_?"), err.get("message", "?"), err.get("detail", ""))
        extra = {k: v for k, v in err.items() if k not in ("code", "message", "detail")}
        if extra:
            text += "\n" + json.dumps(extra, default=str)
        raise RoomError("op", text, str(err.get("hint", "") or ""), err.get("code"))
    return resp.get("data")


def timeout_hint(timeout):
    return "narrow the request (a limit, a smaller window) and retry — the room is still there; the class edge cuts requests longer than %d s" % timeout


CLIENT = None   # "<name>/<version>" from the MCP client's initialize — the audit trail names it


def call_tool(tool, args=None, actor=ACTOR, call_id=None, provenance=False, timeout=CALL_TIMEOUT_S):
    """One facade tool call through the backend's router (tool.call)."""
    params = {"tool": tool, "args": args or {}}
    if CLIENT:
        params["client"] = CLIENT
    if call_id is not None:
        params["callId"] = str(call_id)
    if provenance:
        params["provenance"] = True
    return call_api("tool.call", params, actor=actor, timeout=timeout)


# ── the pinned target (standalone) ────────────────────────────────────────────

def resolve_target(pin):
    """A target id as given, else the open target whose NAME equals `pin` (file name, with or without
    the directory) — exact matches only, through the facade (`target list`: the one path a room allows an
    assistant). Ambiguous or unknown → RoomError('refused') with the candidates: a wrong target is wrong
    evidence, never 'the first match'."""
    if not pin:
        return None
    rows = call_tool("target", {"action": "list"}, timeout=CATALOGUE_TIMEOUT_S)
    if isinstance(rows, dict) and "result" in rows:
        rows = rows["result"]
    targets = rows if isinstance(rows, list) else (rows.get("targets") if isinstance(rows, dict) else []) or []
    for t in targets:
        if isinstance(t, dict) and t.get("id") == pin:
            return pin
    base = os.path.basename(pin)
    hits = [t for t in targets if isinstance(t, dict) and (str(t.get("name", "")) == base or str(t.get("path", "")) == pin)]
    if len(hits) == 1:
        return hits[0]["id"]
    names = ", ".join("%s (%s)" % (t.get("id"), t.get("name")) for t in targets if isinstance(t, dict)) or "none open"
    if len(hits) > 1:
        raise RoomError("refused", "the target pin '%s' matches %d open targets" % (pin, len(hits)), "pin by target id — open targets: " + names, "E_SYNTAX")
    raise RoomError("refused", "the target pin '%s' matches no open target" % pin, "open targets: " + names, "E_NOTFOUND")


TARGET_ID = None   # the configured pin resolved to an id (re-resolved when the room no longer knows it)


def pinned_id():
    """The configured pin's id, resolved on first use and re-resolved after a miss."""
    global TARGET_ID
    if not TARGET:
        return None
    if TARGET_ID is None:
        TARGET_ID = resolve_target(TARGET)
    return TARGET_ID


# ── the catalogue (lazy, bounded, retried without storms) ─────────────────────

class Catalogue:
    def __init__(self):
        self.tools = None          # list of MCP tool dicts once known
        self.by_name = {}
        self.last_error = None     # RoomError
        self.last_try = 0.0
        self.loading = False
        self.lock = threading.Lock()

    def load(self):
        """The tools, fetched when unknown — bounded by CATALOGUE_TIMEOUT_S, not re-asked within RETRY_S of a
        failure (the status tool included), one fetch at a time, and the lock is never held across HTTP: a
        tools/list or a ping on the main loop never waits behind a slow room."""
        with self.lock:
            if self.tools is not None:
                return self.tools
            now = time.monotonic()
            if self.loading or (self.last_try and now - self.last_try < RETRY_S):
                return None
            self.loading = True
            self.last_try = now
        tools, err = None, None
        try:
            tools = self._fetch()
        except RoomError as e:
            err = e
        except Exception as e:
            err = RoomError("unavailable", "the catalogue could not be read (%s)" % e.__class__.__name__, "retry in a minute", "E_BUSY")
        with self.lock:
            self.loading = False
            if tools is not None:
                self.tools = tools
                self.by_name = {t["name"]: t for t in tools}
                self.last_error = None
                log("catalogue: %d tools from %s" % (len(tools), BASE))
            else:
                self.last_error = err
                log("catalogue not loaded (%s): %s" % (err.kind, err.message))
        return tools

    def _fetch(self):
        rows = call_api("ops.tools", timeout=CATALOGUE_TIMEOUT_S)
        tools = []
        if not isinstance(rows, list):
            raise RoomError("catalogue", "the room's tool catalogue has a shape this bridge does not know", "update the course kit (bridge %s)" % BRIDGE_VERSION, "E_UNSUPPORTED")
        for r in rows:
            if not isinstance(r, dict) or not r.get("tool") or not isinstance(r.get("parameters"), dict):
                raise RoomError("catalogue", "the room's tool catalogue has a shape this bridge does not know", "update the course kit (bridge %s)" % BRIDGE_VERSION, "E_UNSUPPORTED")
            tools.append({"name": r["tool"], "description": r.get("description", ""), "inputSchema": r["parameters"]})
        names = {t["name"] for t in tools}
        missing = [n for n in REQUIRED_TOOLS if n not in names]
        if missing:
            raise RoomError("catalogue", "the room's catalogue lacks %s — not the tools this bridge was built for" % ", ".join(missing),
                            "update the course kit (bridge %s), or the room runs another version" % BRIDGE_VERSION, "E_UNSUPPORTED")
        return tools


CAT = Catalogue()
ASKED = threading.Event()      # a client asked for the tools (tools/list) — the background retry runs only then
SERVING = False                # main() serves a client over stdio; --doctor/--selftest print reports there instead
STOPPING = False

EXPORT_TOOL = {
    "name": "jeeves_export",
    "description": "Course mode: export one symbol's reconstruction (a class or a method) as a bounded, immutable evidence artifact kept in your room — the "
                   "answer is its id, sha256 and size, which the Findings page / the classroom's submission step can take. Nothing is sent anywhere; the "
                   "classroom copies the artifact only after you select it there. Example: {\"symbol\": \"Lcom/example/Foo;->bar()V\", \"scope\": \"method\"}",
    "inputSchema": {"type": "object", "properties": {"symbol": {"type": "string", "description": "the class or method (as the code view names it, or the original Lpkg/Cls;->m()V)"},
                                                     "scope": {"type": "string", "enum": ["method", "class"], "description": "the reconstruction's extent (default method)"}},
                    "required": ["symbol"], "additionalProperties": False},
}

STATUS_TOOL = {
    "name": "jeeves_status",
    "description": "The Jeeves room's connection state as this bridge sees it: whether the room can be reached, whether the instructor has opened Jeeves, "
                   "whether the token is accepted, and what to do. Call it when the other Jeeves tools are missing or refuse. Example: {}",
    "inputSchema": {"type": "object", "properties": {}, "additionalProperties": False},
}


def status_report():
    """What a person (or the kit's doctor) needs: endpoint, token file, the room's state, the catalogue, next step."""
    rep = {"bridge": BRIDGE_VERSION, "endpoint": BASE, "token_file": token_state(), "pinned_target": TARGET, "client": CLIENT,
           "result_bound_chars": MAX_RESULT_CHARS, "call_timeout_s": CALL_TIMEOUT_S}
    ep = endpoint_problem()
    if ep:
        rep.update(state="endpoint", problem=ep, next="fix WORKBENCH_URL in the kit's room file (~/.config/jeeves/room.env)")
        return rep
    was_unknown = CAT.tools is None
    tools = CAT.load()
    if tools is not None:
        if was_unknown:
            announce_tools()
        rep.update(state="connected", tools=len(tools),
                   next="the Jeeves tools are available. If your assistant does not show them, it read the list before the room opened: "
                        "start a new session (or /mcp reconnect where your client has it) — this bridge also announces the change to clients that listen")
        return rep
    e = CAT.last_error
    rep.update(state=e.kind if e else "unknown", problem=e.message if e else "?", next=e.hint if e else "")
    if e and e.kind == "locked":
        rep["reconnect"] = "when the instructor opens Jeeves, start a NEW assistant session (or /mcp reconnect where your client has it) — most clients read the tool list once"
    if e and e.kind == "unauthorized":
        rep["reconnect"] = "install the current token (your Today page shows it) into the kit's token file; then start a new assistant session"
    return rep


def announce_tools():
    """The room opened after a client asked: tell it (clients that listen refresh their tool list).
    Only while serving a client — the doctor's stdout is its report."""
    if SERVING and ASKED.is_set():
        send({"jsonrpc": "2.0", "method": "notifications/tools/list_changed"})


def background_refresh():
    """While a client has asked and the catalogue is unknown: one re-try every BG_RETRY_S, and the
    announcement when it lands. One request per interval per bridge — no storm across a class."""
    while not STOPPING:
        time.sleep(BG_RETRY_S)
        if STOPPING or CAT.tools is not None:
            return
        if not ASKED.is_set():
            continue
        if CAT.load() is not None:
            announce_tools()
            return


# ── JSON-RPC plumbing ─────────────────────────────────────────────────────────

OUT_LOCK = threading.Lock()


def send(obj):
    try:
        with OUT_LOCK:
            sys.stdout.write(json.dumps(obj) + "\n")
            sys.stdout.flush()
    except (BrokenPipeError, ValueError):
        # the client went away: nothing to answer to
        raise SystemExit(0)


def result(req_id, res):
    send({"jsonrpc": "2.0", "id": req_id, "result": res})


def error(req_id, code, message, data=None):
    err = {"code": code, "message": str(message)}
    if data is not None:
        err["data"] = data
    send({"jsonrpc": "2.0", "id": req_id, "error": err})


def tool_text(data, provenance=None):
    """The tool result: the JSON text, cut at MAX_RESULT_CHARS with a declared cut; the provenance as a
    second content item when the room gave one — `truncated` true when either side cut, `truncated_by`
    naming which (room, bridge, or both)."""
    text = json.dumps(data, indent=2, default=str)
    cut = len(text) > MAX_RESULT_CHARS
    if cut:
        text = text[:MAX_RESULT_CHARS] + "\n…[result cut at %d of %d chars by the bridge — ask with a narrower window or a limit, or page]" % (MAX_RESULT_CHARS, len(text))
    content = [{"type": "text", "text": text}]
    if provenance:
        p = dict(provenance)
        room_cut = bool(provenance.get("truncated"))
        if cut or room_cut:
            p["truncated"] = True
            p["truncated_by"] = ",".join([w for w, on in (("room", room_cut), ("bridge", cut)) if on])
        content.append({"type": "text", "text": "provenance: " + json.dumps(p, default=str)})
    return {"content": content, "isError": False}


def tool_error(e):
    """A structured error result (the model sees it; the client shows it)."""
    d = e.as_dict() if isinstance(e, RoomError) else {"kind": "op", "message": str(e)}
    return {"content": [{"type": "text", "text": "error: " + d["message"] + (("\nhint: " + d["hint"]) if d.get("hint") else "") + "\n" + json.dumps(d, default=str)}], "isError": True}


# ── the main loop ─────────────────────────────────────────────────────────────

CANCELLED = set()
CANCEL_LOCK = threading.Lock()
CALLS = threading.BoundedSemaphore(MAX_CONCURRENT_CALLS)


def answer(req_id, frame):
    """A call's answer — unless the client cancelled it meanwhile. `frame` is ('result', res) or ('error', code, message)."""
    with CANCEL_LOCK:
        if req_id in CANCELLED:
            CANCELLED.discard(req_id)
            log("call %s cancelled by the client — result dropped (the room's op ran to its own bound)" % req_id)
            return
    if frame[0] == "result":
        result(req_id, frame[1])
    else:
        error(req_id, frame[1], frame[2])


def handle_call(req_id, params):
    """tools/call on its own thread, one of at most MAX_CONCURRENT_CALLS."""
    if not CALLS.acquire(blocking=False):
        answer(req_id, ("result", tool_error(RoomError("unavailable", "%d Jeeves calls are already running — wait for them to finish" % MAX_CONCURRENT_CALLS,
                                                       "retry after the pending calls answer", "E_BUSY"))))
        return
    try:
        answer(req_id, run_call(req_id, params))
    except SystemExit:
        raise
    except Exception as e:
        log("call %s failed in the bridge: %s" % (req_id, e.__class__.__name__))
        answer(req_id, ("error", -32603, "internal error in the bridge (%s)" % e.__class__.__name__))
    finally:
        CALLS.release()


def run_call(req_id, params):
    global TARGET, TARGET_ID
    name = params.get("name")
    arguments = params.get("arguments", {})
    if arguments is None:
        arguments = {}
    if not isinstance(name, str) or not name:
        return ("error", -32602, "tools/call needs a tool name")
    if not isinstance(arguments, dict):
        return ("error", -32602, "tools/call arguments must be an object")
    args = dict(arguments)
    try:
        if name == "jeeves_status":
            return ("result", tool_text(status_report()))
        if name == "jeeves_export" and COURSE_MODE:
            scope = args.get("scope") or "method"
            params = {"symbol": args.get("symbol"), "scope": scope}
            if CLIENT:
                params["client"] = CLIENT
            out = call_api("course.export.artifact", params)
            keep = {k: out.get(k) for k in ("schema", "artifact_id", "sha256", "size", "content_schema", "workspace_generation", "room", "created_at", "existing", "note") if k in out}
            keep["provenance"] = out.get("provenance")
            return ("result", tool_text(keep))
        tools = CAT.load()
        if tools is None:
            return ("result", tool_error(CAT.last_error or RoomError("unavailable", "the room's tool list is not loaded", "call jeeves_status", "E_BUSY")))
        if name not in CAT.by_name:
            return ("error", -32602, "Unknown tool: %s (tools/list names the room's %d tools and jeeves_status)" % (name, len(tools)))
        # standalone: the configured pin, added to EVERY call the model did not aim itself — deliberately
        # regardless of the tool's schema (the pin is this bridge's job, not the model's); a room pins its own
        if TARGET and "target" not in args:
            args["target"] = pinned_id()
        try:
            data = call_tool(name, args, call_id=req_id, provenance=True)
        except RoomError as e:
            if TARGET and args.get("target") == TARGET_ID and e.code == "E_NOTFOUND":
                if PINNED:
                    TARGET_ID = None                       # the room no longer knows that id: resolve the name again, once
                    args["target"] = pinned_id()
                    data = call_tool(name, args, call_id=req_id, provenance=True)
                else:
                    log("session pin %s is gone — unpinned" % TARGET_ID)   # closed elsewhere: answer from the active target
                    TARGET = TARGET_ID = None
                    del args["target"]
                    data = call_tool(name, args, call_id=req_id, provenance=True)
            else:
                raise
        provenance = None
        if isinstance(data, dict) and "provenance" in data and "result" in data and len(data) == 2:
            provenance = data["provenance"]   # a course room answers {result, provenance}
            data = data["result"]
        # standalone session pin: the target this client opened or switched to becomes the target of its
        # later calls; closing the pinned one moves the pin to whatever is active now
        if not COURSE_MODE and not PINNED and name == "target" and args.get("action") in ("open", "switch", "close") and isinstance(data, dict):
            new_pin = data.get("id") or data.get("activeTarget")
            if args.get("action") == "close" and args.get("target") not in (None, TARGET_ID):
                new_pin = TARGET_ID
            if new_pin != TARGET_ID:
                TARGET = TARGET_ID = new_pin
                log("session pinned to %s (%s)" % (TARGET_ID, data.get("name")) if TARGET_ID else "session unpinned")
        return ("result", tool_text(data, provenance))
    except RoomError as e:
        return ("result", tool_error(e))


def dispatch(req_id, method, params):
    global CLIENT, PROTOCOL_VERSION
    if method == "initialize":
        ci = params.get("clientInfo") or {}
        if isinstance(ci, dict) and ci.get("name"):
            CLIENT = str(ci.get("name"))[:60] + ("/" + str(ci.get("version"))[:20] if ci.get("version") else "")
            log("client: %s" % CLIENT)
        asked = str(params.get("protocolVersion") or "")
        PROTOCOL_VERSION = asked if asked in PROTOCOL_VERSIONS else PROTOCOL_VERSIONS[0]   # unknown → the newest this bridge speaks
        # answered at once: the catalogue is the first tools/list's business, bounded there
        result(req_id, {"protocolVersion": PROTOCOL_VERSION, "capabilities": {"tools": {"listChanged": True}}, "serverInfo": SERVER_INFO,
                        "instructions": "Jeeves: the course's static-analysis workbench on your assigned room. Tools answer with the evidence and its provenance "
                                        "(target hash, original symbol, project revision). If the tools are missing or refuse, call jeeves_status — it says whether "
                                        "the room is open, reachable and your token accepted, and what to do. Questions, help requests and findings go through the "
                                        "classroom's own tools and the Findings page, not through Jeeves."})
        threading.Thread(target=background_refresh, name="catalogue-refresh", daemon=True).start()
    elif method == "notifications/initialized":
        pass   # the catalogue and the pin resolve on the first tools/list and the first call — nothing blocks the loop here
    elif method == "tools/list":
        ASKED.set()
        tools = CAT.load()
        result(req_id, {"tools": (tools or []) + ([EXPORT_TOOL] if COURSE_MODE and tools else []) + [STATUS_TOOL]})
    elif method == "tools/call":
        threading.Thread(target=handle_call, args=(req_id, params), name="call-%s" % req_id, daemon=True).start()
    elif method == "notifications/cancelled":
        rid = params.get("requestId")
        with CANCEL_LOCK:
            CANCELLED.add(rid)
        log("cancel requested for %s" % rid)
    elif method == "ping":
        result(req_id, {})
    elif method in ("resources/list", "resources/templates/list"):
        result(req_id, {"resources": [], "resourceTemplates": []} if method.endswith("templates/list") else {"resources": []})
    elif method == "prompts/list":
        result(req_id, {"prompts": []})
    elif method == "logging/setLevel":
        result(req_id, {})
    elif req_id is not None:
        error(req_id, -32601, "method not found: %s" % method)


def main():
    global STOPPING, SERVING
    SERVING = True
    if os.environ.get("WORKBENCH_MCP_CATALOGUE") or os.environ.get("WORKBENCH_MCP_CURATED"):
        log("note: WORKBENCH_MCP_CATALOGUE / WORKBENCH_MCP_CURATED no longer do anything — one catalogue (the facade)")
    if COURSE_MODE and os.environ.get("WORKBENCH_TARGET"):
        log("note: a room pins its course target — WORKBENCH_TARGET is ignored in course mode")
    ep = endpoint_problem()
    log("up — bridge %s · endpoint %s%s%s" % (BRIDGE_VERSION, BASE, (" · pinned to " + TARGET) if TARGET else "", (" · ENDPOINT PROBLEM: " + ep) if ep else ""))
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            msg = json.loads(line)
        except Exception:
            error(None, -32700, "parse error")
            continue
        if isinstance(msg, list):
            error(None, -32600, "batch requests are not supported by this server")
            continue
        if not isinstance(msg, dict):
            error(None, -32600, "invalid request")
            continue
        req_id = msg.get("id")
        method = msg.get("method")
        params = msg.get("params")
        if not isinstance(params, dict):
            params = {}
        if not isinstance(method, str):
            if req_id is not None:
                error(req_id, -32600, "invalid request: no method")
            continue
        try:
            dispatch(req_id, method, params)
        except SystemExit:
            raise
        except Exception as e:
            log("request %s failed in the bridge: %s" % (method, e.__class__.__name__))
            if req_id is not None:
                error(req_id, -32603, "internal error in the bridge (%s)" % e.__class__.__name__)
    STOPPING = True


# ── doctor ────────────────────────────────────────────────────────────────────

def doctor():
    rep = status_report()
    print(json.dumps(rep, indent=2))
    return 0 if rep.get("state") == "connected" else 1


# ── selftest ──────────────────────────────────────────────────────────────────

def selftest():
    tools = CAT.load()
    if tools is None:
        raise SystemExit("catalogue not loaded: %s" % (CAT.last_error.message if CAT.last_error else "?"))
    print("tools/list: %d tools" % len(tools))
    by_name = {t["name"]: t for t in tools}
    assert len(tools) >= 13, "expected the facade (25 tools standalone, the classroom's 13 in a room), got %d" % len(tools)
    for t in tools:
        assert t["inputSchema"].get("additionalProperties") is False, "an open schema: %s" % t["name"]
        assert t["description"] and "Example:" in t["description"], "a tool without a worked example: %s" % t["name"]
    for gone in ("rename", "wb_rename", "agent_review", "bookmark", "device_pull", "map_export", "agent_follow", "ops_tools", "ui_prefs"):
        assert gone not in by_name, "%s is advertised" % gone
    ann = by_name["annotate"]["inputSchema"]
    assert set(ann.get("required", [])) == {"items"}, ann
    item = ann["properties"]["items"]["items"]
    assert item.get("required") == ["symbol"] and {"name", "comment", "tags"} <= set(item["properties"]), item
    if "review" in by_name:
        rev = by_name["review"]["inputSchema"]["properties"]
        assert {"policy", "configure", "proposals", "accept", "reject", "purge"} <= set(rev["action"]["enum"]), rev
    print("facade shape: %d closed schemas, annotate items[{symbol!, name, comment, tags}]%s" % (len(tools), ", review actions" if "review" in by_name else ""))

    ws = call_tool("workspace")
    if isinstance(ws, dict) and "result" in ws and "provenance" in ws:
        ws = ws["result"]
    print("workspace: active project %s" % ws.get("activeProject"))

    maps = call_tool("map", {"action": "list"})
    if maps:
        first = maps[0]["id"] if isinstance(maps[0], dict) else maps[0]
        summ = call_tool("map", {"action": "summary", "id": first})
        print("map summary (%s): %s nodes / %s edges / %s proven" % (first, summ["nodes"], summ["edges"], summ["proven"]))
    else:
        print("map list: no maps in the active project (summary skipped)")

    if "--write" in sys.argv:
        tl = call_tool("target", {"action": "list"})
        cur = (tl[0] if isinstance(tl, list) and tl and isinstance(tl[0], dict) else {}).get("id")
        if cur:
            sw = call_tool("target", {"action": "switch", "target": cur})
            assert sw.get("id") == cur and sw.get("activeTarget") == cur and isinstance(sw.get("targets"), list), sw
            print("target switch → {id %s, activeTarget %s, %d targets}" % (sw["id"], sw["activeTarget"], len(sw["targets"])))

    # an unknown action is a syntax error naming the allowed ones, not a silent pass-through
    try:
        call_tool("project", {"action": "explode"})
        raise AssertionError("an unknown action went through")
    except RoomError as e:
        assert "E_SYNTAX" in str(e.message), e.message
    print("project {action explode} → E_SYNTAX")

    if "--write" not in sys.argv:
        print("write probe skipped (pass --write against the IT fixture)")
        print("SELFTEST PASSED")
        return
    policy = call_tool("review", {"action": "policy"}, actor="human:selftest")
    res = call_tool("annotate", {"target": "t1", "items": [{"symbol": "onMessage", "name": "mcpProbe", "confidence": 0.9}]})
    row = res["results"][0]["rename"]
    if policy.get("mode") == "manual":
        assert row.get("status") == "pending" and res["pending"] == 1, res
        print("llm annotate → pending proposal %s (manual policy)" % row["id"])
        props = call_tool("review", {"action": "proposals"}, actor="human:selftest")
        assert any(p["id"] == row["id"] for p in props), "proposal not in the review queue"
        try:
            call_tool("review", {"action": "reject", "id": row["id"]})
            raise AssertionError("llm actor settled its own proposal — the review gate is broken")
        except RoomError as e:
            assert "humans dispose" in str(e.message), e.message
        print("review reject as llm:mcp → refused (agents propose, humans dispose)")
        rej = call_tool("review", {"action": "reject", "id": row["id"]}, actor="human:selftest")
        assert rej.get("state") == "rejected", rej
        print("review reject as human → rejected (queue clean)")
    else:
        assert row.get("status") == "applied" and res["applied"] == 1, res
        print("llm annotate → applied at once (auto policy), history %s" % row.get("id"))
        hist = call_tool("history", {"action": "list", "target": "t1", "limit": 5}, actor="human:selftest")
        newest = hist[0] if isinstance(hist, list) and hist else None
        assert newest and newest.get("newName") == "mcpProbe", hist
        call_tool("history", {"action": "undo", "target": "t1", "id": newest["id"]}, actor="human:selftest")
        print("history undo as human → reverted (target clean)")
    print("SELFTEST PASSED")


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        selftest()
    elif "--doctor" in sys.argv:
        sys.exit(doctor())
    else:
        main()
