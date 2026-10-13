# Views Specification (v0.1 · draft)

Status: spec discussion · 2026-07-24
Scope: the remaining workbench views beyond code/map/agents — Strings, Runs,
Search, and the dock tabs (Tags, Flows, Bookmarks, History). All live inside
the locked Workbench v1 UI (`WORKBENCH_SPEC.md` §14); reference mockup:
`mockups/vocab/v7-helio-dock.html`.

Shared contracts (apply to every view below):
- Center-surface views (Strings, Runs, Search) swap via the same mechanism
  as map/agents: rail icon + hotkey, all surfaces stay alive, `Esc` returns.
- The right rail always follows its universal rule: *context for the
  current selection* — class bays hide when the view has its own context.
- Cursor contract, 90ms motion, Okabe-Ito signals with redundant coding,
  ellipsis+tooltip on overflow, I-beam only in real inputs.
- Status-ribbon readouts that follow the active target are labeled as such
  ("follows active target") — never presented as project-wide.
- Project proposals (PROPS) and per-target dirty state are separate
  indicators, never merged into one UNSAVED readout.

---

## 1. Strings (`⌃3` / rail icon)

**Purpose**: fastest triage surface — find interesting threads to pull on.

- Full-table center surface over the target's string constants (DEX
  strings; native targets: rodata strings via the engine).
- Columns: value (mono), type chips, xref count, first referrer.
- **Classification** per `WORKBENCH_SPEC.md` §6.4: two layers — lexical
  (patterns, crypto dictionary, base64/hex entropy) and usage (xref role at
  call sites). Lexical-only verdicts render dashed with `?`; every chip
  carries a "why" tooltip (rule + confidence).
- Toolbar: substring/regex filter, multi-select type chips, unreachable-only
  toggle, reachable-from-selection toggle, live counts, export.
- Row select → rail shows string detail: full value, chips with rationale,
  xref list, jump-to-first-use. `↵` jumps.
- Engine caps: `strings` (all engines); `stringXrefs` (JEB, JADX, bridges).

## 2. Runs (`⌃4` / rail icon)

**Purpose**: dynamic analysis — the x64dbg-density principle applied to
Frida: everything about the current hit on one screen.

- **Device bar**: attached device, frida version, spawn/attach, recording
  indicator. Additive law: no device ⇒ the view shows an attach prompt,
  nothing else changes anywhere in the UI.
- **Method breakpoints** (left): enable toggles, hit counts, conditions
  (`cond: arg0 != null`), native BPs (lib targets). Editing a condition is
  one of the few real I-beams. Every breakpoint row carries a **target
  badge** (breakpoints bind to a target's symbols).
- **Hit feed** (center): timestamp, method, decoded args, return, depth,
  run badge, **target badge** on every hit row. Pause/tail/filter. Row
  select → **rail shows the hit detail**: decoded args with types, return
  value, 4-frame backtrace (clickable frames → jump to code).
- **Run history** (right column of the view): run cards (time, hits,
  coverage bar), A/B selection, `⇄ Diff` — methods only in A/B, coverage
  deltas. Runs are project-scoped (RunStore), tagged with the agent id when
  agent-driven. Run naming is unified: display names like `RUN A · run #7`
  are UI sugar over stable run ids (the id is what ops and evidence
  references use).
- Coverage decorates editor glyph margins regardless of view (locked in
  Workbench v1); the Runs view is where you *inspect* it.

## 3. Search (`⌃2` / rail icon; `⌘⇧F` focuses query)

**Purpose**: project-wide full-text across decompiled code (Ghidra 11.3's
decompiled-text search, generalized across targets).

- Query input with regex/case toggles; scope chips: THIS / ALL. THIS is
  dynamically labeled with the active target's name; ALL means all open
  targets of the current project — never across projects (results regroup
  with engine badges); content-type chips: Code / Strings / Comments.
- Results grouped by class (collapsible), hit previews with match
  highlight; stats line (count, classes, ms, index age + rebuild).
- Hit select → rail shows the context excerpt (~6 lines, match highlighted)
  with Open; `F4`/`⇧F4` cycles hits; `↵` opens.
- Index is background-built, cancellable, never blocks typing (the old
  P1-12 lesson). Native targets: search disassembly text.

## 4. Dock tabs (target-scoped, bottom dock)

### Tags
Family filter chips (user-defined families — never hardcoded), table of
tagged items: chips, symbol (mono, was-badges), note, added-by
(human/agent), timestamp. Row select → bottom detail strip / rail: full
signature, editable note, jump, remove-tag. The dock shows **accepted tags
only**; pending agent tag proposals live in the Agents view queue, with a
"review in Agents" footer link when proposals are outstanding.

### Flows
Saved source→sink investigations: name, endpoints, hop count, status
(draft / verified / stale). Flows are **single-target by definition** —
cross-target investigations are Maps. Row select → rail shows the hop chain
(clickable, same jump mechanics as map chain steps). Promote-to-map action
(a saved flow is a seed for a Map — see GRAPHS_SPEC §2); `flow.promote` is
offered in particular when a flow's endpoints resolve to different targets.

### Bookmarks
Line bookmarks across the target: symbol:line, note, timestamp; `⌘D`
toggles in the editor; click jumps. Grouped by class, collapsible.

### History
Rename/comment undo log: original → renamed, symbol, actor (human/agent),
timestamp, per-row undo (restores original; redo on second click). The log
is **per-target**; at the MCP level `history.list [target|all]` spans
targets for cross-target agent-rename correction. `⌘Z` undoes the latest.
Agent renames appear with the agent badge — undoing them is how you correct
an agent retroactively.

---

## 5. View-to-engine capability matrix

| View | JEB | JADX | Ghidra bridge | IDA bridge |
|---|---|---|---|---|
| Strings | full | full (lexical+usage) | full | full |
| Runs (frida) | full | full | n/a (device-side) | n/a |
| Search | full | full | decomp text | decomp text |
| Tags/Flows/Bookmarks/History | native persist | sidecar | sidecar | sidecar |

Missing capability ⇒ the view shows its zero state with an engine note,
never an error.
