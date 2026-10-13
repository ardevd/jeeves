// relation.ts — RELATION pane: follows the caret symbol (xref.to / callees /
// impls, live from the active target's engine), focusSelection, dock widgets.
import { $, S, methodAt, escHtml as esc, tryOpT, $all } from './hub.js';
import type { CaretHit, RelationRow } from '../types.js';
import { activatePanel, showDock } from './chrome.js';

/* the symbol the pane is ABOUT (loadRelationFor's argument): CALLEES rows
   land inside its body, XREFS rows land on the reference to it */
let relSymbol: string | null = null;
let relName = '';
/** the pane's subject (the ⚡ REVERSE button's target — navigation.ts) */
export function relationSubject(): { symbol: string; name: string } | null {
  return relSymbol ? { symbol: relSymbol, name: relName } : null;
}
/** the subject dies with its target (app.ts showEmptyTarget): a stale one
 *  would spawn a mission for a class of the PREVIOUS target, pinned to the
 *  new one (fix-review reverse r2 R2-2) */
export function clearRelationSubject(): void { relSymbol = null; relName = ''; }
/* a landing in flight (landOnRow): the caret-follow that opening the host
   fires (loadSymbol0 → caretFollow) must not re-target the pane away from
   the subject whose row was clicked — the list the user is browsing stays */
let holding = 0;

/** a local-usage row (lineMode): a line inside the OPEN class, not a symbol */
interface LocalRow {
  line: number;
  preview: string;
}

/* Row label: jeb rows carry name+owner (+addr/method); mock rows name+kind. */
function rowLabel(x: RelationRow){
  return x.name || x.method || x.addr || x.from || '?';
}
function rowFile(x: RelationRow){
  return x.owner || x.kind || x.type || '';
}

/* Render xref.to / callees / impls results into the RELATION pane. */
export function renderRelation(xrefs: RelationRow[], callees: RelationRow[], impls: RelationRow[]): void {
  fill('#rel-xrefs', '#rel-xrefs-n', xrefs, false, 'xrefs');
  fill('#rel-callees', '#rel-callees-n', callees, false, 'callees');
  fill('#rel-impls', '#rel-impls-n', impls, false, 'impls');
}

/* the class part of a symbol handle: JEB `Lpkg/C;->m(...)` → `Lpkg/C;`, a dotted
   `pkg.C.member` → `pkg.C` (a lowercase / <init> last segment is a member — the
   tree's rule, code.ts methodFor), a class handle → itself */
function classOf(sym: string): string {
  if (sym.includes('->')) return sym.split('->')[0];
  if (sym.endsWith(';') || !sym.includes('.')) return sym;
  const last = sym.slice(sym.lastIndexOf('.') + 1);
  return /^[a-z<]/.test(last) ? sym.slice(0, sym.lastIndexOf('.')) : sym;
}

/* is `sym`'s class the listing on screen? (no reload for a same-class landing) */
function onScreen(sym: string): boolean {
  const cls = classOf(sym);
  return !!cls && (cls === S.currentSym.addr || cls === S.currentSym.fqcn || cls === S.currentFqcn);
}

/* the CALLEES / XREFS landing (user report 2026-09-09: "clicking on a Callee
   is expected to show the Callee WITHIN THE XREFING SYMBOL — the actual
   behavior jumps to the DEFINITION of the callee"). A callee row lands on the
   call inside the subject's body; an xref row opens the referencing method
   and lands on its reference to the subject. Either way the listing that
   holds the site is opened first (no reload when it is the one on screen),
   then the site is located client-side (code.ts gotoRefSite). No site in the
   listing — a body the decompiler folded ("[...]"), a synthetic bridge, a
   lambda — leaves the caret on the host method (loadSymbol parked it there)
   and says so in the section: a definition the row never promised is not a
   landing. Only a host that will not OPEN falls back to the row's own symbol. */
