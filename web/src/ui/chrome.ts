// chrome.ts — workbench chrome: clock, gauges, theme, toggles, strips, rail,
// layout state, dock tabs, drag-to-dock, updateCenter, jumpToCode, projmenu.
import { $, $all, S, hook, modalUp, pushEscape, ESC, escHtml as escText, tryOpT } from './hub.js';
import { tryOp } from '../api.js';
import type { CenterView, LayoutUi, MapSummary, Project, ProjectMember, WorkspaceState } from '../types.js';

export const ui: LayoutUi = { side: true, dock: true, sideW: 264, rightW: 264, dockH: 190 };
let main: HTMLElement;
let crumbEl: HTMLElement, codeCrumb: string;

/* bytes → '1.2 GB' · '340.5 MB' · '12 KB' · '512 B' (segW): the launcher's
   humanSize thresholds (1024-based) with the unit spelled out — the project
   editor's size column and the orphan note read at a glance, and the spec's
   "(X GB)" copy is this function's output. An absent size prints as '': the
   contract carries sizeBytes per member, but a null must never print 'NaN B'. */
function fmtBytes(n: number | null | undefined){
  if (n == null || !isFinite(n)) return '';
  if (n >= 1 << 30) return (n / (1 << 30)).toFixed(1) + ' GB';
  if (n >= 1 << 20) return (n / (1 << 20)).toFixed(1) + ' MB';
  if (n >= 1024) return Math.round(n / 1024) + ' KB';
  return n + ' B';
}

/* ==================================================================
   CENTER SURFACE — ONE field (S.center), ONE writer (showCenter).
   The three flags this replaced (S.paneView, S.agentsOpen, #mapchip.on)
   were kept mutually exclusive by hand at nine call sites (app review
   P0.2); every surface question is now a lookup on S.center.
================================================================== */
/* center view → the wrap element that IS the surface */
const CENTER_WRAP: Record<CenterView, string> = { code:'codewrap', map:'mapwrap', agents:'agentswrap',
  strings:'stringswrap', runs:'runswrap', search:'searchwrap', device:'devicewrap' };
/* center view → the element that owns the keyboard while it shows (agents:
   the proposals column; map: the canvas; pane views: their wrap) */
const CENTER_FOCUS: Record<CenterView, string> = { code:'code', map:'mapview', agents:'agentswrap',
  strings:'stringswrap', runs:'runswrap', search:'sq', device:'devicewrap' };
/* center view → lit rail button. The map has no rail button (it is a chip),
   so Explorer stays lit under it — unchanged from the flag era */
const CENTER_RAIL: Record<CenterView, string> = { code:'rail-explorer', map:'rail-explorer', agents:'rail-agents',
  strings:'rail-strings', runs:'rail-runs', search:'rail-search', device:'rail-device' };
/* the pane views: a per-view inspector in the rail, the view name in the crumb */
const PANE_INSP: Partial<Record<CenterView, string>> = { strings:'str-insp', runs:'run-insp', search:'srch-insp', device:'dev-insp' };

/* the ONE writer of S.center. Swaps the surface, moves focus onto it
   (opts.focus === false defers that — jumpToCode re-checks modalUp after its
   awaits) and fires the view's opened hook. Always runs, even for the view
   already showing: the map flows call it after loading a DIFFERENT map under
   an open canvas. Rail/⌃N go through selectCenter, the idempotent selector. */
export function showCenter(v: string, opts?: { focus?: boolean }): void {
  let view: CenterView = (CENTER_WRAP as Record<string, string>)[v] ? v as CenterView : 'code';
  /* no chip = no open map (renderMapChip is the activation's readout): an
     empty canvas is not a surface */
  if (view === 'map' && !document.getElementById('mapchip')) view = 'code';
  S.center = view;
  updateCenter();
  if (!opts || opts.focus !== false) focusCenter();
  if (view === 'map') hook('focusMap');
  if (view === 'agents') hook('agentsOpened');
  if (view === 'device') hook('deviceOpened');
  if (PANE_INSP[view]) hook('viewOpened', view);
}

/* idempotent selector (P0.3): the rail, ⌃1–⌃6 and the G dropdown's VIEWS.
   Selecting the view already showing only re-focuses it — no reload, no
   toggle-away (⌃3 twice used to land on whatever the map chip held).
   ⌃1 (Explorer) is the way back to code; ⌘J/⌘B keep toggle semantics. */
export function selectCenter(v: CenterView): void {
  if (S.center === v){ focusCenter(); return; }
  showCenter(v);
}

/* ==================================================================
   UPDATE CENTER — the surface swapper (code / map / agents / views)
================================================================== */
export function updateCenter(): void {
  const c = S.center;
  const mapOn = c === 'map', agOn = c === 'agents';
  const pane = PANE_INSP[c] ? c : '';
  Object.keys(CENTER_WRAP).forEach(function(v){
    if (v !== 'code') document.getElementById((CENTER_WRAP as Record<string, string>)[v])!.hidden = (v !== c);
  });
  const codeHidden = c !== 'code';
  document.body.classList.toggle('chrome-off', codeHidden);
  S.codewrap!.style.display = codeHidden ? 'none' : '';
  /* #mapchip.on is a RENDER of S.center — the chip carries no state */
  const mchip = document.getElementById('mapchip');
  if (mchip) mchip.classList.toggle('on', mapOn);

  // breadcrumb: project root for project-scope surfaces, target root otherwise
  const proj = escText(document.getElementById('projname')!.textContent);
  const tname = escText(activeTargetName() || '—');
  if (agOn){
    crumbEl.innerHTML = '<span class="nav">' + proj + '</span><span class="sep">›</span><span class="cur">agents</span>';
  } else if (mapOn){
    crumbEl.innerHTML = '<span class="nav">' + proj + '</span><span class="sep">›</span><span class="cur">' + escText(S.mapId || 'map') + '</span>';
  } else if (c === 'search'){
    crumbEl.innerHTML = S.srchScope === 'all'
      ? '<span class="nav">' + proj + '</span><span class="sep">›</span><span class="cur">search · all targets</span>'
      : '<span class="nav">' + tname + '</span><span class="sep">›</span><span class="cur">search</span>';
  } else if (pane){
    const projScoped = (c === 'runs' || c === 'device');
    crumbEl.innerHTML = '<span class="nav">' + (projScoped ? proj : tname) + '</span><span class="sep">›</span><span class="cur">' + c + '</span>';
  } else {
    crumbEl.innerHTML = codeCrumb;
  }

  // rail swap: map-only rail for the map, review inspector for agents,
  // per-view inspectors for strings/runs/search/device; class bays restored for code
  document.getElementById('class-info')!.style.display = codeHidden ? 'none' : '';
  document.getElementById('class-rel')!.style.display  = codeHidden ? 'none' : '';
  document.getElementById('mp-sec')!.style.display     = codeHidden ? 'none' : '';   // the retained-metadata section belongs to the code view too
  document.getElementById('sel-scroll')!.style.display = (agOn || pane) ? 'none' : '';
  document.getElementById('ag-insp')!.hidden = !agOn;
  Object.keys(PANE_INSP).forEach(function(v){ document.getElementById((PANE_INSP as Record<string, string>)[v])!.hidden = (v !== c); });
  document.getElementById('map-rel')!.hidden      = !(mapOn && S.mapSel);
  document.getElementById('map-overview')!.hidden = !(mapOn && !S.mapSel);
  if (mapOn) hook('renderOverview');
  /* close the TYPES menu whenever the map surface is NOT the one showing — the
     old condition did the exact opposite, leaving typesMenu.hidden=false with
     the menu invisible, and its ESC.menu handler then ate the code view's
     first Escape (keyboard exits: ⌃1/⌃5/G viewGo — opus map-r5 F4) */
  if (!mapOn) hook('closeTypes');
  /* no first-show fitMap here: focusMap fits on map-identity change (it needs
     repacked real-height cards — a fit computed against the hidden map's
     zero-height cards was garbage, opus map-r2 #2) */

  // rail icon active state mirrors the open surface
  S.rbtns.forEach(function(b){ b.classList.toggle('on', b.id === CENTER_RAIL[c]); });

  // dynamic THIS chip label + dock scope label follow the viewed target
  document.getElementById('ss-this')!.textContent = 'THIS: ' + (activeTargetName() || '—');
  updateDockScope();
  applyLayout();
  if (!codeHidden && S.caretReady) hook('measureRender');
}

/* ==================================================================
   SURFACE FOCUS + PANE-VIEW ESCAPE
   One rule for "which surface owns the keyboard", shared by the focus
   helper and the Esc owners. Every exit path used to focus #code blindly:
   with the map (or a pane view) covering the center, #code is display:none,
   focus fell to <body>, and the visible surface went keyboard-dead (F6).
================================================================== */
/* is the MAP surface the one showing? (map.js keys its Esc/TYPES focus on it) */
export function mapSurfaceOn(): boolean { return S.center === 'map'; }

/* ONE transient notice for a gesture whose result lands on another surface
   (a mission spawned from the code menu runs in AGENTS): one line above the
   status ribbon, 6 s, a click runs the follow-up (or just closes it). One at
   a time — a newer notice replaces the older. Errors take the .err voice.
   Not an openstatus card (openstatus.ts): those are one-per-target.open
   trackers that poll target.info; this is a receipt with no lifecycle. */
let noticeTimer = 0;
export function notice(text: string, opts?: { err?: boolean; onClick?: () => void; ms?: number }): void {
  let el = document.getElementById('notice');
  if (!el){
    el = document.createElement('div');
    el.id = 'notice';
    el.setAttribute('role', 'status');
    document.body.appendChild(el);
  }
  const box = el;
  box.textContent = text;
  box.dataset.tip = text;                  // the shell's tooltip, like every other ellipsised line
  box.classList.toggle('err', !!(opts && opts.err));
  box.hidden = false;
  box.onclick = function(){ box.hidden = true; if (opts && opts.onClick) opts.onClick(); };
  clearTimeout(noticeTimer);
  noticeTimer = setTimeout(function(){ box.hidden = true; }, (opts && opts.ms) || 6000);
}

/* a dock tab whose op FAILED says so — painting the empty-state text ("no
   tags yet — T tags the caret symbol") over a backend error offered
   instructions that could not work (UI audit 2026-09-09) */
export function dockUnavailable(hostId: string, what: string, message: string): void {
  const host = document.getElementById(hostId);
  if (!host) return;
  host.innerHTML = '<div class="drow"><span class="dnote err"></span></div>';
  host.querySelector('.dnote')!.textContent = what + ' unavailable — ' + message;
}

/* focus follows the VISIBLE surface — call this instead of focusing #code */
export function focusCenter(): void {
  const el = document.getElementById(CENTER_FOCUS[S.center] || 'code');
  if (el) el.focus({ preventScroll: true });
}

/* A pane view owns Escape while it owns the center. Keyed on S.center, NOT on
   focus: ⌃2/⌃3/⌃4/⌃6 and the rail buttons never move focus into the wrap, so the
   old per-wrap keydown listeners never fired (F5). A transient inline form inside
   a view keeps its own Escape by pushing a higher-priority handler (runs ＋BP). */
export function pushPaneEscape(view: CenterView): void {
  pushEscape(function(){
    if (S.center !== view) return false;
    showCenter('code');
  }, ESC.view);
}

