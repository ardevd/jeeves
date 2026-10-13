// navigation.ts — unified per-target location history (WORKBENCH_SPEC §6.1):
// every navigation pushes a location; ‹/›, ⌘[/⌘], Esc (code view) walk it;
// chevron dropdown lists recent entries. Also dblclick → go to definition (QA 8)
// and the right-click symbol menu for the code panes + symbol tree (P0.2).
import { $all, S, hook, pushEscape, ESC, tryOpT, methodAt } from './hub.js';
import { loadSymbol, symbolAtCaret, editSymbol, followIdentityChange } from '../views/code.js';
import { placeCaretAt } from './caret.js';
import { loadRelationFor, focusSelection, relationSubject, branchOf } from './relation.js';
import { COMMANDS } from './palette.js';
import { notice } from './chrome.js';
import { retainedFor, ensureParams } from './params.js';
import { DEPTHS, depthTip, reverseLabel, spawnReverse, spawnReverseChain, spawnAnnotateCall, subjectOfHit, subjectOfHandle, subjectAtCaret } from './reverse.js';
import type { ReverseSubject } from './reverse.js';
import type { CaretHit } from '../types.js';

/** one visited location: the symbol handle, its display name, its line */
interface NavLoc {
  symbol: string;
  simple: string;
  line: number | null;
}
/** one target's history: the locations and the cursor into them */
interface NavStack {
  stack: NavLoc[];
  idx: number;
  navigating?: boolean;
}
/** an inline choice on a row (the reverse row's quick | deep pills): its own
 *  action; the row's own `run` is the default — what ↵ does */
interface MenuChoice {
  label: string;
  tip?: string;
  dflt?: boolean;
  run: () => unknown;
}
/** a context-menu row ('-' is a separator) */
interface MenuItem {
  label: string;
  kbd?: string;
  tip?: string;
  choices?: MenuChoice[];
  run: () => unknown;
}

const stacks: Record<string, NavStack> = {};          // tid -> { stack: [{symbol, simple, line}], idx }
let curTid: string | null = null;
let wired = false;

export function setNavTarget(tid: string | null): void {
  curTid = tid;
  if (tid && !stacks[tid]) stacks[tid] = { stack: [], idx: -1 };
  paint();
}

function hbtns(){ return $all('.navbar .hbtn'); }

function paint(){
  const btns = hbtns();
  if (btns.length < 2) return;
  const s = stacks[curTid!] || { stack: [], idx: -1 };
  btns[0].classList.toggle('off', s.idx <= 0);
  btns[1].classList.toggle('off', s.idx >= s.stack.length - 1);
}

/** Push a location (called from loadSymbol / gotoLine after every navigation). */
export function navPush(symbol: string, simple: string, line?: number | null): void {
  if (!curTid) return;
  const s = stacks[curTid];
  if (!s || s.navigating) return;          // history traversal / state restore: no re-entrant push
  s.stack.length = s.idx + 1;                      // a new navigation drops the forward tail
  const top = s.stack[s.idx];
  if (top && top.symbol === symbol && (top.line || 0) === (line || 0)) { paint(); return; }
  /* QA p5: same class under two spellings (boot FQCN vs tree short name) is ONE
     location — update the top entry's line instead of stacking a duplicate. The SAME spelling at
     another line is a jump inside the class (go to definition): its own entry, so ‹ comes back
     (fix-review 3 N2: with every landing keyed on the class's original signature this rule ate them) */
  if (top && top.symbol !== symbol && top.simple === simple &&
      (String(symbol).endsWith(String(top.symbol)) || String(top.symbol).endsWith(String(symbol)))){
    top.symbol = symbol;
    top.line = line || null;
    paint();
    return;
  }
  s.stack.push({ symbol, simple, line: line || null });
  s.idx = s.stack.length - 1;
  if (s.stack.length > 50) { s.stack.shift(); s.idx--; }
  paint();
}

/** A class changed identity (a rename, a restoration): the ‹ › entries that hold one of its old
 *  handles follow, or going back would ask the server for a class that no longer answers to that
 *  name (a restored original makes the analyst's addr unresolvable). */
export function navRemap(oldSymbols: string[], newSymbol: string, newSimple: string): void {
  if (!curTid) return;
  const s = stacks[curTid];
  if (!s) return;
  let changed = false;
  for (const e of s.stack){
    if (oldSymbols.indexOf(e.symbol) < 0) continue;
    e.symbol = newSymbol;
    e.simple = newSimple;
    changed = true;
  }
  if (changed) paint();
}

