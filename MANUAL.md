# Workbench Manual

A reverse-engineering workbench for Android (and beyond): JEB-powered
decompilation, xref navigation, live frida dynamic analysis, project-level
flow maps, and supervised LLM agents — one op registry driving the GUI,
the MCP interface, and scripts alike.

---

## 1. Requirements

`./provision.sh --check` reports every row below for this machine (macOS or Linux) with the
fix line for each gap; `--apply` runs the unattended ones. PROVISIONING.md is the runbook,
also written for an assistant. A running server answers the subset it can see via
`system.doctor` — the same row shape, minus the build-time rows (gradle, node, web-deps,
server), with one combined `frida` row.

| Need | For | Notes |
|---|---|---|
| JDK 21 | everything | Temurin 21 recommended; `backend/workbench.sh` finds it (`JAVA_HOME`, then the OS's JDK list) |
| Gradle 8+ | build | used only at build time |
| Node 18+ | frontend build | `web/src` (TypeScript) → `web/js`; tsc is locked in `web/package-lock.json` |
| JEB (5.42 or later) | real decompilation | your own activated JEB install, named by `JEB_HOME` (see §4.2 and `docs/local-jeb.md`) |
| adb + a rooted device/emulator | Runs, Device views | frida-server is provisioned automatically |
| Python 3.10+ | frida sidecar, MCP server | stdlib only for MCP; frida via `backend/runs/pull-frida.sh` |

## 2. Quickstart

```bash
JEB_HOME=~/jeb backend/workbench.sh   # build + serve (omit JEB_HOME for the mock engine)
# open http://127.0.0.1:7800
```

Open a target with the **+** chip in the target strip (path to an APK,
DEX, or `.jdb2`). First analysis of a large APK takes a while; the
`.jdb2` makes later opens fast.

## 3. Concepts

**Workspace → Projects → Targets → symbols.** A *project* is an
investigation wrapping any number of *targets* (APKs, native libs — each
bound to an analysis engine). Maps, agents, runs, and devices are
project-scoped; classes, strings, tags, bookmarks, and history are
target-scoped. The UI shows scope everywhere: the breadcrumb roots at
the current scope, and the rail splits target views (⌃1–⌃3) from
project views (⌃4–⌃6).

## 4. Configuration

### 4.1 Ports

The app reserves **7800–7809**: `7800` HTTP API + SSE + web UI,
`7801` future VSCode bridge, `7802` sidecar control. Override:
`-Dworkbench.port=7900` or `WORKBENCH_PORT=7900`.

### 4.2 Engine

`JEB_HOME` names your activated JEB install; the server uses its jars and normal
`jvmopt.txt` startup options in place and copies nothing (`docs/local-jeb.md`: the two start-up routes and
the edition they need). A copy of an install under `backend/engine/`
(gitignored) is used when `JEB_HOME` is unset. Without either the app
still runs with the mock engine (fixtures only).

### 4.3 LLM providers (agents)

Agents run *missions* whose writes always land as reviewable proposals.
Providers are configured two ways — **config file** or **UI**; the file
wins on conflicts, env vars win over the file.

**Config files** (both gitignored, created on first use):

- `backend/.workbench/llm.json` — providers. See `backend/llm.example.json`
  for an annotated example:

```json
{
  "providers": {
    "deepseek":  { "baseUrl": "https://api.deepseek.com", "model": "deepseek-v4-pro" },
    "ollama":    { "baseUrl": "http://127.0.0.1:11434", "model": "qwen3:32b", "local": true },
    "my-llama":  { "baseUrl": "http://127.0.0.1:8080", "model": "local", "local": true }
  }
}
```

  Edits to known providers override their `label`/`baseUrl`/`model`/`local`;
  unknown ids are added as new OpenAI-compatible (`http`) providers.
  Keys are never read from this file.

  A provider may also carry **`extraParams`** — a JSON object merged verbatim
  into every chat-completions request body for that provider only. The
  DeepSeek built-in uses it to pin thinking mode
  (`{"thinking": {"type": "enabled"}, "reasoning_effort": "high"}`); other
  providers send no extras, so endpoints that reject unknown fields are
  unaffected. Our own fields (`model`, `messages`, `tools`, `max_tokens`)
  always win on key conflicts.

- `backend/.workbench/llm-keys.json` — a flat `{ "<provider-id>": "<key>" }`
  map, written `chmod 600`:

```json
{ "deepseek": "sk-...", "openrouter": "sk-or-..." }
```

- **Env var override**: `WORKBENCH_LLM_<ID>_KEY` (id uppercased,
  `-` → `_`) beats the file. Useful for CI or one-off runs.

Driver kinds: `cli` (auth delegated to the CLI's own login — Claude Code,
nothing to configure), `http` (any OpenAI-compatible endpoint), `builtin`
(the scripted test agent). Providers with `local: true` get the LOCAL
badge — zero network egress, for confidential samples.

**UI** — two separate areas. **⚙ PROVIDERS** (Agents view, above the
mission cards; also `⌘K` "LLM providers…") is the endpoints: per provider
edit the `baseUrl` and LOCAL flag (http providers; loopback = no key
needed), the model list (comma-separated, first = default; an empty list
sent over MCP clears it), set or clear the API key
(write-only, shown only as `configured ✓`), ADD a new OpenAI-compatible
provider (label, id, URL, models, key), DELETE a user-added one — the
shipped ones can be edited, not removed. **Which model a task uses** is
picked where the task is, and nothing is assumed: the NEW MISSION card
(provider → a model within its family → effort → budget) for a custom
goal, and the ⚡ REVERSING AGENT window for the built-in reversing routine
(§6). Effort is the Claude Code CLI's `--effort` (`low`…`max`) or an
http provider's `reasoning_effort` (`low`…`high`; the picker offers only
what the provider takes). The **Anthropic** provider offers two connections: **Claude Code
login** (`claude auth login`) needs no key — the server strips `ANTHROPIC_API_KEY` from that
mission's environment so the subscription pays, unless `ANTHROPIC_BASE_URL` points at a
gateway; **Anthropic API key** calls the API directly and needs the key stored on the card.

