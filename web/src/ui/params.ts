// params.ts — RETAINED METHODPARAMETERS: the dex metadata of the method under the caret as a SEPARATE
// list in the inspector, and the analyst's explicit mapping actions.
//
// What the list is: the method-level Ldalvik/annotation/MethodParameters; annotation exactly as the
// dex holds it — names and flags in encoded order, nulls kept, duplicates kept, its array lengths
// checked against the current prototype — and, apart from it, the debug_info parameter names and the
// arguments as the decompiler currently names them. What it is NOT: a mapping onto the arguments. An
// optimizer reorders prototypes without touching the annotation (WhatsApp 2.23.4.6 LX/0XS;-><init>
// keeps intentSender,intent on a prototype whose first argument is the Intent), and keeps names for
// arguments that no longer exist; the counts matching proves nothing. So nothing here applies a name
// by itself — opening a method, reading or copying the metadata changes nothing server-side.
//
// The analyst maps names explicitly:
//   APPLY TO ARGUMENT…        one retained name onto ONE chosen formal argument — chosen by position,
//                             with its current name and type shown; cross-position allowed; a collision
//                             with another argument's name offers the SWAP (both as one entry)
//   APPLY LIST BY POSITION…   every entry against the argument at its position, PREVIEWED first (every
//                             current → proposed, the problems), applied only after the analyst accepts
//                             that concrete list — as ONE undoable History entry; disabled when the
//                             counts differ or an argument's binding is unresolved
// Both go through param.rename / param.rename.bulk: validated whole (identifiers, keywords, duplicates,
// collisions with arguments and locals, an existing analyst name is said, null entries and rows already
// named so are no-ops), applied through the engine's own parameter binding (every use follows; types
// and order never change), recorded with provenance (the annotation entry, old/new names, the actor),
// undoable (⌘Z / ⇧⌘Z / HISTORY). Acceptance is the analyst's decision, not a verified correspondence —
// the dialog says so.
//
// Dialog state rules (persona pass + fix-review 2026-10-08): a dialog opens on a FRESH read of the
// method, takes focus (⌘Z and the other keys stay out — palette.ts checks modalUp), previews are
// sequenced (a late answer is dropped) and ACCEPT sends exactly the previewed list; a stale apply
// (E_BUSY: a name moved since the preview) reopens the dialog on fresh data with the picks kept.
import { S, ESC, pushEscape, popEscape, escHtml as esc, tryOpT, hook } from './hub.js';
import { notice } from './chrome.js';
import type { MethodParams, ParamCurrent, ParamPreview, ParamPreviewRow, ParamRenameResult } from '../types.js';

let current: MethodParams | null = null;    // the metadata painted (the method under the caret)
let currentMethod: string | null = null;
let inFlight = 0;
let collapsed = false;

const $ = (id: string) => document.getElementById(id);

type MappingRow = { index: number; name: string | null; annotationIndex?: number };

export function initParams(): void {
  $('mp-bulk')?.addEventListener('click', () => { if (current) void openBulk(current); });
  $('mp-copy')?.addEventListener('click', (ev) => { ev.stopPropagation(); if (current) void copyList(current); });
  $('mp-toggle')?.addEventListener('click', () => {
    collapsed = !collapsed;
    $('mp-sec')?.classList.toggle('collapsed', collapsed);
    const t = $('mp-toggle');
    if (t) { t.textContent = collapsed ? '▸' : '▾'; t.title = collapsed ? 'show the list' : 'collapse the list (the title line stays)'; }
  });
  S.hooks.refreshParams = (method) => { void refreshParams(method); };
  S.hooks.applyRetained = (local) => { if (current) void openSingle(current, undefined, local); };
}

/** the method under the caret (null = none): does it carry retained names? With a local's name: is
 *  that local one of the method's FORMAL ARGUMENTS (the context menu's row — not on a body local, not on
 *  `this`)? */
export function retainedFor(method: string | null | undefined, local?: string | null): boolean {
  if (!method || !current || currentMethod !== method || !hasList(current)) return false;
  if (local == null) return true;
  return (current.parameters || []).some(p => p.name === local);
}

/** the context menu is about to open on a method's token: have its metadata in first (the first
 *  right-click in a method used to come before the answer and show no row) */
export async function ensureParams(method: string | null | undefined): Promise<void> {
  if (!method || (method === currentMethod && current)) return;
  await refreshParams(method);
}

function hasList(d: MethodParams): boolean {
  const st = d.annotation?.status;
  return (st === 'present' || st === 'malformed') && !!(d.annotation?.names && d.annotation.names.length);
}

