# Jeeves New — UI/UX Research & Design Directions

Date: 2026-07-22
Purpose: ground the redesign of Jeeves (Android RE workbench) in (a) an audit of the
current UI and (b) the UI/UX state of the art across IDA Pro, Ghidra, Binary Ninja,
Cutter, x64dbg, ImHex, and AI-assisted RE tools.

Mockups live in `mockups/` — open `mockups/index.html` in a browser.

---

## 1. Where current Jeeves stands

Current Jeeves (`~/projects/jeeves`) is a single-resident-JVM backend (JEB engine) with
a vanilla-JS + Monaco frontend, an MCP server for LLM agents, a Frida sidecar for
dynamic analysis, and a lightly-used VSCode extension.

**What it already does better than the incumbents:**

- **LLM co-presence as a first-class UI concept** — presence pins, "Follow LLM",
  LLM-tinted tabs, live rename streaming. No commercial tool has this; Binary Ninja
  Sidekick is the closest (sidebar chat that edits the database).
- **Headless "Reverse this class" agent runs** with write-scope enforcement — the
  human-supervises-agent workflow is already there in embryonic form.
- **Static + dynamic in one surface** — Frida hit feed, coverage overlays, run A/B
  diffing, next to decompiled code. IDA/Ghidra/BN all treat dynamic analysis as a
  separate tool/mode.
- **Research-annotation model** — tags, bookmarks, source→sink flows, Atlas
  reachability map. Incumbents offer bookmarks/comments; none has flows.

**Where it lags (audit of code + comparison):**

| Gap | Who solved it | How |
|---|---|---|
| No command palette; features undiscoverable | Binary Ninja, IDA 9.2 | Palette as *primary* interface; fuzzy search of actions + symbols + strings; shows hotkeys as teaching aid |
| No project-wide source search (P1-1) | Ghidra 11.3 | Full-text search across all decompiled functions |
| Back-only navigation, no forward (P1-4) | IDA 9.2 "Unified Location History" | One history stack shared across *all* widgets (code, graphs, lists) |
| Fixed 3-pane layout, no docking | Ghidra, Cutter, BN | Dockable/tabbed/splittable panels, saved layouts |
| No method outline (P1-3), keyboard-hostile tree (P1-8) | All incumbents | Symbol tree + outline with full keyboard nav |
| Single-letter hotkeys fragile (Monaco focus-leak bugs) | BN | Small single-key set, context-scoped, rebindable via `keybindings.json` |
| No shortcuts overlay (P1-2) | Ghidra (`F1` anywhere), IDA palette | Discoverability built in |
| Fragile frontend architecture: 3.5k-line `app.js` global scope, cross-script bare globals, CSS-in-JS strings | BN: "the UI is itself a plugin over the same API headless scripts use" | Component model over a single action/op registry |
| Xref/callee trees are DOM lists; Atlas is hand-rolled SVG | IDA 9.2 Dynamic Xref Graph | One interactive graph component (GL/canvas) reused for call/xref/flow graphs |
| No manifest viewer, no diffing UI, no library recognition (backlog) | JEB/IDA | — |

**Backend verdict:** the Java backend (`Commands` op dispatcher, `EventBus`,
`RunStore`, `FridaBridge`, MCP layer, JEB persistence model) is the asset worth
carrying over. The *frontend* is what we redesign from scratch. `JebSession.java`
(4.4k-line monolith) should be split along feature seams during the port, but the
wire protocol (`op + params → JSON`) is sound and already has a parity test.

---

## 2. What the incumbents teach us

**IDA Pro (9.x).** Keyboard-first, modal single-keystroke commands (`N` rename, `X`
xrefs, `G` jump, `Space` graph/text, `F5` decompile, `Esc` back). Power users love the
density; everyone else calls it dated and exclusionary. Its 9.2 release is a design
brief for us: **Unified Location History** across widgets (long-standing daily
friction, finally fixed), **Jump Anywhere** fuzzy dialog, one consolidated **Dynamic
Xref Graph** replacing five legacy graph tools, command palette, faster tabular views.
Lesson: *one graph component, one history stack, one jump dialog*.

**Ghidra.** Mouse-first discoverability, side-by-side Listing + Decompiler always
visible and cursor-synced — repeatedly praised as *the* workflow win over IDA. `F4`
rebinds any key, `F1` contextual help. Criticisms: Swing feels dated, modal
error-dialog storms, UI freezes without cancellable progress, aggressive default
cross-pane syncing ("windows jump at me and frighten me"). Lesson: *synced dual-view
is worth it; never block the UI; let users tame sync*.

**Binary Ninja.** The modern benchmark. Three big ideas: (1) **abstraction-level
switching in one view** (`i` cycles asm→LLIL→MLIL→HLIL, `Space` linear↔graph) — its
most praised interaction; (2) **command palette as the primary interface** — their
blog literally frames it as the fix for discoverability; (3) **the UI is a plugin over
the same public API** as headless scripts — this is why BN's GUI never lags its API
the way Cutter's does. Also: Triage view, deep links (`binaryninja:///...`), JSON
themes, `keybindings.json`. Complaints: lag on large binaries, settings buried in
hamburger menus.

**Cutter/Iaito.** The cautionary tale: GUI bolted onto a CLI core via r2pipe — every
GUI action is an r2 command, new features land CLI-first, GUI perpetually lags, five
predecessor GUIs died. Lesson: *never build the GUI on a different (narrower) API than
scripting uses*. Jeeves already avoids this — its UI, MCP, and VSCode ext all go
through the same `Commands` dispatcher. Keep that law.

