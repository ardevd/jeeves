// palette.ts — ⌘K command palette, jump-anywhere dropdown, global keys.
import { $all, S, hook, pushEscape, popEscape, ESC, modalUp, escHtml, tryOpT } from './hub.js';
import { jumpToCode, showCenter, selectCenter, closeActiveMap, activeTarget, notice } from './chrome.js';
import { focusSelection } from './relation.js';
import { toggleFollow } from './ribbon.js';
import { subjectAtCaret, spawnReverse } from './reverse.js';
import { renderKeyhelp } from './keymap.js';
import { tryOp } from '../api.js';
import { rowsOf } from '../app.js';
import type { SymbolSearchHit, CenterView, EscapeHandle } from '../types.js';

/** what a command's run() may hand back: the JS duck-types the promise
 *  (`if (p && p.catch)`) rather than awaiting it */
type Thenable = { catch?: (fn: (err: unknown) => void) => void } | null | undefined;

/* ==================================================================
   FUZZY MATCH (QA 21) — subsequence match, no deps.
   Scoring: +1 per matched letter, +3 consecutive, +2 start-of-word,
   tiny length penalty so shorter labels rank higher.
================================================================== */
/** a fuzzy hit: its rank and the matched character positions */
interface FuzzyMatch {
  score: number;
  pos: number[];
}

function fuzzy(q: string, text: string): FuzzyMatch | null {
  if (!q) return { score: 0, pos: [] };
  const t = text.toLowerCase();
  let ti = 0, score = 0, last = -2;
  const pos: number[] = [];
  for (const c of q){
    let at = -1;
    for (let i = ti; i < t.length; i++) if (t[i] === c){ at = i; break; }
    if (at === -1) return null;
    pos.push(at);
    score += 1;
    if (at === last + 1) score += 3;                    // consecutive
    if (at === 0 || /[\s\-_./·:]/.test(t[at - 1])) score += 2;   // start-of-word
    last = at;
    ti = at + 1;
  }
  return { score: score - text.length * 0.01, pos };
}

function highlight(text: string, pos: number[]){
  if (!pos.length) return escHtml(text);
  let out = '', cur = 0;
  for (const p of pos){
    out += escHtml(text.slice(cur, p)) + '<em class="hl">' + escHtml(text[p]) + '</em>';
    cur = p + 1;
  }
  return out + escHtml(text.slice(cur));
}

/* ==================================================================
   COMMAND PALETTE (⌘K)
================================================================== */
let overlay: HTMLElement, palInput: HTMLInputElement, keyhelpEl: HTMLElement;
const palItems = function(){ return $all('.pal-item'); };

let palEsc: EscapeHandle | null = null;
function openPal(){
  overlay.classList.add('open');
  renderPal();                     /* `when` predicates are evaluated per open */
  palInput.value = '';
  filterPal('');
  palInput.focus();
  if (!palEsc) palEsc = pushEscape(closePal, ESC.palette);
}
function closePal(){
  overlay.classList.remove('open');
  popEscape(palEsc);
  palEsc = null;
}

/* shortcuts sheet: everything except the sheet goes `inert` while it is open, so
   Tab/Enter/Space cannot reach a live control behind it (review N1), and the
   window-capture gate below swallows every key ahead of the document-capture
   shortcut listeners (review N3). Focus moves IN on open and back to the
   previously focused element on close. */
let keyhelpPrevFocus: HTMLElement | null = null, keyhelpEsc: EscapeHandle | null = null;
function keyhelpInert(on: boolean){
  const kids = document.body.children;
  for (let i = 0; i < kids.length; i++){
    if (kids[i] === keyhelpEl) continue;
    if (on) kids[i].setAttribute('inert', ''); else kids[i].removeAttribute('inert');
  }
}
/* the scroller is the focus host: arrows/PageUp/PageDown then scroll the sheet
   natively (the card itself no longer scrolls — header stays pinned, N7) */
function keyhelpFocusHost(){
  return keyhelpEl.querySelector<HTMLElement>('.kh-cols') || keyhelpEl.querySelector<HTMLElement>('.wbm-card');
}
function openKeyhelp(){
  if (!keyhelpEl.hidden) return;              /* re-entry would overwrite prevFocus with the card */
  keyhelpPrevFocus = document.activeElement as HTMLElement | null;
  keyhelpEl.hidden = false;
  keyhelpInert(true);                       /* blurs whatever was focused behind */
  const host = keyhelpFocusHost()!;
  host.scrollTop = 0;                       /* N6: reopen starts at the top */
  host.focus({ preventScroll: true });
  keyhelpEsc = pushEscape(closeKeyhelp, ESC.keyhelp);   /* top of the escape stack */
}
function closeKeyhelp(){
  keyhelpEl.hidden = true;
  popEscape(keyhelpEsc);
  keyhelpEsc = null;
  keyhelpInert(false);                      /* before focusing: inert nodes reject focus */
  /* N12: an SSE repaint can replace the pre-open element while the sheet is up —
     focusing a detached node is a silent no-op that leaves the keys dead. */
  let back = keyhelpPrevFocus;
  if (!back || !back.focus || !document.contains(back)) back = document.getElementById('code');
  if (back && back.focus) back.focus({ preventScroll: true });
  keyhelpPrevFocus = null;
}
/* another dialog already owns the keyboard: '?' must not stack over it (N11) */
function otherModalOpen(){
  const m = document.querySelector('.wbmodal:not([hidden])');
  return !!(m && m !== keyhelpEl);
}