async function landOnRow(kind: 'callees' | 'xrefs', row: RelationRow, rowSym: string, subject: string | null, subjectName: string){
  const { loadSymbol, gotoRefSite } = await import('../views/code.js');
  const host = kind === 'callees' ? subject : rowSym;         // the method whose body holds the site
  const site: RelationRow = kind === 'callees' ? row : { addr: subject || undefined, name: subjectName };
  if (host){
    let opened = true;
    holding++;
    ++relGen;                                        // an in-flight relation load must not repaint over the held rows (r2 M1)
    try {
      if (!onScreen(host)){
        try { await loadSymbol(host); } catch (e) { opened = false; }   // the host would not open — the row's own symbol below
      }
      if (opened){
        if (gotoRefSite(host, site)) return;
        const what = kind === 'callees' ? 'call to ' + (row.name || rowSym) : 'reference to ' + subjectName;
        const where = kind === 'callees' ? subjectName : rowLabel(row);
        noteRow(kind, 'no ' + what + ' in the listing of ' + where + ' — body folded, synthetic, or not mapped to the text');
        return;
      }
    } finally { holding--; }
  }
  await openRowSymbol(loadSymbol, rowSym);
}

/* the pane's transient note (the strings/runs noteRow idiom): the first row
   of the section's list — the pane holds its subject through the landing, so
   nothing repaints the list under it; the next navigation's repaint (or 5 s)
   takes it away, one at a time */
let noteTimer = 0;
function noteRow(kind: 'callees' | 'xrefs', msg: string){
  const list = document.getElementById(kind === 'callees' ? 'rel-callees' : 'rel-xrefs');
  if (!list) return;
  const old = document.querySelector('.rel-note');
  if (old) old.remove();
  const n = document.createElement('div');
  n.className = 'xrow rel-note';
  n.innerHTML = '<span class="led o"></span><span></span>';
  const text = n.lastElementChild as HTMLElement;
  text.textContent = msg;
  text.title = msg;                                  // the row ellipsises at ~30 chars (r2 M2): the tooltip carries the reason
  list.insertBefore(n, list.firstChild);
  clearTimeout(noteTimer);
  noteTimer = setTimeout(function(){ n.remove(); }, 5000);
}

/* the row's own symbol (an IMPLEMENTATION, or the fallback above): rows arrive
   as addrs, sigs, or dotted Class.method names depending on engine — resolve
   in that order, then fall back to the owner class */
async function openRowSymbol(loadSymbol: (s: string) => Promise<unknown>, sym: string){
  try {
    await loadSymbol(sym);
  } catch (e) {
    // fallback: owner class — sig form splits at '->', dotted form at '.'
    let cls = sym;
    if (cls.includes('->')) cls = cls.split('->')[0];
    else if (cls.includes('.')) cls = cls.split('.')[0];
    if (cls && cls !== sym) {
      try { await loadSymbol(cls); } catch (e2) { /* unresolvable — stay */ }
    }
  }
}

function ledFor(x: RelationRow){
  if (x.actor === 'agent') return 'p';
  if (x.obfuscated) return 'o';
  return 'b';
}

/* one RELATION row (INSPECTOR section or the dock's full list): local-usage rows land on a
   LINE inside the loaded class; symbol rows resolve and load (CALLEES/XREFS on the site) */
function buildRelRow(x: RelationRow | LocalRow, lineMode: boolean | undefined, kind: 'xrefs' | 'callees' | 'impls' | undefined,
                     subject: string | null, subjectName: string, depth: number = 0): HTMLElement {
  const r = document.createElement('div');
  r.className = 'xrow';
  if (depth > 0) r.style.paddingLeft = (6 + depth * 12) + 'px';   // a tree level per expansion
  if (lineMode){
    const lr = x as LocalRow;
    r.innerHTML = '<span class="led b"></span><span>:' + lr.line + '  ' + esc(lr.preview) + '</span><span class="file"></span>';
    r.style.cursor = 'pointer';
    r.title = 'line ' + lr.line;
    r.addEventListener('click', async function(){
      const { gotoLine } = await import('../views/code.js');
      gotoLine(lr.line);
    });
    return r;
  }
  const xr = x as RelationRow;
  r.innerHTML = '<span class="led ' + ledFor(xr) + '"></span><span>' + esc(rowLabel(xr)) +
    '</span><span class="file">' + esc(rowFile(xr)) + '</span>';
  // D12: RELATION rows navigate. Rows carry addr/method (real targets) or
  // just name/kind (mock) — fall back all the way to the row label; 'from'
  // sites carry a +offset that must be stripped. CALLEES and XREFS rows land
  // on the SITE inside the referencing method (landOnRow); IMPLEMENTATIONS
  // open the implementation itself.
  const sym = xr.addr || xr.method || xr.sig || xr.name
      || (xr.from ? String(xr.from).split('+')[0] : null);
  if (sym){
    /* the row names its symbol for the context menu (navigation.ts openRelMenu) and the tree path */
    r.dataset.sym = String(sym);
    r.dataset.name = rowLabel(xr);
    r.dataset.kind = String(sym).includes('->') ? 'method' : 'class';
    r.style.cursor = 'pointer';
    r.title = kind === 'callees' ? 'call site in ' + (subjectName || 'the caret symbol') + ' · ' + String(sym)
           : kind === 'xrefs' ? 'reference in ' + String(sym) : String(sym);
    r.addEventListener('click', async function(ev){
      if ((ev.target as HTMLElement).closest('.led.xexp')) return;      // the dot is the + : its own control
      if (kind === 'callees' || kind === 'xrefs'){ await landOnRow(kind, xr, String(sym), subject, subjectName); return; }
      const { loadSymbol } = await import('../views/code.js');
      await openRowSymbol(loadSymbol, String(sym));
    });
    /* the tree (user 2026-09-20): on XREFS TO a row is a referencing method — its + lists who
       references THAT method; on CALLEES a row is a callee — its + lists what THAT one calls.
       Each level gets the same + again, so the reader goes as deep as they choose. The rows of
       a level land the same way their parents do (the parent is their subject). */
    if ((kind === 'callees' || kind === 'xrefs') && String(sym).includes('->')) addExpander(r, kind, String(sym), rowLabel(xr), depth);
  }
  return r;
}