/** the caret moved: paint the metadata of the method it sits in, or hide the section */
export async function refreshParams(method: string | null | undefined): Promise<void> {
  const sec = $('mp-sec');
  if (!sec) return;
  if (!method) { inFlight++; sec.hidden = true; current = null; currentMethod = null; clearMarks(); return; }   // a pending answer for the method just left is dropped
  if (method === currentMethod && current) return;
  const ticket = ++inFlight;
  const r = await tryOpT('code.params', { symbol: method });
  if (ticket !== inFlight) return;                      // a later caret move superseded this answer
  if (r.error || !r.data) {
    current = null; currentMethod = null; clearMarks();
    /* a method whose metadata cannot be read is said, not silently blank; a non-method under the caret
       or an older server is simply no section */
    if (r.error && !/takes a METHOD|unknown op|not found/.test(r.error.message)) paintError(r.error.message);
    else sec.hidden = true;
    return;
  }
  current = r.data;
  currentMethod = method;
  paint(r.data);
}

/** after a mapping, an undo or a redo: the same method, re-read. The method is passed by the caller
 *  when it knows it: afterEdit re-renders the class, which invalidates the cache (currentMethod = null)
 *  while the caret follow's own refresh is still in flight — reading currentMethod here then hid the
 *  section until the next caret move (browser check 2026-10-08). */
export async function repaintParams(method?: string | null): Promise<void> {
  const m = method || currentMethod;
  currentMethod = null; current = null;
  await refreshParams(m);
}

/** the class was re-rendered (a rename, an undo, a reload): whatever was painted may be stale —
 *  the next caret follow re-reads (code.ts loadSymbol0) */
export function invalidateParams(): void { currentMethod = null; current = null; clearMarks(); }

function paintError(why: string): void {
  const sec = $('mp-sec'), list = $('mp-list'), foot = $('mp-foot'), head = $('mp-head'), n = $('mp-n');
  if (!sec || !list || !foot || !head || !n) return;
  sec.hidden = false;
  n.textContent = '';
  head.textContent = 'metadata unreadable: ' + why;
  list.innerHTML = ''; foot.innerHTML = '';
  const bulk = $('mp-bulk') as HTMLButtonElement | null;
  if (bulk) { bulk.disabled = true; bulk.dataset.tip = 'no metadata to map'; }
}

