# MCP Interface Specification (v0.1 · draft)

Status: spec discussion · 2026-07-24
Scope: the complete MCP/op surface of the workbench. One registry serves
four clients identically: the UI, MCP-connected LLMs, the scripting CLI,
and (future) the VSCode extension. An op that exists but isn't in the
palette/MCP is a build error; the UI can do nothing an LLM can't.

---

## 1. Transport & framing

- MCP server: stdio JSON-RPC (modelcontextprotocol), one tool per op,
  generated from the registry. Aliases allowed (`xrefs` ≡ `xref.to`).
- Wire shape (unchanged from old Jeeves): `op + params → JSON map`.
  Engine names never appear on the wire — ops are backend-agnostic.
- Every request carries `actor`: `human | llm:<agent-id> | script`.
  Every response: `{ ok, data?, error?, meta? }`; `meta` includes
  `truncated`, `ms`, `engineCaps` where relevant.
- Truncation honesty: ops that cap a result set carry `truncated: true` (+
  the cap context) in `data`, and the dispatcher LIFTS a true
  `data.truncated` into `meta.truncated` so meta-only clients see it.
  `symbol.tree` returns `{items, truncated, total}` (`filter`: every row
  unless the caller passes `limit`; `prefix`: the first 500 classes unless
  the caller passes `limit`, with `beginnings` and a `hint` for the rest). No op currently implements
  `nextOffset` — do not look for it; page with the op's own
  `offset`/`count` params where documented. Hub-symbol explosions (unbounded xrefs) always truncate with a
  warning, never dump.
- Pagination: NOT universal. `offset`/`count` exist only where documented
  per op (§4); list ops without them return their full inventory (bounded
  by design: maps/tags/proposals) or their cap + `truncated` flag.
- Mutations are POST-only, CSRF-guarded at the HTTP layer (unchanged),
  and write-scope enforced server-side (see §3).

## 2. Capability discovery

| Op | Returns |
|---|---|
| `caps [target]` | The capability set per §engines: `{ decompile, smali, xrefs, rename, comment, callgraph, strings, runs, persist: native|sidecar|none }` per target. Clients MUST render/degrade from this — a missing cap means the op errors `E_UNSUPPORTED` and the UI hides the affordance. |

## 3. Write model: proposals & scope

- Save semantics: `⌘S` saves the **active target**; proposals are saved
  with the **project**. The old single UNSAVED readout splits into PROPS
  (project proposals, status ribbon) and per-target dirty dots (target
  strip chips) — separate indicators, never merged.