/* the dock scope readout is the ONLY covRun display surface (the COV chip is
   gone) — single source of truth for the string, shared with covCycle. */
export function updateDockScope(): void {
  const ds = document.getElementById('dock-scope');
  if (ds) ds.textContent = S.currentTid
    ? (activeTargetName() || '—') + ' · ' + (S.covRun ? 'run ' + S.covRun : 'no run selected')
    : 'no target open';                                        // the suffix "· this target" was a constant, false without one (UI audit 2026-09-09)
}

/* ==================================================================
   TARGET IDENTITY — from the last workspace.state snapshot (S.state), never
   from chip text (app review P0.1: the chip-text read fell back to the literal
   'gmail.apk', which leaked into Search, the dock scope and the crumb). The
   viewed target is S.currentTid (app.js); with no viewed cursor yet (boot,
   project switch) the backend's active flag stands in — the same rule
   renderChips paints .on with, so the readouts and the strip agree.
================================================================== */
export function activeTarget(): { id: string; name: string } | null {
  const ts = (S.state && S.state.targets) || [];
  const t = S.currentTid ? ts.find(function(x){ return x.id === S.currentTid; })
                         : ts.find(function(x){ return x.active; });
  return t ? { id: t.id, name: t.name } : null;
}
/* name shim for the readouts — '' when nothing is viewed (callers print '—') */
export function activeTargetName(): string {
  const t = activeTarget();
  return t ? t.name : '';
}

/* ==================================================================
   MAP CHIP — rendered from workspace.state.activeMap (map lifecycle §1/§4).
   Three different things used to wear one name: surface visibility (.on),
   activation (state.activeMap) and existence (the sidecar file). The chip is
   the ACTIVATION's readout: no activeMap → no chip, so a closed map cannot
   leave a ghost behind. Body click toggles the surface; the hover-× closes
   the map (map.close) — the same .tchip .tx convention target chips use.
================================================================== */
const MAPCHIP_SVG = '<svg viewBox="0 0 16 16" width="11" height="11" fill="none" ' +
  'stroke="currentColor" stroke-width="1.2"><circle cx="3.5" cy="8" r="1.7"/>' +
  '<circle cx="9" cy="3.5" r="1.7"/><circle cx="12.5" cy="11" r="1.7"/>' +
  '<path d="M4.9 6.8 7.6 4.7M10 5.1l1.7 4.2"/></svg>';

/* pass workspace.state.activeMap (or the id loadMap just rendered); null/'' removes the chip */
export function renderMapChip(id: string | null | undefined): void {
  const strip = document.querySelector('.targets')!;
  let chip = document.getElementById('mapchip');
  const mid = id ? String(id) : null;
  if (!mid){
    if (chip) chip.remove();                 // no activeMap → no chip (and no dead surface)
    if (S.center === 'map') showCenter('code');   // a canvas with no map is not a surface
    return;
  }
  if (!chip){
    chip = document.createElement('div');
    chip.id = 'mapchip';
    chip.className = 'tchip mchip-proj';
    chip.setAttribute('data-pos', 'below');
    strip.insertBefore(chip, strip.firstChild);   // left of the rack divider
    wireMapChip(chip);
  }
  chip.setAttribute('data-tip', mid + ' · show/hide map');
  chip.setAttribute('data-mid', mid);
  chip.innerHTML = MAPCHIP_SVG + '<span class="lbl-t">' + escText(mid) + '</span>' +
    '<span class="tx" role="button" aria-label="Close map" title="Close map">×</span>';
  chip.classList.toggle('on', S.center === 'map');   // .on is a RENDER of S.center
}

function wireMapChip(chip: HTMLElement){
  /* the strip's delegated handler early-returns on #mapchip, so the chip owns
     its own click. Delegated from the chip (renderMapChip rewrites its
     children on every repaint): × = close the map, body = show/hide it. */
  chip.addEventListener('click', function(e){
    if ((e.target as HTMLElement).closest('.tx')){
      e.stopPropagation();                       // never also toggle the surface
      closeActiveMap();
      return;
    }
    showCenter(S.center === 'map' ? 'code' : 'map');   // body click = show/hide (the chip's documented toggle)
  });
}

/* map.close on the CHIP's id — nothing is lost (every map mutation already
   persists atomically), and the map stays in map.list and the projmenu. */
export async function closeActiveMap(): Promise<void> {
  const chip = document.getElementById('mapchip');
  const mid = chip ? chip.getAttribute('data-mid') : null;
  const r = await tryOp('map.close', mid ? { id: mid } : {});
  if (r.error){ console.warn('map.close', r.error.message); return; }
  /* reloadMap re-resolves from workspace.state — now null — so it clears the
     canvas AND drops the chip (renderMapChip(null) returns the surface to
     code when the canvas was showing); no map.list[0] resurrection behind it */
  if (S.hooks.reloadMap) await S.hooks.reloadMap(null);
  if (S.center === 'map') showCenter('code');
  else updateCenter();                           // chip gone: rail/overview flags recompute
}

/* code breadcrumb follows the displayed class (target › package › class). */
export function setCodeCrumb(html: string): void {
  codeCrumb = html;
  if (S.center === 'code') crumbEl.innerHTML = html;
}

/* ==================================================================
   JUMP TO CODE — flash + surface return (cross-target aware)
   QA M3: a cross-target jump runs the SAME full reload path as a chip
   click (S.hooks.targetSwitched: backend switch, tree, breadcrumb,
   restore/default display, relation) — never a partial repaint that
   leaves the surface on the old target. Optional symbol lands on the
   definition (gotoDef resolves + class fallback).
================================================================== */
export async function jumpToCode(target?: string | null, symbol?: string | null): Promise<boolean> {
  /* a programmatic jump owns the code view until it lands: followJump gates on
     this (its codeUp test would open mid-chain — paneView clears before the
     gotoDef await — and a presence event could land its own jump on top of the
     user's explicit destination, review R13). jumpDone lets the follow drain
     its stash afterwards, serialized instead of interleaved. */
  S.jumpInFlight = (S.jumpInFlight || 0) + 1;
  try {
    return await jumpToCode0(target, symbol);
  } finally {
    S.jumpInFlight--;
    if (!S.jumpInFlight) hook('jumpDone');
  }
}

async function jumpToCode0(target: string | null | undefined, symbol: string | null | undefined){
  // only a jump that actually LEAVES the map surface earns the Esc-return.
  // Computed at entry but APPLIED after the loads: loadSymbol clears the flag
  // on entry (any code-internal navigation un-earns it), so setting it up here
  // would be wiped by our own gotoDef below.
  const fromMap = S.center === 'map';
  if (target){
    /* by target ID only (P0.1): the old name/substring re-resolution matched
       'a.apk' against 'beta.apk' and any chip whose tip text held the string.
       Map nodes, runs, search hits and the launcher all carry ids (t5…). */
    const tid = String(target);
    const chip = $all('.targets .tchip[data-tid]').find(function(c){ return c.getAttribute('data-tid') === tid; });
    /* a target with no chip must not fall through to a silent current-target
       load (opus map-r4 #5). The false is surfaced by callers that have a live
       surface (map.js #mr-kind; runs.js/search.js status notes — map-r7 F4) */
    if (!chip){ console.warn('jumpToCode: no chip for target', tid); return false; }
    /* the switch decision is about FRONTEND sync state (S.currentTid), not the
       chip's .on class: refreshState() paints .on from the backend's active
       target without running targetSwitched (review) */
    if (S.currentTid !== tid){
      if (!S.hooks.targetSwitched){ console.warn('jumpToCode: no target switcher registered (backend down?)'); return false; }
      await S.hooks.targetSwitched(chip);
      chip.classList.add('chipflash');
      setTimeout(function(){ chip.classList.remove('chipflash'); }, 800);
    }
  }
  showCenter('code', { focus: false });          // focus is re-checked below, after the awaits
  if (symbol) await hook('gotoDef', symbol);
  S.cameFromMap = fromMap;
  S.codewrap!.classList.add('pane-flash');
  setTimeout(function(){ S.codewrap!.classList.remove('pane-flash'); }, 650);
  /* the awaits above are a TOCTOU window: a modal/overlay opened MID-JUMP owns
     the keyboard now — re-check before focusing code, or a follow-driven jump
     hijacks the modal's keystrokes (review F5). ONE shared predicate (hub.modalUp). */
  if (!modalUp()) document.getElementById('code')!.focus({ preventScroll:true });
  return true;
}
S.jumpToCode = jumpToCode;

/* ==================================================================
   LAYOUT STATE — collapse + live reflow
   Splitter-dragged sizes live in ui.* (in-session; layouts.js snapshots
   them). Collapse wins: a hidden panel renders 0 wide and its splitter
   hotzone goes away (chrome-off model), but the size survives reopen.
================================================================== */
const SIDE_MIN = 160, SIDE_MAX = 480, RIGHT_MIN = 200, RIGHT_MAX = 520, DOCK_MIN = 120;
function clamp(v: number, lo: number, hi: number){ return Math.max(lo, Math.min(hi, v)); }
function clampDockH(h: number){ return clamp(h, DOCK_MIN, Math.round(window.innerHeight / 2)); }

function colWidths(){
  const sideW  = (ui.side && !document.body.classList.contains('chrome-off'))
    ? clamp(ui.sideW, SIDE_MIN, SIDE_MAX) : 0;
  const rightW = document.getElementById('col-right')!.children.length > 0
    ? clamp(ui.rightW, RIGHT_MIN, RIGHT_MAX) : 0;
  const w = { sideW, rightW };
  // viewport-relative budget: the two panels may never crush the center
  // below ~360px (a usable code pane needs most of that)
  const budget = window.innerWidth - 44 - 8 - 360;
  if (w.sideW + w.rightW > budget){
    const over = w.sideW + w.rightW - budget;
    const trimR = Math.min(Math.max(0, w.rightW - RIGHT_MIN), over);
    w.rightW -= trimR;
    if (over - trimR > 0) w.sideW = Math.max(SIDE_MIN, w.sideW - (over - trimR));
  }
  return w;
}

/* collapsed panel → 0px splitter track too: no dead 4px gutter */
function writeCols(w: { sideW: number; rightW: number }){
  main.style.gridTemplateColumns = '44px ' + w.sideW + 'px ' + (w.sideW ? 4 : 0) +
    'px minmax(0, 1fr) ' + (w.rightW ? 4 : 0) + 'px ' + w.rightW + 'px';
}

export function applyLayout(): void {
  const w = colWidths();
  writeCols(w);
  document.getElementById('col-side')!.style.display  = ui.side ? '' : 'none';
  document.getElementById('col-right')!.style.display = w.rightW ? '' : 'none';
  $('#split-side')!.style.display  = w.sideW ? '' : 'none';
  $('#split-right')!.style.display = w.rightW ? '' : 'none';
  const dock = document.getElementById('dock')!;
  dock.style.height  = clampDockH(ui.dockH) + 'px';
  dock.style.display = ui.dock ? '' : 'none';
  document.getElementById('dock-restore')!.style.display = ui.dock ? 'none' : 'flex';
  syncTabs();
  if (S.caretReady) hook('measureRender');
}