/* MAP actions in the palette (GRAPHS_SPEC §4.7). Close is file-neutral and
   runs straight away; delete routes through the projmenu's inline confirm —
   the same two-step the row hover-× uses, never a one-click destroy. */
async function deleteActiveMap(){
  let id: string | null | undefined = S.mapId;
  if (!id){
    const st = (await tryOp('workspace.state', {})).data;
    id = st && st.activeMap;
  }
  if (!id){
    console.warn('map.delete: no open map — delete a closed one from the projmenu MAPS list');
    return;
  }
  hook('confirmDeleteMap', id);
}

/* ==================================================================
   COMMANDS — the ONE command table (app review P0.4). It renders the palette
   rows, the jump-anywhere VIEWS/ACTIONS groups and the VIEWS/EDIT/NAVIGATE
   groups of the '?' sheet (keymap.js). `kbd` is the printed keycap — the key
   HANDLERS stay in initKeys / the owning module, so a row here without a
   handler there is a lie: add both. `when` (optional) hides a row that cannot
   run right now. The palette footer count is this table's length.
   Gone from the old static list: "Follow Agent Cursor ⇧F" (no ⇧F handler ever
   existed — the row opened Agents), "Save All" (it saved the active target).
================================================================== */
/** one row of the COMMANDS table — the palette, the G dropdown and the '?'
 *  sheet all render from it */
export interface Command {
  cat: string;
  label: string;
  kbd?: string;
  view?: CenterView;
  when?: () => boolean;
  run: () => unknown;
}

function onMap(){ return S.center === 'map'; }
function hasReverseSubject(){ return !!subjectAtCaret(); }
function mapOpen(){ return !!document.getElementById('mapchip'); }
function clickBtn(id: string){ const el = document.getElementById(id) as HTMLButtonElement | null; if (el && !el.disabled) el.click(); }

/* TARGET open/close (P0-5): the only open paths were the ＋ chip and the
   projmenu row, the only close the hover-× on a chip — no key, no palette row,
   so a keyboard user could do neither. Both reuse the existing paths, never a
   second one:
   - open = S.hooks.openLauncher, the launcher's show() that ＋ runs. It gates
     through the projmenu (guide strip) when no project is open — kept. Called
     DIRECTLY, not via tadd.click(): a synthetic click bubbles to the document
     outside-click closer, which would slam the guided projmenu shut and cancel
     the flow it just started (the r5 F3 trap).
   - close = the VIEWED chip's × click handler (app.js renderChips: target.close
     → workspace.state → applyState, the backend auto-saves a dirty JEB target
     on close). Resolved by activeTarget() — S.currentTid, the cursor renderChips
     paints .on from — never by .tchip.on, which the map chip wears too
     (map-r4 #1). .click() reaches a visibility:hidden × (only disabled form
     controls refuse it). */
function viewedChipX(){
  const t = activeTarget();
  const chip = t && document.querySelector('.targets .tchip[data-tid="' + t.id + '"]');
  return chip ? chip.querySelector<HTMLElement>('.tx') : null;
}
/* UNDO RE-KEY for the viewed target — the durable way back (the open card that offers it is
   gone after its ×): target.rekeyUndo closes the target without writing the new database and
   puts the old one, kept beside it, back in place. Offered while that old database is on disk
   (workspace.state's row says so: Target.rekeyed). */
function viewedRekeyed(){
  const t = activeTarget();
  const row = t && S.state ? S.state.targets.find(x => x.id === t.id) : undefined;
  return !!(row && row.rekeyed);
}
async function undoRekeyViewed(){
  const t = activeTarget();
  if (!t) return;
  const r = await tryOp('target.rekeyUndo', { target: t.id });
  if (r.error){ notice('undo not applied — ' + r.error.message, { err: true, ms: 12000 }); return; }
  notice(r.data.note, { ms: 12000 });
  if (S.hooks.reseedTarget) await S.hooks.reseedTarget();
}

function closeViewedTarget(){
  const tx = viewedChipX();
  if (!tx){ console.warn('close target: no viewed target'); return; }
  if (tx.classList.contains('busy')) return;   // a close is already in flight (60s+ at GMS scale)
  tx.click();
}
function openTargetLauncher(){
  if (!S.hooks.openLauncher){ console.warn('open target: launcher not initialised (backend down?)'); return; }
  S.hooks.openLauncher();
}