function paint(d: MethodParams): void {
  const sec = $('mp-sec'), list = $('mp-list'), foot = $('mp-foot'), head = $('mp-head'), n = $('mp-n');
  if (!sec || !list || !foot || !head || !n) return;
  const ann = d.annotation || { source: '', status: 'absent' };
  const dbg = d.debugInfo;
  const dbgNames = dbg && dbg.status === 'present' && dbg.names ? dbg.names : [];   // 'present' = at least one name; a table of null slots is 'empty'
  const show = hasList(d) || dbgNames.some(x => x != null);
  sec.hidden = !show;
  if (!show) { clearMarks(); return; }
  const names = ann.names || [];
  const ps = d.parameters || [];
  /* which arguments JEB currently names from the list by position — none, some (a partial mapping
     leaves the rest positional) or all; the per-argument rows and the Java marks say which */
  /* an older server (not restarted behind a fresh UI) has only the compatibility boolean: read it as "all" */
  const an = d.decompiler && (d.decompiler.annotationNamedArguments
    || (d.decompiler.appliesAnnotationByPosition === true ? { status: 'all' as const, count: ps.length, of: ps.length, indices: ps.map(p => p.index) } : null));
  const argLabel = (i: number) => { const p = ps.find(x => x.index === i); return '#' + i + (p && p.name ? ' ' + p.name : ''); };
  const positionalNote = an && an.status === 'all'
    ? ' JEB currently takes every argument\'s name from this list by position (those identifiers are marked in the Java view) — the engine\'s rendering, unverified.'
    : an && an.status === 'some'
      ? ' JEB currently takes ' + an.count + ' of ' + an.of + ' argument names from this list by position (argument' + (an.count === 1 ? ' ' : 's ') + an.indices.map(argLabel).join(', ') + ' — marked in the Java view) — the engine\'s rendering, unverified.'
      : '';
  n.textContent = hasList(d)
    ? names.length + ' name' + (names.length === 1 ? '' : 's') + ' / ' + ps.length + ' argument' + (ps.length === 1 ? '' : 's') + (ann.countMatchesPrototype ? ' — order unverified' : ' — count mismatch')
    : 'debug_info names only';
  head.textContent = hasList(d)
    ? 'Names a compiler stored for this method\'s parameters — possibly for an older signature. Clues, not verified argument names: the order is the annotation\'s, not a verified mapping onto the arguments.' + positionalNote
    : 'Parameter names from the dex\'s debug_info — a compiler\'s record, shown apart from the code.';
  list.innerHTML = '';
  names.forEach((nm, i) => {
    const row = document.createElement('div');
    row.className = 'mp-row';
    const ix = document.createElement('span'); ix.className = 'mp-i'; ix.textContent = '#' + i;
    const name = document.createElement('span'); name.className = 'mp-name' + (nm == null ? ' null' : '');
    name.textContent = nm == null ? 'null' : nm;                       // a metadata VALUE, shown verbatim — not a proposed variable name
    const flags = ann.accessFlagsDecoded && ann.accessFlagsDecoded[i];
    if (flags && flags.length) { const f = document.createElement('span'); f.className = 'mp-flags'; f.textContent = flags.join(','); name.appendChild(f); }
    name.title = nm == null ? 'a null entry — never applied, never erases a name' : 'annotation entry #' + i + ': "' + nm + '"' + (ann.notJavaIdentifiers && ann.notJavaIdentifiers.includes(nm) ? ' — not a usable Java identifier' : '');
    row.appendChild(ix); row.appendChild(name);
    if (nm != null && ps.length) {
      row.tabIndex = 0;
      const b = document.createElement('button'); b.className = 'mbtn'; b.textContent = 'APPLY TO ARGUMENT…';
      b.dataset.tip = 'choose the argument this name goes on — by position, with its current name and type; cross-position is allowed';
      b.addEventListener('click', (ev) => { ev.stopPropagation(); void openSingle(d, i); });
      row.addEventListener('keydown', (ev) => { if (ev.key === 'Enter' && ev.target === row){ ev.preventDefault(); void openSingle(d, i); } });
      row.appendChild(b);
    } else {
      const sp = document.createElement('span'); sp.className = 'mp-dim'; sp.textContent = nm == null ? '' : 'no argument to apply to';
      row.appendChild(sp);
    }
    list.appendChild(row);
  });
  const lines: { text: string; cls: string }[] = [];
  if (ann.status === 'malformed') lines.push({ text: 'malformed: ' + (ann.problems || []).join('; '), cls: 'bad' });
  if (ann.mismatch) lines.push({ text: ann.mismatch + ' — the list may describe an earlier prototype, or synthetic / mandated parameters', cls: 'bad' });
  if (ann.duplicates && ann.duplicates.length) lines.push({ text: 'duplicate names: ' + ann.duplicates.join(', '), cls: 'warn' });
  if (ann.notJavaIdentifiers && ann.notJavaIdentifiers.length) lines.push({ text: 'not Java identifiers: ' + ann.notJavaIdentifiers.join(', '), cls: 'warn' });
  if (ann.nullNames) lines.push({ text: ann.nullNames + ' null entr' + (ann.nullNames === 1 ? 'y' : 'ies'), cls: 'dim' });
  if (dbg && dbg.status === 'present') lines.push({ text: 'debug_info names (a second source): [' + (dbg.names || []).map(x => x == null ? 'null' : x).join(', ') + ']' + (d.sourcesAgree === false ? ' — differ from the annotation' : d.sourcesAgree ? ' — agree with the annotation' : ''), cls: d.sourcesAgree === false ? 'warn' : 'dim' });
  else if (dbg && dbg.status === 'empty') lines.push({ text: 'debug_info: a table of null slots — no names', cls: 'dim' });
  else if (dbg && dbg.status === 'no-code') lines.push({ text: 'no body: no debug info', cls: 'dim' });
  if (!ps.length) lines.push({ text: 'no formal arguments — the retained ' + (names.length === 1 ? 'name may describe' : 'names may describe') + ' an earlier prototype', cls: 'dim' });
  const maps = d.mappings || [];
  if (maps.length) lines.push({ text: maps.length + ' analyst mapping' + (maps.length === 1 ? '' : 's') + ' recorded (HISTORY · ⌘Z undoes · ⇧⌘Z redoes)', cls: 'dim' });
  foot.innerHTML = lines.map(l => '<div class="' + l.cls + '">' + esc(l.text) + '</div>').join('');
  if (ps.length) foot.appendChild(argumentsNow(ps));
  const bulk = $('mp-bulk') as HTMLButtonElement | null;
  if (bulk) {
    const why = bulkBlocker(d);
    bulk.disabled = !!why;
    bulk.dataset.tip = why ? why + ' — individual mappings stay possible' : 'preview every retained name against the argument at the same position; nothing applies until you accept the concrete list';
  }
  markEnginePositional(d);
}