/** Run fn() with pushes suppressed (re-entrancy guard). Used by go() — otherwise
 *  Back/Forward re-enter navPush via loadSymbol and truncate the forward tail —
 *  and by per-target view-state restore, which replays state rather than navigating. */
export async function navGuard<T>(fn: () => Promise<T> | T): Promise<T> {
  const s = stacks[curTid!];
  if (s) s.navigating = true;
  try { return await fn(); }
  finally { if (s) s.navigating = false; }
}

async function go(idx: number){
  const s = stacks[curTid!];
  if (!s || idx < 0 || idx >= s.stack.length) return;
  s.idx = idx;
  const loc = s.stack[idx];
  try {
    await navGuard(async function(){
      await loadSymbol(loc.symbol);
      if (loc.line && S.hooks.gotoLine) S.hooks.gotoLine(loc.line);
    });
  } catch (e){
    /* a dead entry (a class that no longer answers to that handle): said and dropped, never an
       unhandled rejection (fix-review 3 U1) */
    notice('cannot go to ' + loc.simple + ' — ' + String((e as Error).message || e) + '; the entry is dropped', { err: true });
    s.stack.splice(idx, 1);
    if (s.idx >= s.stack.length) s.idx = s.stack.length - 1;
  }
  paint();
}

export function navBack(): void {
  const s = stacks[curTid!];
  if (s && s.idx > 0) go(s.idx - 1);
}

export function navFwd(): void {
  const s = stacks[curTid!];
  if (s && s.idx < s.stack.length - 1) go(s.idx + 1);
}

/* chevron dropdown: recent entries (click / right-click / long-press) */
function dropdown(){
  const s = stacks[curTid!];
  if (!s || !s.stack.length) return;
  let menu = document.getElementById('navhist-menu');
  if (!menu){
    menu = document.createElement('div');
    menu.id = 'navhist-menu';
    menu.className = 'projmenu navhist';
    document.querySelector('.navbar')!.appendChild(menu);
    document.addEventListener('click', function(e){
      if (!(e.target as HTMLElement).closest('#navhist-menu') && !(e.target as HTMLElement).closest('.hbtn')) menu!.hidden = true;
    });
  }
  menu.innerHTML = '';
  for (let i = s.stack.length - 1; i >= Math.max(0, s.stack.length - 20); i--){
    const loc = s.stack[i];
    const row = document.createElement('div');
    row.className = 'pm-item' + (i === s.idx ? ' pm-cur' : '');
    row.textContent = (i === s.idx ? '● ' : '') + (loc.simple || loc.symbol) +
      (loc.line ? ' :' + loc.line : '');
    row.addEventListener('click', function(){ menu!.hidden = true; go(i); });
    menu.appendChild(row);
  }
  menu.hidden = false;
}

/* dblclick → go to definition (QA 8): resolve the token's symbol; class first,
   owning class fallback for members; unresolvable → flash only. */
async function gotoDef(sym: string | null | undefined){
  if (!sym) return;
  let use = String(sym);
  /* the probe is a RESOLVE, not a decompile: it used to decompile the whole class here, before
     loadSymbol put the JUMP STATUS card up — many seconds of a frozen-looking app on a big class,
     then an instant landing off the engine's cache (user 2026-09-19). The one decompile now
     happens inside loadSymbol, under the card. */
  let r = await tryOpT('symbol.resolve', { symbol: use });   // the VIEWED target (fix-review r1 #7)
  if (r.error){
    const cls = use.includes('->') ? use.split('->')[0]
      : use.substring(0, use.lastIndexOf('.'));
    if (cls && cls !== use){
      r = await tryOpT('symbol.resolve', { symbol: cls });
      if (!r.error) use = cls;
    }
  }
  if (r.error) return;                                // flash only
  await loadSymbol(use);
}