export const COMMANDS: Command[] = [
  // NAV
  { cat:'NAV',   label:'Jump anywhere…',      kbd:'g',  run:function(){ openJdrop(); } },
  { cat:'NAV',   label:'Find references',     kbd:'x',  run:function(){ focusSelection(); } },
  { cat:'NAV',   label:'Back',                kbd:'⌘[', run:function(){ hook('navBack'); } },
  { cat:'NAV',   label:'Forward',             kbd:'⌘]', run:function(){ hook('navFwd'); } },
  { cat:'NAV',   label:'Filter symbols',      kbd:'/',  run:function(){ hook('focusTreeFilter'); } },
  // PROJECT — the top of the noun stack had no keyboard reach (UI audit 2026-09-09)
  { cat:'PROJECT', label:'Project menu…',  kbd:'⌘⇧O', run:function(){ hook('openProjMenu'); } },
  { cat:'PROJECT', label:'New project…',   run:function(){ hook('newProject'); } },
  { cat:'PROJECT', label:'Close project',  when:function(){ return !!(S.state && S.state.activeProject); }, run:function(){ hook('closeProject'); } },
  { cat:'NAV',   label:'Keyboard shortcuts',  kbd:'?',  run:function(){ openKeyhelp(); } },
  // TARGET (P0-5) — ⌥W, not ⌘W/⌘⇧W: those close the browser tab before the page sees them
  { cat:'TARGET', label:'Open target…',  kbd:'⌘O', run:function(){ openTargetLauncher(); } },
  { cat:'TARGET', label:'Close target',  kbd:'⌥W', when:function(){ return !!viewedChipX(); }, run:function(){ closeViewedTarget(); } },
  { cat:'TARGET', label:'Undo re-key — put the old database back', when:viewedRekeyed, run:function(){ return undoRekeyViewed(); } },
  // EDIT
  { cat:'EDIT',  label:'Rename symbol',       kbd:'N', run:function(){ hook('openRename'); } },
  { cat:'EDIT',  label:'Comment symbol',      kbd:';',  run:function(){ hook('openComment'); } },
  { cat:'EDIT',  label:'Save target',         kbd:'⌘S', run:function(){ hook('saveTarget'); } },
  { cat:'EDIT',  label:'Undo',                kbd:'⌘Z', run:function(){ hook('undoHistory'); } },
  { cat:'EDIT',  label:'Redo',                kbd:'⇧⌘Z', run:function(){ hook('redoHistory'); } },
  { cat:'EDIT',  label:'Tag symbol',          kbd:'t',  run:function(){ hook('addTag'); } },
  { cat:'EDIT',  label:'Toggle bookmark',     kbd:'⌘D', run:function(){ hook('toggleBookmark'); } },
  { cat:'EDIT',  label:'Toggle breakpoint',   kbd:'F9', run:function(){ hook('toggleBp'); } },
  { cat:'EDIT',  label:'Go to definition',    kbd:'F12', run:function(){ hook('gotoDefAtCaret'); } },
  { cat:'EDIT',  label:'Implementations',     kbd:'i',   run:function(){ hook('implementations'); } },
  { cat:'VIEW',  label:'Outline (class members)', kbd:'o', run:function(){ hook('showOutline'); } },
  // VIEW — center selectors (idempotent, P0.3; `view` marks them for the G dropdown's VIEWS group)
  { cat:'VIEW',  label:'Explorer', kbd:'⌃1', view:'code',    run:function(){ selectCenter('code'); } },
  { cat:'VIEW',  label:'Search',   kbd:'⌃2', view:'search',  run:function(){ selectCenter('search'); } },
  { cat:'VIEW',  label:'Strings',  kbd:'⌃3', view:'strings', run:function(){ selectCenter('strings'); } },
  { cat:'VIEW',  label:'Runs',     kbd:'⌃4', view:'runs',    run:function(){ selectCenter('runs'); } },
  { cat:'VIEW',  label:'Agents',   kbd:'⌃5', view:'agents',  run:function(){ selectCenter('agents'); } },
  { cat:'VIEW',  label:'Device',   kbd:'⌃6', view:'device',  run:function(){ selectCenter('device'); } },
  // VIEW — the two real toggles + named layouts
  { cat:'VIEW',  label:'Toggle dock',         kbd:'⌘J', run:function(){ hook('toggleDock'); } },
  { cat:'VIEW',  label:'Toggle side panel',   kbd:'⌘B', run:function(){ hook('toggleSide'); } },
  { cat:'VIEW',  label:'Save layout…',    run:function(){ hook('layoutSaveDialog'); } },
  { cat:'VIEW',  label:'Apply layout…',   run:function(){ hook('layoutApplyDialog'); } },
  { cat:'VIEW',  label:'Delete layout…',  run:function(){ hook('layoutDeleteDialog'); } },
  { cat:'VIEW',  label:'Reset layout & theme to default', run:function(){ hook('resetUiPrefs'); } },
  // AGENT — the FOLLOW switch's action, no key (none is bound)
  { cat:'AGENT', label:'Toggle follow agent', run:function(){ toggleFollow(); } },
  /* reverse the caret's method/class with an agent (ui/reverse.ts) — the
     right-click rows and the RELATION ⚡ button, from the keyboard */
  { cat:'AGENT', label:'Reverse symbol with agent · quick', when:hasReverseSubject, run:function(){ const s = subjectAtCaret(); return s ? spawnReverse(s, 'quick') : undefined; } },
  { cat:'AGENT', label:'Reverse symbol with agent · deep',  when:hasReverseSubject, run:function(){ const s = subjectAtCaret(); return s ? spawnReverse(s, 'deep') : undefined; } },
  /* the two configurable areas (views/agents.ts): the endpoints, and the
     model/effort/caps behind the built-in reversing routine */
  { cat:'AGENT', label:'Reversing agent settings…', run:function(){ hook('openReverseSettings'); } },
  { cat:'AGENT', label:'LLM providers…',            run:function(){ hook('openProviders'); } },
  // MAP — close/delete need an open map; canvas actions need the map surface
  { cat:'MAP',   label:'Close map',    when:mapOpen, run:function(){ closeActiveMap(); } },
  { cat:'MAP',   label:'Delete map…',  when:mapOpen, run:function(){ deleteActiveMap(); } },
  { cat:'MAP',   label:'Zoom to fit',  kbd:'0', when:onMap, run:function(){ clickBtn('mz-fit'); } },
  { cat:'MAP',   label:'Add node…',    when:onMap, run:function(){ clickBtn('mapm-addnode'); } },
  { cat:'MAP',   label:'Add edge…',    when:onMap, run:function(){ clickBtn('mapm-addedge'); } },
  { cat:'MAP',   label:'Export poster (markdown)', when:onMap, run:function(){ clickBtn('mapm-export'); } },
  { cat:'MAP',   label:'Map types…',   when:onMap, run:function(){ clickBtn('types-btn'); } },
];