/* ==================================================================
   PANEL SPLITTERS — side column, right rail, dock edge. psplit pattern
   (caret.js): pointerdown + capture, live inline write per move, then
   a full applyLayout on release — same geometry, so no jump.
================================================================== */
function initSplitters(){
  function wire(el: HTMLElement, resize: (ev: PointerEvent) => void){
    el.addEventListener('pointerdown', function(e){
      e.preventDefault();
      el.classList.add('on');
      document.body.classList.add('splitting');
      el.setPointerCapture(e.pointerId);
      const move = function(ev: PointerEvent){
        resize(ev);
        if (S.caretReady) hook('measureRender');
      };
      const up = function(){
        el.classList.remove('on');
        document.body.classList.remove('splitting');
        el.removeEventListener('pointermove', move);
        el.removeEventListener('pointerup', up);
        applyLayout();
        prefsChanged();                                    // a dragged width/height persists (decision 17)
      };
      el.addEventListener('pointermove', move);
      el.addEventListener('pointerup', up);
    });
  }

  wire($('#split-side')!, function(ev){
    const left = document.getElementById('col-side')!.getBoundingClientRect().left;
    ui.sideW = clamp(Math.round(ev.clientX - left), SIDE_MIN, SIDE_MAX);
    writeCols(colWidths());
  });
  wire($('#split-right')!, function(ev){
    const right = main.getBoundingClientRect().right;
    ui.rightW = clamp(Math.round(right - ev.clientX), RIGHT_MIN, RIGHT_MAX);
    writeCols(colWidths());
  });
  wire($('#split-dock')!, function(ev){
    const dock = document.getElementById('dock')!;
    ui.dockH = clampDockH(Math.round(dock.getBoundingClientRect().bottom - ev.clientY));
    dock.style.height = ui.dockH + 'px';
  });
}

/* ---- the operator's chrome, server-side (audit decision 17): theme, side/dock, splitters,
   dock height — loaded at boot, saved (debounced) after every change, reset from ⌘K. Named
   layouts stay explicit; this is the one "current" chrome that follows the workspace. */
const UI_DEFAULTS: LayoutUi = { side: true, dock: true, sideW: 264, rightW: 264, dockH: 190 };
let prefsTimer = 0;
function currentPrefs(){
  return { theme: document.body.classList.contains('dark') ? 'dark' : 'light', side: ui.side, dock: ui.dock, sideW: ui.sideW, rightW: ui.rightW, dockH: ui.dockH };
}
export function prefsChanged(): void {
  if (prefsTimer) clearTimeout(prefsTimer);
  prefsTimer = window.setTimeout(function(){
    prefsTimer = 0;
    tryOpT('ui.prefs.set', { prefs: currentPrefs() }).then(function(r){ if (r.error) console.warn('ui.prefs.set', r.error.message); });
  }, 600);
}
export function setTheme(dark: boolean): void {
  document.body.classList.toggle('dark', dark);
  const themesw = document.getElementById('themesw');
  if (themesw){
    themesw.classList.toggle('dark', dark);
    const ops = themesw.querySelectorAll('.op');
    if (ops.length >= 2){ ops[0].classList.toggle('on', !dark); ops[1].classList.toggle('on', dark); }
  }
  if (S.caretReady) hook('measureRender');
}
export async function loadUiPrefs(): Promise<void> {
  const r = await tryOpT('ui.prefs', {});
  if (r.error) return;                                    // no backend yet: the defaults stand
  const p = (r.data || {}) as { theme?: string; side?: boolean; dock?: boolean; sideW?: number; rightW?: number; dockH?: number };
  if (typeof p.side === 'boolean') ui.side = p.side;
  if (typeof p.dock === 'boolean') ui.dock = p.dock;
  if (typeof p.sideW === 'number') ui.sideW = p.sideW;
  if (typeof p.rightW === 'number') ui.rightW = p.rightW;
  if (typeof p.dockH === 'number') ui.dockH = p.dockH;
  if (p.theme) setTheme(p.theme === 'dark');
  applyLayout();
}
export function resetUiPrefs(): void {
  ui.side = UI_DEFAULTS.side; ui.dock = UI_DEFAULTS.dock; ui.sideW = UI_DEFAULTS.sideW; ui.rightW = UI_DEFAULTS.rightW; ui.dockH = UI_DEFAULTS.dockH;
  setTheme(false);
  applyLayout();
  tryOpT('ui.prefs.reset', {}).then(function(r){ notice(r.error ? 'reset not saved — ' + r.error.message : 'layout and theme reset to the defaults', { err: !!r.error }); });
}

function toggleDock(){ ui.dock = !ui.dock; applyLayout(); prefsChanged(); }
/* a panel that wants to be seen: un-collapse the dock first (a click that activated a panel
   inside a display:none dock was a dead click — b2-r1 M6) */
export function showDock(): void { if (!ui.dock){ ui.dock = true; applyLayout(); } }
function toggleSide(){ ui.side = !ui.side; applyLayout(); prefsChanged(); }

/* P0-4: the viewport budget (colWidths) and the dock clamp (clampDockH) were
   re-evaluated only by the actions that happen to call applyLayout — view
   swaps, splitter release, ⌘J/⌘B — never by a window resize, so a shrink kept
   the dragged panel widths and crushed the center under its 360px floor until
   an unrelated action fired. One layout per frame: resize fires per pixel and
   applyLayout rebuilds the dock tabs and re-measures the code view. The clamps
   read ui.* and never write it, so a shrink trims what SHOWS and a grow-back
   restores the dragged size — a reflow, not a reset of what the user dragged
   (layouts.js relies on the same read-only clamping for persisted values). */
let layoutRaf = 0;
function scheduleLayout(){
  if (layoutRaf) return;
  layoutRaf = requestAnimationFrame(function(){ layoutRaf = 0; applyLayout(); });
}

/* ==================================================================
   PANEL TABS — generated from whatever panels live in each tabdock
================================================================== */
export function activatePanel(id: string | null): void {
  $all('.tabdock').forEach(function(td){
    $all(':scope > .dockbody > .panel', td).forEach(function(p){
      p.classList.toggle('active', p.getAttribute('data-panel') === id);
    });
  });
  syncTabs();
  /* a panel becoming visible may need to refresh its content — activatePanel toggles .active but
     never renders, so a tab-click that reveals a lazily-populated panel (OUTLINE) would show a
     stale class (b2 trio review M1). Owners register panelShown to re-render. */
  if (id) hook('panelShown', id);
}

export function syncTabs(): void {
  [['dock-panels','dock-tabs'],['cdock-panels','cdock-tabs']].forEach(function(pair){
    const pEl = document.getElementById(pair[0]);
    const tEl = document.getElementById(pair[1]);
    if (!pEl || !tEl) return;
    tEl.innerHTML = '';
    const panels = $all(':scope > .panel', pEl);
    if (panels.length && !pEl.querySelector(':scope > .panel.active')){
      panels[0].classList.add('active');
    }
    panels.forEach(function(p){
      const b = document.createElement('div');
      b.className = 'dtab p-drag' + (p.classList.contains('active') ? ' on' : '');
      b.setAttribute('data-panel', p.getAttribute('data-panel')!);
      b.setAttribute('data-tip', 'Drag to re-dock · click to focus');
      b.textContent = p.getAttribute('data-title');
      /* a panel that knows its size advertises it via data-count; the tab shows it, like every
         other tab strip in the app (persona LOW: dock tabs carried no counts) */
      const dc = p.getAttribute('data-count');
      if (dc != null && dc !== '') {
        const n = document.createElement('span');
        n.className = 'dtab-n';
        n.textContent = Number(dc).toLocaleString('en-US');
        b.appendChild(document.createTextNode(' '));
        b.appendChild(n);
      }
      tEl.appendChild(b);
    });
  });
  const cd = document.getElementById('cdock')!;
  cd.hidden = !document.querySelector('#cdock-panels > .panel');
}

/* ==================================================================
   DRAG-TO-DOCK — pointer-driven, real DOM re-parenting, Esc cancels
================================================================== */
interface DragRect { left: number; top: number; width: number; height: number }
interface DragState {
  panel: HTMLElement | null; active: boolean; ghost: HTMLElement | null; zone: string | null;
  idx: number; sx: number; sy: number; x: number; y: number; handle: HTMLElement | null;
}
const dz: DragState = { panel:null, active:false, ghost:null, zone:null, idx:-1, sx:0, sy:0, x:0, y:0, handle:null };
let zoneRects: { name: string; rect: DragRect }[] = [];

function initDragDock(){
  document.addEventListener('pointerdown', function(e){
    if ((e.target as HTMLElement).closest('.p-dockback')) return;
    const handle = (e.target as HTMLElement).closest<HTMLElement>('.p-drag');
    if (!handle) return;
    const pid = handle.getAttribute('data-panel');
    const panel = pid ? document.querySelector<HTMLElement>('.panel[data-panel="' + pid + '"]') : handle.closest<HTMLElement>('.panel');
    if (!panel) return;
    dz.panel = panel; dz.handle = handle;
    dz.sx = e.clientX; dz.sy = e.clientY;
    dz.active = false; dz.zone = null; dz.idx = -1;
    e.preventDefault();
  });

  document.addEventListener('pointermove', function(e){
    if (!dz.panel) return;
    if (!dz.active){
      if (Math.abs(e.clientX - dz.sx) + Math.abs(e.clientY - dz.sy) < 6) return;
      startDrag();
    }
    dz.x = e.clientX; dz.y = e.clientY;
    dz.ghost!.style.left = e.clientX + 'px';
    dz.ghost!.style.top  = e.clientY + 'px';
    dz.zone = null; dz.idx = -1;
    clearMarker();
    $all('.zone').forEach(function(z){ z.classList.remove('hot'); });
    for (let i = 0; i < zoneRects.length; i++){
      const r = zoneRects[i].rect;
      if (e.clientX >= r.left && e.clientX <= r.left + r.width &&
          e.clientY >= r.top  && e.clientY <= r.top + r.height){
        dz.zone = zoneRects[i].name;
        document.querySelector('.zone[data-zone="' + dz.zone + '"]')!.classList.add('hot');
        if (dz.zone === 'dock' || dz.zone === 'cdock') dz.idx = tabInsertIndex(e.clientX, dz.zone);
        break;
      }
    }
  });

  document.addEventListener('pointerup', function(){
    if (!dz.panel) return;
    if (dz.active){
      if (dz.zone) movePanel(dz.panel, dz.zone, dz.idx, dz.x, dz.y);
      endDrag();
    } else if (dz.handle!.classList.contains('dtab')){
      activatePanel(dz.handle!.getAttribute('data-panel'));
    }
    dz.panel = null;
  });

  // floating panel: re-dock button
  document.addEventListener('click', function(e){
    const b = (e.target as HTMLElement).closest('.p-dockback');
    if (!b) return;
    const panel = b.closest<HTMLElement>('.panel');
    if (panel) movePanel(panel, 'dock', -1, 0, 0);
  });

  /* Esc cancels an active drag — the drag engine owns Esc while dz.active */
  pushEscape(function(){
    if (!dz.active) return false;
    endDrag();
    dz.panel = null;
  }, ESC.drag);
}

function startDrag(){
  dz.active = true;
  S.dzActive = true;              /* palette.js gates '?' on it (was never set) */
  document.body.classList.add('dragging');
  const g = document.createElement('div');
  g.className = 'dragghost';   /* not 'ghost': the proposal excerpt's .cx.ghost rows inherited this rule's position:fixed and floated to the top-right */
  g.textContent = '⠿ ' + dz.panel!.getAttribute('data-title');
  document.body.appendChild(g);
  dz.ghost = g;
  positionZones();
  document.getElementById('zones')!.classList.add('show');
}