/** the arguments as they stand now, one line each: position · type · name · who named it */
function argumentsNow(ps: ParamCurrent[]): HTMLElement {
  const t = document.createElement('table'); t.className = 'mp-args';
  t.innerHTML = '<thead><tr><th colspan="4">arguments now</th></tr></thead>';
  const tb = document.createElement('tbody');
  ps.forEach(p => {
    const tr = document.createElement('tr');
    tr.innerHTML = '<td class="dim">' + p.index + '</td><td class="dim">' + esc(shortType(p.type)) + '</td><td></td><td class="dim">' + esc(shortSource(p.nameSource)) + '</td>';
    (tr.children[2] as HTMLElement).textContent = p.name || '?';
    tb.appendChild(tr);
  });
  t.appendChild(tb);
  return t;
}

function bulkBlocker(d: MethodParams): string | null {
  const ann = d.annotation;
  if (!ann || !hasList(d)) return 'no retained names';
  if (ann.status === 'malformed') return 'the annotation is malformed';
  if (!ann.countMatchesPrototype) return 'the counts differ (' + (ann.mismatch || 'count mismatch') + ')';
  if ((d.parameters || []).some(p => !p.name)) return 'an argument\'s current binding is unresolved';
  if (!(ann.names || []).some(x => x != null && x !== '')) return 'every entry is null';
  return null;
}