**Providers vs models** (⚙ PROVIDERS): a provider is one vendor endpoint with one API
key — its id is the vendor (`zai`, `deepseek`), never a model version. Model versions are
rows in the provider's list: the radio marks the explicit default, × removes one (a pinned
model that is dropped is never re-pointed — the REVERSING AGENT window and the doctor flag
the depth as *unlisted*), ADD appends. Click a user-added provider's id to rename it: the
key and every task pin move with it, atomically or not at all. SAVE writes the card; the
ADD form creates (`create:true` on the wire).

**From an LLM over MCP**: the same configuration is a tool surface for the
operator's own assistant (an MCP client that is not a mission):
`llm_providers`, `llm_configure` (`create:true` adds a provider; also `extraParams`, a JSON object merged
into every request; `auth` switches a vendor's connection — Anthropic: `cli` = your Claude Code sign-in, `api` = an API key — and travels alone), `llm_setkey`, `llm_remove`, `llm_rename`, `llm_models` (discover a provider's models from its endpoint), `task_list`,
`task_configure`. Missions are refused every `llm.*` / `task.*` mutation
and every `agent.*` op; `agent_spawn` from an assistant needs the bridge
started with `WORKBENCH_ACTOR=human:<name>`. A key sent through
`llm_setkey` passes through the assistant's conversation: for long-lived
keys prefer `WORKBENCH_LLM_<ID>_KEY` in the server's environment.

**Key security**: keys are never returned by any op, never rendered in
the DOM, and both files are gitignored. Verify with
`git status` after setup — nothing under `.workbench/` is tracked.

### 4.3b Logs

Everything that goes wrong is on file: `<home>/logs/workbench.log` (default
`backend/.workbench/logs/`), rotated at 10 MB with five generations kept, one
timestamped line per event, a stack under it for an unexpected exception. It
holds every op that answered an error (code, op, actor, the params in short,
the message), the HTTP layer's failures, a thread's uncaught exception, a
mission's loop failure with its model and goal, and the browser's own uncaught
errors and unhandled rejections (the page files them through `log.client`).
Boot lines mark restarts. Read it back without a shell:

```bash
curl -s -X POST localhost:7800/api/system.logs -H 'Content-Type: application/json' \
  -d '{"params":{"tail":50,"level":"ERROR","grep":"agent"},"actor":"human"}'
```

API keys never appear (a `key` param is written as `<redacted>`).

**Who used the workbench and how**: `<home>/logs/audit.jsonl`, one JSON line per call —
every call by an agent or an MCP client (a mission, a Kimi or Claude Code researcher), every
write by a human; the browser's reads are not recorded. A line carries the time, the actor,
the client the bridge serves (`kimi-code/2.0.2`), the op, the target, the symbol, whether it
wrote, whether it succeeded, and the duration. Read it with the `audit` op (or the `audit`
MCP tool): `{"tail": 50, "actor": "kimi"}` lists calls; `{"summary": true, "since":
"2026-09-20T00:00:00Z"}` answers per actor with calls, writes, errors, the ops used, the
targets touched, the clients seen and the first and last call.

### 4.4 MCP (drive the workbench from an LLM client)

```bash
python3 mcp/workbench_mcp.py            # stdio MCP server "jeeves", 25 tools
python3 mcp/workbench_mcp.py --selftest # smoke test
```

Register it in the client. Claude Code (the bridge fetches the tool catalogue lazily and keeps
retrying, so the backend may come up later; its `jeeves_status` tool says what is missing):

```bash
claude mcp add jeeves -e WORKBENCH_ACTOR=human:alice -- python3 /absolute/path/to/jeeves/mcp/workbench_mcp.py
```

Cursor, Claude Desktop and the other JSON-configured clients take the same
command, arguments and environment:

```json
{"mcpServers": {"jeeves": {"command": "python3",
  "args": ["/absolute/path/to/jeeves/mcp/workbench_mcp.py"],
  "env": {"WORKBENCH_ACTOR": "human:alice"}}}}
```

