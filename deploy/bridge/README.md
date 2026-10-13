# Jeeves for your assistant — the course kit's bridge

Jeeves is the course's code-analysis workbench. Your room runs on the class server; you use it in the
browser from your course home. This small program (`jeeves-mcp`) lets your AI coding assistant — Claude
Code, Codex, OpenCode or Kimi Code — use the same room: read code, symbols, cross-references, strings,
search results and annotations, and quote them with their provenance. It installs nothing but itself:
one Python 3.9+ and this folder. No JEB, no licence, no token in the kit.

## Set-up (the course kit does this; here is what should be in place)

1. **The room file** `~/.config/jeeves/room.env` — two lines:
   ```
   WORKBENCH_URL=https://room-7.<class domain>
   WORKBENCH_BEARER_FILE=/Users/<you>/.config/jeeves/room-token
   ```
   `WORKBENCH_URL` is your room's address from your course home (Today page). It must be `https://`.
2. **The token file** named there — one line: the Jeeves token your Today page shows (`chmod 600`).
   The bridge re-reads it on every call, so a new token is a file write — no restart.
3. **Your assistant knows the bridge** (`<kit>` is this folder's path; the kit's installer runs one of these):
   - Claude Code: `claude mcp add --scope user jeeves -- "<kit>/jeeves-mcp"`
   - Codex: `codex mcp add jeeves -- "<kit>/jeeves-mcp"`
   - OpenCode: `opencode.json` → `"mcp": {"jeeves": {"type": "local", "command": ["<kit>/jeeves-mcp"], "enabled": true}}`
   - Kimi Code: `~/.kimi-code/mcp.json` → `"mcpServers": {"jeeves": {"command": "<kit>/jeeves-mcp"}}`
4. **Check**: `"<kit>/jeeves-mcp" --doctor` prints one report — `"state": "connected"` means your assistant
   will see the Jeeves tools. Any other state says what is missing and what to do (the table below).

Optional lines in the room file: `WORKBENCH_MCP_CA_FILE=<path>` (the class CA bundle, if the course
gives one), `JEEVES_PYTHON=<python3 path>` (which Python to use), `WORKBENCH_MCP_MAX_CHARS` (how much
of a long result reaches your assistant, default 80,000 characters), `WORKBENCH_MCP_CALL_TIMEOUT`
(seconds per call, default 75).

## What you see in your assistant, and when

Your assistant reads the tool list when a session starts. If Jeeves was not open at that moment, you
see ONE tool, `jeeves_status`; ask your assistant to call it and it tells you the state and the next
step. The bridge announces the room's opening to assistants that listen for it; a new session always
shows the tools.