/* The branch under a RELATION row whose + is open: the row itself, then every row shown beneath
   it — its level and any deeper levels the reader opened — in display order, each symbol once
   (user 2026-09-20: "A0 AND ALL THE 10 METHODS XREFING TO/FROM IT"). */
export function branchOf(row: HTMLElement): { symbol: string; name: string }[] {
  const out: { symbol: string; name: string }[] = [];
  const seen = new Set<string>();
  const add = function(el: HTMLElement){
    const sym = el.dataset.sym;
    if (!sym || seen.has(sym)) return;
    seen.add(sym);
    out.push({ symbol: sym, name: el.dataset.name || sym });
  };
  add(row);
  const kids = row.nextElementSibling;
  if (kids && kids.classList.contains('xkids'))
    kids.querySelectorAll<HTMLElement>('.xrow[data-sym]').forEach(add);   // nested levels are nested DOM: they come along
  return out;
}

const LEVEL_CAP = 200;
function addExpander(row: HTMLElement, kind: 'xrefs' | 'callees', sym: string, label: string, depth: number){
  /* the + lives IN the led's place (user 2026-09-20): the dot is the control — drawn over it on
     hover by CSS (::after, absolutely positioned), so the row's text never moves */
  const b = row.querySelector<HTMLElement>('.led');
  if (!b) return;
  b.classList.add('xexp');
  b.title = kind === 'xrefs' ? 'xrefs to ' + label + ' (who references it)' : 'callees of ' + label + ' (what it calls)';
  let kids: HTMLElement | null = null;
  let busy = false;
  const open = async function(){
    if (kids || busy) return;
    busy = true;
    row.classList.add('busy');
    const r = await tryOpT(kind === 'xrefs' ? 'xref.to' : 'callees', { symbol: sym, limit: LEVEL_CAP });
    busy = false;
    row.classList.remove('busy');
    kids = document.createElement('div');
    kids.className = 'xkids';
    if (r.error){
      kids.innerHTML = '<div class="xrow"><span class="led o"></span><span></span></div>';
      (kids.firstElementChild!.lastElementChild as HTMLElement).textContent = r.error.message || 'failed';
      (kids.firstElementChild as HTMLElement).style.paddingLeft = (6 + (depth + 1) * 12) + 'px';
    } else {
      const d = r.data as unknown;
      const list: RelationRow[] = Array.isArray(d) ? d as RelationRow[] : ((d as { rows?: RelationRow[] }) || {}).rows || [];
      const total = Array.isArray(d) ? list.length : Number(((d as { total?: number }) || {}).total || list.length);
      if (!list.length){
        kids.innerHTML = '<div class="xrow"><span class="led"></span><span></span></div>';
        (kids.firstElementChild!.lastElementChild as HTMLElement).textContent = kind === 'xrefs' ? 'no references to ' + label : label + ' calls nothing';
        (kids.firstElementChild as HTMLElement).style.paddingLeft = (6 + (depth + 1) * 12) + 'px';
      }
      list.forEach(function(x){ kids!.appendChild(buildRelRow(x, false, kind, sym, label, depth + 1)); });
      if (total > list.length){
        const more = document.createElement('div');
        more.className = 'xrow rel-more';
        more.style.paddingLeft = (6 + (depth + 1) * 12) + 'px';
        more.innerHTML = '<span class="led o"></span><span></span>';
        more.lastElementChild!.textContent = 'showing ' + list.length + ' of ' + total.toLocaleString('en-US');
        kids.appendChild(more);
      }
    }
    row.after(kids);
    row.classList.add('open');
  };
  (row as HTMLElement & { __xopen?: () => Promise<void> }).__xopen = open;   // the refresh re-opens levels by symbol
  b.addEventListener('click', function(ev){
    ev.stopPropagation();
    if (kids){ kids.remove(); kids = null; row.classList.remove('open'); return; }
    open();
  });
}

