# Agents View Specification (v0.1 · draft)

Status: spec discussion · 2026-07-24
Scope: the Agents view — the workbench-integrated supervision surface for
LLM reversing agents. Reference mockups: `mockups/cockpit.html` (full
cockpit philosophy), `mockups/agents.html` (first workbench panel sketch);
final form lives inside the locked v7 workbench
(`mockups/vocab/v7-helio-dock.html`), reachable via the Agents rail icon
or `⌃5`.

---

## 1. Position in the UI

The Agents view is a **center-surface view** in the workbench, toggled by
the Agents rail icon (badge = active agent count) or `⌃5`. Like the map
view, it swaps the center surface; the rail and ribbons stay put. Unlike
the map (a project artifact), Agents is a *console* — it shows live state,
not a document.

Hierarchy reminder: Workspace → Projects → Targets → engine sessions.
Agents run inside a **project**, are scoped to **targets**, and produce
**proposals** (never silent writes) that the human reviews.

## 2. Concepts

| Concept | Definition |
|---|---|
| **Mission** | One agent run: goal text, model, write-scope (package/namespace or map), token/time budget, optional depth (quick/deep — a reversing mission's brief), state (running/paused/done/queued/blocked). |
| **Agent** | The live worker executing a mission. Multiple agents may run concurrently on different scopes of the same project. |
| **Proposal** | A pending, human-reviewable edit: rename, comment, tag, map node/edge, verdict. Proposals carry confidence + rationale. Nothing an agent writes is final until accepted (auto-accept threshold is a per-mission setting, default: review everything). |
| **Activity** | The event stream (EventBus): navigation, xref walks, renames, flags, blocks — timestamped, per-agent colored. |

## 3. Layout (three columns inside the center surface)

```
┌──────────────────────────────────────────────────────────────┐
│ MISSIONS            │ ACTIVITY STREAM        │ PROPOSALS      │
│ ▣ running card      │ 10:41:03 #7 rename…    │ a→handleInvite │
│   goal, model,      │ 10:41:11 #7 xref walk  │  conf 0.9 ✓ ✗  │
│   budget bar, scope │ 10:41:30 #3 ⚑ hub flag │ comment ghost  │
│ ▣ paused card       │ 10:41:44 #3 BLOCKED ⛔  │  ✓ ✗           │
│ ▣ done card         │  (filter: all/#7/#3/⚠) │ tag: pii       │
│ ＋ new mission      │                        │  ✓ ✗           │
│                     │                        │ Review all ⌘R  │
└──────────────────────────────────────────────────────────────┘
```

- **MISSIONS (left)**: mission cards — goal, status chip (pulsing RUNNING,
  amber PAUSED, green DONE, grey QUEUED), model badge (`deepseek-v4`,
  `local: qwen3-32b` with a LOCAL badge for zero-egress confidentiality),
  budget bar (tokens used/limit), write-scope chip. Controls per card:
  pause/resume, stop, budget edit. `＋ New mission` card at the bottom.
- **ACTIVITY STREAM (center)**: live feed, per-agent color (Okabe-Ito
  discipline: agent hues are #CC79A7 family + one auxiliary, never
  color-only — agent id prefix on every row). Kind labels: rename / xref /
  flag / tag / note / blocked. **BLOCKED rows are vermillion** — write-scope
  violations are the most important events in the stream. Filter chips:
  all / per-agent / ⚠ alerts.
- **PROPOSALS (right)**: the review queue. Each proposal: kind icon, the
  edit (strikethrough-original → proposed), confidence, rationale on hover,
  ✓/✗ (also `⌘A` accept / `⌘X` reject per focused proposal), and a
  **jump affordance** — clicking the proposal's symbol opens the code (or
  map) at that location, so review happens in context. Footer: `Review all
  ⌘R`, pending count feeds the status ribbon's PROPS indicator (project
  proposals) — separate from the per-target dirty dots on the target strip.

## 3.1 The rail in the Agents view — the review inspector

The right rail follows its universal rule: *context for the current
selection*. In the Agents view the selection is a proposal or a mission —
never stale class/Relation data (those hide, same as in the map view):