function runCmd(c: Command){
  try {
    const p = c.run() as Thenable;
    if (p && p.catch) p.catch(function(err){ console.warn('command', c.label, err); });
  } catch (err) { console.warn('command', c.label, err); }
}

/* palette rows from COMMANDS — index.html ships an EMPTY #pal-list */
function renderPal(){
  const list = document.getElementById('pal-list')!;
  list.innerHTML = '';
  COMMANDS.forEach(function(c, i){
    if (c.when && !c.when()) return;
    const it = document.createElement('div');
    it.className = 'pal-item';
    it.dataset.label = c.label;
    it.dataset.idx = String(i);                  /* original order for the empty query */
    it.innerHTML = '<span class="cat">' + escHtml(c.cat) + '</span><span class="plbl">' + escHtml(c.label) + '</span>' +
      (c.kbd ? '<kbd>' + escHtml(c.kbd) + '</kbd>' : '');
    it.addEventListener('click', function(){ closePal(); runCmd(c); });   // QA m4: click runs the action
    list.appendChild(it);
  });
  const n = document.getElementById('pal-count');
  if (n) n.textContent = COMMANDS.length + ' COMMANDS';          /* computed, never a fixture number */
}

let palSel = -1;
function palVisible(){ return palItems().filter(function(it){ return it.style.display !== 'none'; }); }
function palSelect(i: number){
  const rows = palVisible();
  palSel = rows.length ? Math.max(0, Math.min(rows.length - 1, i)) : -1;
  rows.forEach(function(r, j){ r.classList.toggle('sel', j === palSel); });
  if (rows[palSel]) rows[palSel].scrollIntoView({ block: 'nearest' });
}

function filterPal(q: string){
  q = q.trim().toLowerCase();
  const list = document.getElementById('pal-list')!;
  const scored: { it: HTMLElement; score: number }[] = [];
  palItems().forEach(function(it){
    const m = fuzzy(q, it.dataset.label!);
    if (!m){ it.style.display = 'none'; return; }
    it.style.display = '';
    it.querySelector('.plbl')!.innerHTML = highlight(it.dataset.label!, m.pos);
    scored.push({ it: it, score: m.score });
  });
  if (q){
    scored.sort(function(a, b){ return b.score - a.score; });
    scored.forEach(function(s){ list.appendChild(s.it); });     // re-append in rank order
  } else {
    palItems().sort(function(a, b){ return +a.dataset.idx! - +b.dataset.idx!; })
      .forEach(function(it){ list.appendChild(it); });          // original order
  }
  palSelect(0);                                                  /* ↑↓/↵ target the ranked list */
}

/* ==================================================================
   JUMP ANYWHERE — lightweight dropdown anchored to the navbar field
================================================================== */
/** one dropdown row: a command, a target chip, a map, a symbol */
interface JumpItem {
  label: string;
  kbd?: string;
  run: () => unknown;
}
/** the rows the dropdown renders, under their group header */
interface JumpGroup {
  name: string;
  items: JumpItem[];
}
/** a row that survived the query, with its fuzzy hit */
interface JumpMatch {
  it: JumpItem;
  m: FuzzyMatch | null;
}
/** the dropdown row element carries its action (the ↵ handler reads it back) */
interface JumpRow extends HTMLDivElement {
  _run?: () => unknown;
}