function endDrag(){
  /* dz.active must die with the drag: it used to survive teardown (only the next
     pointerdown reset it), which now matters — the Esc owner is keyed on it, so a
     zombie 'active' drag would swallow every later Escape. */
  dz.active = false;
  S.dzActive = false;
  if (dz.ghost) dz.ghost.remove();
  dz.ghost = null;
  clearMarker();
  document.getElementById('zones')!.classList.remove('show');
  document.body.classList.remove('dragging');
}

function positionZones(){
  zoneRects = [];
  function setZone(name: string, rect: DragRect){
    const z = document.querySelector<HTMLElement>('.zone[data-zone="' + name + '"]')!;
    z.style.left = rect.left + 'px'; z.style.top = rect.top + 'px';
    z.style.width = rect.width + 'px'; z.style.height = rect.height + 'px';
    zoneRects.push({ name:name, rect:rect });
  }
  let r: DragRect;
  r = document.getElementById('col-side')!.getBoundingClientRect();
  if (r.width < 10) r = { left:44, top:130, width:220, height:window.innerHeight - 420 };
  setZone('left', r);
  r = document.getElementById('col-right')!.getBoundingClientRect();
  if (r.width < 10) r = { left:window.innerWidth - 264, top:130, width:220, height:window.innerHeight - 420 };
  setZone('right', r);
  r = document.getElementById('dock')!.getBoundingClientRect();
  if (r.height < 10) r = { left:320, top:window.innerHeight - 240, width:window.innerWidth - 640, height:170 };
  setZone('dock', r);
  r = document.querySelector('.editor')!.getBoundingClientRect();
  setZone('cdock', { left:r.left + 40, top:(r as DOMRect).bottom - 200, width:r.width - 80, height:150 });
  setZone('float', { left:window.innerWidth/2 - 120, top:window.innerHeight/2 - 70, width:240, height:120 });
}

function tabInsertIndex(x: number, zone: string){
  const tEl = document.getElementById(zone === 'dock' ? 'dock-tabs' : 'cdock-tabs')!;
  const tabs = tEl.children;
  let idx = tabs.length;
  for (let i = 0; i < tabs.length; i++){
    const r = tabs[i].getBoundingClientRect();
    if (x < r.left + r.width / 2){ idx = i; break; }
  }
  const m = document.createElement('span');
  m.className = 'insmark';
  if (idx >= tEl.children.length) tEl.appendChild(m);
  else tEl.insertBefore(m, tEl.children[idx]);
  return idx;
}

function clearMarker(){ $all('.insmark').forEach(function(m){ m.remove(); }); }

function insertAt(container: HTMLElement, panel: HTMLElement, idx: number){
  const kids = $all(':scope > .panel', container);
  if (idx < 0 || idx >= kids.length) container.appendChild(panel);
  else container.insertBefore(panel, kids[idx]);
}

function movePanel(panel: HTMLElement, zone: string, idx: number, x: number, y: number){
  panel.classList.remove('floating');
  panel.style.left = panel.style.top = '';
  if (zone === 'left')       document.getElementById('col-side')!.appendChild(panel);
  else if (zone === 'right') document.getElementById('col-right')!.appendChild(panel);
  else if (zone === 'dock'){ insertAt(document.getElementById('dock-panels')!, panel, idx); activatePanel(panel.getAttribute('data-panel')); }
  else if (zone === 'cdock'){ insertAt(document.getElementById('cdock-panels')!, panel, idx); activatePanel(panel.getAttribute('data-panel')); }
  else if (zone === 'float'){
    document.getElementById('float-layer')!.appendChild(panel);
    panel.classList.add('floating');
    panel.style.left = Math.max(50, (x || 300) - 150) + 'px';
    panel.style.top  = Math.max(40, (y || 200) - 16) + 'px';
  }
  syncTabs();
  applyLayout();
}

/* Repaint a segmented gauge to `fill` lit segments (the last three read as the
   warning band). Gauges were painted once from data-fill at init and never again;
   a live readout needs a setter, and the segment count stays the element's. */
export function paintGauge(g: HTMLElement | null, fill: number): void {
  if (!g) return;
  const segs = parseInt(g.getAttribute('data-segs')!, 10) || 12;
  const on = Math.max(0, Math.min(segs, Math.round(fill || 0)));
  /* a same-value repaint must not rebuild the segment DOM (opus r2 N1) */
  if (String(on) === g.getAttribute('data-fill') && g.childElementCount === segs) return;
  g.setAttribute('data-fill', String(on));
  g.innerHTML = '';
  for (let i = 0; i < segs; i++){
    const seg = document.createElement('i');
    if (i < on) seg.className = (i >= segs - 3) ? 'on w' : 'on';
    g.appendChild(seg);
  }
}

/* ratio (0..1) → lit segments of a gauge element */
export function setGaugeRatio(g: HTMLElement | null, ratio: number): void {
  if (!g) return;
  const segs = parseInt(g.getAttribute('data-segs')!, 10) || 12;
  const r = Number.isFinite(ratio) ? Math.max(0, Math.min(1, ratio)) : 0;
  /* any non-zero reading lights at least one segment: an empty gauge means
     "nothing", not "a little" */
  paintGauge(g, r > 0 ? Math.max(1, Math.round(r * segs)) : 0);
}

/* Global tooltip: one fixed div on <body>, driven by delegated mouseover.
   The old CSS [data-tip]:hover::after tips clipped against overflow:hidden
   ancestors (.status .sitem ellipsizes; rail; tabs), so hovering showed
   nothing there — a body-level fixed div has no clipping ancestor.
   Placement: above the element by default; data-pos="below" forces under;
   rail items open to the right (flush left screen edge); data-tip-wrap wraps
   long text at 38ch. Hard-won details (opus r1-r3):
   - width:max-content, or shrink-to-fit measures against the PREVIOUS tip's
     stale `left`; overflow-wrap:anywhere, or an unbroken token (a server
     openError path) defeats max-width and paints off-viewport.
   - hide on pointerdown CAPTURE — drag handlers preventDefault pointerdown,
     which suppresses the compat mousedown; and while a button is held,
     mouseover's e.buttons (stateless, can't wedge like a flag) gates shows.
   - a 500ms watch reconciles a visible tip against anchor removal and
     reflow; a MutationObserver catches data-tip GAINED or rewritten under a
     stationary pointer (device.js's openError write, ribbon live readouts).
   - keyboard: focusin gated on :focus-visible (click-focus doesn't resurrect
     what pointerdown hid); keydown hides only pointer-driven tips, so the
     key that activates FOLLOW doesn't kill the tip it just rewrote; the
     aria-describedby lands on the focused node — role=tooltip alone is inert.
   - placeTip compares against the last placement before writing — a
     reconciling tick that changed nothing must not pay a layout write or
     slide the tip under the reader's eyes. */
let tipEl: HTMLElement | null = null;
let tipFor: Element | null = null;
let tipAria: Element | null = null;      /* element holding our aria-describedby (usually tipFor) */
let tipFocus = false;    /* shown via keyboard focus, not the pointer */
let tipWatch = 0;
let tipRect: { l: number; t: number; w: number; h: number; tw: number; th: number } | null = null;      /* last placement inputs — no-op guard */
let tipHoverRaw: Element | null = null;   /* last raw hover target — the observer re-resolves
                             the innermost tip anchor from it (covers elements
                             that GAIN data-tip while hovered) */

function hideTip(){
  if (!tipFor && (!tipEl || tipEl.hidden)) return;   /* hot path: every keypress */
  if (tipAria) tipAria.removeAttribute('aria-describedby');
  tipAria = null;
  tipEl!.hidden = true;
  tipEl!.textContent = '';     /* don't keep the last error/serials in the DOM */
  tipFor = null;
  tipFocus = false;
  tipRect = null;
  if (tipWatch){ clearInterval(tipWatch); tipWatch = 0; }
}

function placeTip(t: Element){
  const r = t.getBoundingClientRect();
  const tw = tipEl!.offsetWidth, th = tipEl!.offsetHeight;
  if (tipRect && tipRect.l === r.left && tipRect.t === r.top &&
      tipRect.w === r.width && tipRect.h === r.height &&
      tipRect.tw === tw && tipRect.th === th) return;   /* nothing moved */
  tipRect = { l: r.left, t: r.top, w: r.width, h: r.height, tw: tw, th: th };
  let x, y;
  if (t.closest('.rail')){                       /* flush-left rail: open right */
    x = r.right + 8;
    y = r.top + r.height / 2 - th / 2;
  } else {
    const below = t.getAttribute('data-pos') === 'below' || r.top < th + 10;
    y = below ? r.bottom + 6 : r.top - th - 6;
    x = r.left + r.width / 2 - tw / 2;
  }
  x = Math.max(4, Math.min(x, window.innerWidth - tw - 4));
  y = Math.max(4, Math.min(y, window.innerHeight - th - 4));
  tipEl!.style.left = x + 'px';
  tipEl!.style.top = y + 'px';
}

function showTip(t: Element, viaFocus: boolean, ariaEl?: Element | null){
  const text = t.getAttribute('data-tip');
  if (!text){ hideTip(); return; }
  const wrap = t.hasAttribute('data-tip-wrap');
  if (t === tipFor && tipEl && !tipEl.hidden && tipEl.textContent === text &&
      tipEl.classList.contains('wrap') === wrap){
    if (viaFocus) tipFocus = true;   /* hover-then-tab on one anchor: focus wins */
    /* the aria target may still need to move: hover a wrapper, then tab to a
       focusable child — the described node is the focused one (opus r4 F8) */
    const a = ariaEl || t;
    if (a !== tipAria){
      if (tipAria) tipAria.removeAttribute('aria-describedby');
      tipAria = a;
      a.setAttribute('aria-describedby', 'tip');
    }
    return;                                       /* re-hovering children */
  }
  if (tipAria && tipAria !== t && tipAria !== ariaEl)
    tipAria.removeAttribute('aria-describedby');
  tipFor = t;
  tipFocus = !!viaFocus;
  tipEl!.textContent = text;
  tipEl!.classList.toggle('wrap', wrap);
  tipAria = ariaEl || t;
  tipAria.setAttribute('aria-describedby', 'tip');
  tipEl!.hidden = false;
  placeTip(t);
  if (!tipWatch) tipWatch = setInterval(watchTip, 500);
}

/* reconciles a visible tip against anchor removal / reflow under a stationary
   pointer; text and wrap rewrites arrive via the MutationObserver instead */
function watchTip(){
  const t = tipFor;
  if (!t || tipEl!.hidden){ hideTip(); return; }
  if (!t.isConnected || !t.getAttribute('data-tip')){ hideTip(); return; }
  placeTip(t);
}