- **Proposal selected** (default): the **proposal inspector** — everything
  needed to judge the proposal without leaving the queue: kind + confidence
  + full rationale (not truncated), a **code excerpt with the proposed edit
  rendered as an inline ghost diff** (strikethrough-original → proposed, or
  ghosted comment line), supporting evidence (xref count, similar already-
  renamed symbols, the agent's reasoning steps), and ✓/✗ buttons — the same
  `agent.review` op as the queue. If the proposal targets a map, the
  excerpt shows the affected map region instead.
- **Mission selected**: **mission detail** — full goal text, model config,
  budget breakdown (tokens by class), the write-scope definition, and a
  per-class progress checklist (done / in-flight / pending), plus
  pause/resume/stop controls.
- Nothing selected: a compact summary — agents active, proposals pending by
  kind, blocked-events count needing attention.

## 4. Integration contracts

- **Code**: accepting a rename proposal lands it as a normal rename (same
  op as F2) — History dock can undo it. Proposals on visible symbols appear
  as ghost markup in the editor (cockpit-style) when FOLLOW is on.
- **Maps**: map proposals (nodes/edges/verdicts) appear in the same queue
  with a map badge; accepting applies them to the map; the RELATION pane's
  verdict accept/reject is the same queue surfaced in context.
- **Runs**: a mission may attach a frida session; its hits feed the Runs
  view (the project console) tagged with the agent id; the RUNTIME dock tab
  shows the active target's subset of those sessions.
- **Presence**: the status ribbon's agent chip + Follow toggle are the
  minimal surface; the Agents view is the full console. Clicking an agent
  card's position pin (`at sync.c · onMessage L61`) follows that agent.
- **Write-scope enforcement is server-side** (unchanged from old Jeeves):
  the stream's BLOCKED events are the enforcement made visible.

## 5. MCP / op coverage

| Op | Mutating | Purpose |
|---|---|---|
| `agent.missions` | no | list missions (state, model, budget, scope, progress) |
| `task.list` / `task.configure <task> <depth> [--model] [--effort] [--turns] [--budget]` | list / yes | the built-in tasks' settings (today `reverse` × `quick`/`deep`): the model (`provider:model`), effort (`low…max`), turn cap and budget a depth runs on when `agent.spawn {depth}` names no model — the REVERSING AGENT window (Agents view) edits them; persisted in `backend/.workbench/tasks.json` |
| `agent.spawn <goal> [--scope] [--model] [--budget] [--depth quick\|deep] [--target] [--effort] [--turns]` | yes | start a mission; `effort` is the claude CLI's `--effort` / an http provider's `reasoning_effort`, `turns` an explicit turn cap; `depth` is the reversing brief (quick: the subject alone, one pass · deep: its call tree, 3 hops) — it sets the prompt's instructions, the turn caps (http loop 10 / 40 turns, claude CLI 20 / 80) and the default budget (120k / 500k — metered on http providers only); the GUI's "reverse with agent" rows are its producer. `target` pins the mission (default: the active target at spawn): every tool call carries it, and a pinned target closed mid-mission blocks it with that reason |
| `agent.pause` / `agent.resume` / `agent.stop <id>` | yes | lifecycle |
| `agent.setbudget` / `agent.setscope <id>` | yes | mid-flight adjustments (scope shrink only; expansion requires human confirm) |
| `agent.activity [--agent] [--kinds]` | no | the event stream (paged; SSE for live) |
| `agent.proposals [--mission] [--kind]` | no | the review queue |
| `agent.review <proposal-id> <accept|reject>` | yes | settle a proposal (same op the UI buttons call) |
| `agent.follow <id>` / `agent.unfollow` | no | presence following |

Agents themselves use the ordinary workbench ops (decompile, xrefs, rename
as proposal, `map.*`, etc.) — `agent.*` ops are for *supervising* them,
and are also what an orchestrator-LLM would call to run a fleet.

## 6. Interaction details (mockup level)

- `⌃5` or the rail icon toggles the view; the rail badge shows active
  agents; a mission going BLOCKED pulses the badge vermillion until viewed.
- Keyboard: `↑↓` moves proposal selection, `⌘A`/`⌘X` settle, `↵` jumps to
  the proposal's code location, `Esc` returns to the previous center view.
- Everything in this view obeys the locked contracts: cursor rules (arrow
  over read-only, pointer on jumps/toggles, I-beam only in the new-mission
  goal field and budget fields), 90ms motion, no duplicated controls, no
  uninformative titles.

## 7. Non-goals (v1)

- No chat interface (the cockpit's conversational channel is a v2 question;
  supervision ≠ conversation).
- No multi-project mission spanning (a mission lives in one project).
- No scheduling/cron of missions.

## 8. Cloud catalog assignments

The optional cloud controller keeps original class descriptors as stable keys
and reserves exactly eight distinct classes per session. Discovery-time catalog
and focus labels are historical. Immediately before a new spawn, it resolves
the original descriptor against the live target, verifies the same unambiguous
identity and persists the returned current display name with the admission.
Already acknowledged or reconciled missions retain their original goals and are
not spawned again. This refresh does not alter research scope or the run window.

## 9. Open questions

1. Auto-accept thresholds: per-confidence auto-accept (e.g. renames with
   conf ≥ 0.95 auto-land) or always-review? (Mockup: always-review default,
   threshold is a mission setting.)
2. Does the activity stream persist across restarts (RunStore-like) or is
   it session-only?
3. Fleet orchestration: is "agent supervising agents" (an orchestrator
   calling `agent.*`) a v1 scenario or explicitly v2?