`WORKBENCH_ACTOR=human:<name>` says the client acts for the operator: it may
spawn and revert missions, settle proposals and set the review policy. Without
it the client is an `llm:` actor: reads and `annotate` work (writes follow the
review policy), `mission` and `review` are refused. `WORKBENCH_URL` points at
another port; `WORKBENCH_TARGET` pins every call to one target. Without it the
session is pinned to the target the client last opened, switched to or was
left with after a close (each answers `{id, name, state, activeTarget,
targets}`); a client working on several apks names `target` on every call —
the active target is one server-wide cursor, so `switch` does not steer a
comparison — and a pin whose target went away (closed elsewhere, a restart)
is dropped on the next call, which answers from the active target.

Save and close through the facade are asynchronous (`target.save {async}`,
`target.close {async}`; the facade sets it, the UI's own calls stay
synchronous): `save` answers `{started, startedAt}` and `target.saveStatus`
carries `last {ok, at, ms, bytes, error?}` — the outcome, for a client whose
tool call times out before a 2 GB jdb2 is written; `close` answers `{closing,
closed, activeTarget, targets}` at once, the target leaves the list, and
`workspace.state.closing` names the jdb2 until the write is done.

`symbol.search {query, kind?, limit?}` ranks the exact name first (then the
same name in another case, prefixes, substrings) and keeps one kind on
request (class | method | field; fields are searched too). A symbol
`LOuter$Inner;` whose inner class was renamed on its own resolves through
the dex's structural records — the outer's static `Companion` field, an
inner class's synthetic `this$0`, the EnclosingClass/InnerClass annotations
when a build keeps them (a WhatsApp release keeps none) — and a miss carries
the outer's inner classes as `candidates` with their `innerName`.

`symbols {prefix}` (`symbol.tree {prefix, limit?}`) is a place in the package
tree, one level, shallow: every sub-package with its class count, then the
classes in address order, one row each — `{name, kind, addr, fqcn}`, without
methods or fields (`code {view: outline}` lists a class's members; a class
address as the prefix answers that one class with them). The prefix is a
package in any spelling (`com.foo.bar`, `com/foo/bar`, `Lcom/foo/bar/`); a last
segment that is not a whole package is the beginning of a name, case-sensitive
(`X.0A` → what starts with `0A` under `X`; the answer echoes `under` and
`startingWith`) — when that beginning is itself a sub-package, the package wins:
lengthen it. `limit` cuts the classes only. Without it the listing stops at 500
classes — the package listing is the exception to "the caller's cut, never the
tool's": a WhatsApp build keeps 90,525 classes in one package — and a cut
answer says `truncated`, `total`, `beginnings` (how many classes continue with
each next character: which beginnings exist, so nobody guesses) and a `hint`.
Nothing under the prefix is `E_NOTFOUND` with the nearest existing level's
packages as `candidates` and a `hint`; the MCP bridge passes both on. (Until
2026-10-03 a dotted prefix fell into a name search and came back with every
match's members: 779 KB for one 157-class package.) `symbol.tree {filter}` —
the tree pane's lazy listing, the scripted mission's name search — is unchanged.

Heap: every open target lives in the one JVM. A WhatsApp-size apk (125 MB,
80k classes) is ~3–4 GB analysed and ~5.5 GB reloaded; `workspace` (and
`workspace.state`) carry `heap {maxBytes, liveBytes, headroomBytes}`. An open
that will not fit is refused for an agent or MCP client (E_BUSY naming the
headroom, the need and the targets to close) and proceeds with a `warning`
for a human. Clients working through several apks open one at a time, wait
for `ready`, and close before the next. `WORKBENCH_XMX` sizes the heap
(launcher default 16g; 32g for multi-apk research on a 128 GB machine).
Closing a target releases its heap: two JEB caches that outlived an unload
(the native-signature manager's analyzer maps and dexdec's ApkResHelper
cache, whose value pins its own weak key) are swept on every close since
2026-09-21; the sweep logs what it dropped, and warns if a JEB upgrade moves
the cache.

From scratch, with nothing open, a client can do the whole job itself:
`project {action: open, name}` (creates the project if it is new), `target
{action: open, path: "/…/app.apk"}` (returns `state: loading`; poll `target
{action: info}` until `ready` — a minute for a small apk, several for a big
one), then `symbols` / `code` / `xrefs` / `strings` / `search`, `annotate` to
name what it understands, `history {action: undo}` to take a name back, and
`target {action: save}` when done — nothing is written to the jdb2 until then
(`save_status` shows the progress of a long save). Or delegate: `mission
{action: spawn, goal, depth: "deep"}` runs the built-in reversing agent and
`mission {action: turns | turn}` reads its transcript.

The server advertises the workbench's **tool facade** (`ops.tools`; source
`dev.workbench.llm.ToolFacade`): 25 verb-shaped tools with strict schemas
and worked examples, each folding one or more registry ops behind an
`action` (or `view` / `direction`) selector. Reads: `symbols`, `code`,
`xrefs`, `strings`, `search`. Writes: `annotate` (rename, comment and tag
in one `items[]` call) and `map_verdict`. Maps: `map`, `map_edit`, `flow`.
Dynamic: `devices`, `runs`, `run`, `breakpoints`. Operator: `project`,
`target`, `mission`, `review`, `llm`, `reversing_agent`, `index`,
`workspace`, `history`, `presence`, `audit`. Every call goes through the backend's
`tool.call` op, which routes it and lets the routed op enforce scope, the
actor rule (`mission` / `review` / `llm` want a human actor:
`WORKBENCH_ACTOR=human:<name>`) and the review policy (`review {action:
policy}`: **auto** applies agent writes at once, undoable from History;
**manual** queues them as proposals). Missions spawned in claude-CLI mode
get the same server, restricted by `--allowedTools` to the nine loop tools.
The browser's own plumbing (layouts, prefs, hover, file browser …) and the
retired cruft (bookmarks, device pull, map export …) are not tools.