function initTips(){
  if (tipEl) return;                             /* idempotent */
  tipEl = document.createElement('div');
  tipEl.id = 'tip';
  tipEl.setAttribute('role', 'tooltip');
  tipEl.hidden = true;
  document.body.appendChild(tipEl);
  document.addEventListener('mouseover', function(e){
    if (e.buttons) return;      /* mid-drag — authoritative, self-healing */
    const t = e.target && (e.target as Element).closest ? (e.target as Element).closest('[data-tip]') : null;
    tipHoverRaw = e.target as Element | null;
    if (t) showTip(t, false); else hideTip();
  });
  /* a data-tip gained or rewritten under a STATIONARY pointer fires no mouse
     event (device.js's openError; ribbon readouts) — surface it without polling.
     tipFor is handled regardless of hover: a keyboard-driven (focused) tip
     isn't hovered, and its anchor rewrites on activation (FOLLOW toggle).
     The hover branch re-resolves the innermost anchor from tipHoverRaw (the
     raw hover target — closest() at mouseover time misses elements that GAIN
     data-tip later, r5 R6), gated on :active (a rewrite landing mid-drag must
     not pop a tip, r4 F3) and !tipFocus (a background rewrite must not steal
     a keyboard user's tip, r4 F4). attributeOldValue skips the same-value
     rewrites ribbon painters emit (r4 F12). An anchor REPLACED wholesale
     (childList) isn't seen here — Chrome re-hit-tests on layout change, so
     the next boundary event re-acquires (measured, r4 F13). */
  new MutationObserver(function(muts){
    for (const m of muts){
      const t = m.target;
      if (!(t instanceof Element)) continue;
      if (m.oldValue === t.getAttribute(m.attributeName!)) continue;
      if (t === tipFor){
        if (t.getAttribute('data-tip')) showTip(t, tipFocus, tipAria);
        else hideTip();
        continue;
      }
      if (tipFocus || document.documentElement.matches(':active')) continue;
      if (tipHoverRaw && (t === tipHoverRaw || t.contains(tipHoverRaw))){
        const anchor = tipHoverRaw.closest('[data-tip]');
        if (anchor) showTip(anchor, false);
      }
    }
  }).observe(document.body, { subtree: true, attributes: true,
    attributeOldValue: true, attributeFilter: ['data-tip', 'data-tip-wrap'] });
  /* pointer left the window entirely (no relatedTarget) — no mouseover follows */
  document.addEventListener('mouseout', function(e){
    if (!e.relatedTarget){ tipHoverRaw = null; hideTip(); }
  });
  document.addEventListener('focusin', function(e){
    const t = e.target && (e.target as Element).closest ? (e.target as Element).closest('[data-tip]') : null;
    /* :focus-visible only matches the focused node itself — and the aria
       belongs on that node, not on a non-focusable wrapper carrying the tip */
    if (t && (e.target as Element).matches(':focus-visible')) showTip(t, true, e.target as Element);
  });
  document.addEventListener('focusout', hideTip);
  document.addEventListener('pointerdown', hideTip, true);
  /* a keypress hides POINTER tips; focus-driven tips survive activation keys
     (FOLLOW's Space rewrites its own tip) — except text-entry anchors, which
     match :focus-visible even when click-focused: a pinned tip would cover
     the UI for the whole typing session (r4 F2) */
  window.addEventListener('keydown', function(){
    if (!tipFocus || (tipAria && tipAria.matches('input,textarea,select,[contenteditable]')))
      hideTip();
  }, true);
  window.addEventListener('scroll', hideTip, true);
  window.addEventListener('resize', hideTip);
  window.addEventListener('blur', hideTip);
}

/* ==================================================================
   CHROME INIT — tips, gauges, theme, toggles, strips, rail, projmenu
================================================================== */
export function initChrome(): void {
  main = document.getElementById('main')!;
  crumbEl = document.getElementById('crumb')!;
  codeCrumb = crumbEl.innerHTML;

  /* FQCN copy: the class crumb segment carries the full name (code.js writes
     data-fqcn + the title); a click copies it, acked by the outline flash the
     rest of the shell uses. Delegated — the crumb is re-rendered per caret move. */
  crumbEl.addEventListener('click', function(e){
    const seg = (e.target as HTMLElement).closest<HTMLElement>('.cur[data-fqcn]');
    if (!seg) return;
    const fqcn = seg.getAttribute('data-fqcn');
    if (navigator.clipboard && navigator.clipboard.writeText)
      navigator.clipboard.writeText(fqcn!).catch(function(err){ console.warn('clipboard', err); });
    seg.style.outline = '2px solid var(--blue)';
    setTimeout(function(){ seg.style.outline = ''; }, 500);
  });

  initTips();

  /* segmented gauges */
  $all('.gauge').forEach(function(g){ paintGauge(g, parseInt(g.getAttribute('data-fill')!, 10) || 0); });

  /* theme switch (LIGHT/DARK) */
  const themesw = document.getElementById('themesw')!;
  themesw.addEventListener('click', function(){
    setTheme(!document.body.classList.contains('dark'));
    prefsChanged();                                        // the theme persists server-side (decision 17)
  });
  S.hooks.resetUiPrefs = resetUiPrefs;

  /* toggle switches — optimistic paint for the decorative ones. A switch that
     owns SERVER state opts out (data-bound) and paints from the op's result:
     flipping the knob on click would claim a change the backend may refuse. */
  document.addEventListener('click', function(e){
    const sw = (e.target as HTMLElement).closest('.tswitch');
    if (sw && !sw.hasAttribute('data-bound')) sw.classList.toggle('on');
  });

  /* tree / xref expand-collapse */
  document.addEventListener('click', function(e){
    const node = (e.target as HTMLElement).closest('[data-t]');
    if (!node) return;
    const caret = node.querySelector('.caret');
    const kids  = node.parentElement!.querySelector(':scope > .kids');
    if (!kids) return;
    kids.classList.toggle('cl');
    if (caret) caret.classList.toggle('cl');
  });

  /* target strip switching (delegated — chips are live/dynamic) */
  const strip = document.querySelector('.targets')!;
  strip.addEventListener('click', function(e){
    const chip = (e.target as HTMLElement).closest<HTMLElement>('.tchip');
    if (!chip || chip.id === 'mapchip') return;
    /* .on is renderChips' to paint (from S.currentTid, app.js) — ONE writer
       (P0.1). A target click leaves the map for the code view; a pane view or
       agents stay up and re-query the new target via targetSwitched. */
    if (S.center === 'map') showCenter('code');
    hook('targetSwitched', chip);
  });
  /* the map chip is created by renderMapChip (it does not exist in a mapless
     project), so its listeners are bound at creation — not here */

  /* editor tab switching is live-model only (S.openTabs + renderTabs in app.js,
     QA 16) — the static-fixture binding was removed in the QA2 majors pass (p4) */

  /* activity rail: idempotent selectors (P0.3) — the button of the view already
     showing only re-focuses it; Explorer is the way back to code (and the map
     exit, QA m6) */
  S.rbtns = $all('.rbtn');
  const RAIL_VIEW: Record<string, CenterView> = {};
  Object.keys(CENTER_RAIL).forEach(function(v){ if (v !== 'map') RAIL_VIEW[(CENTER_RAIL as Record<string, string>)[v]] = v as CenterView; });
  S.rbtns.forEach(function(b){
    const v = RAIL_VIEW[b.id];
    if (v) b.addEventListener('click', function(){ selectCenter(v); });
  });

  /* dock collapse affordances + the ⌘J/⌘B hooks (QA 10) */
  document.getElementById('dock-chev')!.addEventListener('click', toggleDock);
  document.getElementById('dock-restore')!.addEventListener('click', toggleDock);
  document.getElementById('side-chev')!.addEventListener('click', toggleSide);
  S.hooks.toggleDock = toggleDock;
  S.hooks.toggleSide = toggleSide;

  initSplitters();
  /* P0-4: the window is the fourth splitter — its edge moves the budget the
     other three are clamped against (the tip hider on the same event stays) */
  window.addEventListener('resize', scheduleLayout);
  initDragDock();
  initProjMenu();
}

/* ==================================================================
   PROJECT MENU — live from project.list (QA m1/m2): switch / open / new /
   close all hit the backend; the strip, breadcrumb and data follow.
================================================================== */
interface EditorProject { name: string; id: string | null; open: boolean; active: boolean }
interface Editing { name: string; err: string | null }