/* ==================================================================
   CONTEXT MENU (P0.2) — ONE delegated `contextmenu` handler for the code
   panes (#codewrap) and the symbol tree (#tree). Symbol rows only: a code
   LINE in the java pane, or a class/interface row in the tree. Everywhere
   else — the smali pane (no caret to act on, P1.2), package rows, the tab
   strip, the empty state — the browser's own menu is left alone: a
   suppressed right-click that shows nothing reads as a dead surface.

   Code side: the caret is parked on the right-clicked token FIRST
   (caret.js placeCaretAt, the click's own math), so every row IS the
   keyboard action it names — F2 / ; / t / ⌘D / F9 all read symbolAtCaret —
   and the caret move itself drives RELATION (caretFollow), which is what
   "Find references" then focuses (the x key). Tree side: rows act on the
   NODE's own symbol, never on whatever the parked caret happens to sit on
   after an open — Rename opens the class first and names it explicitly
   (code.js editSymbol). Keycaps are read off the COMMANDS table, never
   retyped here.

   The popup is the projmenu (same rows, same kbd column, ESC.menu priority
   on the central dispatcher, outside-click close) anchored to the pointer
   and clamped to the viewport (mockup explorer.html openCtx). ↑↓ walk the
   rows, ↵ runs the lit one; any other key closes the menu and goes on to
   its owner — F2 over an open menu opens the rename the menu was offering.
================================================================== */
let ctx: HTMLElement | null = null, ctxOrigin: HTMLElement | null = null;

function kbdOf(label: string){
  const c = COMMANDS.find(function(x){ return x.label === label; });
  return c && c.kbd ? c.kbd : '';
}

function copyText(s: string | undefined){
  if (navigator.clipboard && navigator.clipboard.writeText)
    navigator.clipboard.writeText(String(s)).catch(function(err){ console.warn('clipboard', err); });
}

/* copy ack — the crumb segment's 500 ms outline (chrome.js), on the element
   the text came from */
function flashCopied(el: HTMLElement | null){
  if (!el) return;
  el.style.outline = '2px solid var(--blue)';
  setTimeout(function(){ el.style.outline = ''; }, 500);
}

function ctxEl(){
  if (ctx) return ctx;
  ctx = document.createElement('div');
  ctx.id = 'ctxmenu';
  ctx.className = 'projmenu ctxmenu';
  ctx.tabIndex = -1;
  ctx.hidden = true;
  document.body.appendChild(ctx);
  ctx.addEventListener('contextmenu', function(e){ e.preventDefault(); });   // no browser menu over ours
  ctx.addEventListener('keydown', ctxKeys);
  document.addEventListener('click', function(e){
    if (!ctx!.hidden && !(e.target as HTMLElement).closest('#ctxmenu')) closeCtx();
  });
  /* a scrolled listing leaves the anchor behind (mockup): capture, so the
     code pane's own scroll counts, not just the document's — but ONLY the
     surface the menu is anchored to: the caret move that opens the code
     menu re-syncs the smali pane (caret.js syncSmali scrollIntoView), and
     that scroll must not dismiss the menu a frame after it appeared */
  document.addEventListener('scroll', function(e){
    if (ctx!.hidden || !ctxOrigin) return;
    const t = e.target as Node;
    if (t === document || (t.contains && t.contains(ctxOrigin))) closeCtx();
  }, true);
  return ctx;
}

function ctxRows(){ return $all('.pm-item', ctx); }
function litRow(row: HTMLElement){
  ctxRows().forEach(function(r){ r.classList.toggle('pm-cur', r === row); });
}

function ctxKeys(e: KeyboardEvent){
  const rows = ctxRows();
  const i = rows.findIndex(function(r){ return r.classList.contains('pm-cur'); });
  if (e.key === 'ArrowDown' || e.key === 'ArrowUp'){
    e.preventDefault();
    e.stopPropagation();
    if (!rows.length) return;
    litRow(rows[e.key === 'ArrowDown' ? (i + 1) % rows.length : (i <= 0 ? rows.length - 1 : i - 1)]);
    return;
  }
  if (e.key === 'Enter'){
    e.preventDefault();
    e.stopPropagation();
    if (i >= 0) rows[i].click();
    return;
  }
  /* Escape is the dispatcher's (hub.js) and never reaches here; a lone
     modifier is not a command; anything else closes the menu and travels
     on to its owner (palette.js keys) */
  if (e.key === 'Escape' || ['Shift', 'Meta', 'Control', 'Alt', 'CapsLock'].includes(e.key)) return;
  closeCtx();
}

function closeCtx(){
  if (!ctx || ctx.hidden) return;
  ctx.hidden = true;
  ctx.innerHTML = '';
  /* hand the keyboard back to the surface the menu came from — the code
     caret or the tree cursor — never leave focus parked on <body> */
  const a = document.activeElement;
  if (ctxOrigin && (!a || a === ctx || a === document.body)) ctxOrigin.focus({ preventScroll: true });
  ctxOrigin = null;
}