### Several assistants naming the same target

Give each assistant its own `WORKBENCH_ACTOR`, such as `llm:worker-1` and
`llm:worker-2`, and pin `WORKBENCH_TARGET`. The shared default `llm:mcp`
identifies one actor; it cannot distinguish separate assistants.

Jeeves remembers the owner of each unsaved rename. Another assistant cannot
replace that name: its `annotate` item fails with `RENAME_CONFLICT`, showing the
current name, actor and history entry. The first assistant can refine its own
name. The operator can correct it. Human class, method and field names and
analyst-mapped formal arguments remain protected by their existing policies.
Unsaved agent ownership applies to classes, methods, fields and identified locals.
A class rename does not change a member's identity for this check. JEB also
renames virtual methods together with their definitions and overrides. Jeeves
checks and records that whole group, so choosing a sibling implementation or
an interface method cannot bypass the first assistant's ownership.

The check, rename and history record share one transaction with database saves.
A failed save keeps the ownership claims. A successful native save with durable
history releases them, so a later round may refine the saved names. An optional
`expectedName` also rejects a stale read after the save:

```json
{"target":"t1","items":[{"symbol":"Lexample/Message;","name":"MessageEnvelope","expectedName":"Message"}]}
```

`annotate` still returns each item's outcome. A failed rename keeps the readable
`error` string and adds `errorDetails` with `code`, `currentName`, `currentActor`
when there is an unsaved owner, `originalSymbol`, `unsaved`, and `historyId` when
available. Read the assigned name and continue the analysis; repeating the same
conflicting rename will not overwrite it. A batch may partly succeed, so inspect
`applied`, `failed` and every item before deciding what to retry.

Automatic local naming (`locals.auto`) checks all affected locals before its bulk
write. It records stable identities and claims every applied name before writing
history, so a failed journal write cannot leave later bulk items unprotected.
Type-bound locals also follow ownership rules: a conflicting automatic follow is
skipped and its typed error is recorded in the parent history entry's `followed`
list. An applied follow whose journal write fails reports that failure.

Before saving a parallel round, stop its clients from issuing further work and
read `ops.active` over HTTP. It returns `{observedAt, calls}`; each call names its
`id`, `op`, `actor`, `startedAt`, and supplied `target`/`symbol`. A routed call may
appear beside its nested operation. A disconnected or timed-out client remains
listed until its backend dispatch actually finishes. The query excludes itself,
keeps no full request bodies, and imposes no reporting cap. Wait until the round's
actors disappear, then check any explicitly started background tasks separately
(`save_status`, target loading, indexing, or missions). This is observation of
current dispatches, not a lock that prevents new work from starting.

The model-free regression checks can run independently of an analysis project:

```sh
gradle -p backend classes concurrentRenameTest opsActiveTest
python3 -B mcp/test_concurrent_rename.py
JEB_HOME=/path/to/activated-jeb gradle -p backend concurrentRenameIT renameFamilyIT searchIndexPersistenceIT
```

The Python test starts five real MCP bridge processes and its own temporary mock
server, then stops them. The native tests use authored DEX fixtures and fresh
homes under `backend/build/tmp`; they check conflicts and saved names after
reopening. They do not open an existing analysis database or call a model.

## 5. The UI

### Views (Ctrl+digit)

| Key | View | Scope |
|---|---|---|
| ⌃1 | Explorer (code) | target |
| ⌃2 | Search | target / all targets in project |
| ⌃3 | Strings | target |
| ⌃4 | Runs (frida) | project |
| ⌃5 | Agents | project |
| ⌃6 | Device (adb catalog) | project |

`⌃N` and the rail buttons *select* a view: pressing the key of the view
already showing does nothing (it only re-focuses it); `⌃1` Explorer is the
way back to the code view. The toggles are `⌘J` (dock) and `⌘B` (side
panel). The `⌘K` palette lists the same views and every other command.

Maps open from the project chip → MAPS; the active map lives as one chip in
the target rack (click shows/hides it, hover-× closes it). The class tabs
and file navigator exist **only** in Explorer.

### The caret (code view)

