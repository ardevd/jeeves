# Jeeves ↔ class server handoff — v2 (2026-10-09, the A10 contract)

Status: **the Jeeves side of the server's frozen contract**. The server owns the contract
(its integration contract, read 2026-10-09). Jeeves owns
its implementation, in `backend/src/main/java/dev/workbench/course/`. Everything here is what the
room DOES against that contract, checked against an in-process fixture that speaks it
(`backend/src/test/java/dev/workbench/it/CourseIT.java`, 93 checks) and on the real engine
(`PacketIT`, 26). v1 of this document (2026-10-07) proposed shapes the server did not adopt
(`/v1/room`, the room posting to the board, a room-settable activity): they are gone from the room.


## 1. Deployment shape

One Jeeves process per room, the server's `bin/jeeves-room` entrypoint, bound to
`WORKBENCH_HOST:WORKBENCH_PORT` (loopback, `api_port_base + slot`), behind the class's nginx. The
environment is the server's launch plan — the room reads nothing else:

```
WORKBENCH_HOME=/srv/room-home                      # the private durable home (store, capture, files, tmp)
WORKBENCH_HOST=127.0.0.1  WORKBENCH_PORT=<api port>
WORKBENCH_COURSE=1  WORKBENCH_COURSE_ROOM=room-N
WORKBENCH_COURSE_ACCOUNT_ID=<32 hex>  WORKBENCH_COURSE_GENERATION=<32 hex>  WORKBENCH_COURSE_SESSION_ID=<epoch>
WORKBENCH_COURSE_CREDENTIALS=$CREDENTIALS_DIRECTORY  # room-N--auth, room-N--store, room-N--gateway, room-N--classroom
WORKBENCH_COURSE_PROXY_TOKEN_FILE=$CREDENTIALS_DIRECTORY/nginx-token
WORKBENCH_COURSE_AUTH_URL / _STORE_URL / _GATEWAY_URL / _CLASSROOM_URL=http://127.0.0.1:<port>
WORKBENCH_COURSE_MANIFEST=/run/jeeves-target.json  # day1-target-manifest-v1
WORKBENCH_COURSE_CAPTURE=strict  WORKBENCH_COURSE_CAPTURE_DURABLE=1
WORKBENCH_COURSE_PORTAL_URL=https://<domain>  WORKBENCH_COURSE_TRUST_PROXY=127.0.0.1  WORKBENCH_COURSE_EVAL_TAINT=true
JAVA_TOOL_OPTIONS=-Xmx16g
```
Optional (not in the wrapper today): `WORKBENCH_COURSE_ROOTS` (read-only course folders the room may
browse/open), `WORKBENCH_COURSE_PROVIDER` (the Day 1 preset), `WORKBENCH_LLM_*` (the class gateway as a
managed provider, its key from a FILE), `WORKBENCH_COURSE_CAPTURE_ROOT`. Unset `WORKBENCH_COURSE` → a
standalone workbench; course variables without the switch → no boot (named). The room FAILS CLOSED
at boot without: the room name, the auth credential file, the manifest setting, the real engine
(`WORKBENCH_COURSE_ALLOW_MOCK=1` is for tests). The launcher's `WORKBENCH_COURSE_ACCOUNT_ID /
_GENERATION / _SESSION_ID` must be the credential's owner per auth, or the room is FENCED.

## 2. What the room does with each server surface

| Surface (server) | Room (Jeeves) |
|---|---|
| nginx → `Authorization: Bearer <nginx-token>`, `X-WB-Principal: N`, `X-WB-Grant`, `X-Forwarded-Host/Proto` | `CourseIdentity.authorize`: the peer must be in `TRUST_PROXY`; the bearer must equal `PROXY_TOKEN_FILE` (constant-time) when configured; the principal must be this room (`N` or `room-N`); a grant is REQUIRED — browser and machine alike; no grant → 404 |
| `POST auth:/internal/grant {audience, grant}` → `{…, machine, account_id, workspace_generation, course_session_id, policy_revision, valid_for_seconds}`; 403 while unreleased / owner gone | validated with a 1.5 s bound, cached ≤ 2 s, every second on streams (browser AND machine); the grant's `account_id`/`workspace_generation` must equal the room context's owner → else 403 ("another account" / "earlier generation"); 401 → sign in; 403 "released" → "Jeeves access has not been released by the instructor…"; the actor is DERIVED: `human:<handle>` for a browser, the body's kind (`llm:<name>`) within the room for a machine grant, never `agent-N` |
| `POST auth:/internal/jeeves/context {}` (room credential) → `jeeves-room-v1` | `CoursePolicy`: polled every 1 s (1.5 s bound); `enabled` only while a fresh (≤ `enforcement_seconds` = 5) answer says `access_allowed`; `disabled` / `fenced` (403 fenced/revoked, or the launch identity differs) / `unknown` (no good answer for 5 s) / `starting`; leaving `enabled` fences the room (below) |
| `POST auth:/internal/jeeves/observe {…}` → `{received, received_at, valid_for_seconds: 15}` | every 5 s, the EXACT fields: `sequence` (a per-generation counter persisted beside the capture — `<capture>/observe.seq` — seeded from the context's `next_observation_sequence`, never a clock; a 409 re-reads the context and posts once more), `runtime_revision`, `package_sha256` (from the package manifest; 40/64 zeros + "unpinned" in `engine.detail` on a development checkout), `target_sha256` (the open target's digest; zeros when none), `components{engine,target,capture,model,classroom}{state,detail ≤ 100 chars and ≤ 300 UTF-8 bytes}` — `engine` is `ready` only once the JEB core has started (its version known), `starting` with JEB on the classpath and no core yet — `active_missions`, `open_streams`, `pending_capture`, `policy_revision`, `fenced`, `cancellation_unconfirmed`. The status behind an observation is computed with a 3 s deadline; a slow one falls back to the last snapshot |
| `POST classroom:/v1/room/context {}` → `jeeves-classroom-v1 {owner, activity{id,revision,sha256}, teaching_revision, actions{…{sent:false, browser}}}` | `CourseActivity`: every 15 s and on demand; the identity every event carries (`activity_id/_revision/_sha256/_source/_stale`); stale after 60 s without an answer; `course.activity` is READ-ONLY (a supplied id → E_SCOPE); the `actions` URLs are the handoff destinations |
| `POST store:/v1/events` → `{event_id, seq, duplicate, durable}`; 409 / 503; `GET /health {status: ready|stalled|blocked}` | unchanged admission protocol (strict before dispatch, same-id retries, duplicate = admission); envelopes carry `account_id`, `workspace_generation`, `course_session_id`, `policy_revision`, the activity identity; `> 1 MiB` payloads as digest + `payload_location{where, path, sha256, bytes, capture_home_durable}` and `payload_complete:false`; blob files AND directories fsynced; `capture.homeDurable` (declared + fsync works) and `capture.eventExportDurable` reported apart |
| `GET gateway:/health {state: OPEN|…, student, route{provider, qualification, state, reason}}` | `OPEN` = the scheduler accepts work → `dependencies.gateway: ok`; any other state is that word (lower case); `route` → `course.status.model` (`ready` only with `qualification: qualified`; a fixture/unconfigured route is `degraded`/`blocked`; `verified: false` always from the room) |
| `POST gateway:/v1/chat/completions` | the room registers the gateway as its MANAGED provider `class` at boot (`Course.gatewayUrl()` + `/v1`, stream-only, `reasoning_content:all`, max_tokens 8192), its bearer the `room-N--gateway` credential FILE read at call time (never env/argv); the served model ids come from the connection card as `WORKBENCH_COURSE_CLASS_MODELS` (not guessed — empty = `course.status.model: unconfigured`); the Day 1 preset defaults to `class:<first model>`. The agent loop's streaming client sends `X-Request-ID` = the instance-qualified logical id. `llm.setkey` is refused in a room (keys are enrolled on the classroom's Provider page) |
| Findings, help, readiness (browser-only) | handoffs only: `course.share` composes the Findings form's FIELDS (`fields{type ∈ finding|tool|comparison|workflow|image, title, summary, details, original_symbol, symbol, artifact_sha256, project_revision, activity_id, activity_revision, request_id}`) plus `provenance{…}` and answers `{sent:false, reason: human_action_required, browser: <actions.finding or portal/classroom/findings>#jeeves=<url-safe base64 of the small fields — a prefilled draft a page may read, proposed to the server agent>, browserPlain, copy: <the Details text>}` + a private `contribution.draft` event; a machine client (the student's assistant) may compose too — nothing is sent either way. `course.help` / `course.thread` → the messages page with the words + ONE context line (activity · target · symbol). `course.progress {status: ready|help|not_ready}` (Today's three states; `working` = Not ready yet) → the Today page. A room credential never posts. |
| The student's assistant (the MCP bridge, `mcp/workbench_mcp.py` 0.9.2 — the kit `deploy/bridge/`, README for the student) | the bridge talks to the room's HTTPS address through the edge with the room token the course issued (a one-line file, re-read per call; a missing/empty/malformed file stops the call before any HTTP; redirects refused; non-loopback must be https; TLS validated, `WORKBENCH_MCP_CA_FILE` optional); `initialize` answered at once, the catalogue fetched lazily on `tools/list` (10 s bound, re-asked ≤ every 5 s, a 30 s background retry announcing `notifications/tools/list_changed` when the room opens) — until then ONE tool, `jeeves_status`, says the state (locked / unauthorized / fenced / unavailable / catalogue / endpoint) and the next step in the student's words; the REAL edge's 404 denial page is read as "not open yet — or the token is not current" (an `X-WB-Denied` header, when the server adds one, selects the exact state); each call on its own thread (≤ 4), bounded at 75 s (the edge's proxy_read_timeout), `notifications/cancelled` drops the pending result; results bounded (80,000 chars, declared, under Claude Code's MCP output cap) with a `provenance:` item from the room; the room allows the token exactly `tool.call`, `ops.tools`, `course.status`, `course.export.artifact` and refuses every other op; the catalogue is the classroom's subset enforced by the room; the room pins its course target; the room captures the assistant's tool calls (`assistant.tool_call`) and its annotations under `assistant-<client>`, never its model transcript, and starts no mission for it; `jeeves_export` → `course.export.artifact` (owner-keyed id). What the server supplies: the room's HTTPS address and the student's room token as the kit's two files (`~/.config/jeeves/room.env`, the token file), the nginx room location forwarding `Authorization: Bearer <room API token>` → `X-WB-Principal` + `X-WB-Grant` (machine grant) + `Authorization: Bearer <nginx-token>` to the room, `X-WB-Denied` on the denial page (asked), the artifact export's consumer (by owner-keyed id; `{action: get}` or the meta file beside the content), the room location's read timeout ≥ 75 s. |
| Cleanup (the server's release step) | on SIGTERM the room first FENCES its missions (their terminal records written), waits up to 3 s for their threads, then writes `<capture>/receipt.json` (`jeeves-cleanup-receipt-v1`; also by `course.receipt {write:true}`): `account_id`, `workspace_generation`, `course_session_id`, `pid`, `capture_root`, `capture_home_durable`, every run's journal path/bytes/sha256/events, blobs count/bytes, `pending_export`, `refused`, `event_export_durable`. `course.reset` is an EDIT rollback and says the capture is never reset. |

## 3. The fence (what "disabled" does inside the room)

Within the enforcement window (5 s) of the context saying `access_allowed: false`, of a 403 on the
credential, of the launch identity not matching, or of 5 s without a good answer:
- new requests: 403 `E_SCOPE` (disabled/fenced) or 503 `E_BUSY` (unknown/starting) with the reason;
  `course.status` keeps answering for a session that still validates (the instructor's room, the
  window between the room's own fence and the edge's). **What a student's browser actually sees is
  the EDGE's answer** (nginx `auth_request` → `room_guard` on every room request: a 403 from auth
  becomes the classroom's 404 "denied" page, an auth outage its 503 page — no JSON, no reason). The
  web client therefore classifies by HTTP status, not by prose: 401/403/404 after the page lived →
  "NO ACCESS" (the classroom closed Jeeves or the session ended — the course home says which);
  5xx / no answer → "NO ANSWER, retrying every 5 s". The room's own reasons reach a page only
  through the stream's `closed` event (while the session still validates) and in room 26. A
  denial reason header from the edge (`X-WB-Denied: not-released|session|owner|auth-unavailable`)
  would let the page say which — proposed to the server agent (persona pass 2026-10-09 C1);
- open SSE streams: closed at the next 1 s check (a `closed` event with the reason when the session
  still validates); the browser does NOT retry a stream the server ended with a non-200 — the web
  client re-creates it with backoff (2 s → 30 s) and the STATE item says "LIVE PAUSED" meanwhile;
- missions running/paused/queued: `AgentManager.fence` (on its own thread) — `stopped`, `fenced:true`,
  `stopReason: "fenced: …"`, the loop's thread interrupted (a request in flight returns as a recorded
  failure; the remaining tool calls of the turn — writes included — are not run),
  `cancellationUnconfirmed:true` → `displayState: draining` until the thread returns, then
  `cancelled`; the capture's `mission.terminal` says `cancelled` with the fence reason; a request
  about to leave is journaled with `dispatched:false, fenced:true` and not sent. Paused missions
  are fenced too (the server's `active_missions` accounting counts them) — a decision question for
  the instructor: keep them paused instead (nothing of theirs is in flight)?
- re-enabling: requests served again; nothing restarts by itself; the fenced missions stay cancelled.
Measured on the fixture (CourseIT §7/§8): mission fenced 911 ms after the withdrawal; stream closed
1.9 s; auth outage → access `unknown` at 4.7 s, stream closed at 4.7 s, requests 503 meanwhile.

## 4. Confinement

`fs.list` shows `<home>/files` (the student's) and `WORKBENCH_COURSE_ROOTS` only — parent `""` at a
root, `/` and the home itself refused (E_SCOPE), an entry whose canonical path leaves the roots (a
symlink out) hidden and counted; `target.open` only under the home or a root, checked on the
canonical path before any read; `device.*` refused ("this room runs on the class server, not on your
laptop"); cli/claude missions refused (no CLI, nothing capturable). Not ported from the hosted
worktree: the upload endpoint, the SSH/port kit, the loopback-provider ban (the class gateway IS a
loopback provider).

## 5. Event envelope (as journaled and exported) — additions since v1

```json
{"schema": "day1-event-v1", "event_id": "c4590d7a:m1:t1:a1:model.request", "room": "room-7", "actor": "human:alice",
 "run_id": "c4590d7a-m1", "mission": "m1", "conversation_id": "c4590d7a-m1-c1", "turn_id": "t1", "request_id": "c4590d7a-m1-t1-a1", "attempt": 1,
 "activity_id": "E4b", "activity_revision": 5, "activity_sha256": "<64 hex>", "activity_source": "classroom", "activity_stale": false,
 "account_id": "<32 hex>", "workspace_generation": "<32 hex>", "course_session_id": "<epoch>", "policy_revision": 1,
 "target": "base.apk", "target_sha256": "<64 hex>", "target_manifest_sha256": "<64 hex>", "original_method": "L…;->run()V", "snapshot_id": "t1@h17",
 "provider": "class", "model_requested": "kimi-k3", "event_type": "model.request",
 "payload_blob_sha256": "<64 hex>", "payload_bytes": 18308, "visibility": "private-research", "eval_taint": true, "ts": "…"}
```
New event types: `contribution.draft` (`sent:false` — a finding composed for the Findings page; never a
publication). A `model.request` with `dispatched:false, fenced:true, fence_reason` is a request the
fence stopped. Exported payloads carry `payload_complete` (true inline) or `payload_location`.

## 6. Example errors a client sees (changed since v1)

| Situation | Answer |
|---|---|
| No grant on the request (the old bearer path) | 404 `E_NOTFOUND` "no session grant on the request (X-WB-Grant) — open the room through the class portal, or point the MCP client at the room's API URL" |
| The edge's credential missing (when `PROXY_TOKEN_FILE` is set) | 404 `E_NOTFOUND` "not reached through the class server (no edge credential on the request)" |
| Unreleased (auth 403 "released") | 403 `E_SCOPE` "Jeeves is not open yet — the instructor opens it from the classroom; the classroom (slides, materials, messages) stays available" (what a browser actually gets from the edge is its 404 page; see §3) |
| Access withdrawn (room-side) | 403 `E_SCOPE` "The instructor closed Jeeves at 10:42 — your names and comments are kept, and nothing restarts by itself; the classroom … stays available"; missions `fenced: …` |
| Auth outage past the window | 503 `E_BUSY` "The class sign-in service has not answered for 5 s — Jeeves paused: running agent runs were stopped (their work is kept) and new work waits; it resumes when the service answers" / "…auth service is unavailable — the room cannot tell who you are" |
| A session of another account / an earlier generation / a grant without them | 403 `E_SCOPE` "…another account than this room's owner…" / "…an earlier generation of this room's workspace — it was reset…" / "…grant names no account — the class server's grant does…" |
| The credential's generation fenced | 403 `E_SCOPE` "This room was reset or given to another account — open your course home for your room" — permanent for the process |
| The owner suspended / revoked (auth 403 "revoked") | 403 `E_SCOPE` "Your account is suspended or unknown to the class server right now — … the room reopens by itself when the server allows it" — lifts with the next good answer |
| `course.activity {id}` | `E_SCOPE` "The classroom sets the activity (the instructor moves the class on from Today); Jeeves cannot change it. Current: E4b, revision 5 — your work is filed under it" |
| `llm.setkey` in a room | `E_UNSUPPORTED` "a model key is enrolled on the classroom's Provider page (Today → Provider), never typed into the room …" |
| `course.progress {status: done}` | `E_SYNTAX` "status must be ready, help or not_ready (the Today page's three states: I'm ready, I need help, Not ready yet)" — `working` is accepted as Not ready yet |
| `course.share` without preview | 200 `{sent:false, reason: human_action_required, browser, copy, provenance, requestId}` |
| `device.list` in a room | `E_UNSUPPORTED` "… this room runs on the class server, not on your laptop …" |
| `agent.spawn {mode: cli}` in a room | mission `blocked`, "cli missions do not run in a course room …" |
| `fs.list {dir: "/"}` / `target.open {path: "/etc/hosts"}` | `E_SCOPE` "outside this room's files …" / "outside this room: …" |