function shortType(t: string | undefined): string {
  if (!t) return '?';
  if (t.startsWith('L') && t.endsWith(';')) return t.slice(1, -1).replace(/^.*\//, '').replace(/\$/g, '.');
  return ({ I: 'int', J: 'long', Z: 'boolean', B: 'byte', S: 'short', C: 'char', F: 'float', D: 'double', V: 'void' } as Record<string, string>)[t] || t;
}
function shortSource(s: string | undefined): string {
  if (!s) return 'unknown';
  if (s.startsWith('annotation')) return 'JEB: annotation by position';
  if (s === 'generic') return 'JEB: generic';
  if (s === 'engine') return 'JEB';
  return s;   // analyst | debug_info | unknown
}

/* ------------------------------------------------------------ the Java view's marks */

function clearMarks(): void {
  document.querySelectorAll<HTMLElement>('#code .mp-pos').forEach(e => { e.classList.remove('mp-pos'); e.removeAttribute('title'); });
}

/** identifiers JEB named from the annotation by position: marked in the method's lines, so the Java
 *  view never passes a retained list off as the arguments' names (persona pass 2026-10-08) */
function markEnginePositional(d: MethodParams): void {
  clearMarks();
  const pos = (d.parameters || []).filter(p => p.name && (p.nameSource || '').startsWith('annotation')).map(p => p.name as string);
  if (!pos.length) return;
  const ms = S.javaMethods || [];
  const jm = ms.find(x => x.addr === d.method || x.signature === d.method || (d.original_method !== undefined && x.addr === d.original_method));
  const from = jm && jm.line ? Number(jm.line) : 0;
  if (!from) return;
  const after = ms.map(x => Number(x.line || 0)).filter(l => l > from).sort((a, b) => a - b);
  const to = after.length ? after[0] : S.codeLines.length + 1;
  for (let ln = from; ln < to; ln++) {
    const cl = S.codeLines[ln - 1] ? S.codeLines[ln - 1].cl : null;
    if (!cl) continue;
    cl.querySelectorAll<HTMLElement>('.nav[data-kind="local"]').forEach(sp => {
      if (!pos.includes(sp.textContent || '')) return;
      sp.classList.add('mp-pos');
      sp.title = 'named by JEB from the retained MethodParameters list, by position — unverified; the inspector maps names explicitly';
    });
  }
}

/* ------------------------------------------------------------ copy */

async function copyList(d: MethodParams): Promise<void> {
  const ann = d.annotation || { source: '', status: 'absent', names: [] as (string | null)[] };
  const names = ann.names || [];
  const ps = d.parameters || [];
  const lines = [
    'Retained MethodParameters — argument mapping unverified',
    'method: ' + d.method + (d.original_method && d.original_method !== d.method ? ' (dex: ' + d.original_method + ')' : ''),
    'annotation (encoded order): ' + names.map((nm, i) => '#' + i + ' ' + (nm == null ? 'null' : nm) + (((ann.accessFlagsDecoded || [])[i]) || []).map(f => ' [' + f + ']').join('')).join(', '),
    'counts: ' + names.length + ' names / ' + ps.length + ' arguments' + (ann.countMatchesPrototype ? ' (a match — proves nothing about the order)' : ' (mismatch: ' + (ann.mismatch || '') + ')'),
    'debug_info: ' + (d.debugInfo ? d.debugInfo.status + (d.debugInfo.names ? ' ' + JSON.stringify(d.debugInfo.names) : '') : 'n/a'),
    'arguments now: ' + ps.map(p => p.index + ' ' + shortType(p.type) + ' ' + (p.name || '?') + ' (' + shortSource(p.nameSource) + ')').join('; '),
    'analyst mappings: ' + ((d.mappings || []).length ? (d.mappings || []).map(m => (m.id || '?') + ' ' + JSON.stringify(m.changes || [])).join('; ') : 'none'),
  ];
  try { await navigator.clipboard.writeText(lines.join('\n')); notice('retained MethodParameters copied with their provenance and status'); }
  catch { notice('copy failed — the browser refused clipboard access', { err: true }); }
}

/* ------------------------------------------------------------ the dialogs */

let escHandle: ReturnType<typeof pushEscape> | null = null;

function openModal(title: string, sym: string): { body: HTMLElement; ok: HTMLButtonElement; err: HTMLElement; close: () => void } {
  const modal = $('mpmodal')!, body = $('mpm-body')!, ok = $('mpm-ok') as HTMLButtonElement, err = $('mpm-err')!, cancel = $('mpm-cancel') as HTMLButtonElement;
  $('mpm-title')!.textContent = title;
  $('mpm-sym')!.textContent = sym;
  body.innerHTML = '';
  err.textContent = '';
  ok.disabled = true;
  ok.textContent = 'APPLY';
  modal.hidden = false;
  modal.tabIndex = -1;
  modal.focus({ preventScroll: true });                 // the keys stay in the dialog (⌘Z outside is refused by palette.ts while it is up)
  const onKey = (ev: KeyboardEvent) => {
    if (ev.key === 'Enter' && !ok.disabled && (ev.target as HTMLElement).tagName !== 'SELECT' && (ev.target as HTMLElement).tagName !== 'BUTTON'){ ev.preventDefault(); ok.click(); }
  };
  modal.addEventListener('keydown', onKey);
  const close = () => {
    modal.hidden = true;
    ok.onclick = null; cancel.onclick = null;
    modal.removeEventListener('keydown', onKey);
    if (escHandle) { popEscape(escHandle); escHandle = null; }
    document.getElementById('code')?.focus({ preventScroll: true });
  };
  escHandle = pushEscape(() => { if (modal.hidden) return false; close(); return true; }, ESC.modal);
  cancel.onclick = close;
  return { body, ok, err, close };
}

/** a dialog opens on a FRESH read of the method — never on the rail's cache */
async function fresh(method: string): Promise<MethodParams | null> {
  const r = await tryOpT('code.params', { symbol: method });
  if (r.error || !r.data) { notice('the method\'s metadata could not be re-read: ' + (r.error ? r.error.message : 'no data'), { err: true }); return null; }
  current = r.data; currentMethod = method;
  paint(r.data);
  return r.data;
}

/** a table of the method's arguments to pick ONE destination from: position, current name (source), type */
function argumentTable(ps: ParamCurrent[], selected: number, onPick: (i: number) => void): HTMLTableElement {
  const t = document.createElement('table'); t.className = 'mp-table';
  t.innerHTML = '<thead><tr><th>pos</th><th>type</th><th>current name</th><th>named by</th></tr></thead>';
  const tb = document.createElement('tbody');
  ps.forEach(p => {
    const tr = document.createElement('tr'); tr.className = 'pick' + (p.index === selected ? ' sel' : '');
    tr.innerHTML = '<td class="dim">' + p.index + '</td><td>' + esc(shortType(p.type)) + '</td><td></td><td class="dim">' + esc(shortSource(p.nameSource)) + '</td>';
    (tr.children[2] as HTMLElement).textContent = p.name || '?';
    tr.addEventListener('click', () => { onPick(p.index); tb.querySelectorAll('tr').forEach(r => r.classList.toggle('sel', r === tr)); });
    tb.appendChild(tr);
  });
  t.appendChild(tb);
  return t;
}

function expectCurrentOf(ps: ParamCurrent[]): { index: number; name: string | undefined }[] { return ps.map(p => ({ index: p.index, name: p.name })); }

/** the server refused because a name moved since the preview (E_BUSY "… as previewed" / "changed since the preview") */
function stale(message: string): boolean { return /as previewed|since the preview/.test(message); }

/** APPLY RETAINED NAME TO ARGUMENT: pick the retained entry (pre-picked from its row) and the destination
 *  argument; a collision with another argument's current name offers the SWAP as one atomic entry */
async function openSingle(d0: MethodParams, annotationIndex?: number, preselectLocal?: string, picks?: { entry: number; arg: number }): Promise<void> {
  const fd = await fresh(d0.method);
  if (!fd) return;
  const d: MethodParams = fd;                                                 // non-null for the closures below (a hoisted function does not see the guard)
  const names = d.annotation?.names || [];
  const ps = d.parameters || [];
  if (!ps.length) { notice('this method has no formal arguments — the retained names may describe an earlier prototype'); return; }
  const m = openModal('APPLY RETAINED NAME TO ARGUMENT', d.method);
  let pickedEntry = picks ? picks.entry : (annotationIndex ?? -1);
  let pickedArg = picks ? picks.arg : preselectLocal ? (ps.find(p => p.name === preselectLocal)?.index ?? -1) : -1;
  const entryBox = document.createElement('div');
  const entryTable = document.createElement('table'); entryTable.className = 'mp-table';
  entryTable.innerHTML = '<thead><tr><th>entry</th><th>retained name</th><th>flags</th></tr></thead>';
  const etb = document.createElement('tbody');
  names.forEach((nm, i) => {
    if (nm == null) return;
    const tr = document.createElement('tr'); tr.className = 'pick' + (i === pickedEntry ? ' sel' : '');
    tr.innerHTML = '<td class="dim">#' + i + '</td><td></td><td class="dim">' + esc(((d.annotation?.accessFlagsDecoded || [])[i] || []).join(',')) + '</td>';
    (tr.children[1] as HTMLElement).textContent = nm;
    tr.addEventListener('click', () => { pickedEntry = i; etb.querySelectorAll('tr').forEach(r => r.classList.toggle('sel', r === tr)); void preview(); });
    etb.appendChild(tr);
  });
  entryTable.appendChild(etb);
  const h1 = document.createElement('div'); h1.className = 'crs-sec'; h1.textContent = 'RETAINED NAME (annotation entry — its number is not an argument position)';
  const h2 = document.createElement('div'); h2.className = 'crs-sec'; h2.textContent = 'DESTINATION ARGUMENT (by position; any position)';
  const preview_ = document.createElement('div'); preview_.className = 'crs-note';
  const swapBtn = document.createElement('button'); swapBtn.className = 'mbtn mp-swap'; swapBtn.hidden = true;
  entryBox.appendChild(h1); entryBox.appendChild(entryTable); entryBox.appendChild(h2);
  entryBox.appendChild(argumentTable(ps, pickedArg, i => { pickedArg = i; void preview(); }));
  entryBox.appendChild(preview_);
  entryBox.appendChild(swapBtn);
  m.body.appendChild(entryBox);
  let seq = 0;
  let lastPreview: { pv: ParamPreview; entry: number; arg: number } | null = null;
  const finish = async (what: string) => {
    m.close(); notice(what);
    try { await hook('afterEdit'); } catch (e) { notice('applied and recorded — the view could not be refreshed: ' + String((e as Error).message || e), { err: true }); }
    await repaintParams(d.method);
  };
  async function preview(): Promise<void> {
    const my = ++seq;
    m.ok.disabled = true; m.err.textContent = ''; swapBtn.hidden = true; lastPreview = null;
    if (pickedEntry < 0 || pickedArg < 0) { preview_.textContent = pickedEntry < 0 ? 'pick a retained name' : 'pick the destination argument'; return; }
    const entry = pickedEntry, arg = pickedArg, name = names[entry] as string;
    const r = await tryOpT('param.rename.bulk', { symbol: d.method, preview: true, mapping: [{ index: arg, name, annotationIndex: entry }] });
    if (my !== seq) return;                                                // a later pick superseded this answer
    if (r.error) { m.err.textContent = r.error.message; return; }
    const pv = r.data as ParamPreview;
    const row: ParamPreviewRow = (pv.rows || [])[0] || {};
    preview_.textContent = 'argument ' + arg + ' (' + shortType(row.type) + '): ' + (row.currentName || '?') + ' → ' + name
      + (row.problem ? '\n✗ ' + row.problem : row.noop ? '\n' + row.noop : row.adopts ? '\n' + row.adopts : '') + (row.overwrites ? '\n! ' + row.overwrites : '')
      + '\nentry #' + entry + ' onto position ' + arg + (entry === arg ? ' (same position — still unverified)' : ' (cross-position)');
    if (row.collidesWith != null && row.collidesWith !== arg) {
      /* the other argument holds this name: the resolution the message asks for, as one atomic entry */
      const other = row.collidesWith;
      const mine = ps.find(p => p.index === arg), theirs = ps.find(p => p.index === other);
      const back = mine && mine.name ? mine.name : null;
      swapBtn.hidden = !back;
      swapBtn.textContent = 'SWAP: argument ' + arg + ' ← ' + name + ', argument ' + other + ' ← ' + (back || '?') + (theirs ? ' (now ' + (theirs.name || '?') + ')' : '');
      swapBtn.onclick = async () => {
        if (!back) return;
        swapBtn.disabled = true; m.err.textContent = '';
        const backEntry = names.indexOf(back);
        const pair: MappingRow[] = [{ index: arg, name, annotationIndex: entry }, backEntry >= 0 ? { index: other, name: back, annotationIndex: backEntry } : { index: other, name: back }];
        const p2 = await tryOpT('param.rename.bulk', { symbol: d.method, preview: true, mapping: pair });
        const pv2 = p2.data as ParamPreview | undefined;
        if (p2.error || !pv2 || !pv2.applicable) { m.err.textContent = p2.error ? p2.error.message : 'the swap cannot apply: ' + ((pv2 && pv2.problems) || []).join('; '); swapBtn.disabled = false; return; }
        const r2 = await tryOpT('param.rename.bulk', { symbol: d.method, mapping: pair, expectCurrent: expectCurrentOf(ps) });
        if (r2.error) {
          if (stale(r2.error.message)) { m.close(); notice(r2.error.message + ' — re-read; your picks are kept', { err: true }); void openSingle(d, undefined, undefined, { entry, arg }); return; }
          m.err.textContent = r2.error.message; swapBtn.disabled = false; return;
        }
        await finish('arguments ' + arg + ' and ' + other + ' swapped: ' + name + ' ↔ ' + back + ' — your mapping, one HISTORY entry (⌘Z undoes it whole)');
      };
    }
    lastPreview = { pv, entry, arg };
    m.ok.disabled = !pv.applicable;
  }
  m.ok.onclick = async () => {
    if (!lastPreview || !lastPreview.pv.applicable) return;
    const { entry, arg } = lastPreview;                                      // exactly what was previewed
    m.ok.disabled = true;
    const r = await tryOpT('param.rename', { symbol: d.method, index: arg, name: names[entry], annotationIndex: entry, expectCurrent: expectCurrentOf(ps) });
    if (r.error) {
      if (stale(r.error.message)) { m.close(); notice(r.error.message + ' — re-read; your picks are kept', { err: true }); void openSingle(d, undefined, undefined, { entry, arg }); return; }
      m.err.textContent = r.error.message; m.ok.disabled = false; return;
    }
    await finish('argument ' + arg + ' named ' + names[entry] + ' — your mapping, recorded in HISTORY (⌘Z undoes)');
  };
  void preview();
}

/** APPLY LIST BY POSITION: every argument's row starts with the entry at ITS position (the naive
 *  reading); a picker on each row lets the analyst take another entry instead, or keep the current
 *  name — so a swap (the 0XS correction) is one atomic acceptance too. Every change re-asks the
 *  server's preview; nothing applies until ACCEPT, and only exactly the previewed list. */
async function openBulk(d0: MethodParams, choice0?: number[]): Promise<void> {
  const fd = await fresh(d0.method);
  if (!fd) return;
  const d: MethodParams = fd;
  const names = d.annotation?.names || [];
  const ps = d.parameters || [];
  const m = openModal('APPLY LIST BY POSITION', d.method);
  /* the choice per argument position: an annotation entry index, or -1 = keep the current name */
  const choice: number[] = choice0 && choice0.length === ps.length ? choice0.slice() : ps.map(p => (p.index < names.length && names[p.index] != null ? p.index : -1));
  const t = document.createElement('table'); t.className = 'mp-table';
  t.innerHTML = '<thead><tr><th>pos</th><th>type</th><th>current</th><th></th><th>proposed (annotation entry)</th><th>note</th></tr></thead>';
  const tb = document.createElement('tbody');
  const noteCells: HTMLTableCellElement[] = [];
  const sels: HTMLSelectElement[] = [];
  ps.forEach(p => {
    const tr = document.createElement('tr');
    const cur = document.createElement('td'); cur.textContent = p.name || '?';
    const prop = document.createElement('td');
    const sel = document.createElement('select'); sel.className = 'mp-pick'; sel.dataset.pos = String(p.index);
    const keep = document.createElement('option'); keep.value = '-1'; keep.textContent = '— keep ' + (p.name || '?'); sel.appendChild(keep);
    names.forEach((nm, i) => { if (nm == null) return; const o = document.createElement('option'); o.value = String(i); o.textContent = '#' + i + ': ' + nm + (i === p.index ? ' (same position)' : ''); sel.appendChild(o); });
    sel.value = String(choice[p.index]);
    sel.addEventListener('change', () => { choice[p.index] = parseInt(sel.value, 10); void preview(); });
    sels.push(sel);
    prop.appendChild(sel);
    const note = document.createElement('td'); note.className = 'dim'; noteCells.push(note);
    tr.innerHTML = '<td class="dim">' + p.index + '</td><td>' + esc(shortType(p.type)) + '</td>';
    tr.appendChild(cur); const arrow = document.createElement('td'); arrow.className = 'arrow'; arrow.textContent = '→'; tr.appendChild(arrow); tr.appendChild(prop); tr.appendChild(note);
    tb.appendChild(tr);
  });
  t.appendChild(tb);
  m.body.appendChild(t);
  const foot = document.createElement('div'); foot.className = 'crs-note';
  m.body.appendChild(foot);
  m.ok.textContent = 'ACCEPT AND APPLY';
  let seq = 0;
  let lastPreview: { pv: ParamPreview; mapping: MappingRow[] } | null = null;
  async function preview(): Promise<void> {
    const my = ++seq;
    m.ok.disabled = true; m.err.textContent = ''; lastPreview = null;
    const mapping: MappingRow[] = [];
    choice.forEach((c, pos) => { if (c >= 0) mapping.push({ index: pos, name: names[c], annotationIndex: c }); });
    noteCells.forEach(n => { n.textContent = ''; n.className = 'dim'; });
    if (!mapping.length) { foot.textContent = 'nothing chosen — every argument keeps its name'; return; }
    sels.forEach(s => { s.disabled = true; });                              // one preview at a time: the answer belongs to these picks
    const r = await tryOpT('param.rename.bulk', { symbol: d.method, preview: true, mapping });
    sels.forEach(s => { s.disabled = false; });
    if (my !== seq) return;
    if (r.error) { m.err.textContent = r.error.message; return; }
    const pv = r.data as ParamPreview;
    (pv.rows || []).forEach((row: ParamPreviewRow) => {
      const pos = row.position ?? row.index;
      const cell = noteCells[pos as number];
      if (!cell) return;
      cell.textContent = row.problem || row.noop || row.adopts || row.overwrites || 'renames';
      cell.className = row.problem ? 'bad' : row.overwrites ? 'warn' : 'dim';
    });
    const crossed = mapping.filter(x => x.annotationIndex !== x.index).length;
    const adopted = (pv.rows || []).filter(r => r.adopts && !r.problem).length;
    const k = pv.effective || 0;
    foot.textContent = (pv.applicable
        ? k + (k === 1 ? ' argument would take the proposed name' : ' arguments would take the proposed names') + (crossed ? ' (' + crossed + ' cross-position)' : ' by position') + (adopted ? ' — ' + adopted + ' adopted from JEB\'s pick as yours' : '') + ' — '
        : (pv.why ? pv.why : 'cannot apply: ' + (pv.problems || []).join('; ')) + ' — ')
      + 'the annotation\'s order is unverified even where the counts match; accepting is your decision, recorded as such, undone in one step.';
    lastPreview = { pv, mapping };
    m.ok.disabled = !pv.applicable;
  }
  m.ok.onclick = async () => {
    if (!lastPreview || !lastPreview.pv.applicable) return;
    const mapping = lastPreview.mapping;                                      // exactly the previewed list
    m.ok.disabled = true;
    const r2 = await tryOpT('param.rename.bulk', { symbol: d.method, mapping, expectCurrent: expectCurrentOf(ps) });
    if (r2.error) {
      if (stale(r2.error.message)) { m.close(); notice(r2.error.message + ' — re-read; your picks are kept', { err: true }); void openBulk(d, choice); return; }
      m.err.textContent = r2.error.message; m.ok.disabled = false; return;
    }
    const res = r2.data as ParamRenameResult;
    const k = (res.changes || []).length;
    m.close();
    notice(k + (k === 1 ? ' argument renamed' : ' arguments renamed') + ' — your mapping, one HISTORY entry (⌘Z undoes it whole)');
    try { await hook('afterEdit'); } catch (e) { notice('applied and recorded — the view could not be refreshed: ' + String((e as Error).message || e), { err: true }); }
    await repaintParams(d.method);
  };
  void preview();
}