`←→` char · `⌥←→` word · `↑↓` line · Home/End · click places ·
double-click goes to definition · `⌘[` / `⌘]` back/forward through the
unified history. The symbol under the caret lights all its occurrences
yellow. `Tab` cycles split → java-only → smali-only → split, `\` swaps
pane sides, drag the divider to resize, `b` toggles the smali opcode-bytes
column, `s` toggles the smali band following the caret line, `c` cycles
the coverage run (the dock scope label shows the active one).

### Editing

`N` rename (a symbol, or a local under the caret) · right-click a local → "Name all locals after their types" (`DownloadHelper v5` → `downloadHelper`, a second one `downloadHelper_2`; each is an undoable History entry, and a later rename of the class renames the local again) · `;` comment · `⌘S` save (`.jdb2`) · `⌘Z`/`⇧⌘Z` undo/redo ·
`⌘D` bookmark line · `T` tag symbol · `X` find references (RELATION pane).

The RELATION pane (right rail) follows the caret symbol. Its rows navigate:
an XREFS TO row opens the referencing method and lands the caret on its
reference to the symbol; a CALLEES row lands on the call inside the symbol's
own body (the pane keeps listing the symbol you were browsing — a later caret
move resumes the follow); an IMPLEMENTATIONS row opens the implementation.
A body the decompiler folded (`[...]`) or a synthetic call has no site to land
on: the caret stays on the method and the section says so.

**Retained MethodParameters (right rail, above RELATION).** When the caret sits in a method whose dex
carries the method-level `Ldalvik/annotation/MethodParameters;` annotation, the inspector shows it as a
SEPARATE list — *Retained MethodParameters — argument mapping unverified*: names a compiler stored for
the method's parameters, possibly for an older signature — clues, not the arguments' names. The entries
(`#0`, `#1`, …) and flags exactly as encoded, in encoded order, nulls and duplicates kept, with each
array's length checked against the current prototype ("3 names / 3 arguments — order unverified"); the
debug_info parameter names as a second source (a table of null slots is said to name nothing); and
"arguments now" — each argument with its type, its current name and who named it (analyst · debug_info ·
JEB: annotation by position · JEB: generic). The order is the annotation's, not a verified mapping to the
arguments: an optimizer reorders prototypes without touching the annotation (WhatsApp 2.23.4.6
`LX/0XS;-><init>` keeps `intentSender, intent` on a prototype whose first argument is the Intent) and
keeps names for arguments that no longer exist. Reading the list changes nothing; **COPY** copies it with
its provenance and its unverified status; the ▾ collapses it. Methods that carry a list have an orange
line number in the Java view. JEB's decompiler sometimes names the arguments after the annotation by
position itself (seen on methods without debug_info names — not on the 2.23.4.6 import); the list says
so when that is what you are looking at — "every argument", or after a partial mapping "2 of 4 arguments
… positions 2, 3" — and exactly those identifiers are dotted-underlined in the Java view, the engine's
rendering, unverified; the "arguments now" table names each argument's source. Mapping is yours: **APPLY TO ARGUMENT…** on an entry (or right-click
an argument → *Apply retained name to argument…*) puts ONE name on the argument you pick, by position
with its current name and type shown, any position — when another argument holds that name the dialog
offers the **SWAP** as one entry; **APPLY LIST BY POSITION…** previews every current → proposed pair
first and applies the list only when you accept it, as one undoable entry — the ACCEPT button stays
disabled, with the reason, while the counts differ, an argument's binding is unresolved, a row has a
problem or nothing would change. Invalid identifiers, keywords, duplicates, collisions with arguments or
locals and an argument that already carries your name are said, never sanitised; null entries and rows
that already carry your own name are no-ops (not recorded), while a row equal to JEB's positional pick
adopts it as yours — applied and recorded as adopted, undone like any mapping. A dialog opens on a fresh read of the method and keeps the
keys to itself; if a name moved under an open preview the apply is refused and the dialog reopens on
fresh data with your picks kept. Names go through the engine's own parameter binding (every use follows;
types and order never change), are recorded in HISTORY with their provenance (which entry went where,
or your own name), and undo in one step (`⌘Z`; `⇧⌘Z` or the HISTORY row's REDO brings them back).
Acceptance is your decision, not a verified correspondence. Agents cannot map names; an agent's
argument name equal to a retained entry is marked `= retained` in HISTORY, and an argument you mapped
is not theirs to rename. Over HTTP/MCP: `code.params`, the `code` tool's view `params`, and the raw
`methodParameters` block on a method's java / smali / packet results.

**Original names.** Right-click a renamed symbol → *Revert to original name* (`rename.revert`) puts
its ORIGINAL dex name back (an obfuscated `1cq` is a fine original and a refused new name); `⌘Z` of a
rename does the same through the same validated path, and so does the crash-window replay. The view
follows the restored class — code, DEX pane, inspector, breadcrumb, tab, tree row and the ‹ › history —
wherever you reached it from (a search result holds the analyst's address, which no longer resolves
once the original is back); if the refresh itself fails you are told so and asked to reopen the class —
the revert is done and in HISTORY either way.

### Navigation

`G` jump anywhere (views, targets, maps, symbols, actions; `@`/`>`
prefixes) · `⌘K` command palette (fuzzy) · `⌥1…9` switch target ·
`⌘J` dock · `⌘B` side panel · `Esc` back / close overlay · `?`
keyboard-shortcuts sheet (also via the HINTS item in the status ribbon).

### Named layouts

Panel/dock arrangements (which panel lives in which zone, active dock
tabs, floating positions, side/dock visibility) are saveable as named
layouts, like IDA desktops. Palette (`⌘K`): **Save Layout…** names the
current arrangement (same name overwrites), **Apply Layout…** /
**Delete Layout…** pick from the saved list. Layouts persist per
workspace in `backend/.workbench/layouts.json` and apply across targets;
a layout naming a panel that no longer exists skips it harmlessly.

## 6. Agents

Spawn a mission (Agents view → NEW MISSION): goal, optional scope
(`pkg:com.example.*`, `target:<id>`, `map:<id>`), budget, provider.
Watch proposals arrive; review with ✓/✗, `⌘A`/`⌘X`, or `⌥R` (ACCEPT ALL — two-step; proposals without a confidence or rationale stay for one-by-one review)
— the rail shows the full rationale and ghost diff for each. Out-of-scope
writes are blocked server-side and shown as vermillion BLOCKED rows.
Accepted edits appear in History with the agent badge and undo normally.

**Reverse with agent.** Right-click a method in the code view (or a class in
the symbol tree), or press the RELATION pane's `⚡ REVERSE` button, or run
"Reverse symbol with agent" from `⌘K`: two rows, **quick** and **deep**.
Quick reverses the method alone — one pass, a rename and a comment, capped
at 20 CLI turns (or 120k tokens on an http provider), write-scope its
class. Deep reverses the method and its call tree — 3 hops, every member
it can name with evidence, capped at 80 CLI turns (or 500k tokens),
write-scope the viewed target. A right-click on a call site reverses the
callee and on a class name the class (platform types such as `String` and
compiler thunks are skipped); on a local, a field or a keyword inside a
body, the enclosing method — while the ⚡ button on a field subject
reverses the field's class. The mission is
pinned to the target you are viewing (switching chips afterwards does not
re-aim it) and shows its depth as a chip on the mission card; a notice
above the ribbon names the mission (click it, or `⌃5`, to watch).

**Which model reverses.** The routine is built in, so it is not typed into
NEW MISSION: the **⚡ REVERSING AGENT** window (Agents view, above the
cards; `⌘K` "Reversing agent settings…"; the ⚡ button's menu) sets, per
depth, the provider and model, the effort, the turn cap and the budget.
There is **no default model**: until a depth has one, ⚡ Reverse at that
depth spawns nothing and the notice points you at the window. Effort,
turn cap and budget blank = the depth's defaults (the caps above). The
last line shows what the next ⚡ Reverse resolves to. Stored in
`backend/.workbench/tasks.json` (`task.list` / `task.configure` — also
MCP tools `task_list` / `task_configure`); `agent.spawn` takes the same
`depth: quick|deep` (the prompt's brief, the caps), `effort`, `turns` and
a `target` to pin to.

## 7. Dynamic analysis (Runs view)

Runs, breakpoints and proposals are PROJECT-scoped (since 2026-09-10): they live under
`backend/.workbench/projects/<slug>/` (runs/ and proposals.json), every runs.* and agent.proposals
read answers for the active project, the RUNTIME dock shows the VIEWED target's runs, and a pre-upgrade
global runs/ or proposals.json folds into the first project activated afterwards (parked as
runs.migrated; a fold marker pins the destination). Missions have no unscoped form: a blank scope is
the pinned target, `project` is the explicit whole-project opt-in.

Attach a rooted device/emulator (adb), then SPAWN or ATTACH a package. The preflight line under
the device bar (adb · devices · frida sidecar · engine, from `system.doctor`) says what an attach
would fail on, with the fix; the device bar names the root capability — `root (adbd)` runs commands
plainly, `root (su)` hops each one through su (a grant prompt on Magisk), `no root` cannot provision
frida-server. A run card's ✕ deletes that run (two clicks); "✕ delete ended" in the RUN HISTORY header
deletes every ended run of the project (`runs.rm`); hit logs are parked as `<run>.jsonl.deleted-<ts>`
beside the store, never unlinked, and a live run is refused until detached.
Add breakpoints on methods (F9 on the caret symbol, or ＋BP), watch the
hit feed with decoded args and backtraces, and diff runs (⇄ DIFF A⇄B).
Coverage decorates the editor glyph margin (`c` in the code view cycles
the run; the dock scope label shows the active one).

## 8. Devices (Device view)

The catalog inventories a rooted device: packages, libs, services,
processes, partitions — refreshed with diffs. PULL copies an artifact
into the project and opens it as a target (sha256 provenance,
change detection). KEYWORD SCAN runs a substring scan over the
catalog; annotations arrive as proposals.

## 9. Maps

Project-level flow graphs over any targets. Open from the project chip →
MAPS. Nodes anchor to real symbols; double-click jumps to code (across
targets). Types (node kinds, edge kinds, verdicts, tags) are configured
per project in the TYPES menu — nothing is hardcoded. Build maps by hand
(＋NODE/＋EDGE — the kind field offers the registry's kinds), from a symbol's right-click in the code, the tree or
the RELATION ⚡ menu (Add to map… — the node carries the symbol and the viewed target), from flows (PROMOTE), or by agents (`map.*` ops;
verdicts arrive as proposals). `map.export` renders md/json posters.

## 10. Persistence

- `<target>.jdb2` — engine database (decompilation, renames, comments).
- `<artifact>.workbench/` — per-target sidecar: history, tags, bookmarks,
  flows, and `search-index/` — the persisted search corpus (per-class gz +
  `header.json` + `pending.json`). The corpus warm-loads on open (no
  rebuild); classes edited since the build are re-indexed from the history
  log. Delete it to force a full re-index.
- `backend/.workbench/targets/<slug>-<hash8>/` — the **target store**: the home of an
  opened/pulled artifact, keyed by content hash. Holds the artifact copy, its `<base>.jdb2`
  engine database, the `<base>.workbench/` sidecar, and (for a device pull) a carried-forward
  `<base>.prov.json` provenance record. A `device.pull` lands the bytes here and is deduped by
  content — re-pulling the same bytes reopens the existing entry instead of transferring again
  (decision 16).
- `<base>.jdb2.key` — the database's **JEB project key**, one line, beside every `.jdb2` the
  workbench has created, loaded or saved. JEB identifies a project by the key it was created
  under; the key is stored inside the database and can only be read by loading it, and one
  workbench holds one project per key — loading a second database with the key of an open
  target does not fail, it takes that target's place (a save of the first would then write the
  second into its file). New databases get a key of their own (`wb-t3-9f…`, 16 random hex
  digits; only a copy of the file carries the same one). Databases made before 2026-10-04 are
  keyed `wb-t1`, `wb-t2` … per server run, so different apks share keys; the record is what
  lets `target.open` see a clash **before** it happens. What it does then depends on who asks:
  - **You, in the workbench**: the open is carried out. The database being opened is set aside
    whole (kept as `<base>.jdb2.rekeyed-<time>`, with its key record), the apk is analysed
    again under a key of its own and its edit history re-applied — a re-key, described below.
    The open card says what was done and why, how many edits there are to re-apply, where the
    old database is, and stays until dismissed; edits that could not be re-applied show on a
    caution line. The target's chip keeps saying it (tooltip) for as long as the old database
    is on disk. **The way back** is `UNDO RE-KEY` on that card, ⌘K "Undo re-key", or
    `target.rekeyUndo {target}`: the target is closed without writing the new database, the
    old one goes back in place byte for byte, and edits made in between replay onto it at its
    next open. Technically: the workbench's own open gestures (launcher, project menu, device
    view) send `target.open {rekey: true}`, honoured for a human actor only.
  - **An agent or MCP client** — including one registered as `human:<name>`, whose tool calls
    cannot carry that flag: refused (E_BUSY, naming the open target and the key). A model does
    not get to replace one of your databases or spend minutes re-analysing on its own.
  - If the target that holds the key is on its way out (you closed it and opened this one),
    the open waits for the close and then loads normally — no re-key.
  - Not resolvable by a re-key, so refused for everyone: a `.jdb2` opened by itself (there is no
    apk to analyse again — open the apk), a key that is not on record yet (the message carries
    the command that records it), and a database some other process has open (its `.lock`
    file is fresh; a stale one left by a killed workbench is named as such).

  A clash the records could
  not show — a database with a wrong record, or a copied apk + `.jdb2` pair with none — is
  found right after the load: that open is undone and ends as the new target's error, and the
  target it displaced says so (`warning`) and must be closed and reopened before it can save.
  Two scripts, both for the operator, neither needs the workbench stopped:
  - `backend/jdb2-keys.sh` records the keys of a whole store (or of the `.jdb2` files named),
    one database at a time in a process of its own, and lists the databases that share a key.
    It saves nothing; a database a workbench has open is skipped. `JDB2_KEYS_XMX` sizes each
    read (default 12g; the largest databases need more — a read that runs out says so).
  - `backend/jdb2-rekey.sh <store entry | file.jdb2>` does a **re-key** from a shell — what a
    human's open of a clashing database does on the spot: it gives one database a key of its
    own, so it can be open beside the others of its group. It sets the `.jdb2` aside (kept as
    `<base>.jdb2.rekeyed-<time>`) and leaves a marker (`<base>.jdb2.replay`). The next
    `target.open` of that apk analyses it again, re-applies the edit history in order — the
    renames (class, method, field, local) and comments made through the workbench — and only
    then writes the new database; an open interrupted before that writes nothing and the next
    one starts over. An entry that cannot be re-applied is counted and logged, not dropped in
    silence (`replayFailed` in the target's info, the entries in `logs/workbench.log`): the
    usual case is an edit recorded under a name whose own rename was later undone. Not carried
    over: the decompilations cached in the old database, anything done to it outside the
    workbench, and edits made while the `.jdb2` itself (not the apk) was the opened target —
    the script refuses when there are any. The target must be closed; a database with no apk
    beside it cannot be re-keyed. A `.jdb2` deleted by hand, without the marker, is simply
    analysed again with no history replayed. The way back is `target.rekeyUndo` (above); by
    hand, with the target closed: delete the new `<base>.jdb2`, `<base>.jdb2.key` and any
    `<base>.jdb2.replay`, then rename the `.rekeyed-<time>` file and its `.key` back. Delete
    the `.rekeyed` files you no longer want — each is a whole database.
- `backend/.workbench/pulled/` — **transient staging** for `device.pull`: in-flight `.part-<ts>`
  files, and non-openable pulls (an ELF/`.so` with no engine yet) that were never adopted into a
  store. An openable artifact's staging copy is reaped once the store adopts it.
- `backend/.workbench/` — project state: maps, proposals, runs, devices,
  llm config. **All gitignored; safe to delete for a clean slate**
  (maps seed regenerates).

## 11. Troubleshooting

| Symptom | Fix |
|---|---|
| `7800 in use` | another instance is running; use it, or `-Dworkbench.port=7901` |
| The DEX view's branch labels do not match the bytes | a branch operand is a signed displacement in 16-bit code units from the instruction (`0x6e + 2·0x22 = 0xb2`), and a label `:l_<hex>` names the destination's byte offset — check the offset column: the label stands on the instruction at that offset. Switch payloads with their case destinations, fill-array-data payloads with their elements and the try table (`.catch <type> {:try_start_ .. :try_end_} :catch_`) are rendered as their own rows; a destination that is not an instruction start is written out in the operand (`+N units → hex`). If a label still disagrees with the bytes, the running server predates 2026-10-08 — restart the workbench. The listing is composed from the engine's instruction model — not a qualified round-trippable smali export |
| Mock targets only | no JEB install was found — set `JEB_HOME` and restart |
| The heap does not come back after closing a large target | JEB keeps per-context caches of a closed project until the process exits; the workbench unloads the project and reclaims nothing beyond that (`closeCapabilities` says so). Restart the workbench after cycling several large targets; `WORKBENCH_XMX` sizes the heap |
| `target.open` → E_BUSY "…cannot be open at the same time as tN … same internal JEB project key" | an agent's or MCP client's open of a database that shares its JEB key with an open target (your own open of it from the workbench is carried out: the database is re-keyed on the spot, §10). The client closes the target it names only if it opened it; otherwise you open the apk from the workbench, after which it opens beside the other for every client. The same refusal for a `.jdb2` opened by itself: open its apk instead |
| `target.open` → E_BUSY "the operator runs backend/jdb2-keys.sh '<file>' …" | the database's key is not on record — an old database beside an old-style target, or a `.jdb2` opened by itself beside any target: run the command in the message once (the workbench keeps running), or open it while none of the targets it names is open |
| A target ends in error "…a project a failed open or close left in the JEB context" | found when the load was about to start: no target holds that project, so nothing can close it — restart the workbench |
| A target shows `warning: … loaded over this target …` and `target.save` answers "no longer the one JEB has under its key" | a database with a wrong or missing key record displaced it; that open was undone. Close and reopen the target: renames and comments replay from history, new decompilations are redone on demand |
| JEB licensing error | re-apply the license setup in your JEB install, re-vendor |
| frida "server down" | `adb root`; the sidecar re-provisions on attach retry |
| Attach fails, device rows wrong | `./provision.sh --check` names the gap (adb, root capability, ABI — arm64-v8a only, frida-server running or down) |
| Device bar says "root unknown" | the 10 s poll never runs the su probe (a Magisk grant prompt must not come from a timer): REFRESH, an attach or `system.doctor` probes it |
| A queue full of stale proposals | `agent.purge` (MCP `review {action: purge}`) settles pending rows before a date / of a mission / without a rationale / whose target is gone as "purged" — nothing is applied |
| Search results stale | Renames/comments auto-invalidate per class in the background; REBUILD in the Search view for bulk staleness |
| Old sidecar state confusing tests | delete `backend/.workbench/` and `<artifact>.workbench/` |

## 12. Development

- `gradle parityTest` — registry ↔ `design/MCP_SPEC.md` parity (must
  always be green).
- `gradle m1IT … m8IT m10aIT` — per-milestone integration suites.
- Repo layout: `backend/` (Java, Gradle), `web/` (ES-module UI),
  `mcp/` (MCP server), `design/` (specs — the contract).

## Database save durability

See [Saving edits and recovering interrupted writes](docs/persistence.md) for the asynchronous save completion rule, verified native checkpoints, recovery after interruption, durable undo/redo across restart, and independent backup/reopen checks. A failed recovery cannot overwrite the native checkpoint with partial state. A closed target alone is not a save-success receipt.

See [Search-index interruption recovery](docs/search-index-recovery.md) for the derived search corpus, pending-work ordering, and failure-injection checks.