/* After an edit lands (an agent's rename, an accepted or reverted row): the pane re-fetches its
   subject's lists — names in the tree are stale otherwise (user 2026-09-20: "the agent didn't
   rename the classes": it had, the rows were old) — and re-opens every level that was open,
   by symbol, level by level. */
let refreshing = false;
export async function refreshRelationTree(): Promise<void> {
  if (!relSymbol || refreshing) return;
  const sections = ['#rel-xrefs', '#rel-callees', '#rel-impls'];
  /* an opened row is remembered by its MEMBER (the part after ->) and its depth: a class rename
     changes the owner half of every symbol under it, the member survives */
  const keyOf = function(r: HTMLElement){
    const sym = r.dataset.sym!; const d = (r.style.paddingLeft || '0').replace('px', '');
    const sibs = r.parentElement ? [...r.parentElement.children].filter(function(x){ return x.classList.contains('xrow') && (x as HTMLElement).dataset.sym; }) : [];
    return (sym.includes('->') ? sym.slice(sym.indexOf('->')) : sym) + '@' + d + '#' + sibs.indexOf(r);   // + its place in the level: the same member exists in sibling classes
  };
  const opened = new Set<string>();
  sections.forEach(function(sel){ $all(sel + ' .xrow.open[data-sym]').forEach(function(r){ opened.add(keyOf(r)); }); });
  refreshing = true;
  try {
    await loadRelationFor(relSymbol, relName);
    if (!opened.size) return;
    for (let pass = 0; pass < 12; pass++){        // each pass opens the levels the previous one revealed
      const todo = sections.flatMap(function(sel){ return $all(sel + ' .xrow[data-sym]:not(.open)'); })
        .filter(function(r){ return opened.has(keyOf(r)) && !!(r as HTMLElement & { __xopen?: () => Promise<void> }).__xopen; });
      if (!todo.length) break;
      for (const r of todo) await (r as HTMLElement & { __xopen?: () => Promise<void> }).__xopen!();
    }
  } finally { refreshing = false; }
}

/* the full list in the dock (audit row 8): every row of one section, same rows, same
   clicks — capped only by DOCK_CAP so a 7k-row xref list cannot freeze the page */
const DOCK_CAP = 3000;
let dockSubject: string | null = null;   // whose rows the dock list holds
/* a target or project switch empties the list (the rows would navigate into the wrong target) */
export function clearRelationDock(): void {
  const host = document.getElementById('dock-rel-rows');
  const head = document.getElementById('dock-rel-head');
  if (host) host.innerHTML = '';
  if (head) head.textContent = 'the INSPECTOR\'s RELATION sections cap at 300 rows — "open all in the dock" lists every row here';
  document.querySelector('.panel[data-panel="relall"]')?.removeAttribute('data-count');
  dockSubject = null;
}
function openAllInDock(kind: 'xrefs' | 'callees' | 'impls' | undefined, all: (RelationRow | LocalRow)[], lineMode: boolean | undefined,
                       subject: string | null, subjectName: string){
  const host = document.getElementById('dock-rel-rows');
  const head = document.getElementById('dock-rel-head');
  if (!host) return;
  host.innerHTML = '';
  const label = kind === 'callees' ? 'CALLEES of ' : kind === 'impls' ? 'IMPLEMENTATIONS of ' : 'XREFS of ';
  if (head) head.textContent = label + (subjectName || subject || '?') + ' · ' + all.length.toLocaleString('en-US') + (all.length > DOCK_CAP ? ' (first ' + DOCK_CAP.toLocaleString('en-US') + ')' : '');
  const frag = document.createDocumentFragment();
  all.slice(0, DOCK_CAP).forEach(function(x){ frag.appendChild(buildRelRow(x, lineMode, kind, subject, subjectName)); });
  host.appendChild(frag);
  dockSubject = subject;
  /* the dock tab shows this count too (persona LOW: the body printed · 1797 while the tab did not) */
  document.querySelector('.panel[data-panel="relall"]')?.setAttribute('data-count', String(Math.min(all.length, DOCK_CAP)));
  showDock();
  activatePanel('relall');
}