function initProjMenu(){
  const projMenu = document.getElementById('projmenu')!;
  const pmList   = document.getElementById('pmlist')!;
  const projChip = document.getElementById('projchip')!;
  let projects: Project[] = [];

  async function refreshProjects(){
    const r = await tryOp('project.list', {});
    projects = r.data || [];
    return projects;
  }

  /* MAPS section: live from map.list, ✓ on the ACTIVE map; a row switch calls
     map.open then reloads the canvas (carryover — was a static fcm-only row) */
  async function renderMapRows(confirmId?: string){
    const host = document.getElementById('pmmaps')!;
    host.innerHTML = '';
    const [ml, st] = await Promise.all([tryOp('map.list', {}), tryOp('workspace.state', {})]);
    const active = (st.data && st.data.activeMap) || S.mapId;
    (ml.data || []).forEach(function(m){
      const row = document.createElement('div');
      row.className = 'pm-item';
      row.innerHTML = '<span class="pm-check">' + (m.id === active ? '✓' : '') + '</span>' +
        '<span class="pm-name">' + escText(m.id) + ' ▤</span>' +
        '<span class="pm-n num">' + (m.nodes ?? '') + ' nodes</span>' +
        '<span class="tx" role="button" aria-label="Delete map" title="Delete map (file is parked)">×</span>';
      row.querySelector('.tx')!.addEventListener('click', function(e){
        e.stopPropagation();                 // never also switch to the map
        confirmDeleteMap(row, m, m.id === active);
      });
      if (m.id === confirmId) confirmDeleteMap(row, m, m.id === active);
      row.addEventListener('click', async function(){
        if (row.classList.contains('pm-confirm')) return;   // the confirm owns this row
        closeProjMenu();
        await tryOp('map.open', { id: m.id });
        /* reloadMap RENDERS the chip (it may not exist yet — a mapless project
           has none) BEFORE the surface shows: showCenter refuses a chipless map.
           It also fits + focuses the canvas (focusMap). */
        if (S.hooks.reloadMap) await S.hooks.reloadMap(m.id);
        showCenter('map');
      });
      host.appendChild(row);
    });
  }

  /* Two-step delete, no typing: the row BECOMES the confirm. The projmenu is
     the map inventory, and newProject() already replaces the list with an
     inline mutating row, so this matches the menu's own idiom — no new modal.
     Retyping the id would be ceremony: the file is parked, not destroyed, and
     the parked path is surfaced right here afterwards. */
  function confirmDeleteMap(row: HTMLElement, m: MapSummary, wasActive: boolean){
    row.className = 'pm-item pm-confirm';
    row.innerHTML = '';
    const name = document.createElement('span');
    name.className = 'pm-name';
    name.textContent = 'delete ' + m.id + ' · ' + (m.nodes ?? 0) + ' nodes';
    const go = document.createElement('span');
    go.className = 'pm-go';
    go.textContent = 'DELETE';
    const cancel = document.createElement('span');
    cancel.className = 'pm-cancel';
    cancel.textContent = 'cancel';
    row.appendChild(name);
    row.appendChild(go);
    row.appendChild(cancel);
    cancel.addEventListener('click', function(e){ e.stopPropagation(); renderMapRows(); });
    go.addEventListener('click', async function(e){
      e.stopPropagation();
      go.textContent = '…';
      const r = await tryOp('map.delete', { id: m.id, confirm: true });
      if (r.error){ name.textContent = r.error.message; go.textContent = 'DELETE'; return; }
      /* the surface follows only when the DELETED map was the open one — the
         backend closed it, so reloadMap re-resolves to nothing and drops both
         the canvas and the chip. Reloading otherwise would throw away the open
         map's selection for a row the user never touched. */
      if (wasActive){
        if (S.hooks.reloadMap) await S.hooks.reloadMap(null);
        updateCenter();
      }
      await renderMapRows();
      /* recovery IS the parked file — this is the only place the user can
         learn its path (map.restore would be a trivial reverse-move; the
         response carrying the path is the honest story until then) */
      const note = document.createElement('div');
      note.className = 'pm-parked';
      note.textContent = (r.data && r.data.parked)
        ? 'parked: ' + r.data.parked
        : 'deleted (no sidecar file on disk)';
      document.getElementById('pmmaps')!.appendChild(note);
    });
  }

  function renderProjMenu(){
    pmList.innerHTML = '';
    projects.forEach(function(p){
      const row = document.createElement('div');
      row.className = 'pm-item';
      row.innerHTML = '<span class="pm-check">' + (p.active ? '✓' : '') + '</span>' +
        '<span class="pm-name">' + escText(p.name) + '</span>' +
        '<span class="pm-n num">' + ((p.targetIds || []).length) + ' tgt</span>' +
        EDIT_BTN;
      row.addEventListener('click', function(){ switchProject(p); });
      /* the editor's view of the row (segW): an OPEN project is addressed by
         id (project.switch / project.close take one); active decides whether
         its members are the strip's chips */
      const proj: EditorProject = { name: p.name, id: p.id, open: true, active: !!p.active };
      bindEditBtn(row, proj);
      pmList.appendChild(row);
      mountEditor(row, proj);
    });
    /* a real disabled state (P0.6): aria-disabled + the default cursor (CSS),
       not an opacity that keeps the pointer and the live handler */
    const closeRow = document.getElementById('pm-close')!;
    /* the LAST project closes too — the workspace returns to the empty boot
       state (2026-09-07: empty is a legal state, not an error) */
    const canClose = projects.some(p => p.active);   // close needs an ACTIVE project (r1 #10), not merely a row
    closeRow.setAttribute('aria-disabled', canClose ? 'false' : 'true');
    closeRow.setAttribute('data-tip', canClose ? 'Close the active project (targets auto-save)' : 'no project is open');
  }

  async function afterProjectChange(state: WorkspaceState){
    const resumeOpen = !!S.hooks.needProjectThenOpen;   // read BEFORE closeProjMenu clears it
    renderProjMenu(); closeProjMenu();
    projChip.style.outline = '1px solid var(--blue)';
    setTimeout(function(){ projChip.style.outline = 'none'; }, 500);
    if (S.hooks.projectSwitched) await S.hooks.projectSwitched(state);
    /* the open-target flow parked here until a project existed — land the user
       in the launcher now (guided flow, 2026-09-07) */
    if (resumeOpen && S.hooks.openLauncher) S.hooks.openLauncher();
  }

  async function switchProject(p: Project){
    if (p.active){ closeProjMenu(); return; }
    if (S.hooks.saveViews) S.hooks.saveViews();   /* the viewed position outlives the project (view.json) */
    const r = await tryOp('project.switch', { project: p.id });
    if (r.error) return;
    await afterProjectChange(r.data);
  }

  async function closeProject(){
    if (projects.length < 1) return;
    const active = projects.find(function(p){ return p.active; });
    if (!active) return;
    if (S.hooks.saveViews) S.hooks.saveViews();   /* the viewed position outlives the project (view.json) */
    const r = await tryOp('project.close', { project: active.id });
    if (r.error) return;
    await afterProjectChange(r.data);
  }

  /* ＋ New project: inline name row in the menu → project.open */
  function newProject(){
    pmList.innerHTML = '';
    const row = document.createElement('div');
    row.className = 'pm-item';
    const inp = document.createElement('input');
    inp.className = 'pm-newname';
    inp.placeholder = 'new project name';
    inp.spellcheck = false;
    row.appendChild(inp);
    pmList.appendChild(row);
    inp.focus();
    inp.addEventListener('keydown', async function(e){
      if (e.key === 'Escape'){ e.preventDefault(); e.stopPropagation(); renderProjMenu(); }
      if (e.key === 'Enter'){
        e.preventDefault();
        const name = inp.value.trim();
        if (!name){ renderProjMenu(); return; }
        const r = await tryOp('project.open', { name });
        if (r.error){ inp.value = r.error.message; return; }
        await afterProjectChange(r.data);
      }
    });
  }

  /* ON DISK: projects that exist only as sidecars (a restart forgets the open
     set — project.known enumerates the .project-name markers). Clicking LOADS
     by name: project.open rebinds the same name-keyed sidecar, so maps and the
     persisted activeMap come back; targets stay closed (they are engine
     sessions — reopen via target.open). Open projects are excluded (they are
     in OPEN PROJECTS already); an all-open/empty list hides the section. */
  let knownGen = 0;   // overlapping renders: the LATEST one wins (r5 F6)
  async function renderKnownRows(){
    const gen = ++knownGen;
    const host = document.getElementById('pmknown')!;
    const head = document.getElementById('pmknown-head')!;
    const sep = document.getElementById('pmknown-sep')!;
    const r = await tryOp('project.known', {});
    if (gen !== knownGen) return;   // a newer render owns the section
    host.innerHTML = '';
    const known = (r.data || []).filter(k => !k.open);
    head.hidden = host.hidden = sep.hidden = known.length === 0;   // an empty section is noise
    known.forEach(function(k){
      const row = document.createElement('div');
      row.className = 'pm-item';
      row.innerHTML = '<span class="pm-check"></span>' +
        '<span class="pm-name">' + escText(k.name) + '</span>' +
        '<span class="pm-n num">' + (k.maps || 0) + ' maps</span>' +
        EDIT_BTN +
        '<span class="tx" role="button" aria-label="Forget project" title="Forget (the sidecar is parked, not deleted)">×</span>';
      /* an ON DISK project has no id — the editor addresses it by name (every
         op in its contract accepts one); a member click / add target LOADS it
         first (segW). The forget × is the .tx that is NOT the ✎. */
      const proj: EditorProject = { name: k.name, id: null, open: false, active: false };
      bindEditBtn(row, proj);
      row.querySelector('.tx:not(.pm-ed)')!.addEventListener('click', function(e){
        e.stopPropagation();                 // never also LOAD the project
        /* the row BECOMES the confirm — the map-delete idiom, no modal. The
           sidecar is PARKED (<slug>.forgotten-<ts>), never unlinked. */
        row.className = 'pm-item pm-confirm';
        row.innerHTML = '';
        const nm = document.createElement('span');
        nm.className = 'pm-name';
        nm.textContent = 'forget ' + k.name + ' · ' + (k.maps || 0) + ' maps';
        const go = document.createElement('span');
        go.className = 'pm-go';
        go.textContent = 'FORGET';
        const cancel = document.createElement('span');
        cancel.className = 'pm-cancel';
        cancel.textContent = 'cancel';
        row.appendChild(nm); row.appendChild(go); row.appendChild(cancel);
        cancel.addEventListener('click', function(ev){ ev.stopPropagation(); renderKnownRows(); });
        go.addEventListener('click', async function(ev){
          ev.stopPropagation();
          go.textContent = '…';
          const fr = await tryOp('project.forget', { name: k.name, confirm: true });
          if (fr.error){ nm.textContent = fr.error.message; go.textContent = 'FORGET'; return; }
          if (editing && editing.name === k.name) editing = null;   // its editor went with it (segW)
          await renderKnownRows();
          /* recovery IS the parked path — the only place to learn it. Force the
             section visible even when the forget emptied the list. */
          head.hidden = host.hidden = sep.hidden = false;
          const note = document.createElement('div');
          note.className = 'pm-parked';
          note.textContent = (fr.data && fr.data.parked) ? 'parked: ' + fr.data.parked : 'forgotten';
          host.appendChild(note);
        });
      });
      row.setAttribute('data-tip', 'Load project — maps restored; targets reopen via target.open');
      row.addEventListener('click', async function(){
        if (row.classList.contains('pm-confirm')) return;   // the confirm owns this row (r3 #3)
        const rr = await tryOp('project.open', { name: k.name });
        if (rr.error) return;
        await afterProjectChange(rr.data);
      });
      host.appendChild(row);
      mountEditor(row, proj);
    });
  }

  /* ==================================================================
     PROJECT EDITOR (segW, 2026-09-08) — a project row's hover-✎ expands the
     row into the project's MEMBERS: the persisted membership the global
     target store keeps per project (backend/.workbench/targets/<key>/ —
     artifact copy + jdb2), live from project.targets. Open members carry the
     strip's LED; members only on disk ('closed') reopen from their store copy
     on click. The panel is the MAPS section's grammar (pm-item rows, hover-×,
     row-becomes-the-confirm) indented under the row — the menu gains no
     second visual language, and no modal.

     ONE record, keyed by NAME: ids remint when a project reopens and ON DISK
     rows carry no id at all, so the name is the only key that survives a
     project moving between OPEN PROJECTS and ON DISK (close / load / delete
     re-render both lists). Every list render re-derives the panel from the
     record (mountEditor) — a panel that lived only in the DOM would vanish
     with the next openProjMenu or post-op render. The record outlives a
     menu close on purpose: the flows below close the menu through the
     shell's landings and re-open it to show a refusal (setEditErr).
  ================================================================== */
  const EDIT_BTN = '<span class="tx pm-ed" role="button" aria-label="Edit project" title="Edit project — targets, add, delete">✎</span>';
  let editing: Editing | null = null;          // { name, err } — the expanded project, or null
  let editGen = 0;             // overlapping project.targets renders: the LATEST wins (renderKnownRows' r5 F6 idiom)

  /* the ✎ toggles the panel; the row's own click (switch / load) must not fire */
  function bindEditBtn(row: HTMLElement, proj: EditorProject){
    row.querySelector('.pm-ed')!.addEventListener('click', function(e){
      e.stopPropagation();
      const was = editing && editing.name === proj.name;
      closeEditor();
      if (!was){ editing = { name: proj.name, err: null }; mountEditor(row, proj); }
    });
  }

  /* collapses whatever panel is up (there is at most one) without a list
     render — the toggle must feel instant, and a render would refetch
     project.known */
  function closeEditor(){
    editing = null;
    $all('.pm-edit', projMenu).forEach(function(el){ el.remove(); });
    $all('.pm-editing', projMenu).forEach(function(el){ el.classList.remove('pm-editing'); });
  }

  /* called by both list renders for every row: the row whose name matches
     the record gets the panel (re)mounted under it */
  function mountEditor(row: HTMLElement, proj: EditorProject){
    if (!editing || editing.name !== proj.name) return;
    row.classList.add('pm-editing');
    const panel = document.createElement('div');
    panel.className = 'pm-edit';
    panel.innerHTML = '<div class="pm-parked">loading targets…</div>';
    row.after(panel);
    renderEditor(panel, proj);
  }

  /* the panel's content, live from project.targets — the list is the whole
     point of the panel, so a refused fetch IS the content, never a console
     line. The contract answers { items }; a bare array is tolerated (the op
     is being built in parallel). */
  async function renderEditor(panel: HTMLElement, proj: EditorProject){
    const gen = ++editGen;
    const r = await tryOp('project.targets', { project: proj.id || proj.name });
    /* a newer render owns the panel, or the editor collapsed / moved meanwhile */
    if (gen !== editGen || !panel.isConnected || !editing || editing.name !== proj.name) return;
    panel.innerHTML = '';
    const items: ProjectMember[] = r.error ? [] : (Array.isArray(r.data) ? r.data : (r.data && r.data.items) || []);
    const nOpen = items.filter(function(t){ return t.open; }).length;
    const total = items.reduce(function(a, t){ return a + (Number(t.sizeBytes) || 0); }, 0);
    const head = document.createElement('div');
    head.className = 'pm-head';
    head.textContent = 'TARGETS · ' + items.length + (nOpen ? ' · ' + nOpen + ' open' : '') +
      (total ? ' · ' + fmtBytes(total) : '');
    panel.appendChild(head);
    if (r.error){
      const e = document.createElement('div');
      e.className = 'pm-err';
      e.textContent = 'project.targets: ' + r.error.message;
      panel.appendChild(e);
    } else if (!items.length){
      const hint = document.createElement('div');
      hint.className = 'pm-parked';
      hint.textContent = 'no targets in this project — ＋ Add target… opens an artifact into it';
      panel.appendChild(hint);
    }
    items.forEach(function(t){ panel.appendChild(memberRow(proj, t)); });
    panel.appendChild(addRow(proj));
    panel.appendChild(deleteRow(proj));
    /* the panel's error line: a refusal from any of its ops (project.switch /
       target.open / …) — carried by the record so it survives a re-mount */
    const err = document.createElement('div');
    err.className = 'pm-err pm-operr';
    err.hidden = !editing.err;
    err.textContent = editing.err || '';
    panel.appendChild(err);
  }

  /* an op refusal reaches the user in the panel's error line: painted into
     the live panel when the menu is up, else the menu re-opens on the same
     editor (a landing closed it — afterProjectChange, or a chip click's
     bubble to the outside-click closer) and the record carries the message
     into the re-mounted panel. An editor collapsed (Esc) or moved to another
     project while the op was in flight is re-pointed at the refusing one:
     the user clicked, and a console line is not an answer. */
  function setEditErr(proj: EditorProject, msg: string){
    if (!editing || editing.name !== proj.name) editing = { name: proj.name, err: null };
    editing.err = msg;
    const el = projMenu.hidden ? null : projMenu.querySelector<HTMLElement>('.pm-edit .pm-operr');
    if (!el){ openProjMenu(); return; }
    el.textContent = msg;
    el.hidden = false;
  }
  /* a new gesture starts clean — the previous refusal must not outlive it */
  function clearEditErr(){
    if (editing) editing.err = null;
    const el = projMenu.querySelector<HTMLElement>('.pm-edit .pm-operr');
    if (el){ el.hidden = true; el.textContent = ''; }
  }

  /* the strip's LED voices (app.js ledFor / the open-status card): green =
     ready, orange = not ready yet, vermillion = error; a member that is only
     on disk has no engine — the hollow ring, not a colour */
  function ledClass(t: ProjectMember){
    if (t.state === 'error') return 'v';   // an errored engine reads as error wherever it lives (r2 nit)
    if (t.openElsewhere) return 'b';   // live in ANOTHER project (950876a-r1 #6) — not this one's green
    if (!t.open || t.state === 'closed') return 'x';
    if (t.state === 'ready') return 'g';
    return 'o';
  }

  function memberRow(proj: EditorProject, t: ProjectMember){
    const label = t.name || t.artifactName || t.key || '?';
    const row = document.createElement('div');
    row.className = 'pm-item pm-member';
    row.innerHTML = '<span class="led ' + ledClass(t) + '"></span>' +
      '<span class="pm-name">' + escText(label) + '</span>' +
      '<span class="pm-n num">' + escText(fmtBytes(t.sizeBytes)) + '</span>' +
      '<span class="tx" role="button" aria-label="Remove from project" title="Remove from project (the analysis on disk is kept)">×</span>';
    /* the tip says what the click does — and for a closed member, from where */
    row.setAttribute('data-tip', t.open
      ? 'Switch to this target · ' + (t.state || 'open') +
        (t.artifactName && t.artifactName !== label ? ' · ' + t.artifactName : '')
      : 'Reopen from the store — ' + (t.storePath || '(no storePath)'));
    row.setAttribute('data-tip-wrap', '');
    row.querySelector('.tx')!.addEventListener('click', function(e){
      e.stopPropagation();                 // never also activate the member
      confirmRemove(row, proj, t);
    });
    row.addEventListener('click', function(){
      if (row.classList.contains('pm-confirm')) return;   // the confirm owns this row
      activateMember(proj, t);
    });
    return row;
  }

  /* the ops that take a project: an OPEN one by id, an ON DISK one by name
     (project.open rebinds the name-keyed sidecar — the ON DISK row's own
     click). The ACTIVE project needs neither. */
  function activateProject(proj: EditorProject){
    if (S.hooks.saveViews) S.hooks.saveViews();   /* the viewed position outlives the project (view.json) */
    return proj.open ? tryOp('project.switch', { project: proj.id })
                     : tryOp('project.open', { name: proj.name });
  }

  /* an OPEN member of the ACTIVE project is a strip chip — match the state by
     path first (the store copy IS the engine's artifact), then key, then name.
     workspace.state's targets carry {id, name, path, …}; key is speculative. */
  function chipFor(t: ProjectMember){
    const ts = (S.state && S.state.targets) || [];
    const m = ts.find(function(x){ return x.path && x.path === t.storePath; }) ||
              ts.find(function(x){ return x.key && x.key === t.key; }) ||
              ts.find(function(x){ return x.name === t.name; }) || null;
    return m ? document.querySelector<HTMLElement>('.targets .tchip[data-tid="' + m.id + '"]') : null;
  }

  /* row click: an OPEN member becomes the viewed target; a CLOSED one reopens
     from its store copy — target.open {path: storePath} is that exact
     analysis, jdb2 included. Membership belongs to the ACTIVE project (a
     target opened while a project is active joins it), so a member of another
     project is reached THROUGH that project: switch / load it first via the
     row-click landing (afterProjectChange — closes this menu as a project
     row click does), then the target. */
  async function activateMember(proj: EditorProject, t: ProjectMember){
    clearEditErr();
    /* open-elsewhere (r2 F10): the backend NAVIGATES to the OWNING project on a
       re-open of the store path (navigateToTarget). Pre-switching to the EDITED
       project first would mount it only for the backend to switch away again —
       skip it; the open IS the navigation. */
    if (t.openElsewhere){
      if (!t.storePath){ setEditErr(proj, 'no storePath for ' + (t.name || t.key)); return; }
      const r0 = await tryOp('target.open', { path: t.storePath, rekey: true });   // the user's gesture: see launcher.ts doOpen
      if (r0.error){ setEditErr(proj, 'target.open: ' + r0.error.message); return; }
      closeProjMenu();
      if (r0.data && r0.data.deduped){
        /* land the navigation on the RETURNED id. Gate the heavy rebind on an
           ACTUAL project change (r5 M1/M2): projectSwitched wipes tabs/views
           and clicks the .on chip itself — on a same-project dedupe that
           destroys the tab strip AND double-fires the switch. */
        const wasProject = S.state && S.state.activeProject;
        const st = (await tryOp('workspace.state', {})).data;
        if (!st){ setEditErr(proj, 'workspace.state failed after the open — the strip may be stale'); return; }
        if (st.activeProject !== wasProject){
          if (S.hooks.projectSwitched) await S.hooks.projectSwitched(st);   // its own chip click lands the active target
          return;
        }
        if (S.center === 'map') showCenter('code');     // the plain-open path's rule (the map leaves for code)
        const chip = document.querySelector<HTMLElement>('.targets .tchip[data-tid="' + r0.data.id + '"]');
        if (chip && S.hooks.targetSwitched) await S.hooks.targetSwitched(chip);
        else if (S.hooks.targetOpened) await S.hooks.targetOpened(r0.data);
        /* no chip = the strip is STALE (another actor opened it — nothing polls
           workspace.state): the targetOpened chain refreshes state first, then
           clicks. A bare miss would desync the strip from the backend's
           activeTarget (r6 M) — the next class open would render the NEW
           target under the OLD chip */
      } else {
        /* genuinely fresh open (the elsewhere engine died between render and
           click — the dedupe scan skips error/closed): the card chain owns it */
        if (S.hooks.targetOpened) await S.hooks.targetOpened(r0.data);
      }
      return;
    }
    if (!proj.active){
      const r = await activateProject(proj);
      if (r.error){ setEditErr(proj, (proj.open ? 'project.switch: ' : 'project.open: ') + r.error.message); return; }
      await afterProjectChange(r.data);
    }
    if (t.open){
      let chip = chipFor(t);
      if (!chip){
        /* the strip could not place it (a key the state does not carry, a
           renamed target): switch by the LIVE target id project.targets ships
           per open member — the store key is NOT an id resolveTargetId knows
           (950876a-r1 #5) */
        if (!t.target){ setEditErr(proj, 'no live target id for ' + (t.name || t.key)); return; }
        const r = await tryOp('target.switch', { target: t.target });
        if (r.error){ setEditErr(proj, 'target.switch: ' + r.error.message); return; }
        const cur = ((r.data && r.data.targets) || []).find(function(x){ return x.active; });
        chip = cur ? document.querySelector<HTMLElement>('.targets .tchip[data-tid="' + cur.id + '"]') : null;
      }
      if (!chip){
        /* a chip miss HERE is a stale strip, not a missing target — the
           switch already committed the backend's activeTarget (r7 M), and a
           bare error would leave the strip showing the OLD target while the
           next target-less op reads the NEW one. Land through the chain that
           repaints first (refreshState → chip); the green-receipt flash on an
           already-ready target is the accepted cost (the :1392 precedent). */
        closeProjMenu();
        if (S.hooks.targetOpened) await S.hooks.targetOpened({ id: t.target! });
        return;
      }
      closeProjMenu();
      if (S.center === 'map') showCenter('code');     // the chip's own click leaves the map for the code view
      if (S.hooks.targetSwitched) await S.hooks.targetSwitched(chip);
      return;
    }
    if (!t.storePath){ setEditErr(proj, 'no storePath for ' + (t.name || t.key)); return; }
    const r = await tryOp('target.open', { path: t.storePath, rekey: true });   // the user's gesture: see launcher.ts doOpen
    if (r.error){ setEditErr(proj, 'target.open: ' + r.error.message); return; }
    /* ACCEPTED: the open-status card owns the poll and the READY → strip →
       chip chain (app.js targetOpened = targetReady + chip click — the device
       pull's landing); the menu goes, the card in the corner is the progress */
    closeProjMenu();
    if (S.hooks.targetOpened) await S.hooks.targetOpened(r.data);
  }

  /* the row BECOMES the confirm (the MAPS idiom). The copy is the point of the
     second step: target.remove drops the MEMBERSHIP (closing the engine if it
     is up) — the store entry with its jdb2 survives, so remove is never
     delete, and there is nothing to retype. */
  function confirmRemove(row: HTMLElement, proj: EditorProject, t: ProjectMember){
    const label = t.name || t.key;
    row.className = 'pm-item pm-confirm pm-member';
    row.removeAttribute('data-tip');
    row.innerHTML = '';
    const name = document.createElement('span');
    name.className = 'pm-name';
    name.textContent = 'remove ' + label + (t.open ? ' · open' : '');
    const go = document.createElement('span');
    go.className = 'pm-go';
    go.textContent = 'REMOVE';
    const cancel = document.createElement('span');
    cancel.className = 'pm-cancel';
    cancel.textContent = 'cancel';
    const copy = document.createElement('span');
    copy.className = 'pm-copy';
    copy.textContent = 'removes from the project; the analysis on disk is kept' +
      (t.open ? ' — the open engine closes first' : '');
    row.append(name, go, cancel, copy);
    cancel.addEventListener('click', function(e){ e.stopPropagation(); renderEditor(row.closest<HTMLElement>('.pm-edit')!, proj); });
    go.addEventListener('click', async function(e){
      e.stopPropagation();
      clearEditErr();
      go.textContent = '…';                 // an engine close can take 60s+ at GMS scale (the chip × says so)
      const r = await tryOp('target.remove', { project: proj.id || proj.name, target: t.key });
      if (r.error){ name.textContent = r.error.message; go.textContent = 'REMOVE'; return; }
      /* an OPEN member of the ACTIVE project left the strip too: the shell
         follows the returned state through its own landings, never a hand
         edit of the strip */
      if (t.open && proj.active) await landAfterRemove(r.data);
      /* the row's 'N tgt' count and the panel re-derive from the backend */
      await refreshProjects();
      renderProjMenu();
      await renderKnownRows();
    });
  }

  /* after target.remove took an OPEN member of the ACTIVE project: the
     returned workspace.state no longer has it, and the strip/ribbon/chip
     painters are app.js's (applyState — not exported), so the landing rides
     the two hooks that end in applyState: targetSwitched (target.switch → a
     fresh state) when a target remains — the VIEWED one if it survived, else
     the backend's active — and projectSwitched (the honest empty state: the
     same project, zero targets) when none does. Both called directly, never
     via chip.click(): a synthetic click bubbles to the outside-click closer
     and would slam this menu shut mid-flow. */
  async function landAfterRemove(state: WorkspaceState | null | undefined){
    let st = state && Array.isArray(state.targets) ? state : null;
    if (!st){                                  // the contract says workspace.state — verify, don't trust
      const r = await tryOp('workspace.state', {});
      st = r.data || null;
      if (!st) return;
    }
    const ts = st.targets || [];
    if (!ts.length){
      if (S.hooks.projectSwitched) await S.hooks.projectSwitched(st);
      return;
    }
    const keep = ts.find(function(x){ return x.id === S.currentTid; }) ||
                 ts.find(function(x){ return x.active; }) || ts[0];
    const chip = document.querySelector<HTMLElement>('.targets .tchip[data-tid="' + keep.id + '"]');
    if (chip && S.hooks.targetSwitched) await S.hooks.targetSwitched(chip);
    else if (S.hooks.projectSwitched) await S.hooks.projectSwitched(st);
  }

  /* ＋ Add target… → the launcher (the strip's ＋ / ⌘O path). A target opened
     while a project is ACTIVE joins it, so another project is activated first
     and the guided flow's resume flag lands afterProjectChange in the
     launcher; a refused switch clears the flag — stale, it would hijack a
     later unrelated project.open. */
  function addRow(proj: EditorProject){
    const row = document.createElement('div');
    row.className = 'pm-item pm-add';
    row.innerHTML = '<span class="pm-sp"></span><span class="pm-name">＋ Add target…</span>';
    row.setAttribute('data-tip', proj.active ? 'Open an artifact into this project (⌘O)'
                                             : 'Activates the project, then opens the launcher');
    row.addEventListener('click', async function(){
      clearEditErr();
      if (!proj.active){
        S.hooks.needProjectThenOpen = true;
        const r = await activateProject(proj);
        if (r.error){
          delete S.hooks.needProjectThenOpen;
          setEditErr(proj, (proj.open ? 'project.switch: ' : 'project.open: ') + r.error.message);
          return;
        }
        await afterProjectChange(r.data);             // resumes into the launcher
        return;
      }
      closeProjMenu();
      if (S.hooks.openLauncher) S.hooks.openLauncher();
    });
    return row;
  }

  /* DELETE PROJECT — the destructive sibling of forget. Forget (the ON DISK ×)
     PARKS the sidecar as .forgotten-<ts>; project.delete WIPES the project dir
     — maps, session, membership. Two-step, the row becomes the confirm, in
     the vermillion voice with the copy forget's lacks: nothing is parked. The
     targets' analyses are store entries and stay; the op reports the ones no
     project references any more (orphanedTargets) and that report is the
     transient note under the lists. An OPEN project is closed first —
     project.delete refuses one with E_BUSY, and where the forget flow simply
     never offers open rows, this row is on every project, so the close is
     the first half of the gesture: an ACTIVE project's close lands the shell
     in the empty state through projectSwitched (what Close project does), a
     non-active open one closes quietly. */
  function deleteRow(proj: EditorProject){
    const row = document.createElement('div');
    row.className = 'pm-item pm-del';
    row.innerHTML = '<span class="pm-sp"></span><span class="pm-name">Delete project…</span>';
    row.setAttribute('data-tip', 'Wipe the project folder from disk — not parked (that is Forget)');
    row.addEventListener('click', function(){
      if (row.classList.contains('pm-confirm')) return;
      armDelete(row, proj);
    });
    return row;
  }

  function armDelete(row: HTMLElement, proj: EditorProject){
    row.className = 'pm-item pm-confirm pm-del';
    row.removeAttribute('data-tip');
    row.innerHTML = '';
    const name = document.createElement('span');
    name.className = 'pm-name';
    name.textContent = 'delete ' + proj.name + (proj.open ? ' · open — closes first' : '');
    const go = document.createElement('span');
    go.className = 'pm-go';
    go.textContent = 'DELETE';
    const cancel = document.createElement('span');
    cancel.className = 'pm-cancel';
    cancel.textContent = 'cancel';
    const copy = document.createElement('span');
    copy.className = 'pm-copy';
    copy.textContent = 'wipes the project folder — maps, session, membership — from disk. ' +
      'NOT parked. Targets’ analyses stay in the store.';
    row.append(name, go, cancel, copy);
    cancel.addEventListener('click', function(e){ e.stopPropagation(); renderEditor(row.closest<HTMLElement>('.pm-edit')!, proj); });
    go.addEventListener('click', async function(e){
      e.stopPropagation();
      clearEditErr();
      go.textContent = '…';
      if (proj.open){
        if (S.hooks.saveViews) S.hooks.saveViews();   /* the viewed position outlives the project (view.json) */
        const cr = await tryOp('project.close', { project: proj.id });
        if (cr.error){ name.textContent = 'project.close: ' + cr.error.message; go.textContent = 'DELETE'; return; }
        if (proj.active && S.hooks.projectSwitched) await S.hooks.projectSwitched(cr.data);
      }
      const r = await tryOp('project.delete', { name: proj.name, confirm: true });
      if (r.error){
        if (!proj.open){ name.textContent = r.error.message; go.textContent = 'DELETE'; return; }
        /* the close above moved the project to ON DISK — the lists must
           re-derive (this row is stale); the message rides the record into
           the panel re-mounted under the ON DISK row */
        editing = { name: proj.name, err: 'project.delete: ' + r.error.message };
        openProjMenu();
        return;
      }
      closeEditor();
      /* the lists re-derive (the project is gone from both) and the menu
         stays — or returns, when the close's landing clicked a chip and the
         bubble shut it */
      openProjMenu();
      const orph = (r.data && r.data.orphanedTargets) || [];
      if (orph.length){
        const bytes = orph.reduce(function(a, o){ return a + (Number(o.sizeBytes) || 0); }, 0);
        pmNote(orph.length, ' target analys' + (orph.length === 1 ? 'is' : 'es') +
          ' now unreferenced (' + (fmtBytes(bytes) || '0 B') + ')');
      }
    });
  }

  /* the menu's transient note — the rail's noteRow idiom (runs.js; strings.js
     noteXrefs): one line, one at a time, self-expiring — in its own slot
     under the lists (#pm-note): a note inside a list dies with that list's
     next async render, and every op here re-renders them */
  let noteTimer = 0;
  function pmNote(n: number, rest: string){
    const el = document.getElementById('pm-note');
    if (!el) return;
    el.innerHTML = '<b>' + escText(n) + '</b>' + escText(rest);
    el.hidden = false;
    clearTimeout(noteTimer);
    noteTimer = setTimeout(function(){ el.hidden = true; }, 8000);
  }

  function openProjMenu(confirmId?: string){
    refreshProjects().then(renderProjMenu);
    renderMapRows(confirmId);
    renderKnownRows();
    const g = document.getElementById('pm-guide');
    if (g) g.hidden = !S.hooks.needProjectThenOpen;   // the guided flow's strip
    projMenu.hidden = false;
  }
  /* palette ACTIONS → the SAME inline confirm (GRAPHS_SPEC §4.7: all actions
     also in the palette). Delete never gets a one-click path. */
  S.hooks.confirmDeleteMap = function(id){ openProjMenu(id); };
  function closeProjMenu(){
    projMenu.hidden = true;
    /* a dismissed menu cancels the guided open-target flow — a stale flag
       would hijack an unrelated project.open later */
    delete S.hooks.needProjectThenOpen;
    const g = document.getElementById('pm-guide');
    if (g) g.hidden = true;
  }
  projChip.addEventListener('click', function(e){
    e.stopPropagation();
    projMenu.hidden ? openProjMenu() : closeProjMenu();
  });
  document.addEventListener('click', function(e){
    if (!projMenu.hidden && !(e.target as HTMLElement).closest('.projwrap')) closeProjMenu();
  });
  /* "Open target…" — the same launcher as the strip's ＋ (targets open into the
     active project). It was labelled "Open project…" next to a ⌘⇧O keycap
     that toggles this MENU — label, key and action were three things (P0.6). */
  document.getElementById('pm-open')!.addEventListener('click', function(){
    closeProjMenu();
    document.getElementById('tadd')!.click();
  });
  document.getElementById('pm-new')!.addEventListener('click', newProject);
  document.getElementById('pm-close')!.addEventListener('click', closeProject);
  /* the palette's PROJECT rows (UI audit 2026-09-09: projects had no keyboard reach) */
  S.hooks.openProjMenu = function(){ openProjMenu(); };
  S.hooks.newProject = function(){ newProject(); };
  S.hooks.closeProject = function(){ closeProject(); };
  /* ＋ NEW MAP: was a dead button (just closed the menu) — wired to the map
     view's create dialog (map-r5 F18) */
  document.getElementById('pm-map-new')!.addEventListener('click', function(){
    closeProjMenu();
    if (S.hooks.newMap) S.hooks.newMap();
  });
  document.addEventListener('keydown', function(e){
    if ((e.metaKey || e.ctrlKey) && e.shiftKey && e.key.toLowerCase() === 'o'){
      e.preventDefault();
      projMenu.hidden ? openProjMenu() : closeProjMenu();
    }
  }, true);
  /* project menu: Esc closes it, ahead of the view-level handlers (menu priority) */
  pushEscape(function(){
    if (projMenu.hidden) return false;
    /* an expanded editor collapses first (its armed confirms with it); the
       next Escape closes the menu (segW) */
    if (editing){ closeEditor(); return; }
    closeProjMenu();
  }, ESC.menu);
}