let jdrop: HTMLElement, jdropQ: HTMLInputElement, jdropList: HTMLElement, jsel = -1;

/* the G dropdown's static groups come from COMMANDS too (one table): VIEWS =
   the center selectors, ACTIONS = every other command whose `when` passes */
function cmdItem(c: Command): JumpItem { return { label: c.label, kbd: c.kbd, run: c.run }; }
function viewsGroup(): JumpGroup { return { name:'VIEWS', items: COMMANDS.filter(function(c){ return !!c.view; }).map(cmdItem) }; }
function actionsGroup(): JumpGroup { return { name:'ACTIONS', items: COMMANDS.filter(function(c){ return !c.view && (!c.when || c.when()); }).map(cmdItem) }; }
function staticGroups(){ return [viewsGroup(), actionsGroup()]; }
let liveGroups: JumpGroup[] | null = null;

let jgroupsGen = 0;

/* QA m8: TARGETS/MAPS/SYMBOLS built live on open — workspace.state, map.list,
   symbol.search on the active target. VIEWS/ACTIONS are static and work
   offline; there are NO fixture fallbacks for the live groups (they were
   mockup rows — gmail.apk, wallet.apk — that surfaced when the backend was
   slow, and the hardcoded MAPS entry carried the reload/focus ordering bug). */
async function refreshJgroups(){
  const gen = ++jgroupsGen;
  const groups = [viewsGroup()];
  const st = (await tryOp('workspace.state', {})).data;
  if (st && st.targets){
    groups.push({ name: 'TARGETS', items: st.targets.map(function(t){
      return { label: t.name + ' · ' + String(t.engine || '').toUpperCase(),
        run: function(){
          const c = document.querySelector<HTMLElement>('.targets .tchip[data-tid="' + t.id + '"]');
          if (c) c.click();
        } };
    }) });
  }
  const ml = (await tryOp('map.list', {})).data;
  if (ml && ml.length){
    groups.push({ name: 'MAPS', items: ml.map(function(m){
      return { label: m.id, run: async function(){
        /* persist the activation server-side (projmenu parity) — a G-opened map
           was otherwise forgotten, and a project round-trip restored the
           PREVIOUS map (map-r6 #8) */
        await tryOp('map.open', { id: m.id });
        /* reload BEFORE showCenter: updateCenter computes map-rel's visibility
           from S.mapSel and loadMap is what nulls it — the old order showed the
           NEW map's title over the OLD map's node body (opus map-r5 F5). It
           also RENDERS the chip, which showCenter('map') requires. */
        if (S.hooks.reloadMap) await S.hooks.reloadMap(m.id);
        showCenter('map');                       // the canvas owns arrows/Esc from here (focusMap)
      } };
    }) });
  }
  const ss = rowsOf<SymbolSearchHit>((await tryOpT('symbol.search', { query: 'a', limit: 500 })).data);   // the VIEWED target's classes — the rows jump there (fix-review r3 #5)
  if (ss && ss.length){
    const items = ss.filter(function(s){ return s.kind === 'class'; }).slice(0, 6).map(function(s){
      return { label: s.name || String(s.addr),
        run: function(){ jumpToCode(null, s.addr || s.name); } };   /* the viewed target: no chip switch */
    });
    if (items.length) groups.push({ name: 'SYMBOLS', items });
  }
  groups.push(actionsGroup());
  /* an @-query answered mid-refresh owns liveGroups (its live SYMBOLS) — a
     stale or superseded refresh must not clobber it with the 'a'-seed, and the
     live search won't re-fire for an unchanged query (map-r8 ii) */
  if (gen !== jgroupsGen || jdropLiveQ) return;
  liveGroups = groups;
}

let jdropLiveQ: string | null = null;