/* lineMode (folded fillLocal, GUI review finding 12): local-usage rows land
   on a LINE inside the already-loaded class; symbol rows resolve and load. */
function fill(sel: string, countSel: string, rows: RelationRow[] | LocalRow[], lineMode?: boolean, kind?: 'xrefs' | 'callees' | 'impls'){
  const subject = relSymbol, subjectName = relName;   // captured at render: the rows belong to THIS subject
  const el = $(sel);
  const cnt = $(countSel);
  if (cnt) cnt.textContent = String(rows ? rows.length : 0);
  if (!el) return;
  el.innerHTML = '';
  /* a real class has thousands of xrefs: rendered whole, the section buried
     CALLEES and IMPLEMENTATIONS thousands of rows down (UI audit 2026-09-09,
     explorer HIGH). Each section scrolls on its own (shell.css .selx) and
     stops at ROW_CAP rows with a "more" line that says what was left out —
     and opens the whole list in the dock (audit row 8). */
  const ROW_CAP = 300;
  /* the dock list is a SNAPSHOT of one subject: when the caret moved on, its head says so */
  const head = document.getElementById('dock-rel-head');
  if (head && dockSubject && subject && dockSubject !== subject && !head.textContent!.startsWith('⚠'))
    head.textContent = '⚠ stale — ' + head.textContent + ' · the caret moved on; open all again from RELATION';
  const all = (rows as (RelationRow | LocalRow)[] || []);
  const capped = all.length > ROW_CAP;
  all.slice(0, ROW_CAP).forEach(function(x){ el.appendChild(buildRelRow(x, lineMode, kind, subject, subjectName)); });
  if (capped){                                                   // after the rows: the first row stays a real one
    const more = document.createElement('div');
    more.className = 'xrow rel-more';
    more.innerHTML = '<span class="led o"></span><span></span>';
    more.lastElementChild!.textContent = 'showing ' + ROW_CAP.toLocaleString('en-US') + ' of ' + all.length.toLocaleString('en-US') + ' · open all in the dock ▸';
    (more.lastElementChild as HTMLElement).dataset.tip = 'capped at ' + ROW_CAP + ' rows so the sections below stay reachable — click to list every row in the dock\'s RELATION tab';
    more.style.cursor = 'pointer';
    more.addEventListener('click', function(){ openAllInDock(kind, all, lineMode, subject, subjectName); });
    el.appendChild(more);
  }
}

function loading(){
  ['#rel-xrefs-n', '#rel-callees-n', '#rel-impls-n'].forEach(function(s){
    const el = $(s);
    if (el) el.textContent = '…';
  });
}

/* pane ownership (GUI review finding 6): a slow relation load repainted over
   a newer local list — the stale response replaced it with the PREVIOUS
   symbol's rows under the local's header. Bumped by both renderers */
let relGen = 0;