- Human edits apply immediately (`actor: human`).
- Agent edits: the proposal gate is **per-op, not universal** — the curated
  mutation tools (`rename`, `comment`, `tag.add`, `map.setverdict`) land as
  `pending` proposals for llm actors, visible in the Agents review queue and
  settled by `agent.review` (or the UI's ✓/✗ — same op); every other mutating
  op applies directly. `agent.*` ops are refused for llm actors server-side
  (human-only). Per-mission auto-accept thresholds may bypass (AGENTS_SPEC
  §8.1).
- Write-scope: each mission declares a scope (namespace, target set, or
  map id). Out-of-scope mutations fail `E_SCOPE` and emit a BLOCKED event
  to the activity stream. Scope shrink is agent-allowed; expansion
  requires human confirm.
- Error model: `E_UNSUPPORTED · E_SCOPE · E_NOTFOUND · E_BUSY · E_SYNTAX · E_IO · RENAME_CONFLICT`
  with human-readable `message` and machine `detail`.

### Concurrent rename ownership

Under the auto review policy, renames apply immediately and are undoable;
manual policy keeps proposals pending. At apply time, class/method/field and
identified-local renames share a per-target transaction gate with recovery
history and native save. The unsaved-name cache is keyed by original DEX
identity, with local identity also including JEB's identifier item id. A virtual
method rename preflights every definition and override that JEB changes with it,
using the published override-query closure. One history entry records their
stable identities in `renameTargets`; claims and human-name protection cover the
whole group. Selecting a different member cannot evade ownership. If JEB cannot
provide the rename family, the operation fails before any name changes.
Two different actors cannot overwrite each other's unsaved name: the second
write returns `RENAME_CONFLICT`, with no engine mutation or applied history
entry. The owning actor may refine it; human names remain protected, and
human corrections remain possible. Each assistant must supply a distinct
`WORKBENCH_ACTOR`; `llm:mcp` is one actor, not a session identifier.

`rename` accepts optional `expectedName`; the `annotate` item carries it through.
A mismatch is `RENAME_CONFLICT` even after a successful save has released the
unsaved ownership. The error contains `currentName`, `originalSymbol`, `unsaved`,
`currentActor` and `historyId` when a claim exists, plus `expectedName` for a
compare-and-set mismatch. Batch results preserve the readable `error` and add
`errorDetails` containing the full typed error; the facade forwards both.
`annotate` remains a per-item result envelope and may partly succeed.

After the existing validation, ownership and `expectedName` checks, a native
request for the current name returns `applied:false, unchanged:true`. No native
mutation, cache invalidation, history entry or author transfer occurs. A method
family is unchanged only when all affected members already have the name.
Rename batches and `annotate` expose `status:unchanged` and an `unchanged` count,
separate from `applied`, `pending` and `failed`. Comments/tags still apply
independently. Auto-applied or manually accepted rename proposals that require
no edit settle as `unchanged`, with no history ID or undo action. Existing
historical entries are retained unchanged.

`agent.review` remains human-only. Its accepted renames use explicit internal
analyst authority for ownership checks while retaining the proposing agent's
`accepted by human` history attribution. The `expectedName` precondition still
applies. Auto-accept and ordinary rename calls retain normal agent checks;
an actor-string suffix does not grant review authority.

A native destination-name refusal retains `E_BUSY` and adds `requestedName`,
`currentName`, `originalSymbol`, `conflicts[]`, and
`collisionLookup: matched|unresolved|incomplete`. Each conflict has `kind`,
`originalSymbol`, `originalName`, `currentName`, `addr`, and `match`; member rows
include `owner`, method rows identify `affectedOriginalSymbol` when looking through
the rename family, and local/argument rows include `originalMethod`, `itemId` and
source `line`. Locals and arguments both use `kind: local`, as in rename history.
The readable message identifies the same occupants. Lookup follows a native refusal
and does not impose new uniqueness checks; it uses current names, not stale aliases.
Method matching uses the full descriptor. Unresolved/reserved refusals do not
fabricate an occupant; an incomplete lookup includes `collisionLookupError`.
These fields survive the per-item `errorDetails` in both rename batches and annotate.

Ownership clears only after both the recovery history and native database save
succeed. Undo/redo rebuilds active unsaved claims, and original signatures keep
member claims valid when the owner class's display name changes. A persistence
failure must be surfaced separately from whether the in-memory edit applied;
keep the target open and repair storage before proceeding.

Automatic local naming (`locals.auto`) preflights all affected stable local
identities under the same target gate. Every engine-applied bulk item is claimed
before journaling starts, and all applied items enter recovery history even if a
journal write fails. Type-bound automatic follow-ups check ownership too; a
refused follow appears in the parent entry's `followed` list with `errorDetails`.
Applied follow-ups carry stable original method/local identity and surface a
history-write failure. No conflict path silently replaces another actor's local.

## 4. Op registry

### 4.1 Projects & targets
| Op | M | Purpose |
|---|---|---|
| `project.list / project.known / project.open / project.close / project.switch / project.forget` | ✓ | the project menu's ops; several projects concurrent; `project.known` lists ON-DISK projects (loadable, maps restore by name — targets do not resurrect); `project.forget {name, confirm:true}` parks a closed project's sidecar |
| `project.delete {name, confirm:true}` / `project.targets [project]` | ✓ /  | the project editor: delete WIPES the project dir (not parked — forget's destructive sibling; closed projects only; returns the orphaned store entries it leaves behind) · targets lists a project's store membership (name, storePath, size, live state) |
| `target.list / target.open / target.close / target.switch / target.remove` | ✓ | the target strip; `target.open {path, engine?, opts}` — the artifact is copied into the content-keyed store (`<home>/targets/<slug>-<hash8>/`, jdb2 inside) and projects own MEMBERSHIP of it; `target.remove` drops membership (the store entry survives) |
| `target.info [target]` | | stats: classes, engine, heap, dirty, caps Carries `identity` — the same executable · database · revision · runtime block as the packet's snapshot; `runtime` is the BOOT build (build-info.properties read once when the process started: build_sha, build_dirty, built_at, built_at_basis, process_started_at, build_file_changed_since_start — true when the classes directory was rebuilt under the running process; engine). `workspace.state` carries the same `runtime` block, so the cheap read-only answer to "which build and engine are live" is `workspace.state` (`.runtime`) and the capability set is `ops.list` (the registered ops) / `ops.tools` (the facade) — a client never infers runtime support from a source checkout. |
| `target.recent [--limit]` | | the target store's entries by last use (the launcher's RECENT TARGETS zone, WORKBENCH_SPEC §2.4 zone 1): key, name, storePath, the projects holding it, present (still in the store), open (the live id when open now), lastUsedIso; newest first, 9 by default (audit decision 18) |
| `ops.active` | | Read-only in-flight dispatch snapshot: `{observedAt, calls:[{id, op, actor, startedAt, target?, symbol?, tool?}]}`. Excludes this query, has no reporting cap, never records the full parameters/query/token. A disconnected HTTP/MCP client does not clear the call; `finally` removes it after success, classified failure, or thrown Error. Nested dispatches appear separately. Stop producers before using an empty actor set as a round barrier; already-returned background work has its own status APIs. |
| `tool.call {tool, args, client?}` | | the facade's router (the MCP bridge's tools/call) — `ToolFacade.call(…, external=true)`. |
| `code.site {symbol, at?, context?, lines?, java?}` — facade `code {view: site}` | | (P1 §C, 2026-10-09) ONE call site's bounded context. The symbol may carry the offset (an xref row's `from`: `Lcls;->m()V+634h`) or `at` names it (`+634h` / `634h` / `0x634`, hex). Answers `call-site-v1`: `caller` {addr, origSig, name, owner}; `site` {offset, ref (`<origSig>+Xh`), instruction, size, kind: call | branch | return | other, callee {ref, addr, origSig, name, internal} for a call; ref is the method descriptor alone, without register-operand punctuation}; `dex` {rows ±context instructions with the site marked `at`, before/after, omitted}; `block` {id, instructions, range, successors, exceptional, entry, predecessors [{id, edge: regular | exceptional, last, lastOffset, controls: switch | guard | fallthrough}], note} — the containing CFG block and the branch instructions of its IMMEDIATE predecessors: SYNTACTIC context, never dominance, feasibility or data flow; `java` {line, from, to, totalLines, text with `>>` on the mapped line, mappedBy} — JEB's own address→coordinates mapping on the class document (`java:false` skips the decompile), or `unavailable`; `extent`; `more` — the exact continuation (a `window` for code.decompile, code.smali for the listing, `code {view: packet, entry}` for the branch selection). An offset outside the body → E_NOTFOUND naming the span. |
| `symbol.locate {anchors: [{kind: string \| table \| api \| class \| name \| prototype, value}], limit?}` — facade `search {anchors}` | | (P1 §I) a location across builds by SEVERAL fallible anchors. string/table → the methods referencing a matching string (strings {filter} → string.xrefs, first 50 strings); api → the callers (xref.to); class → classes by name (symbol.search kind=class) and marks candidates inside them; name → methods by name; prototype → marks candidates whose parameter list equals the value (never searches). Answers `anchor-locate-v1`: `anchors` [{i, kind, value, status: located \| not_located \| marker \| error, counts, searched}], `candidates` ranked by anchors matched [{addr, kind: method \| class, origSig, name, owner, matched: [i…], unmatched: [i…], score}], `status`: `located` (one candidate matched every anchor by these queries) \| `ambiguous` (several) \| `partial` (the best matched some) \| `not_located` (none — never "absent"), `total`, `truncated`, `note`. Anchor presence is not structural correspondence, not a connected static path and not a runtime observation: the caller records those separately, with each anchor's `searched` scope. |
| `fs.list [dir]` | | one host-fs directory, dirs-first: `{dir, parent, entries:[{name, path, dir, size}], truncated?}` — the launcher BROWSE pane; blank dir → the launch-time caller's cwd (`-Dworkbench.cwd`). |
| `workspace.state` | | full snapshot for client boot (open projects/targets/views) |
| `workspace.sysmem` | | host memory (`totalBytes`/`usedBytes`/`freeBytes`) + this JVM's `heapMaxBytes`/`heapUsedBytes` — the HEAP ribbon gauge's max (the Σ host gauge was removed 2026-09-05; the op stays for agents) |

