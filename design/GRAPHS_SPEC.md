# Graphs & Maps Specification (v0.1 · draft)

Status: spec discussion · 2026-07-23 · branch `graph-integration`
Scope: integration of complex cross-module flow graphs ("Maps") into the
locked Workbench v1 UI (`WORKBENCH_SPEC.md` §14), informed by the fcm_map
project (`~/projects/fcm_map`). Covers: the Map concept, schema, UI
integration, MCP op coverage, agent workflow, and versioning/diff.

---

## 1. What fcm_map teaches (design inputs)

From the fcm_map audit:

- The artifact analysts actually build is a **layered attack-surface graph**:
  endpoints → front-door dispatch → handlers, with per-field provenance
  verdicts, positioned gates, ordered trace chains, and dynamic evidence.
- Scale is human: ~50 nodes, ~50–100 edges, ~100–200 annotated fields.
  Readability and navigation dominate; layout performance is a non-issue.
- Today's pain: hand-authored JSON + Graphviz poster. No node→code jump,
  untyped implicit edges, free-text code anchors (`"h() line ~147"`),
  evidence as log paths, no live/runtime layer, diff = a boolean flag.
- What was already right: verdict enums (`AUTHORED/MIXED/IDENTITY/GAP/DEAD`,
  `verbatim/constrained/server_regenerated/unknown`), gates with positions
  (`pre-parse/post-parse/pre-action`), chains as ordered `{step, addr,
  detail}`, per-build versioning. We adopt these conventions wholesale.

## 2. The Map concept

A **Map** is a first-class, **project-level** artifact — it belongs to the
project, never to a single target. Its nodes anchor to engine-resolved
symbols across *any* of the project's targets (APK, native lib, source
tree), and cross-target edges (Java handler → JNI native → source file)
are the primary use case, not an edge case. Maps carry analyst verdicts and
runtime evidence, and generalize fcm_map (attack-surface maps) and old
Jeeves flows (source→sink) into one schema.

- Maps live in the project sidecar: `<project>/maps/<map-id>.map.json`.
- Maps are engine-neutral: symbol references resolve through the engine
  adapter of the owning target (same mechanism as tags/flows).
- Maps are agent-writable through the op registry (§5) — the map an agent
  builds is the same map the human sees, live.

## 3. Schema (v1)

```jsonc
{
  "schema": 1,
  "id": "fcm-attack-surface",
  "title": "FCM push attack surface",
  "build": "gms-26.25.32",              // free-form; drives diffing
  "nodes": [{
    "id": "mdm-svc",
    "kind": "endpoint|frontdoor|allowlist|handler|sink|source|native|custom",
    "label": "mdm.services.GcmReceiverService",
    "sublabel": "MDM remote policy",     // short analyst blurb
    "target": "gms.apk",                 // owning target in the project
    "symbol": {                          // engine-resolved, never free text
      "addr": "Lcom/google/android/gms/mdm/services/GcmReceiverChimeraService;",
      "origSig": "L…;->a(Landroid/content/Intent;)V",   // rename-surviving key
      "entry": "onHandleIntent"
    },
    "verdict": "authored|mixed|identity|gap|dead|none",
    "proven": true,                      // dynamically proven (evidence attached)
    "layer": 2,                          // layout hint (spine column)
    "tags": ["push", "mdm"]
  }],
  "edges": [{
    "from": "frontdoor", "to": "mdm-svc",
    "kind": "transport|dispatch|call|dataflow|gate",
    "label": "gcms extra",               // optional
    "gate": "allowlist",                 // optional gate id
    "confidence": "static|dyn|low"
  }],
  "fields": [{                           // per-field verdict layer
    "node": "mdm-svc",
    "field": "new_password (MdmRemotePolicyProto.f)",
    "verdict": "verbatim|constrained|server_regenerated|unknown",
    "rationale": "…",
    "evidence": [{ "run": "run-2026-07-21-mdm", "note": "captured bytes…" }]
  }],
  "chains": [{                           // ordered trace per node (optional)
    "node": "mdm-svc",
    "steps": [{ "label": "base64-decode",
                "symbol": {"addr": "L…;->h(Landroid/content/Intent;)V", "line": 147 } }]
  }],
  "gates": [{ "id": "allowlist", "label": "GcmDestinationAllowlist (48 FQCNs)",
              "position": "pre-parse|post-parse|pre-action" }]
}
```