/* items: { label, kbd?, run } or '-' (separator; leading / doubled / trailing
   ones are dropped so a conditional row never leaves a dangling rule) */
function openCtx(x: number, y: number, head: string, items: (MenuItem | '-')[], origin: HTMLElement | null){
  const menu = ctxEl();
  menu.innerHTML = '';
  const h = document.createElement('div');
  h.className = 'pm-head';
  h.textContent = head;
  h.title = head;
  menu.appendChild(h);
  let pending = false, count = 0;
  items.forEach(function(it){
    if (it === '-'){ pending = true; return; }
    if (pending && count){
      const sep = document.createElement('div');
      sep.className = 'pm-sep';
      menu.appendChild(sep);
    }
    pending = false;
    count++;
    const row = document.createElement('div');
    row.className = 'pm-item';
    if (it.tip) row.dataset.tip = it.tip;          // the shell's own tooltip (chrome.ts initTips), not the native title
    const nm = document.createElement('span');
    nm.className = 'pm-name';
    nm.textContent = it.label;
    row.appendChild(nm);
    /* inline choices: one row, several actions on its right — a pill runs
       its own action, the row (and ↵) the default one */
    (it.choices || []).forEach(function(c){
      const pill = document.createElement('span');
      pill.className = 'pm-choice' + (c.dflt ? ' dflt' : '');
      pill.textContent = c.label;
      if (c.tip) pill.dataset.tip = c.tip;
      pill.addEventListener('click', function(e){
        e.stopPropagation();                         // not the row's default action
        closeCtx();
        c.run();
      });
      row.appendChild(pill);
    });
    if (it.kbd){
      const k = document.createElement('kbd');
      k.textContent = it.kbd;
      row.appendChild(k);
    }
    row.addEventListener('click', function(e){
      e.stopPropagation();
      closeCtx();                                  // focus is back home before the action opens anything
      it.run();
    });
    row.addEventListener('pointerenter', function(){ litRow(row); });   // ↵ runs the hovered row too
    menu.appendChild(row);
  });
  ctxOrigin = origin || null;
  menu.hidden = false;
  /* clamp to the viewport (mockup explorer.html openCtx) — measured shown */
  menu.style.left = Math.max(0, Math.min(x, window.innerWidth - menu.offsetWidth - 8)) + 'px';
  menu.style.top = Math.max(0, Math.min(y, window.innerHeight - menu.offsetHeight - 8)) + 'px';
  menu.focus({ preventScroll: true });
}

/* the original (pre-rename) name behind a caret hit, when the engine told
   us: the open class (symbol.origName) or one of its methods
   (methods[].origName — addr/signature first, then the bare name). Null →
   no "Copy original name" row rather than a row that copies nothing. */
function originalNameOf(hit: CaretHit){
  const cs = S.currentSym || {};
  if (hit.line === undefined) return cs.origName || null;   // the class fallback (no token under the caret)
  const ms = S.javaMethods || [];
  const m = ms.find(function(x){ return x.origName && (x.addr === hit.symbol || x.signature === hit.symbol || x.sig === hit.symbol); })
         || ms.find(function(x){ return x.origName && x.name === hit.name; });
  if (m) return m.origName;
  if (cs.origName && (hit.symbol === cs.addr || hit.symbol === cs.fqcn)) return cs.origName;
  return null;
}

/* java pane: the caret already sits on the token (onContextMenu) */
/* the "reverse with agent" row (ui/reverse.ts): ONE row naming the subject
   (the menu's head may be a different token — a local inside the method that
   gets reversed), with the depths as inline pills on its right; ↵ / a click
   on the row itself is the first depth (quick) */
/* "Add to map…" (audit decision 10): the subject becomes a node of the open map, with its
   symbol and the viewed target; without a map the row says where to make one */
function addToMapRow(sub: ReverseSubject | null): (MenuItem | '-')[] {
  if (!sub) return [];
  const mapOpen = !!(S.state && S.state.activeMap);
  return [{
    label: 'Add to map…',
    tip: mapOpen ? 'add ' + sub.name + ' as a node of the open map (symbol + target, registry kind picker)'
                 : 'no map open — project chip › MAPS · ＋ New map… first',
    run: function(){ hook('addToMap', sub); },
  }];
}