function renderJdrop(){
  const GROUPS = liveGroups || staticGroups();
  let q = jdropQ.value.trim().toLowerCase();
  let only: string | null = null, liveSym: string | null = null;
  if (q.charAt(0) === '>'){ only = 'ACTIONS'; q = q.slice(1).trim(); }
  else if (q.charAt(0) === '@'){ only = 'SYMBOLS'; q = q.slice(1).trim(); liveSym = q; }
  // @-queries search the engine LIVE — the static seed (6 classes from query
  // 'a') can't find real symbols on a big target
  if (liveSym && liveSym !== jdropLiveQ){
    jdropLiveQ = liveSym;
    const myQ = liveSym;
    tryOpT('symbol.search', { query: myQ, limit: 50 }).then(function(r){
      if (myQ !== jdropLiveQ) return;                       // a newer query owns the list
      const items = rowsOf<SymbolSearchHit>(r.data).slice(0, 8).map(function(s){
        return { label: (s.name || String(s.addr)) + (s.owner ? ' · ' + s.owner : ''),
          run: function(){ jumpToCode(null, s.addr || s.name); } };   /* the viewed target: no chip switch */
      });
      const rest = (liveGroups || staticGroups()).filter(function(g){ return g.name !== 'SYMBOLS'; });
      if (items.length) rest.unshift({ name: 'SYMBOLS', items });
      liveGroups = rest;
      renderJdrop();
    }).catch(function(){});
  }
  if (!liveSym) jdropLiveQ = null;
  jdropList.innerHTML = '';
  /* budget: the flat 8-row global let the leading groups (VIEWS 6 + TARGETS)
     starve MAPS/SYMBOLS/ACTIONS out entirely — header included (opus map-r5
     F16). One guaranteed row per non-empty group, then round-robin the rest
     up to a per-group cap, so every group stays reachable. */
  const rendered: { name: string; matches: JumpMatch[] }[] = [];
  GROUPS.forEach(function(g){
    if (only && g.name !== only) return;
    const matches = g.items
      .map(function(it){ return { it: it, m: fuzzy(q, it.label) }; })
      .filter(function(x){ return !!x.m; })
      .sort(function(a, b){ return b.m!.score - a.m!.score; });
    if (matches.length) rendered.push({ name: g.name, matches: matches });
  });
  const PER_GROUP = 4, TOTAL = 8;
  const alloc = rendered.map(function(){ return 1; });
  let rowsLeft = Math.max(0, TOTAL - rendered.length);
  for (let progressed = true; progressed && rowsLeft > 0;){
    progressed = false;
    rendered.forEach(function(r, i){
      if (rowsLeft > 0 && alloc[i] < Math.min(PER_GROUP, r.matches.length)){
        alloc[i]++;
        rowsLeft--;
        progressed = true;
      }
    });
  }
  /* second pass at full budget: a single matching group (an @-query's 8 live
     hits) may use ALL the rows — the per-group cap would waste half
     (map-r6 #7) */
  for (let progressed = true; progressed && rowsLeft > 0;){
    progressed = false;
    rendered.forEach(function(r, i){
      if (rowsLeft > 0 && alloc[i] < r.matches.length){ alloc[i]++; rowsLeft--; progressed = true; }
    });
  }
  rendered.forEach(function(r, gi){
    const gh = document.createElement('div');
    gh.className = 'jgrp';
    gh.textContent = r.name + ' · ' + r.matches.length;
    jdropList.appendChild(gh);
    r.matches.slice(0, alloc[gi]).forEach(function(x){
      const row = document.createElement('div') as JumpRow;
      row.className = 'jrow';
      row.innerHTML = '<span>' + highlight(x.it.label, x.m!.pos) + '</span>' +
        (x.it.kbd ? '<kbd>' + x.it.kbd + '</kbd>' : '');
      row.addEventListener('click', function(){ closeJdrop(); const p = x.it.run() as Thenable; if (p && p.catch) p.catch(function(err){ console.warn('jump run', err); }); });
      row._run = x.it.run;
      jdropList.appendChild(row);
    });
  });
  jsel = -1;
}
let jdropEsc: EscapeHandle | null = null;
function openJdrop(){
  jdrop.hidden = false;
  jdropQ.value = '';
  renderJdrop();
  refreshJgroups().then(renderJdrop).catch(function(){});
  jdropQ.focus();
  if (!jdropEsc) jdropEsc = pushEscape(closeJdrop, ESC.menu);
}
function closeJdrop(){
  jdrop.hidden = true;
  popEscape(jdropEsc);
  jdropEsc = null;
}

/* ==================================================================
   KEYS (global)
================================================================== */
const CTRL_VIEW: Record<string, CenterView> = { '1':'code', '2':'search', '3':'strings', '4':'runs', '5':'agents', '6':'device' };

/* the 1–9 an ⌥-chord names: the physical key first (see the ⌥1…9 handler) */
function altDigit(e: KeyboardEvent): number {
  const m = /^(?:Digit|Numpad)([1-9])$/.exec(e.code || '');
  if (m) return +m[1];
  return /^[1-9]$/.test(e.key) ? +e.key : 0;
}