**x64dbg.** The dynamic-analysis cockpit: everything about the *current instruction*
on one screen — disasm, registers (changed ones flash red), stack, up to 5 memory
dumps, infobox with resolved operand values. Jeeves' Frida dock should borrow this
density: current hit, decoded args, backtrace, run diff — one glance.

**ImHex.** Proof a small team can ship the most modern-feeling UI in the space:
GPU-rendered, dark-first, HiDPI; pattern DSL; data inspector showing every
interpretation of bytes at cursor. Lesson: *modern rendering + one good DSL beats
endless modal dialogs*.

**AI-assisted RE.** Two integration patterns have converged:
- *Embedded assistant that writes to the database* (BN Sidekick 3.0: chat scoped to
  current function, AI renames/types/comments land as normal undoable edits,
  AI-curated indexes of points-of-interest).
- *MCP: the tool as agent backend* (GhidraMCP, ida-pro-mcp — Jeeves is already here
  and ahead of the pack).
Emerging conventions to adopt: AI edits as first-class **reviewable, undoable
operations**; context-menu one-shots ("explain", "rename everything"); AI-generated
**worklists** as tables; **local-model support** (confidentiality is the #1 blocker
for malware analysts).

**Universal table stakes for 2026:** command palette; browser-style back/forward with
one unified history; dockable panels with saved layouts; searchable settings +
user keybindings file; deep links; dark-first theme; cancellable progress everywhere;
the UI and all automation sharing one op registry.

---

## 3. Design principles for Jeeves New

1. **One action registry.** Every capability is a named op with optional keybinding;
   the palette, menus, toolbar, MCP tools, and hotkeys all render from that single
   registry. (Fixes discoverability *and* the fragile-hotkey class of bugs.)
2. **Unified navigation history** across code, graphs, strings, and docks. Back
   *and* forward. (IDA 9.2's most celebrated fix.)
3. **Selection-synced context panels** (BN's always-live xref panel) — but every sync
   is user-tameable (Ghidra's "jumping windows" complaint).
4. **The agent is a colleague, not a feature.** Agent activity, proposed edits, and
   review queues are permanent UI citizens — extending what Jeeves uniquely already
   has (co-presence, headless runs, write scopes).
5. **Static + dynamic stay fused.** Runtime state decorates code (coverage, hit
   counts) rather than living in a separate "debug mode".
6. **Research artifacts are first-class data**: flows, tags, bookmarks, hypotheses —
   with a board/graph surface, not just docks.
7. **Frontend on a real component model** (no global-scope script soup); UI consumes
   the exact same op API as MCP/scripts.
8. **Two first-class themes — light (default) and dark — always color-blind-safe.**
   Signal colors come from the Okabe–Ito palette in both themes (blue #0072B2 /
   sky #56B4E9 selection, vermillion #D55E00 markup, bluish green #009E73 live,
   orange #E69F00 caution, reddish purple #CC79A7 agent), and color is never the
   only signal (shapes, stripes, dashed underlines, labels). GPU-friendly
   rendering, and performance budgeted for Gmail-scale DEX from day one.

---

## 4. Three design philosophies → three mockups

### A. `workbench.html` — "The Workbench" (evolutionary)
The safest path: keep the familiar IDE shape, fix everything on the gap list.
VSCode/BN-style activity bar, dockable panels, command palette, unified history,
outline panel, project-wide search, agent presence integrated but secondary.
*For users coming from IDA/Ghidra/BN who want Jeeves' unique features without a new
mental model.*

### B. `board.html` — "The Investigation Board" (graph-first, radical)
The Atlas becomes the home screen. The investigation is a living graph of sources,
sinks, flows, and runtime evidence; code is a detail panel you drill into, not the
center. Hypotheses and open questions are pinned to the board. Flows are drawn, not
listed. *For campaign-style research where the question is "how does data move
through this app", not "what does this function do".*

### C. `cockpit.html` — "The Agent Cockpit" (AI-supervision-first)
The primary objects are *missions* (agent runs). The human's job is triage: a review
queue of agent-proposed renames/comments/tags to accept/reject as inline diffs, a
live multi-agent activity stream, budget/write-scope controls, and code that shows
agent proposals as ghost text. *For the workflow Jeeves is uniquely positioned for:
fleets of agents doing the grunt work, human doing the judgment.*

---

## 5. Module disposition (from current Jeeves)

| Module | Verdict |
|---|---|
| `Commands` op dispatcher + wire protocol | **Carry over** — it *is* the action registry (principle 1) |
| `JebSession` | **Carry over, split** along seams (decompile / xrefs / renames / tags-flows / graph) during port |
| `EventBus` + SSE co-presence | **Carry over** — foundation of philosophy C |
| `RunStore` + Frida bridge + agent | **Carry over** — fix lock/perf issues (P1-13) |
| MCP server (72 tools) | **Carry over** — regenerate from the op registry instead of hand-maintained parity |
| Atlas `graph.js` (hand-rolled SVG) | **Redesign** — one proper graph engine (canvas/GL), reused by board + xref graphs |
| Monaco frontend (`app.js` + feature scripts) | **Rewrite** — component model, no cross-script globals; keep Monaco as the editor widget |
| VSCode extension | **Defer** — second-class today; revisit after web UI stabilizes |
| Tag families hardcoded for Gmail | **Fix** — user-defined tag families (P1) |