function reverseRows(sub: ReverseSubject | null): (MenuItem | '-')[] {
  if (!sub) return [];
  return ['-', {
    label: reverseLabel(sub),
    tip: 'spawn a reversing mission on the ' + sub.kind + ' ' + sub.name + ' — ↵ is quick; pick the depth on the right',
    run: function(){ return spawnReverse(sub, DEPTHS[0].depth); },
    choices: DEPTHS.map(function(d, i){
      return { label: d.depth, tip: depthTip(sub, d.depth), dflt: i === 0, run: function(){ return spawnReverse(sub, d.depth); } };
    }),
  }];
}

function openCodeMenu(e: MouseEvent, hit: CaretHit){
  const sym = hit.symbol || '';                    // locals carry none (relation.js renderLocalUsages)
  const isTok = hit.line !== undefined;            // a token under the caret vs. the class fallback
  const hot = document.querySelector<HTMLElement>('#code .tok-hot');
  const items: (MenuItem | '-')[] = [];
  if (isTok && sym) items.push({ label: 'Go to definition', kbd: 'dbl-click', run: function(){ hook('gotoDef', sym); } });
  /* the caret move already drove RELATION (caretFollow) — this is the x key */
  items.push({ label: 'Find references', kbd: kbdOf('Find references'), run: function(){ focusSelection(); } });
  /* the method under the pointer (a call site reverses the callee), else the
     one enclosing the caret line, else the class */
  items.push(...reverseRows(subjectOfHit(hit)));
  /* a CALL SITE (a method token inside another method's body): reverse the callee AND annotate
     the call — the callee in depth, the ten lines around the call in the caller, every argument
     named, a comment on the call itself (user 2026-09-20) */
  if (isTok && hit.kind === 'method' && sym && S.caretReady){
    const caller = methodAt(S.javaMethods, S.caret.line + 1);
    const callee = subjectOfHandle(sym, hit.name);
    const line = S.codeLines[S.caret.line] ? (S.codeLines[S.caret.line].lc ? S.codeLines[S.caret.line].lc!.textContent || '' : '') : '';
    if (caller && callee && caller.addr && caller.addr !== sym && caller.signature !== sym)
      items.push({
        label: '⚡ Reverse and annotate call to ' + callee.name,
        tip: 'a deep mission on this call: ' + callee.name + ' in depth, every symbol within ten lines of the call in ' + caller.name + ' named (types, fields, methods, locals — every argument), and a comment on the call itself · write-scope: the viewed target',
        run: function(){ return spawnAnnotateCall(callee, { symbol: String(caller.addr), name: String(caller.name), line: caller.line }, line, S.caret.line + 1); },
      });
  }
  items.push(...addToMapRow(subjectOfHit(hit)));
  items.push('-');
  if (hit && hit.local){                           // a local: the enclosing method + its name (code.ts symbolAtCaret)
    items.push({ label: 'Rename local…', kbd: kbdOf('Rename symbol'), run: function(){ hook('openRename'); } });
    /* an ARGUMENT of a method that carries retained MethodParameters: the analyst's door to mapping
       one retained name onto this argument (ui/params.ts) — never automatic */
    const encl = S.caretReady ? methodAt(S.javaMethods, S.caret.line + 1) : null;
    if (encl && encl.addr && retainedFor(encl.addr, hit.local)){   // a formal argument of the method — not a body local, not `this`
      const loc = hit.local;
      items.push({ label: 'Apply retained name to argument…', tip: 'pick one of the method\'s retained MethodParameters names for this argument — the mapping is yours, recorded and undoable; the annotation\'s order is not a verified mapping', run: function(){ hook('applyRetained', loc); } });
    }
  }
  if (hit && hit.local){                           // on a local only (user 2026-09-19: no shortcut — the menu is the one door)
    items.push({ label: 'Name all locals after their types', run: function(){ hook('autoNameLocals'); } });
  }
  if (sym && !(hit && hit.local)){                 // a symbol with an addr: rename / comment / tag
    items.push({ label: 'Rename…',  kbd: kbdOf('Rename symbol'),  run: function(){ hook('openRename'); } });
    items.push({ label: 'Comment…', kbd: kbdOf('Comment symbol'), run: function(){ hook('openComment'); } });
    items.push({ label: 'Tag…',     kbd: kbdOf('Tag symbol'),     run: function(){ hook('addTag'); } });
  }
  items.push('-');
  items.push({ label: 'Toggle bookmark',   kbd: kbdOf('Toggle bookmark'),   run: function(){ hook('toggleBookmark'); } });   // caret LINE
  items.push({ label: 'Toggle breakpoint', kbd: kbdOf('Toggle breakpoint'), run: function(){ hook('toggleBp'); } });         // caret METHOD
  items.push('-');
  /* the signature is the token's own data-sym — the exact string every op
     takes (JEB addr `Lpkg/C;->m(desc)ret`, dotted for the mock) */
  items.push({ label: sym ? 'Copy signature' : 'Copy name', run: function(){ copyText(sym || hit.name); flashCopied(hot); } });
  const orig = originalNameOf(hit);
  if (orig) items.push({ label: 'Copy original name', run: function(){ copyText(orig); flashCopied(hot); } });
  /* the UI door to rename.revert: the dex's own name back, however unpronounceable (an obfuscated
     `1cq` is a fine original and a refused NEW name) — recorded in HISTORY, ⌘Z puts the analyst's back */
  if (orig && sym && !(hit && hit.local) && orig !== hit.name){
    const revertSym = sym;
    items.push({ label: 'Revert to original name', tip: 'put the dex\'s own name back: ' + orig + ' — recorded in HISTORY; ⌘Z restores yours', run: async function(){
      const tidAtCall = S.currentTid;
      const r = await tryOpT('rename.revert', { symbol: revertSym });
      if (r.error){ notice('not reverted — ' + r.error.message, { err: true }); return; }
      /* the mutation is done and recorded whatever happens below: the identity follows (the viewed
         class, its tabs and tree row, the ‹ › history — a restored class no longer answers to the
         analyst's addr the view was opened with), then the view reloads; a reload failure is said as
         a refresh failure, never as a failed revert (independent review 2026-10-08) */
      const followed = followIdentityChange(r.data, revertSym, tidAtCall);
      notice(orig + ' is the name again (HISTORY · ⌘Z undoes)');
      if (!followed) return;   // the viewed target changed while the request was in flight: not this view's to reload
      try { await hook('afterEdit'); }
      catch (e){ notice(orig + ' is the name again and recorded in HISTORY — but the view could not be refreshed (' + String((e as Error).message || e) + '); reopen ' + orig, { err: true }); }
    } });
  }
  openCtx(e.clientX, e.clientY, hit.name! + (hit.kind ? ' · ' + hit.kind : ''), items, document.getElementById('code'));
}