Deliberate choices:
- **Typed edges** (fcm_map's were implicit) — enables edge-kind filters and
  gate-on-edge rendering.
- **Stable symbol refs**: `addr` for display, `origSig` for rename-survival,
  optional `line` for chain steps. No free-text anchors.
- **Evidence by run ID** — references the project's RunStore, not log paths.
- No layout coordinates: layout is computed (§4); `layer` is the only hint.

## 4. UI integration (locked Workbench v1)

The map is **not a mode and not a separate screen** — it lives inside the
existing chrome:

1. **Maps are project-level surfaces, living in the target rack.** The
   project chip's menu carries a MAPS section (list + "new map…"), and the
   open map appears as a **project-scope chip at the left of the target
   strip** (a distinct graph glyph — not the project cube — **one chip for
   the open map**, rendered from `workspace.state.activeMap`, so a project
   with nothing open has no chip; tooltip "<id> · show/hide map", hover-×
   closes; divider before the target chips) — the same rack
   that determines which APKs/.so/source trees are open. It is NOT an
   editor tab: maps never share the class tab row. There is **one active
   map per project** and one canvas: `map.open` activates, `map.close`
   deactivates, `map.delete` removes. Chip body = show/hide the surface;
   the switchers are the projmenu MAPS section and the ⌘K MAPS group.
   While a map is active,
   the breadcrumb roots at the *project* (`privacy-invite-flow ›
   fcm-attack-surface` — the WORKBENCH_MOCK fixture scene), and nodes carry **target badges** so cross-target
   structure is visible at a glance. Map state (zoom, pan, selection)
   preserved across view switches.
2. **Canvas**: layered spine layout (rank left→right, fcm_map's instinct),
   pan (`grab`/`grabbing` cursors per the cursor contract) / zoom
   (buttons + ⌘scroll), minimap strip. Nodes: rounded boxes with kind icon,
   label, sublabel, verdict chip, ⚡ for verbatim fields, thick border for
   dynamically-proven.
   **No hardcoded filters or type buttons anywhere.** Node kinds, edge
   kinds, verdicts, and tags live in a per-project **type registry**;
   a single TYPES menu (map toolbar) lists every configured type with
   color swatch, count, and visibility eye-toggle, plus inline add-type.
   Rendering and visibility both read from the registry. Types are
   configurable from the UI (add/edit/hide) and via MCP (`map.type.list` /
   `map.type.add` / `map.type.set`); when adding a node or edge, its type
   is chosen — or created — from the same registry. Default registry ships
   with the fcm_map-derived set (authored → vermillion, mixed → orange,
   identity → bluish green, gap → grey, dead → dark; low-confidence edges
   dashed, dyn edges glow per the phosphor discipline).
3. **Single-click a node → RELATION pane gains a MAP section**: verdict,
   gates, a **code excerpt** of the node's entry method (syntax-highlighted,
   ~8 lines, clickable ENTRY header jumps to code), fields table (field ·
   verdict · evidence), and the trace chain as a clickable step list.
   **While a map is active, the rail shows ONLY graph-related content** —
   the class-info bay and the XREFS/CALLEES/IMPLEMENTATIONS sections hide;
   with no node selected, the rail shows a map-overview panel (title,
   build, per-verdict counts, evidence summary). The map never opens side
   windows.
4. **Double-click a node (or a chain step) → jumps to code — across
   targets.** The owning target becomes active in the target strip, the
   class opens in the editor with the caret parked on the entry symbol
   (yellow all-occurrence highlight, breadcrumb, unified history push).
   `Esc` returns to the map tab with selection/zoom intact. Node→code→node
   round-trip is *the* core loop; it must be ≤2 clicks in both directions,
   even when the node lives in a different target than the current one.
5. **Runtime integration**: edges/nodes with attached run evidence render
   the live glow; a "show evidence" action jumps to the Runs view (the
   project console) filtered to the referenced run.
6. **Diff view** (v1.1): two maps of the same `id` with different `build`
   values → nodes colored added/removed/changed (`changedVs` generalized).
7. Keyboard: map canvas is caret-free; `←→↑↓` moves node selection,
   `↵` opens code, `Esc` back. All actions also in the palette.

## 5. MCP / op coverage

Same op registry as everything else (palette, MCP tools, and UI render from
one source). All ops are actor-tagged and write-scope enforced.

### 5.1 Orientation ops (read) — how an LLM groks a map fast

Designed so an agent can orient in 1–3 calls before touching anything:

| Op | Purpose |
|---|---|
| `map.list` | Maps in the project: id, title, build, node/edge counts, pending-proposal counts |
| `map.summary <id>` | **The orientation op.** One token-cheap call: title, build, layer structure, node count by kind and by verdict, edge count by kind, proven nodes, gates, pending proposals, last-modified. Read this first. |
| `map.outline <id>` | Compact textual tree of the graph: spine layers left→right, one line per node (`id · kind · verdict · label · target · ⚡fields`), edges as indents. The LLM's "at a glance" view without JSON bulk. |
| `map.get <id>` | Full map JSON, paged (`offset`/`count` on nodes/edges) for large maps |
| `map.node <id> <node>` | Full node detail: verdict + rationale, gates, fields with evidence, trace chain, symbol refs |
| `map.neighbors <id> <node> [depth=1]` | Adjacent nodes/edges up to depth — local exploration without fetching the whole graph |
| `map.paths <id> <from> <to> [max=5]` | Paths between two nodes — attack-path queries ("how does mtalk reach the LOCK sink?") |
| `map.find <id> <query>` | Fuzzy search over node labels, symbols, sublabels, field names → matching nodes |
| `map.type.list` | The type registry for this project (node kinds, edge kinds, verdicts, tags) |
| `map.diff <id> <buildA> <buildB>` | Build-vs-build comparison (added/removed/changed nodes, verdict changes) |
| `symbol.resolve <addr-or-origSig>` | String address → engine symbol across the project's targets (used by jumps and by agents referencing code) |

### 5.2 Mutation ops (write) — proposals are reviewable

| Op | Purpose |
|---|---|
| `map.create <title>` | New map in the project. Idempotent on the slugged id — a title that slugs to an existing map returns that map's summary with `existing: true` (a fresh one carries `existing: false`), so no caller can mistake an adoption for a creation |
| `map.addnode` / `map.updatenode` / `map.rmnode` | Node CRUD (`fields: {id, kind, label, layer, sublabel?, symbol?, target?, tags?, proven?}`; `layer` `0, 1, 2, 3 …` sets the left-to-right column and defaults to `0`; symbol refs validated via `symbol.resolve`) |
| `map.addedge` / `map.rmedge` | Edge CRUD (typed; gate/confidence attributes) |
| `map.setverdict` | Node/field verdict + rationale. Agent-issued verdicts land as **pending proposals** (human accepts/rejects in the RELATION pane or via `agent.review`) |
| `map.setchain` | Replace a node's trace chain |
| `map.attachevidence` | Link a run/hit (RunStore ID) to a field or edge — flips `proven` |
| `map.type.add` / `map.type.set` | Configure the type registry (same registry the TYPES menu edits) |
| `map.export <id> <fmt>` | Poster export: md / svg / json (the fcm_map `render.py` successor) |
| `map.open <id>` | Open/activate the map in the UI (agent→human handoff: "look at this"). Per-project; persisted to `projects/<slug>/session.json` |
| `map.close [id]` | Deactivate: clears the project's `activeMap` and nothing else — no map file is touched and the map keeps its row in `map.list`. **Idempotent**: closing with nothing open is a success, not `E_NOTFOUND`. Bare form closes whatever is active; `{id}` only closes a match |
| `map.delete <id> --confirm` | Remove the map from the store and **park** its sidecar as `<id>.map.json.deleted-<ts>` (never unlinked). Deleting the open map implies close. Returns `{id, parked}` — the parked path is the recovery story. **Human only**: `confirm:true` is enforced at the op layer (every registered op becomes an MCP tool), and no agent tool maps to it |

## 6. Agent workflow (how fcm_map would run inside the workbench)

1. Human opens `gms.apk` target + creates map `fcm-attack-surface`.
2. Agent mission "map the FCM push surface": finds the front door via
   string xrefs, walks dispatch, enumerates the allowlist — emitting
   `map.addnode/addedge` as it goes. Human watches the graph grow live
   (presence pins on the canvas).
3. Agent proposes per-field verdicts (`map.setverdict`, pending) with
   rationales; human reviews in the RELATION pane fields table
   (accept/reject, ⌘A/⌘X grammar from the cockpit).
4. Frida runs attach evidence (`map.attachevidence`); proven nodes get
   the thick border automatically.
5. New GMS build drops → agent re-runs on a second target, `map.diff`
   surfaces what changed.
6. `map.export` produces the poster for the report.

## 7. Non-goals (v1)

- No auto-generated whole-app call graphs as maps (the Atlas problem:
  hub pollution). Maps are curated; auto-reachability may *suggest* nodes.
- No WebGL/custom renderer — SVG/DOM canvas suffices at this scale.
- No real-time collaborative editing (single user + agents only).
- No arbitrary freehand drawing — every node anchors to a symbol or is an
  explicit `custom` annotation node.

## 8. Open questions

1. Chain steps: are they nodes (composable, xref-able) or stay nested?
   (Spec says nested in v1 — chains are per-node narratives, not graph
   topology.)
2. Gates as edge attributes vs. first-class nodes? (v1: attribute + a
   `gates[]` dictionary; promote if a gate needs its own verdicts.)
3. Cross-target edges (APK → native .so → source file): one map spanning
   targets is the whole point — but per-target write-scope for agents needs
   a map-level scope grant. Default: map inherits the union of its targets'
   scopes, intersected with the mission scope.
4. `map.export` fidelity: do we keep Graphviz-style clusters, or export the
   interactive canvas state (zoom/selection) as well?