/* RELATION follows the caret: query the three relation ops for a symbol. */
export async function loadRelationFor(symbol: string, displayName?: string): Promise<void> {
  if (holding) return;                                 // a landing holds the subject (landOnRow)
  const gen = ++relGen;
  relSymbol = String(symbol);
  /* the subject's NAME for the rows' landing notes: from the addr when it is a
     member (`->name(`) — a token's text is not a name for a lambda, where the
     token spans the whole expression — else the display name / last segment */
  relName = relSymbol.includes('->') ? relSymbol.slice(relSymbol.indexOf('->') + 2).split(/[(:]/)[0]
          : (displayName || relSymbol.split(/[;.]/).pop()!);
  const nameEl = document.getElementById('rel-name');
  const sigEl = document.getElementById('rel-sig');
  if (nameEl) nameEl.textContent = displayName || String(symbol).split(/[;.]/).pop()!;
  if (sigEl){ sigEl.textContent = String(symbol); sigEl.title = String(symbol); }   // QA 25: ellipsis tooltip
  loading();
  /* bind the VIEWED target (review: target-less ops resolve the server's
     activeTarget — a shared cursor ANY actor can yank mid-gesture; a reviewer
     probe once moved it and every relation op 404'd against the wrong apk,
     hidden as zeros by tryOp) */
  const [x, c, i] = await Promise.all([
    tryOpT('xref.to', { symbol: symbol }),
    tryOpT('callees', { symbol: symbol }),
    tryOpT('impls',  { symbol: symbol }),
  ]);
  if (gen !== relGen) return;                          // a newer click owns the pane
  /* a failed op is not "0 xrefs" (GUI review finding 7): tryOp swallows the
     error and x.data||[] rendered 0/0/0 — pixel-identical to a real symbol
     with no relations, and exactly how the Bug-A desync hid for weeks.
     All three failing means the symbol is not in this target — say so */
  if (x.error && c.error && i.error){
    fill('#rel-xrefs', '#rel-xrefs-n', []);
    fill('#rel-callees', '#rel-callees-n', []);
    fill('#rel-impls', '#rel-impls-n', []);
    const el = $('#rel-xrefs');
    if (el) el.innerHTML = '<div class="xrow"><span class="led o"></span><span>' +
      esc(x.error.message || 'not found in this target') + '</span></div>';
    return;
  }
  renderRelation(x.data || [], c.data || [], i.data || []);
}

/* Local variable: the backend gives locals NO addr (JebEngine classifyElement
   emits kind:'local' with a type only), so there is nothing to query — but
   every usage is already on screen. Compute the xref list client-side: all
   local-kind tokens with the same name inside the enclosing method's line
   range (S.javaMethods). Immune to the server-active desync by construction —
   no backend call at all. Rows land on the line, not on a symbol. */
export function renderLocalUsages(hit: CaretHit): void {
  if (holding) return;                                 // a landing holds the subject (landOnRow)
  ++relGen;   // own the pane synchronously — a slow stale relation load must not repaint over this (finding 6)
  relSymbol = null; relName = '';   // a local has no body for a callee to land in
  const nameEl = document.getElementById('rel-name');
  const sigEl = document.getElementById('rel-sig');
  const m = methodAt(S.javaMethods, hit.line!);   // the ONE resolver (finding 11)
  if (nameEl) nameEl.textContent = hit.name!;
  if (sigEl){ const sig = m ? String(m.signature || m.name) : 'local'; sigEl.textContent = sig; sigEl.title = sig; }
  const rows: LocalRow[] = [];
  const seen: Record<number, boolean> = {};
  (S.codeLines || []).forEach(function(L, idx){
    const line1 = idx + 1;
    /* finding 2: NO matching method range (abstract/interface methods emit
       line:null,endLine:null) NARROWS to the hit's own line — never widens
       to the whole file (live repro: 19 bogus cross-method "usages" under
       one header) */
    if (m ? (line1 < m.line! || line1 > (m.endLine || m.line!)) : line1 !== hit.line) return;
    L.tokens.forEach(function(t){
      if (t.el.classList.contains('nav') && t.el.getAttribute('data-kind') === 'local'
          && t.name === hit.name && !seen[line1]){
        seen[line1] = true;   // one row per line (finding 10: two uses on one line are ONE usage)
        rows.push({ line: line1, preview: (L.text || '').trim() });
      }
    });
  });
  fill('#rel-xrefs', '#rel-xrefs-n', rows, true);
  fill('#rel-callees', '#rel-callees-n', []);
  fill('#rel-impls', '#rel-impls-n', []);
}

/* X / FIND REFS → focus + flash the RELATION pane. */
export function focusSelection(): void {
  const sc = document.getElementById('sel-scroll');
  if (!sc) return;
  sc.scrollTop = 0;
  sc.classList.add('sel-flash');
  setTimeout(function(){ sc.classList.remove('sel-flash'); }, 700);
}
S.hooks.focusSelection = focusSelection;

/* dock: tags family filter chips are rendered live by ui/tags.js (M4). */