/* symbol tree: a class/interface row (app.js treeNode stamps data-sym /
   data-name / data-kind / data-orig on exactly the rows that open on click) */
function openTreeMenu(e: MouseEvent, div: HTMLElement){
  const sym = div.dataset.sym!;
  const name = div.dataset.name || sym;
  const kind = div.dataset.kind || 'class';
  const orig = div.dataset.orig || '';
  const items: (MenuItem | '-')[] = [
    /* the row's own click path (app.js openTreeClass): loadSymbol on the handle */
    { label: 'Open', kbd: '↵', run: function(){ loadSymbol(sym).catch(function(){}); } },
    /* the class's relations WITHOUT opening it — the RELATION rows are the next hop */
    { label: 'Find references', kbd: kbdOf('Find references'), run: function(){ loadRelationFor(sym, name); focusSelection(); } },
    ...reverseRows({ symbol: sym, name: name, kind: 'class', fqcn: div.dataset.fqcn }),   // the scope needs the dotted class, the handle may be a bare name (r1 M8)
    ...addToMapRow({ symbol: sym, name: name, kind: 'class', fqcn: div.dataset.fqcn }),
    '-',
    { label: 'Rename…', kbd: kbdOf('Rename symbol'), run: async function(){
      /* open first: the rename bookkeeping in code.js editDialog (reload,
         tab re-key, history) assumes the renamed class is the OPEN one */
      try { await loadSymbol(sym); } catch (err) { return; }     // unresolvable node text (M6) → nothing to rename
      const cs = S.currentSym || {};
      editSymbol('rename', { name: cs.name || name, symbol: cs.addr || cs.fqcn || sym, kind: 'class' });
    } },
    '-',
    { label: 'Copy signature', run: function(){ copyText(sym); flashCopied(div); } },
  ];
  if (orig) items.push({ label: 'Copy original name', run: function(){ copyText(orig); flashCopied(div); } });
  openCtx(e.clientX, e.clientY, name + ' · ' + kind, items, document.getElementById('tree'));
}

/* RELATION rows (the rail's XREFS TO / CALLEES / IMPLEMENTATIONS and the dock's full list): the
   symbol's own menu — the browser's menu came up here (user 2026-09-20). Inside an opened tree
   level, "Rename tree path" runs a quick mission on every method from the pane's subject down to
   this row, one after the other. */