function initKeys(){
  /* shortcuts sheet gate — WINDOW + capture, so it runs ahead of every
     document-capture shortcut listener (chrome.js ⌘⇧O, map.js #types-menu):
     with the sheet open ? / Esc close it and NOTHING else fires (N3), and Tab
     cannot walk focus out of the card (N1). */
  window.addEventListener('keydown', function(e){
    if (!keyhelpEl || keyhelpEl.hidden) return;
    if (e.key === 'Escape') return;         /* the escape dispatcher (hub.js) owns Esc */
    if (e.key === '?'){ e.preventDefault(); closeKeyhelp(); }
    else if (e.key === 'Tab'){ e.preventDefault(); keyhelpFocusHost()!.focus({ preventScroll: true }); }
    e.stopImmediatePropagation();
  }, true);
  document.addEventListener('keydown', function(e){
    const k = e.key.toLowerCase();
    const mod = e.metaKey || e.ctrlKey;
    if (mod && k === 'k'){ e.preventDefault(); if (modalUp() && !overlay.classList.contains('open')) return; overlay.classList.contains('open') ? closePal() : openPal(); return; }   // not over a dialog (settings r1 M6)
    if (mod && k === 'j'){ e.preventDefault(); hook('toggleDock'); return; }
    if (mod && k === 'b'){ e.preventDefault(); hook('toggleSide'); return; }
    if (e.ctrlKey && !e.metaKey && CTRL_VIEW[k]){
      e.preventDefault();
      selectCenter(CTRL_VIEW[k]);   // idempotent selector (P0.3): ⌃N for the current view does nothing; ⌃1 is the way back
      return;
    }
    if (mod && e.key === '['){ e.preventDefault(); hook('navBack'); return; }
    if (mod && e.key === ']'){ e.preventDefault(); hook('navFwd'); return; }
    /* ⌘O = open target (P0-5). Not ⌘⇧O — that toggles the projmenu (chrome.js,
       document capture, so it has already run by the time this sees the key).
       Always swallowed (the browser's own Open File dialog must never appear
       over the workbench), acted on only when no dialog owns the keyboard:
       the launcher re-entering its own show() would blank a half-typed path. */
    if (mod && k === 'o' && !e.shiftKey && !e.altKey){
      e.preventDefault();
      if (!modalUp()) openTargetLauncher();
      return;
    }
    /* Escape has ONE owner: the escape dispatcher in hub.js (the palette and
       jdrop register/unregister themselves in open/close). */
    const tag = e.target && (e.target as HTMLElement).tagName;
    const typing = tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' ||
      (e.target && (e.target as HTMLElement).isContentEditable) || overlay.classList.contains('open');
    if (e.key === '?' && !typing && !S.dzActive && !otherModalOpen()){ e.preventDefault(); openKeyhelp(); return; }
    if ((e.key === 'F2' || ((e.key === 'n' || e.key === 'N') && !e.metaKey && !e.ctrlKey && !e.altKey)) && !typing){ e.preventDefault(); hook('openRename'); return; }   // N renames (user 2026-09-19); F2 stays a quiet alias   // N renames (user 2026-09-19); F2 stays a quiet alias
    if (mod && k === 's' && !typing){ e.preventDefault(); hook('saveTarget'); return; }
    if (mod && k === 'z' && !typing){
      e.preventDefault();
      if (modalUp()) return;                                   // never behind a dialog (an open mapping preview would go stale under it)
      hook(e.shiftKey ? 'redoHistory' : 'undoHistory');       // ⇧⌘Z redoes the newest undone entry — the manual promised it (persona pass 2026-10-08)
      return;
    }
    if (k === ';' && !mod && !e.altKey && !typing){
      e.preventDefault();                         // no ';' leak into the comment field
      hook('openComment');
      return;
    }
    if (k === 't' && !mod && !e.altKey && !typing){
      e.preventDefault();                         // QA m5: swallow the triggering 't'
      hook('addTag');
      return;
    }
    if (mod && k === 'd' && !typing){
      e.preventDefault();
      hook('toggleBookmark');
      return;
    }
    if (k === '/' && !mod && !e.altKey && !typing){
      e.preventDefault();
      /* the open view's OWN filter when it has one — '/' used to focus the
         tree filter, a hidden input, from Strings/Runs/Device/Search (UI
         audit 2026-09-09) */
      const own: Record<string, string> = { strings: 'str-filter', runs: 'hit-filter', device: 'dev-filter', search: 'sq' };
      const el = own[S.center] ? document.getElementById(own[S.center]) as HTMLInputElement | null : null;
      if (el){ el.focus(); el.select(); return; }
      hook('focusTreeFilter');
      return;
    }
    if (k === 'g' && !mod && !e.altKey){
      if (!typing){
        e.preventDefault();                         // QA 15: no 'g' leak into the query
        openJdrop();
      }
      return;
    }
    /* ⌥1…⌥9 switch target (QA 14). The digit comes from e.code (Digit1 /
       Numpad1), not e.key: with Option held macOS reports the Option-layer
       glyph ('¡', '™', '£'…) in e.key, never the digit — the trap the ⌥W rule
       below documents — so the binding was dead on the platform the app is
       built for (review 2026-09-09 HIGH-3). e.key stays as the fallback for
       drivers and layouts that do report the plain digit. */
    /* the ⌥W guards apply here too (fix-review r1 #5): no Shift (⌥⇧1 is not
       ⌥1 — e.code is modifier-blind), no key-repeat (a held chord must not
       queue N overlapping target switches), not under a dialog */
    const digit = e.altKey && !mod && !e.shiftKey && !e.repeat && !typing && !modalUp() ? altDigit(e) : 0;
    if (digit){
      e.preventDefault();
      const chips = $all('.targets .tchip:not(#mapchip)');
      const n = digit - 1;
      if (chips[n]) chips[n].click();
      return;
    }
    /* ⌥W = close the viewed target (P0-5). e.code as well as e.key: with Option
       held macOS reports e.key as the Option-layer glyph ('∑'), never 'w' —
       the physical key is the only stable name there (Windows/Linux and the
       Playwright driver report the plain letter). !e.repeat — a held key must
       not close the NEXT target once the strip re-renders; the × handler's own
       busy guard covers the in-flight one. Gated like ⌘S (not while typing)
       and on modalUp: a 60s auto-saving close must not fire under a dialog
       that owns the keyboard. */
    if (e.altKey && !mod && !e.shiftKey && (e.code === 'KeyW' || k === 'w') && !e.repeat && !typing && !modalUp()){
      e.preventDefault();
      closeViewedTarget();
      return;
    }
    if (e.key === 'F9' && !typing){                 // QA 13: F9 toggles a BP on the caret symbol
      e.preventDefault();
      hook('toggleBp');
      return;
    }
    if (e.key === 'F12' && !typing){                // go-to-definition on the caret symbol (WORKBENCH_SPEC §7)
      e.preventDefault();
      hook('gotoDefAtCaret');
      return;
    }
    if (k === 'i' && !mod && !e.altKey && !e.shiftKey && !typing && S.center === 'code'){   // implementations
      e.preventDefault();
      hook('implementations');
      return;
    }
    if (k === 'o' && !mod && !e.altKey && !e.shiftKey && !typing && S.center === 'code'){   // outline (⌘⇧O is the project menu)
      e.preventDefault();
      hook('showOutline');
      return;
    }
    if (k === 'x' && !mod && !e.altKey && !typing){
      e.preventDefault();                         // no 'x' leak into the listing
      focusSelection();
      return;
    }
  });
}