### 4.2 Symbols & navigation
| Op | M | Purpose |
|---|---|---|
| `symbol.resolve <addr-or-origSig> [target]` | | string → engine symbol (cross-target). A method reference is matched leniently (class + name + parameter types when given, the return type ignored — an agent's inferred descriptor) but NEVER silently: a lenient hit answers `exact:false`, `lenient` (what was overlooked: `name only`, `return type omitted`, `display name`, `owner` — partial references; or `return type`, `parameter types`, `parameter arity` — a COMPLETE descriptor that disagrees with the method found), `asked` (the text as typed) and, for a disagreement, `contradiction:true` + `note`; an address given exactly answers `exact:true`. `code.decompile` repeats a lenient resolution as `resolution: {asked, addr, origSig, lenient, contradiction, note}`. `exact:true` also when asked by the dex's ORIGINAL signature of a renamed method (`by: original signature`); a reference by the original NAME of a renamed method is `lenient: original name` (an identity, not a guess). The Workspace adds what only it knows (`Workspace.resolveSymbol`): parameter types that differ ONLY by class renames recorded in this target's history make the reference a HISTORY ADDRESS — `lenient: rename history`, no contradiction (a guess by arity stays `parameter types`, a contradiction). `differs` lists EVERY component that differs (parameter types / parameter arity / return type / name), so a renamed parameter never hides a wrong return type; a dotted `Owner.method` shorthand is `name only` — with several overloads it is an ambiguity naming the candidates, never a first pick; an xref row's `from` with its offset (`…V+634h`) resolves to the method and keeps `offset`. The answer itself carries `requested`, `resolvedOriginal`, `resolutionMode` (exact | lenient | contradiction). Every lenient resolution is also said in the op's envelope as `meta.resolution` {requested, resolvedOriginal, resolutionMode, asked, addr, origSig, lenient, differs?, contradiction?, note?} — symbol.resolve, code.decompile, code.hover, code.params, locals.list, impls, xref.to/from, callers, callees keep their result shape and still say it; through the facade (`tool.call`, the bridge, the agent loop — which keep only `data`) the block is put INTO the data (`resolution` on a map; a bare list is wrapped as {rows, resolution}). The evidence, hook and corpus paths — `evidence.packet`, `code.smali`, `runs.bp.add` — resolve STRICTLY (`Workspace.resolveForEvidence`): a contradiction is E_NOTFOUND naming the method found by its ORIGINAL descriptor (and how it is displayed), a history address passes; the provenance carries `symbol` (as asked), `symbol_resolved` and `symbol_resolution` (`exact` / `lenient: …` / `contradiction: …`). Acceptance on the fixture apk: exact, shorthand, wrong return type, wrong parameter type, same-arity ambiguity (the candidates named, never a pick), the obfuscated case collision (never folded), a renamed method by its original descriptor, a history address. |
| `symbol.tree [target] [filter] [prefix] [limit]` | | package/class/method hierarchy → `{items, truncated, total}`. `filter`: a Dalvik prefix lists one level (the tree pane); a name searches classes and returns them with members (the scripted mission). `prefix` (the MCP `symbols` tool): one level under a package in any spelling — all sub-packages, then shallow class rows `{name, kind, addr, fqcn}` in address order; a partial last segment = a name beginning (`under`, `startingWith`); `limit` cuts classes only, 500 without it (+ `beginnings`, `hint`); `E_NOTFOUND` + `candidates` + `hint` when nothing is there |
| `symbol.search <query> [target]` | | fuzzy over classes/methods/fields (Jump Anywhere backend) |
| `ui.open <symbol-or-map>` | | open/focus in the UI (agent→human handoff) |

Agent-read name provenance (`agentNames`, `humanNames` and row-level `namedBy`)
is derived from active rename history and verified against the current engine
name. Attribution is keyed by stable `originalSymbol`, including every recorded
`renameTargets` member, and follows effective undo/redo order. Name-list rows
carry the current `addr`, original `was`, `originalSymbol` and `historyId`; agent
rows also carry `by` and optional `confidence`. A stale name, contradictory
legacy descriptor or unresolvable historical alias produces no attribution row.
Provenance decoration does not mutate the database, rewrite history or replace
the requested operation's resolution metadata.

For `symbol.resolve` (the `symbols {symbol}` facade), the symbol is the top-level
row. When its current address, original identity and name match active history,
that row carries `was`, `namedBy` and `nameProvenance`, plus the corresponding
`agentNames` or `humanNames` entry. An unnamed member does not inherit its class's
author, and a same-name override or overload is not attributed to this identity.
Human reads retain their existing undecorated response.

`locals.list` and `code.params` participate in agent-read provenance. A local
inventory row carries `originalMethod`, `originalSymbol` (original method plus
`#local:<unsigned itemId>`), `originalName`, `itemId`, and `parameterIndex` for a
formal argument. Its `nameProvenance` and `namedBy` describe the current assignment;
`was` is included only when the original name is known. `code.params.parameters`
joins by original method and formal index. Java reads include the corresponding
`agentNames`/`humanNames` entries, with `kind: local|parameter`, current method
`addr`, stable identity, history and optional confidence. A non-rendered formal
argument may instead use `#parameter:<index>` and `previousName` (not a fabricated
original name). A human parameter mapping and local rename of that argument share
one identity when the engine supplies its text item. Cleared mappings, undo and
redo follow effective history order. Navigation owners do not broaden a method
read into a class read. Each relevant method is inventoried at most once per
decoration; common variable names elsewhere do not trigger historical method
decompilation. Unprovable legacy local identities are omitted.

Native local discovery uses the method's AST identifiers and temporarily sets
the public `JavaDocument.propnameDisregardCollapse` rendering property. It restores
that property afterwards, matches the actual actionable identifier token (including
indentation-spanning object locations), and deduplicates by item identity. It does
not infer method ownership from declaration text or parameter count.

Cross-reference `type` is JEB's reference-type value, passed through unchanged.
`UNKNOWN` is an unclassified reference, not evidence that no access occurs.
Pagination applies before the client sees rows: `total` and `truncated` describe
the full result and whether the response includes all of it.

### 4.3 Code
| Op | M | Purpose |
|---|---|---|
| `code.decompile <symbol> [--window from,to]` | | source + semantic token map (classTokens) |
| `code.smali <symbol>` | | listing with offsets + opcode bytes (cap `smali`; native engines: disassembly) |
| `code.hover <symbol> <offset>` | | tooltip model (kind, owner, sig, comment) |
| `locals.list <method> [target]` | | Displayed local variables with `name`, optional `type`, and `line`. Native JEB rows also include `originalMethod` (the original method descriptor), `originalName` (the identifier’s original name), `itemId` (unsigned decimal string to avoid JSON precision loss), and `originalSymbol` (`originalMethod + "#local:" + itemId`). This is the same identity used by `resolveLocal` and rename ownership. Match both identity and name when checking a saved/reopened database; name alone can identify a different variable. |
| `naming.coverage <symbol> [target, recordedBy]` | | Fresh per-method argument/local inventory and durable actor-specific assessments. See the naming coverage contract below. |
| `naming.coverage.record <symbol> <inventoryFingerprint> <evidence> items:[{identity,disposition,evidence}] [target]` | ✓ | Atomically merge assessments for the caller and fresh inventory; does not edit native names. |
| `code.outline <class>` | | method/field outline Since 2026-10-09 (the FMessage observer follow-up): every member with `origSig` (the ORIGINAL descriptor — what the phone runs; a display rename exists only here) and `access`; fields with `type` (original) / `displayType`, `static`, `final`, `declaredIn`; `collisions`: every member name shared by a FIELD and a METHOD of the class, by original name, with the field's type and the methods' original descriptors and the hook advice (a bare name selects the method wrapper in Frida — read the field by `getDeclaredField`, call the method by its descriptor), members marked `collides`; `inherited: true` adds `inherited {chain, fields (with inheritedFrom), unknown}` — the superclass chain's fields as this dex declares them (up to 6 levels; framework supers listed under unknown). Jeeves's DEX metadata is reported as it is, never changed for a consumer's syntax. |
| `evidence.packet <method> [--entry offset\|block] [--maxInstructions N] [--question …] [--java false] [--student false]` | | the MCP `code` tool's view `packet`: ONE method's bounded evidence — `original_method` beside the display names, the code in the dex's ORIGINAL names with stable refs (`<original descriptor>+<offset>h`), the CFG blocks (regular + exceptional edges), the selection (whole method, or the blocks reachable from `entry` — never a text cut), `omitted`, `missing`, `references` {calls, fields, types, strings}, `provenance` (the analyst's display names, kept apart), the whole-method java, a `snapshot` {target, artifactSha256, historyRevision} and an optional `student` block (every block with lines/successors/exceptional); a class → E_SYNTAX; a bodiless method → `mode: no-code`. Through the facade the student block is left out unless asked for (`student: true`) The packet's `snapshot` keeps THREE identities apart (2026-10-09, the WhatsApp path investigation: a packet from a reopened .jdb2 had no artifactSha256 while its note still said that field identified the bytes): `executable` {state: measured \| recorded \| conflict \| missing \| unknown, sha256, file, bytes, basis, inputClass?} — measured from the artifact file in the store, or by RE-READING the file JEB's project record points at (JEB does not keep the APK inside the .jdb2 — measured 2026-10-09: its input is a FileInput; with that file gone the database alone cannot name its bytes); recorded = a device pull's record, not re-measured; conflict = the file beside the database is not its input (sibling_sha256 given; sha256 is the input's); missing; unknown while the target still loads, `database` {file, bytes, modified, storeKey — never the executable's identity}, `revision` {history, snapshot_id}, `runtime` {build_sha, build_dirty, built_at, built_at_basis, engine}; the compat field `artifactSha256` is present ONLY when the executable was measured, and `note` says the state in words; `method.codeItem {dex, instructionsOffset}` names the exact DEX member. The packet's symbol is resolved STRICTLY (a contradicting descriptor is E_NOTFOUND naming the original one). |

Native variable inventories and current argument-name lookup use one complete
`JavaDocument` part when its anchor count is one; the line hint does not truncate
that anchor. A multi-anchor implementation retains the full-format fallback.
These two variable-only paths temporarily disable public
`ResolveMethodCallTargets`/`ResolveFieldAccessTargets`, preserving identifier
items and coordinates while avoiding unnecessary virtual target resolution.
Restore the prior namespaced overrides, including an absent override, on every
exit. Normal decompiled text, semantic tokens, xref sites and search rendering
retain their existing settings and current/original identity contract.

### 4.4 Relations
| Op | M | Purpose |
|---|---|---|
| `xref.to <symbol> [--grouped] [--limit]` | | callers (flat or recursive tree) |
| `xref.from <symbol>` | | referenced symbols |
| `callees <symbol> / callers <symbol> [--depth]` | | forward/backward trees, hub-aware (`maxHubDegree`, `lowConfidence` flags) |
| `impls <symbol>` | | implementations/overrides |

### 4.5 Strings & search

`search` keeps its raw API group-list data and includes `meta.searchCoverage`.
Facade/MCP `search {query}` returns `{groups, coverage}` to preserve coverage in
data-only clients, including zero matches. `coverage.partialCoverage` concerns
the decompiled-code/comment corpus; string-pool searches are separate. Coverage
includes indexed/total class counts, pending work and index revision observations.
Partial coverage and concurrent edits prevent interpreting no matches as absence.
The `search {anchors}` response remains `anchor-locate-v1`.
| Op | M | Purpose |
|---|---|---|
| `strings [target] [--filter] [--type] [--unreachable] [--sort priority\|xrefs\|order]` | | table rows: value, types+confidence, xref count, first referrer. Sorted BEFORE paging: `priority` (default — usage-confirmed crypto/url/intent/permission/content-uri/path first, lexical guesses under them, plain last; ties by xref count) · `xrefs` · `order` (the dex pool's enumeration, the pre-audit default) |
| `string.xrefs <string-id> [--grouped]` | | usages of one string |
| `search <query> [--scope target|all] [--kind code|strings|comments] [--regex] [--limit N]` | | project-wide decompiled-text search, grouped hits. Capped: 200 hits / 50 classes / 20 per class by default; `--limit N` (≤ 5000) raises the hit cap and scales the other two — one bigger in-memory scan, no pagination (audit decision 6). The UI's stats line says when a cap bit and offers the raise |

### 4.6 Edit ops (proposals for agents)
| Op | M | Purpose |
|---|---|---|
| `rename <symbol> <name> [expectedName]` | ✓ | class/method/field/local + namespace moves; a NEW name is a Java identifier that is not a keyword |
| `rename.revert <symbol>` | ✓ | back to the symbol's ORIGINAL dex name (validated against the dex — "1cq" is a fine original and a refused new name); recorded in History; humans only. history.undo of a rename whose old name the identifier rule refuses goes through the same restoration |
| `code.params <method>` | | (a READ, listed beside the ops it previews for) the retained parameter metadata: the dex's `Ldalvik/annotation/MethodParameters;` names/accessFlags verbatim in encoded order (nulls, duplicates kept; each array's length checked against the prototype separately; `argumentMapping: unverified` always), the debug_info names as a second source (`status: empty` for a table of null slots), the prototype, the method's `locals`, the arguments as the decompiler names them and why (analyst · debug_info · annotation applied by the engine by position · generic · unknown), `decompiler.annotationNamedArguments {status none|some|all, count, of, indices}` — which arguments JEB currently names from the list by position (a partial mapping leaves the rest positional; `appliesAnnotationByPosition` is the compatibility boolean for ALL, `appliesAnnotationToAny` for any), the analyst's `mappings` from History. The RAW block (no decompiler names — `appliesAnnotationByPosition: null`, "not computed in this view") is carried as `methodParameters` on a method's `code.decompile`, `code.smali`, `evidence.packet` (+ `mappings`); `code.decompile` of a class carries `methodParametersIndex`. The MCP `code` tool: view `params`. One class render on a cold method (cached until a name moves) |
| `param.rename <method> <index> <name> [annotationIndex]` / `param.rename.bulk <method> mapping:[{index, name, annotationIndex?}] [preview]` | ✓ | the ANALYST's explicit mapping of retained names onto formal arguments by position (cross-position allowed), through the engine's own parameter binding (every use follows; types and order never change). Validated whole (identifier, keyword, duplicates, collisions with arguments AND the method's locals, an existing analyst name said), a row that already carries the analyst's own name is a no-op (not applied, not recorded — the preview equals the commit) while a row equal to JEB's positional pick ADOPTS it as the analyst's mapping (applied, recorded `adopted`, undo gives the name back to the engine — the user is never gated), applied whole or not at all (swaps included), ONE History entry (kind `parameters`) with provenance: `changes[].from` (annotation entry k — checked against the dex, a claimed index whose entry is another name is dropped — or `analyst`), `source`, `original_method` (the mapping follows the method through renames) — undo puts back what each argument had (an earlier analyst name, or the engine's own); null/empty entries are no-ops; `expectCurrent:[{index,name}]` refuses a stale preview (E_BUSY). The preview is a read anyone may ask for; the apply is the analyst's own: a browser session — an agent is refused; `history.undo`/`redo` of a `parameters` or `revert` entry likewise. Not an MCP tool: the mapping is the UI's gesture. An agent may name an argument through `rename {local}` unless the analyst mapped it; a name equal to a retained entry is recorded `matchesRetainedName` |
| `comment <symbol> <text>` | ✓ | |
| `tag.add <symbol-or-line> <family:tag>` / `tag.rm` / `tag.list [target]` | ✓ | families user-defined (`tag.family.add`) |
| `bookmark.add <symbol:line> [note]` / `bookmark.rm` / `bookmark.list [target]` | ✓ | |
| `history.list [target|all] [--limit]` / `history.undo [id]` / `history.redo` | ✓ | rename/comment undo log (per-target; `all` spans targets) |

### 4.7 Flows
| Op | M | Purpose |
|---|---|---|
| `flow.list [target] / flow.get <id> [target]` | | saved source→sink flows with hop chains (single-target) |
| `flow.add <name> <source> <sink> [hops] [target]` / `flow.rm <id>` | ✓ | |
| `flow.promote <id> [--map <id>]` | ✓ | seed a Map from a saved flow (GRAPHS_SPEC §2) |

### 4.8 Runs (dynamic)
Runs and breakpoints are PROJECT-scoped (WORKBENCH_SPEC §8): every runs.* read answers for the active
project's store (projects/<slug>/runs/), E_NOTFOUND when no project is open. Records carry the target's
live id AND its durable store key (targetKey, targetName), so a [target] filter survives a restart. A
live frida session keeps its own project's store — a project switch never re-routes its hits, and
closing a project detaches its sessions. The pre-batch-2 global runs/ dir folds into the first project
activated after the upgrade (the emptied dir is parked with a .migrated suffix).

| Op | M | Purpose |
|---|---|---|
| `runs.devices [--probe false]` | | attached/available devices (a live session row carries its `target` and `project`); `root` = adbd-root \| su \| none \| ? — `probe:false` (pollers) answers root from the cache or "?", never running the su probe (a Magisk grant prompt comes from REFRESH / attach / the doctor, not a timer) |
| `runs.attach <device> <pkg>` / `runs.spawn` | ✓ | frida session lifecycle |
| `runs.bp.list [target] / runs.bp.add <symbol> [--cond] / runs.bp.rm / runs.bp.toggle` | ✓ | method breakpoints, DEX and native. A DEX breakpoint takes a JEB symbol; a NATIVE breakpoint takes a module + one of: an export (resolved by frida, no engine), a base offset (from a disassembler), or a function name (resolved to an offset by idalib against a local/pulled copy of the module — pass so=path, else the pulled copy is used) instead of a symbol — it hooks the .so in the attached process via a second frida agent and is coverage-keyed native:module!export (a resolved function keys as native:module!function). The bp binds by the exact device PATH when known (a full-path module, or a pulled copy's origin) so same-basename libraries don't collide, and the agent refuses to bind if the LOADED module's GNU build-id differs from the copy the offset was computed against (a stale pull / wrong build) — for offset mode pass so=<the disassembled file> to enable that check on a full-path pin. `bp.list` = the active project's, or one target's (live id or store key) |
| `runs.hits [target] [--run] [--method] [--limit]` / `runs.diff [target] <a> <b>` / `runs.coverage [target] [--run] [--class]` | | hit feed (args, return, depth, backtrace) / diff / coverage — `[target]` picks that target's project store (execIds are per-store sequences), the active project's without it; an execId absent from the chosen store is E_NOTFOUND |
| `runs.runs [target]` | | run history (id, time, hits, coverage) of the active project — `[target]` = that target's subset, the RUNTIME dock's view |
| `runs.rm [target] <run\|all>` | ✓ | delete one recorded run, or every ENDED run of the store (`all`): the index row and coverage go, the hit log is parked as `<run>.jsonl.deleted-<ts>` (never unlinked), stacks stay. A run with a live session is E_BUSY (detach first; `all` skips it and reports `skippedLive`). Human-only: an agent actor gets E_SCOPE. MCP: the curated tool `wb_runs_rm` (strict schema; available in curated mode) |

The Message Lab (`runs.lab.*` ops, the `message_lab` facade) is retired: its runtime is not part of
this repository and the facade must not appear in any catalogue (`MessageLabIT` asserts it).

### 4.9 Maps — see GRAPHS_SPEC §5
Orientation: `map.list · map.summary · map.outline · map.get · map.node ·
map.neighbors · map.paths · map.find · map.type.list · map.diff ·
symbol.resolve`. Mutation: `map.create · addnode · updatenode · rmnode ·
addedge · rmedge · setverdict · setchain · attachevidence · type.add/set ·
export · open · close · delete`.
`map.close {id?}` is idempotent and file-neutral: it clears the active
project's `activeMap` (bare form closes whatever is open, `{id}` only closes a
match). Closing with nothing open is a success, not `E_NOTFOUND`.
`map.delete {id, confirm:true}` is human-only (no agent tool maps to it) and
**parks** the sidecar as `<id>.map.json.deleted-<ts>` rather than unlinking it;
the result carries `{id, parked}`. Deleting the open map implies close.
Both act on the ACTIVE project's store, like every other `map.*` op — neither
can reach another project's map.

### 4.10 Agents — see AGENTS_SPEC §5
`agent.missions · agent.spawn · agent.pause/resume/stop · agent.setbudget ·
agent.setscope · agent.activity · agent.proposals · agent.review ·
agent.follow/unfollow`.
`agent.follow` is server state, so `agent.missions` rows carry `following: bool` —
a client that just booted paints its FOLLOW switch from the mission list rather
than from a remembered click.

`agent.proposals [--mission] [--kind] [--target]` answers the ACTIVE project's pending queue (batch 2,
2026-09-10): a proposal is filed with the project of the target it resolves to (else the active
project) and carries project, target, targetKey and targetName; --target narrows to one target (live id
or store key). Ids come from ONE sequence across projects (proposals.seq beside projects/), so a review
issued while another project is active is E_NOTFOUND, never a settle of a look-alike id; a no-project
workspace answers E_NOTFOUND. The pre-batch-2 global proposals.json folds into the first project
activated after the upgrade.

`agent.purge [--before ISO] [--mission] [--noRationale] [--targetGone]` settles the ACTIVE project's
PENDING proposals that match every given criterion as purged — nothing applied or un-applied; the
queue cleaner for a dead mission's leftovers, rows filed before a date, rows without a rationale, rows
whose target is gone. At least one criterion; humans only (an agent actor gets E_SCOPE); MCP tool
`wb_agent_purge` (curated).

`agent.spawn` has NO unscoped mission (batch 2, audit decision 3): a blank scope becomes
target:<pinned target>; with a project open but no target it becomes project; the word project is
the explicit whole-project opt-in — every target the mission's project holds and the project's maps
(map.* is admitted, unlike under a package scope); with neither a target nor a project the spawn is
E_SYNTAX. Missions carry project (the pinned target's project, else the active one). For
`agent.setscope`, project is the widest form: narrowing from it is agent-allowed, widening to it
needs the human — and every form stays inside the mission's project: a target another project
holds, or another project's maps, is outside scope whatever the string says. `device.spawn-mission`
has the same rule (scope project, refused without an open project).

### 4.11 LLM providers (multi-LLM)
`llm.providers · llm.configure · llm.rename · llm.setkey · llm.remove`.
Providers carry `{id, label, driver: builtin|cli|http, baseUrl, models[], local,
configured, builtin}`. `llm.configure {id, baseUrl?, model?, label?, models?, local?, extraParams?, create?}`
edits a provider; an UNKNOWN id is E_NOTFOUND unless `create:true` (the create gate — a stale client
cannot resurrect a renamed-away id; the id slug rule is checked first, so a bad id is E_SYNTAX either
way). A provider is ONE vendor endpoint with ONE key — ids are the vendor (`zai`, never `glm52`);
`models` replaces its version list (the user's order); `model` is the explicit default and, when
`models` is given, must be one of them (E_SYNTAX — no silent adoption). Dropping a pinned model never
re-points the pin: `task.list` marks the depth `resolved.unlisted` and the doctor's reversing row warns.
`llm.rename {id, newId}` moves a user-added provider (or `custom`) to a new id — key, config and every
task pin, atomically or not at all (keys first; any failed write reverts and the E_IO names the on-disk
state and the recovery); refused for built-ins other than `custom`, a registered `newId`, a `newId`
holding a stored key, or one sharing another provider's env-variable name; the response says when an
env override changes the effective credential. `llm.remove {id}` drops a user-added
one (its key and any task pin naming it go with it) — built-ins are edited, never removed. `llm.*` mutations are operator-only: a mission actor gets E_SCOPE; an unmissioned MCP client may call them (tools `llm_providers` / `llm_configure` / `llm_setkey` / `llm_remove` / `llm_rename` / `llm_models`). A provider is a VENDOR; where a vendor offers more than one way to connect, its row carries `auth` + `authOptions` and `llm.configure {id, auth}` switches it — `auth` travels ALONE (combined with models/model/baseUrl/local it is E_SYNTAX) and re-derives the endpoint, key requirement and seed list. Today only `anthropic` chooses: `cli` (your Claude Code sign-in via `claude auth login`, no key; `configured` means installed AND signed in) or `api` (an Anthropic API key against `https://api.anthropic.com/v1`, the OpenAI-compatible surface). The legacy id `claude` is reserved and resolves as `anthropic`. `llm.probe {id, model?}` (not an MCP tool) is the wet test: one tiny completion — key valid, credit, the provider replies. `agent.spawn` has no implicit provider: without a model it runs only `triage-package <prefix>` (scripted) or a configured reversing depth. Keys are WRITE-ONLY: `llm.setkey` stores (the key param is required — an empty string clears, an absent param is E_SYNTAX; llm-keys.json, chmod 600;
env override `WORKBENCH_LLM_<ID>_KEY` wins) and no op ever returns key material —
`llm.providers` reports only `configured: true|false`. `agent.spawn --model
<provider:model>` infers the mission mode from the provider driver; http drivers
run the OpenAI-compatible agent loop (curated tool subset, internal dispatcher,
budget-pause).

### 4.12 Layouts (named view configurations)
| Op | M | Purpose |
|---|---|---|
| `layout.list` | | saved layouts: names + ts |
| `layout.save <name> <layout>` | ✓ | upsert one named snapshot of the panel/dock arrangement (client-produced JSON, stored opaquely) |
| `layout.apply <name>` | | returns the stored snapshot — applying it to the DOM is the client's job |
| `layout.delete <name>` | ✓ | E_NOTFOUND on unknown names (same as apply) |
| `ui.prefs` | | the operator's chrome — theme light\|dark, side/dock visibility, sideW/rightW/dockH — stored server-side (ui.json) so it follows the workspace across machines; named layouts stay explicit, this is the one current chrome (audit decision 17) |
| `ui.prefs.set <prefs>` | ✓ | merge the known keys (validated; unknown = E_SYNTAX). Operator-only: a mission gets E_SCOPE |
| `ui.prefs.reset` | ✓ | back to the defaults (the palette's "Reset layout & theme") |

### 4.13 Devices — see DEVICE_SPEC §ops
| Op | M | Purpose |
|---|---|---|
| `device.list` | | connected devices (serial, model, android, root, frida) |
| `device.catalog <serial> [--refresh] [--kind] [--filter]` | | catalog entries, paged; refresh re-scans and returns a diff. Carries `kindTotals`/`cappedKinds` (per-kind device truth vs. the scan caps) and, after a refresh, `removed[]` tombstones — see DEVICE_SPEC §4 |
| `device.browse <serial> <path>` | | raw filesystem listing (root shell) |
| `device.services <serial>` | | binder service registry: name, interface, host process, owning package/apk |
| `device.components <serial> <pkg>` | | components one package DECLARES (service/receiver/provider/activity), from `dumpsys package --all-components`: fully-qualified class, `permission` and intent actions where the dump prints them, plus the package `versionName`/apk path |
| `device.proc <serial> <pid>` | | live /proc detail for one running process (state, ppid, uid/gid, threads + per-thread table, memory, cpu, ctxt switches, fds, oom, exe/cwd, cmdline) in one adb round trip; a pid that has exited answers E_NOTFOUND |
| `device.pull <serial> <path>` | ✓ | pull artifact → opens a target |
| `device.annotate <serial> <name> <note>` | ✓ | human annotation (agents propose) |
| `device.spawn-mission <serial> <goal>` | ✓ | catalog triage mission |

### 4.14 Co-presence (who is looking at what)
| Op | M | Purpose |
|---|---|---|
| `presence.ping <addr> [member]` | | "I am now at this symbol" — sets the CALLER's position and publishes a `presence` event. No mutation; the browser pings on class open / symbol navigation (debounced) so agents can see the human |
| `presence.whereis [who]` | | freshest position of an actor-kind (`who`: `agent` default, `human`) → `{present, clients, actor, addr, name, member, memberName, ts, ageMs}`. A pure read: it moves no pin and publishes nothing |

Positions are project-scoped, latest-wins per actor, and never expire server-side —
the returned ageMs is what lets a client dim a stale pin and drop a dead one.

Agents mostly do not need to ping. A locating op called by an llm:/agent actor
publishes a **view** presence event on its own (code.decompile, code.smali,
code.outline, code.classinfo, symbol.resolve, xref.to, xref.from, callees,
callers, impls), and the write ops rename, comment and tag.add publish **edit** —
a rename pins the POST-rename addr, so a follower lands on the live symbol rather
than on a handle that no longer resolves. Human reads never auto-emit: a browser's
background fetches are not navigation, which is why the browser pings explicitly.

### 4.15 Built-in tasks (the reversing agent's settings)
| Op | M | Purpose |
|---|---|---|
| `task.list` | | the built-in task catalogue — today `reverse` with its `quick` / `deep` depths: each depth's configured `{model, effort, turns, budget}` (null / 0 = default), the defaults it falls back to, and the provider/model the next spawn resolves to |
| `task.configure <task> <depth> [--model] [--effort] [--turns] [--budget]` | ✓ | MERGE one depth's settings: an absent param keeps the stored value, a blank string / 0 resets the field. `model` is `provider:model`, must resolve (the scripted agent is refused) and has NO default — an unconfigured depth spawns nothing (`agent.spawn {depth}` is E_SYNTAX until it is set); `effort` is `low\|medium\|high\|xhigh\|max` (the claude CLI's `--effort`; http providers use their model's reasoning-effort contract, detailed below); turn cap and budget default per depth. `agent.spawn {depth}` with no explicit model runs on these (backend/.workbench/tasks.json); `llm.remove` resets the depths that named the removed provider. Operator-only: a mission actor gets E_SCOPE, an unmissioned MCP client (the user's assistant) may call it — MCP tools `task_list` / `task_configure` carry strict schemas |

With `WORKBENCH_HEADLESS=1` (also `true`), HTTP and CLI agent missions have no
turn ceiling. This overrides depth defaults, saved `task.configure` turns and
explicit `agent.spawn` turns without rewriting the saved task settings.
`agent.setturns` is a reported no-op for these missions. Their records carry
`turnsUnlimited: true`, `turnCap: 0`, and no `maxTurns`/`turnsNeeded` ceiling;
`turnsDone` continues to count requests. `task.list` depths carry
`turnsUnlimited: true` and zero `defaults.turnsLoop`/`defaults.turnsCli`, while
`turns` preserves the stored configuration. Manual pause/resume/stop and token
budget enforcement remain effective. The CLI runner omits `--max-turns`.
No mission restart/resumption semantics change. With the flag absent or false,
the existing bounded behavior applies. Java's headless rendering flag does not
select this execution policy.

`WORKBENCH_UNLIMITED_BUDGET=1|true` together with the headless flag additionally
removes HTTP mission token ceilings. Mission records carry `budgetUnlimited:true`,
`budget:0`, and 64-bit `inputTokens`, `outputTokens`, `tokensUsed`, `tokensRaw`,
`cacheHitTokens`; each turn retains the complete provider `usage` object.
`agent.setbudget` is a reported no-op in this mode. Task rows disclose the override
while preserving stored budgets. Provider balances are not changed or capped.
Default/non-headless budget behavior is unchanged.

`agent.missions` adds `loopActive` for a live HTTP loop, including final record
persistence. `agent.stop` sets `cancellationUnconfirmed` while this loop drains;
`displayState` is then `draining`. No new tools/turns are dispatched after stop,
but an in-flight response contributes its actual usage. Terminal state alone is
not acknowledgement that all work has stopped. Save barriers also wait for
`loopActive` and `cancellationUnconfirmed` to be false.

HTTP `reasoning_effort:max` is passed unchanged for `deepseek-v4-pro`,
`deepseek-flash`, and `glm-5.3` models; their `medium`/`xhigh` aliases map to `high`.
Other models retain the existing max/xhigh-to-high compatibility mapping.
Configured provider output allowances and mission `maxOutput` take precedence
over the 4096 fallback, bounded by the endpoint allowance and any effective
mission budget. Streaming usage supports both DeepSeek cache hit/miss fields and
Z.ai `prompt_tokens_details.cached_tokens`.

The `code` facade accepts `view:locals`, dispatched to `locals.list`. Headless
HTTP loops deliver whole tool payloads and matching name-provenance rows; ordinary
interactive payload cuts remain unchanged. Context-window maintenance still
applies. `annotate` accepts batches of 1–50 items; full classes may require several.
Native text search refreshes pending invalidated classes and persists their text
and header; concurrent queries share that work. On-demand mode starts no automatic
reindex worker on edits or reopen, but explicit full rebuilds remain available.
Failed/superseded renders stay pending and cannot publish obsolete names.

Agent-name attribution conservatively excludes historical names absent from all
returned string values before native resolution. Every remaining possible match
is checked against the current engine, including affected method-family members,
human ownership, undo/redo order and original identities. No attribution state is
cached across reads; unrelated history does not add native lookups to each read.
Original descriptors/local identities accompany current names in the existing
symbol, locals and agent-provenance contracts. Failed renders remain explicitly
partial; a query overlay does not claim successful durable cache persistence.

### 4.16 System (prerequisites)
| Op | M | Purpose |
|---|---|---|
| `system.doctor [--rows a,b,…]` | | the prerequisite rows a running server can answer (`--rows` = only those ids — the Runs preflight asks for adb,devices,frida,jeb-engine), same shape as `./provision.sh --json`: `{id, status: ok\|warn\|missing, required, found, want, fix, auto}` (`auto`: the fix runs unattended — no sudo, no secrets) — JDK; engine (vendored, version once a target opened); web build; python3 + the MCP bridge; frida sidecar (venv frida version, vendored server binary); adb (`WORKBENCH_ADB`, the macOS/Linux SDK paths, `$ANDROID_HOME`, PATH); attached devices with root capability `adbd-root` / `su` / `none`, SELinux and frida-server state (that row also carries a `devices[]` detail list — serial, state, model, android, root, selinux, fridaServer — the one key beyond the common shape; the row has a 20 s budget — devices beyond it are counted as "not probed" — and an ABI other than arm64-v8a is a warn, since the vendored frida-server is android-arm64); claude CLI (found, logged in); providers with keys (never the keys); the reversing depths' resolution. The subset a running server can see: no build-time rows (gradle, node, web-deps, server); the script's `frida-server` + `frida-venv` collapse into one `frida` row. Read-only (its one side effect is starting the frida sidecar). Operator-only like `llm.*` / `task.*`: a mission actor gets E_SCOPE (host paths and the provider inventory stay with the operator); an unmissioned MCP client — the user's assistant — may call it. The fixes are per-OS command lines; nothing is installed from the server — the operator (or their assistant, PROVISIONING.md) runs them |

## 5. Cross-cutting conventions

- **Scoping params**: every project-scoped op takes an optional `project`
  param (default: the current project); every target-scoped op takes an
  optional `target` param (default: the active target). `map.*`, `agent.*`,
  `runs.*`, and `device.*` are project-scoped and accept `project`.
- **Symbol addressing**: every op accepts the same symbol grammar —
  `addr` (engine), `origSig` (rename-surviving), or fuzzy name (resolved
  via `symbol.resolve`; ambiguity returns candidates, never guesses).
- **Events**: SSE channels — `progress`, `presence`, `activity`,
  `proposals`, `runs`. MCP clients receive them as notifications; the
  `hello` snapshot replays current state (agents, runs, presence).
- **Idempotency**: all mutation ops are idempotent on repeated identical
  params (safe agent retries).
- **Cost hints**: ops declare `cost: cheap|scan|heavy`; `heavy` ops
  (whole-DEX scans) run off the session lock and are cancellable via
  `op.cancel <ticket>`.

## Save completion and durability

The private cloud campaign exports partial method-variable coverage as
`status:collecting`, with `classes_total` and the classes already checked.
Post-save partial coverage takes precedence over pre-save evidence. Generic-name
flags (including short locals and `A0a`-style members) are review hints only;
they do not change native renameability or establish semantic correctness.

A target save is serialized with its edit transaction. Async completion requires `saving:false`, `last.at >= startedAt`, and `last.ok:true`; failures before the native write also produce a fresh failed outcome. Rename ownership clears only after history and native state are persisted. Undo/redo events have durable revisions and replay in execution order across a restart; discard includes pending reversals of saved entries. Failed replay prevents saving a partially recovered project. Close waits for active target edits and refuses a failed history flush before detaching; a native synchronous close-save failure reports `closed:true, saved:false`. See [persistence contract](../docs/persistence.md) for checkpoint, recovery, and verification semantics.

The search corpus is derived state. Its pending recovery entry clears only after replacement text and the header persist; see [search-index recovery](../docs/search-index-recovery.md).

Cloud proxy authorities: `WORKBENCH_PROXY_HOSTS` adds explicit exact Host values to
the existing loopback Host set. The server still binds 127.0.0.1, and matching Origin
is required when supplied. Authentication remains the local reverse proxy’s duty.

The optional `ops/cloud` controller uses the existing loopback ops, not a second
MCP service. It enumerates the complete package/class tree, claims eight distinct
original class IDs in a durable SQLite catalog, and reconciles a spawn through a
unique assignment tag. A failed or ambiguous persistence/spawn step admits no
new session. Native-only verification opens a copied JDB with no edit-history
sidecar; live verification follows reopening the source target. No host lifecycle
or automatic checkpoint-deletion operations are included.

### Durable naming coverage

The loop-visible and MCP facade tool `naming_coverage` routes `action:get`
(default) to `naming.coverage` and `action:record` to `naming.coverage.record`.
All ordinary mutation scope checks apply to record; `recordedBy` cannot change
its author. This is evidence storage, separate from native edits/proposals.
Mission write counts/revert include direct local/argument renames under the
normalized exact actor identity. Local rename rationale persists in history.
An HTTP mission cannot be reverted while its loop is alive or stop is unconfirmed;
this also guards a paused label while its last provider/tool call is still active.
Successful records count as research progress for loop completion without forcing
unnecessary renames. Failed records and coverage reads do not suppress that nudge;
class completion still requires the whole inventory and independent review.

`schema:naming-coverage-v1` returns `originalMethod`, `hasCode`, `variables`,
`inventoryFingerprint`, `inventoryErrors`, `counts`, `recordedBy`, `record`, and
`status` (`unreviewed|stale|needs_review|complete`). Parameter identities use the
original method plus `#parameter:<formalIndex>`; body locals retain their exact
`#local:<itemId>`. Rendered parameters also carry their local identity and item
ID. `renameableLocal` describes whether annotate can target the current local
name, including declaration arguments on abstract/native methods with
`hasCode:false`. Their native IDs provide capability and provenance; the existing
no-body fingerprint remains based on original method, formal index and name.
Current names use the rendered declaration; differing low-level parameter
bindings are exposed as `parameterBindingName`/`parameterBindingMismatch` metadata
and must not replace another argument's displayed name.
JEB's zero item ID means no actionable item: such locals carry
`identityKind:rendered-only`, `renameableLocal:false`, and `renameUnavailableReason`.
Rename and rename-ownership lookup return `E_UNSUPPORTED` with
`reason:JEB_NO_ACTIONABLE_ITEM`. They cannot be repaired by trying collision suffixes.
All distinct zero-ID AST identifiers remain visible: the first keeps `#local:0`,
subsequent ones use `#local:0:rendered:<ordinal>` in rendered occurrence order.
These are inventory labels, not native item IDs or rename targets; fingerprint
and save/reopen verification must check them. A needed but unavailable rename
stays unresolved. Formal arguments without code are inventoried too; a missing name must be
unresolved. Native `code.params` now includes the public SDK's `hasCode` flag.
The fixed receiver is explicitly excluded. Unsupported/failed inventory reads
are never converted into complete empty inventories.

The fingerprint covers original identities, displayed names, argument/local
bindings, code presence and inventory failures, not mutable rendering line
numbers or target IDs. A stale record attempt is E_BUSY. Each decision must
name a unique current identity, use `renamed|retained|unresolved`, and contain
nonblank evidence; a method-level evidence string is also required. `renamed`
requires current history provenance. Partial records merge only for the same
actor and unchanged fingerprint; a changed inventory requires fresh assessment.
An explicit empty items list reviews a variable-free method. No count limit is
imposed by this tool. Atomic, fsynced sidecars preserve separate actor records;
unreadable files produce E_IO and are retained. Names and reviews remain model
claims; complete coverage does not certify semantic correctness.

Headless in-process tool replies are not cut by the interactive 6K/16K limits.
The external Python MCP bridge still has its own existing limits: four in-flight
calls, 75-second call timeout, 16 MiB HTTP response maximum, and an 80,000-character
result cut by default. These are not on the cloud in-process agent path. Provider
context/output allowances and context maintenance still apply. Coverage records
can be reread after conversation compaction; bounded transcript retention is not
used as the source of per-variable completion evidence.