function openRelMenu(e: MouseEvent, row: HTMLElement){
  const sym = row.dataset.sym!;
  const name = row.dataset.name || sym;
  const kind = row.dataset.kind || 'method';
  const opened = row.classList.contains('open');            // its + is open: the branch under it is on screen
  const branch = opened ? branchOf(row) : [];
  const sub = subjectOfHandle(sym, name);
  const items: (MenuItem | '-')[] = [
    { label: 'Open', kbd: '↵', run: function(){ row.click(); } },                                  // the row's own landing
    { label: 'Go to definition', run: function(){ loadSymbol(sym).catch(function(){}); } },
    { label: 'Find references', kbd: kbdOf('Find references'), run: function(){ loadRelationFor(sym, name); focusSelection(); } },
    ...reverseRows(sub),
  ];
  if (opened){
    const methods = branch.filter(function(p){ return p.symbol.includes('->'); });
    items.push({
      label: 'Rename tree branch (' + methods.length + ' method' + (methods.length === 1 ? '' : 's') + ')',
      tip: 'a quick reversing mission on ' + name + ' and on every method shown under it, one after the other — '
         + methods.slice(0, 8).map(function(p){ return p.name; }).join(', ') + (methods.length > 8 ? ', …' : ''),
      run: function(){ return spawnReverseChain(methods.map(function(p){ return subjectOfHandle(p.symbol, p.name)!; })); },
    });
  }
  items.push(...addToMapRow(sub));
  items.push('-');
  items.push({ label: 'Rename…', kbd: kbdOf('Rename symbol'), run: async function(){
    try { await loadSymbol(sym); } catch (err) { return; }
    editSymbol('rename', { name: name, symbol: sym, kind: kind, line: kind === 'method' ? S.caret.line + 1 : undefined });
  } });
  items.push({ label: 'Comment…', kbd: kbdOf('Comment symbol'), run: async function(){
    try { await loadSymbol(sym); } catch (err) { return; }
    editSymbol('comment', { name: name, symbol: sym, kind: kind });
  } });
  items.push('-');
  items.push({ label: 'Copy signature', run: function(){ copyText(sym); flashCopied(row); } });
  openCtx(e.clientX, e.clientY, name + ' · ' + kind, items, row.closest<HTMLElement>('.selx, #dock-rel-rows'));
}

/* an agent's comment in the code view (a whole-line comment row, or the lifted call-site comment):
   its own menu — delete it (History undo of that one entry: the text goes, the EDITS row stays and
   says reverted; two agents commenting one call is the case, user 2026-09-20), copy it */
function openCommentMenu(e: MouseEvent, row: HTMLElement){
  const hid = row.dataset.cmHid || '', by = row.dataset.cmBy || 'agent', text = row.dataset.cmText || '';
  const items: (MenuItem | '-')[] = [];
  if (hid) items.push({
    label: 'Delete comment (by ' + by + ')',
    tip: 'undo this comment\'s History entry — the text goes, the EDITS row stays and says reverted; redo from HISTORY brings it back',
    run: async function(){
      const r = await tryOpT('history.undo', { target: S.currentTid, id: hid });
      if (r.error){ notice('comment not deleted — ' + r.error.message, { err: true }); return; }
      notice('comment by ' + by + ' deleted · ⇧⌘Z or HISTORY redo brings it back');
      hook('reloadCode');
      if (S.hooks.historyChanged) S.hooks.historyChanged();
      if (S.hooks.proposalsChanged) S.hooks.proposalsChanged();
    },
  });
  items.push({ label: 'Copy comment', run: function(){ copyText(text); flashCopied(row); } });
  openCtx(e.clientX, e.clientY, 'comment · ' + by, items, document.getElementById('code'));
}