export function initPalette(): void {
  overlay  = document.getElementById('overlay')!;
  palInput = document.getElementById('pal-input') as HTMLInputElement;
  palInput.addEventListener('input', function(){ filterPal(palInput.value); });
  /* the footer promised ↑↓ SELECT · ↵ EXECUTE; nothing handled them (P0.4) */
  palInput.addEventListener('keydown', function(e){
    if (e.key === 'ArrowDown'){ e.preventDefault(); palSelect(palSel + 1); }
    else if (e.key === 'ArrowUp'){ e.preventDefault(); palSelect(palSel - 1); }
    else if (e.key === 'Enter'){
      e.preventDefault();
      const row = palVisible()[palSel < 0 ? 0 : palSel];
      const c = row && COMMANDS[+row.dataset.idx!];
      if (c){ closePal(); runCmd(c); }
    }
  });
  renderPal();
  overlay.addEventListener('click', function(e){ if (e.target === overlay) closePal(); });

  keyhelpEl = document.getElementById('keyhelp')!;
  renderKeyhelp(keyhelpFocusHost(), COMMANDS);   /* sheet rows: KEYMAP + the COMMANDS groups */
  keyhelpEl.addEventListener('click', function(e){ if (e.target === keyhelpEl) closeKeyhelp(); });
  const hints = document.getElementById('hints-item');
  if (hints){
    /* N2: clicking HINTS must not take focus — otherwise it becomes prevFocus and
       closing the sheet parks focus on the ribbon, leaving the code keys dead.
       Keyboard reach (Tab) still focuses it; :focus-visible rings it. */
    hints.addEventListener('mousedown', function(e){ e.preventDefault(); });
    hints.addEventListener('click', openKeyhelp);
    hints.addEventListener('keydown', function(e){         /* role=button: Enter/Space activate */
      if (e.key === 'Enter' || e.key === ' '){ e.preventDefault(); openKeyhelp(); }
    });
  }

  jdrop = document.getElementById('jdrop')!;
  jdropQ = document.getElementById('jdrop-q') as HTMLInputElement;
  jdropList = document.getElementById('jdrop-list')!;

  document.getElementById('jumpbox')!.addEventListener('click', function(e){
    e.stopPropagation();
    jdrop.hidden ? openJdrop() : closeJdrop();
  });
  jdropQ.addEventListener('input', renderJdrop);
  jdropQ.addEventListener('keydown', function(e){
    const rows = $all<JumpRow>('.jrow', jdropList);
    function paint(){ rows.forEach(function(r, i){ r.classList.toggle('sel', i === jsel); }); }
    if (e.key === 'ArrowDown'){ e.preventDefault(); jsel = Math.min(rows.length - 1, jsel + 1); paint(); }
    else if (e.key === 'ArrowUp'){ e.preventDefault(); jsel = Math.max(0, jsel - 1); paint(); }
    else if (e.key === 'Enter'){
      e.preventDefault();
      const r = rows[jsel < 0 ? 0 : jsel];
      if (r){ closeJdrop(); const p = r._run!() as Thenable; if (p && p.catch) p.catch(function(err){ console.warn('jump run', err); }); }
    }
    /* Esc: the escape dispatcher closes the dropdown (registered by openJdrop) */
  });
  document.addEventListener('click', function(e){
    if (!jdrop.hidden && !(e.target as HTMLElement).closest('.jumpwrap')) closeJdrop();
  });

  initKeys();
}