| `jeeves_status` says | what it means | what you do |
|---|---|---|
| `connected` (N tools) | your assistant can use Jeeves | work; if the tools are not showing, start a new assistant session |
| `locked` | the class server did not let this connection in: Jeeves is **not open yet** — *or* the **token** on this computer is not the current one (the server's answer does not say which) | if the instructor says Jeeves is open, install the current token from your Today page and start a new session; otherwise wait for the go — the classroom (slides, materials, messages) stays available |
| `unauthorized` | the token file is missing, empty, or the server refused the token (when it says so) | install the current token from your Today page; new session |
| `fenced` | this room is no longer yours: it was reset or reassigned, or your account is paused | open your course home for your current room and connect from there |
| `unavailable` | the room or the class server is not answering, or a call took longer than the bound | retry in a minute; for a slow call, ask for less (a limit, a smaller window) |
| `catalogue` | the room runs tools this bridge does not know | update the course kit |
| `endpoint` | the room file is wrong (not https, a redirect, an untrusted certificate, a bad CA file) | fix the room file; for a certificate error on a python.org Python (macOS), run `Install Certificates.command` once |

The bridge never bypasses the room's gate: when the instructor has not opened Jeeves, nothing on your
computer can open it.

## What your assistant can do here — and what belongs elsewhere

In the classroom your assistant gets the analysis tools: `symbols`, `code`, `xrefs`, `strings`,
`search`, `annotate` (rename, comment, tag), `map`, `workspace`, `history` (read), `target` (list,
info, changes, save, switch), `project` (list), `audit`, `presence`, plus two of the bridge's own:
`jeeves_status` (above) and `jeeves_export` (below). Opening or closing targets, undoing history,
running the app on a device and starting Jeeves' own autonomous agent are yours to do from the page —
an assistant asking for them is told so, in plain words.

**Questions, help requests, feedback and findings go through the classroom's own tools and the
Findings page**, not through Jeeves. Jeeves gives your assistant the evidence; you submit it.

## Results, evidence, exports

Every result carries a second item, `provenance: {...}`: the exact target the call ran on (your room's
course target: id, name, APK sha256, whether it matches the course's), the project revision at that
moment, the tool and symbol, your assistant's name and the call id, and `truncated` — true when the
room or the bridge cut the result (`truncated_by` says which). Rows carry the original DEX name
(`origSig`) next to any name an analyst gave (`analystNamed`): names are hypotheses, `origSig` is the
locator. Long results are cut at 80,000 characters with a note — ask for less, or page.

`jeeves_export {symbol, scope}` writes one symbol's reconstruction as an immutable evidence artifact
in your room and answers its id, sha256 and size. Nothing leaves the room: the Findings page / the
classroom's submission step takes the id and copies the artifact only after you select it there. The
same export twice is the same artifact.

A call is bounded at 75 s (the class edge's limit): a very wide search comes back as `unavailable`
with "did not answer within 75 s" — narrow it and retry; the room is fine.

## What the room records

The room records your assistant's **tool calls**: the tool, its arguments, the full answer (up to
200,000 characters), when, which target and which revision — as part of the course's analysis record,
under your account. It does **not** see or record your conversation with the assistant (that stays
between you and your assistant's provider). An assistant navigating code never starts the room's own
agent.

## For the kit's maintainers

| file | what |
|---|---|
| `jeeves-mcp` | the launcher (bash): reads the room file (`JEEVES_ROOM_FILE` overrides the path; `export` prefixes and quotes are fine; the environment wins), drops any `WORKBENCH_BEARER` from the environment (the token lives in the file), finds a Python 3.9+ (`JEEVES_PYTHON`), execs the bridge. `--doctor` prints one connection report (exit 0 connected / 1 not / 3 no Python). Paths with spaces are fine. |
| `workbench_mcp.py` | the bridge (`BRIDGE_VERSION`); `--doctor`, `--selftest` |
| `VERSION.json` | version, the Jeeves commit it was built from and that commit's date (two builds of one commit are the same bytes) |
| `CHECKSUMS.sha256` | sha256 of every file in the kit |

Build: `deploy/bridge/build-bridge.sh` (from a committed tree) → `deploy/bridge/out/jeeves-mcp-bridge-<version>-<rev12>/`
and a deterministic `.tar.gz` (+ `.sha256`).

Behaviour the server side relies on: `initialize` is answered before any HTTP; the catalogue
(`ops.tools`) is fetched on the first `tools/list` with a 10 s bound and re-asked at most every 5 s
while it fails, plus one background retry every 30 s once a client asked (announced with
`notifications/tools/list_changed`); every tool call is `POST /api/tool.call {tool, args, client, callId,
provenance: true}` with `Authorization: Bearer <room token>`; `jeeves_export` is `POST /api/course.export.artifact`.
The room allows a token exactly these ops (`tool.call`, `ops.tools`, `course.status`,
`course.export.artifact`) and refuses the rest. TLS is validated (default store, certifi fallback,
or `WORKBENCH_MCP_CA_FILE`); redirects are refused; `http://` only to loopback; no user:password,
query or fragment in the URL; a token file that is missing, empty or not one printable line stops the
call before any HTTP; at most 4 concurrent calls; results are read up to 16 MiB then cut to the
character bound; a cancelled call's result is dropped (the room's op still finishes). Token material
never appears in logs, errors or results. The real class edge answers every authentication failure
with its 404 denial page — the bridge's `locked` wording names both causes; an `X-WB-Denied` header
on that page, when the server adds one, selects `unauthorized` / `fenced` / `locked` exactly.

Qualification: `mcp/bridge_qualify.py` (`.qa/scripts/a10-bridge-qualify.sh` boots a course-mode
room behind a stand-in edge that answers like the real one, a TLS edge with a throwaway certificate,
and a fake room) — 36 checks over the real stdio transport; client handshakes
(`.qa/scripts/a10-client-handshakes.sh`) are a separate receipt per client version. A client's
model-driven tool loop is a separately authorized live run.