function onContextMenu(e: MouseEvent){
  const cm = (e.target as HTMLElement).closest<HTMLElement>('.cl.llmcm, .cl.llmsite');
  if (cm && cm.dataset.cmHid && (cm.classList.contains('llmcm') || (e.target as HTMLElement).closest('.lcm'))){
    e.preventDefault();
    openCommentMenu(e, cm);
    return;
  }
  const xr = (e.target as HTMLElement).closest<HTMLElement>('.xrow[data-sym]');
  if (xr && xr.closest('#rel-xrefs, #rel-callees, #rel-impls, #dock-rel-rows')){
    e.preventDefault();
    openRelMenu(e, xr);
    return;
  }
  const tn = (e.target as HTMLElement).closest<HTMLElement>('.tnode[data-sym]');
  if (tn){
    e.preventDefault();
    openTreeMenu(e, tn);
    return;
  }
  const cl = (e.target as HTMLElement).closest<HTMLElement>('#code .cl');
  if (!cl || !placeCaretAt(cl, e.clientX)) return;   // smali pane, gutter void, stale row → browser menu
  const hit = symbolAtCaret();
  if (!hit) return;
  e.preventDefault();
  /* the method's retained metadata decides one of the rows: have it in before the menu opens (the
     first right-click in a method used to come before the answer and show no row) */
  const enclM = S.caretReady ? methodAt(S.javaMethods, S.caret.line + 1) : null;
  void ensureParams(enclM && enclM.addr ? enclM.addr : null).then(function(){ openCodeMenu(e, hit); });
}

export function initNavigation(): void {
  if (wired) return;
  wired = true;
  /* context menu (P0.2): one handler, two hosts — see the CONTEXT MENU block */
  const wrap = document.getElementById('codewrap');
  const tree = document.getElementById('tree');
  if (wrap) wrap.addEventListener('contextmenu', onContextMenu);
  if (tree) tree.addEventListener('contextmenu', onContextMenu);
  ['rel-xrefs', 'rel-callees', 'rel-impls', 'dock-rel-rows'].forEach(function(id){   // RELATION rows: the symbol's menu
    const el = document.getElementById(id);
    if (el) el.addEventListener('contextmenu', onContextMenu);
  });
  /* the RELATION rail's ⚡ REVERSE button: the same two rows, anchored under
     the button, for the pane's subject (a field row reverses its class) */
  const rev = document.getElementById('rel-reverse');
  if (rev) rev.addEventListener('click', function(){
    if (ctx && !ctx.hidden){ closeCtx(); return; }   // a second click dismisses (a toggle, not a blink — r2 R2-17)
    /* the pane's subject, else what the code menu would offer for the same
       caret (the enclosing method / the open class) — one answer for one
       caret (r1 M9) */
    const rs = relationSubject();
    const sub = (rs ? subjectOfHandle(rs.symbol, rs.name) : null) || subjectAtCaret();
    if (!sub){ notice('open a class and put the caret on a method first', { err: true }); return; }
    const r = rev.getBoundingClientRect();
    /* open on the next tick, after every click-away closer has run on THIS
       click (the ctx menu's own, the project menu's, the jump dropdown's):
       stopping propagation instead would have left those menus up (r1 M5).
       The keyboard goes back to the code caret on close, not to the button
       (r1 L3) — the button was only the way in. */
    const origin = S.center === 'code' ? document.getElementById('code') : rev;
    setTimeout(function(){
      const rows: (MenuItem | '-')[] = reverseRows(sub).filter(function(x){ return x !== '-'; });
      rows.push(...addToMapRow(sub));
      rows.push('-', { label: '⚙ Reversing agent settings…',
        tip: 'which model, effort and caps run quick and deep — Agents view → ⚡ REVERSING AGENT',
        run: function(){ hook('openReverseSettings'); } });
      openCtx(r.left, r.bottom + 4, sub.name + ' · reverse with agent', rows, origin);
    }, 0);
  });
  /* Esc closes it ahead of the view-level handlers (menu priority); declines
     while hidden so the field-scoped Escapes keep theirs */
  pushEscape(function(){ if (!ctx || ctx.hidden) return false; closeCtx(); }, ESC.menu);
  const btns = hbtns();
  if (btns.length >= 2){
    btns[0].addEventListener('click', navBack);
    btns[1].addEventListener('click', navFwd);
    [btns[0], btns[1]].forEach(function(b){
      let press = 0;
      b.addEventListener('contextmenu', function(e){ e.preventDefault(); dropdown(); });
      b.addEventListener('pointerdown', function(){ press = setTimeout(dropdown, 500); });
      b.addEventListener('pointerup', function(){ clearTimeout(press); });
      b.addEventListener('pointerleave', function(){ clearTimeout(press); });
    });
  }
  S.hooks.navBack = navBack;
  S.hooks.navFwd = navFwd;
  S.hooks.gotoDef = gotoDef;
  S.hooks.navPush = navPush;
  S.hooks.navGuard = navGuard;
  S.hooks.setNavTarget = setNavTarget;
  paint();
}
