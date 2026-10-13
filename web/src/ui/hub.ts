// hub.ts — shared helpers + mutable cross-module state (the mockup's IIFE scope, split).
import { op } from '../api.js';
import type { OpError, TryResult } from '../api.js';
import type { EscapeHandle, EscapeHandler, HookFns, MethodInfo, OpParams, OpResult, WorkbenchState } from '../types.js';

export function $<T extends Element = HTMLElement>(s: string): T | null { return document.querySelector<T>(s); }
export function $all<T extends Element = HTMLElement>(s: string, r?: ParentNode | null): T[] {
  return Array.prototype.slice.call((r || document).querySelectorAll<T>(s)) as T[];
}

/* the message of whatever was thrown — an Error's own, else the value's
   string form (the JS idiom `(e && e.message) || e`, with catch variables
   now `unknown`) */
export function errMsg(e: unknown): string {
  if (e instanceof Error) return e.message;
  if (e && typeof e === 'object' && 'message' in e && (e as { message?: unknown }).message)
    return String((e as { message: unknown }).message);
  return String(e);
}

/* the ONE HTML escaper (review 2026-09-09 HIGH-4): sixteen modules carried
   their own copy, and only device.ts's escaped the double quote — every
   hand-built title="…" / data-*="…" attribute across the views was open to a
   string with a `"` in it (an APK's string pool, an agent-written map label).
   Text AND attribute safe: & < > " ' — one helper for both positions. */
export function escHtml(s: unknown): string {
  return String(s ?? '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

/* bind an op's params to the VIEWED target (review 2026-09-09 MED-7/16/17/20):
   a target-less op resolves the server's activeTarget, a shared cursor any
   actor can yank mid-gesture. The writes already did this by hand (code.ts);
   the reads, deletes, save and BP add now share the same one-liner. */
export function withTarget(params: OpParams = {}): OpParams {
  if (params.target !== undefined) return params;   // an explicit target (a BP's own, a node's own) wins (fix-review r2 #4)
  return S.currentTid ? { ...params, target: S.currentTid } : params;
}

/* the backend's wording for a viewed tid it no longer KNOWS (ids remint
   across server restarts) — Workspace.engineFor's "target not found". Its
   sibling "target not open: <tid>" is a REGISTERED target whose engine is
   gone: not a stale id, and reseeding there would retarget the op
   (fix-review r2 #3), so it is deliberately not matched. */
export function staleTargetError(e: unknown): boolean {
  return /target not found/.test(errMsg(e));
}

/* op() bound to the VIEWED target, with the stale-tid recovery loadSymbol0
   (code.ts) pioneered: on a dead tid, reseed S.currentTid from the live state
   (app.ts reseedTarget) and retry ONCE under the new id; any other failure
   rethrows untouched. Every op the review's target-binding class bound
   (MED-7/16/17/20) goes through here — a stale cursor after a backend restart
   must not empty the docks or no-op ⌘Z / ⌘S / ＋BP (fix-review r1 #1). */
export async function opT<K extends string>(name: K, params: OpParams = {}): Promise<OpResult<K>> {
  try { return await op(name, withTarget(params)); }
  catch (e) {
    if (params.target !== undefined) throw e;   // a caller's own target is never re-aimed (fix-review r3 #3)
    if (!S.currentTid || !staleTargetError(e) || !S.hooks.reseedTarget) throw e;
    const tid = await S.hooks.reseedTarget();   // the same artifact's new id — or null: the shell landed elsewhere / a switch superseded it
    if (tid) return op(name, { ...params, target: tid });
    /* landed on a DIFFERENT target: only the reads that feed the shell follow
       it (the landed surface wants exactly them — fix-review r4 #2's race);
       a write meant for the dead target, and the gesture that opened a class
       there, fail as they should */
    if (S.currentTid && RELANDABLE.has(name)) return op(name, { ...params, target: S.currentTid });
    throw e;
  }
}

/* the reads opT may retry under a NEWLY LANDED viewed target: shell state
   (tree, docks, gutter, relation pane, the search/strings tables, the jump
   seeds, the ribbon probes). Not here on purpose: every write, and
   code.decompile/classinfo/smali — a class open is a gesture on ONE apk. */
const RELANDABLE = new Set<string>([
  'symbol.tree', 'symbol.resolve', 'symbol.search', 'xref.to', 'callees', 'impls',
  'strings', 'string.xrefs', 'search', 'search.index', 'tag.list', 'bookmark.list',
  'flow.list', 'flow.get', 'history.list', 'runs.bp.list', 'runs.coverage', 'target.info',
]);

/* tryOp's never-throws shape over opT */
export async function tryOpT<K extends string>(name: K, params: OpParams = {}): Promise<TryResult<OpResult<K>>> {
  try { return { data: await opT(name, params) }; }
  catch (e) { return { error: e as OpError }; }
}

/* An op error as a sentence for a notice. The one case that misleads as-is: "unknown op" means
   the RUNNING server predates the feature the UI just used (the UI is served from disk and moves
   ahead of a server that was not restarted) — say that, not "not found". */
export function explainError(e: { code?: string; message?: string } | null | undefined): string {
  if (!e) return 'unknown error';
  const m = String(e.message || '');
  if (/^unknown op:/.test(m)) return 'the running server predates this feature — restart the workbench to update it (' + m.replace(/^unknown op:\s*/, '') + ')';
  return m;
}

/* The browser's own errors go to the server log beside the server's (<home>/logs/workbench.log):
   uncaught exceptions, unhandled promise rejections — with the page URL and the stack. Never
   throws, never loops (a failure to log is dropped). */
let clientLogBusy = 0;
export function logClient(level: 'error' | 'warn' | 'info', message: string, stack?: string, context?: unknown): void {
  if (clientLogBusy > 3) return;             // a flood (a render loop) must not become a request loop
  clientLogBusy++;
  const body = { params: { level, message: String(message).slice(0, 2000), stack: stack ? String(stack).slice(0, 4000) : undefined,
    url: location.href, context }, actor: 'human' };
  fetch('/api/log.client', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
    .catch(function(){ /* nothing to do */ })
    .then(function(){ clientLogBusy = Math.max(0, clientLogBusy - 1); });
}
export function initClientLog(): void {
  window.addEventListener('error', function(ev){
    logClient('error', ev.message || 'error', ev.error && ev.error.stack ? ev.error.stack : (ev.filename + ':' + ev.lineno + ':' + ev.colno));
  });
  window.addEventListener('unhandledrejection', function(ev){
    const r = ev.reason;
    if (r && typeof r === 'object' && 'code' in r){     // a failed op that nobody caught: the server logged the op already, note the caller
      logClient('warn', 'unhandled op error: ' + String((r as { message?: string }).message || r), (r as { stack?: string }).stack);
      return;
    }
    logClient('error', 'unhandled rejection: ' + (r && r.message ? r.message : String(r)), r && r.stack ? r.stack : undefined);
  });
}

/* a colour that may reach a style="…" from server text (registry entries,
   tag families — map.type.add/set and agents write them unvalidated): only a
   hex literal passes (fix-review r1: one check, not three copies) */
export function isHexColor(c: unknown): boolean {
  return /^#[0-9a-fA-F]{3,8}$/.test(String(c));
}

/* ONE modal predicate for every "is a dialog/overlay up" gate (review: three
   hand-copies had already drifted). Covers tag/launcher/keyhelp modals, the ⌘K
   palette overlay and the jump dropdown. palette.js keeps its own variant ON
   PURPOSE: otherModalOpen excludes keyhelp so '?' doesn't see itself. */
export function modalUp(): boolean {
  return !!document.querySelector('.wbmodal:not([hidden])') ||
    document.getElementById('overlay')!.classList.contains('open') ||
    !document.getElementById('jdrop')!.hidden;
}

// Cross-module mutable state. Modules read/write fields directly.
// Every field the modules assign later is declared here with its resting
// value, so the object's shape is complete from boot (types.ts WorkbenchState).
export const S: WorkbenchState = {
  /* ONE center-surface field (app review P0.2). Written only by chrome.js
     showCenter(); everything else reads it. The three flags it replaced
     (paneView, agentsOpen, #mapchip.on) were kept exclusive by hand. */
  center: 'code',        // 'code' | 'map' | 'agents' | 'strings' | 'runs' | 'search' | 'device'
  /* last workspace.state snapshot (app review P0.1): target identity is read
     from here (chrome.js activeTarget), never from chip text */
  state: null,
  currentTid: null,      // the VIEWED target's id — app.js seeds/maintains it
  // code/caret
  caret: { line:16, col:0, goal:0 },
  codeLines: [],
  charW: 7,
  caretReady: false,
  // hooks registered by feature modules (updateCenter + friends call these)
  hooks: {},
  // code view model (code.ts loadSymbol / app.ts tabs + per-target views)
  openTabs: [],
  targetViews: {},
  currentClass: null,
  currentSym: {},
  currentFqcn: null,
  javaMethods: [],
  crumbBase: '',
  lineMarks: {},
  covRun: null,
  // shell (chrome.ts / caret.ts)
  rbtns: [],
  syncOn: true,          // caret.ts: java↔smali caret sync ('s' toggles)
  bytesOn: true,         // caret.ts: smali bytes column ('b' toggles)
  cameFromMap: false,
  jumpInFlight: 0,
  dzActive: false,
  // map / search state the shell reads (map.ts / search.ts own the writes)
  mapId: null,
  mapSel: null,
  srchScope: 'target',
};

// Call a registered hook if present.
export function hook<K extends keyof HookFns>(name: K, ...args: Parameters<HookFns[K]>): ReturnType<HookFns[K]> | undefined {
  const fn = S.hooks[name] as HookFns[K] | undefined;
  if (fn) return (fn as (...a: Parameters<HookFns[K]>) => ReturnType<HookFns[K]>)(...args);
  return undefined;
}

/* ==================================================================
   ESCAPE STACK — ONE dispatcher for the whole app (window + capture, so
   it runs ahead of every document-capture/element listener).

   An owner registers a handler with a priority and gets a handle back:
     const h = pushEscape(close, ESC.modal);  ... popEscape(h);
   Escape runs the TOPMOST applicable handler only — highest priority
   first, most recently pushed breaking ties — then consumes the event
   (preventDefault + stopImmediatePropagation), so no second owner fires.
   A handler that returns false DECLINES (it is registered permanently
   but not applicable right now): the next owner down gets its turn, and
   if nobody claims it the event is left untouched, which is how the
   field-scoped Escapes (tree filter, pane views, inline add-forms) keep
   owning their own input.
================================================================== */
export const ESC = { keyhelp: 100, modal: 90, menu: 80, palette: 70, drag: 60, view: 10 } as const;

const escStack: EscapeHandle[] = [];
let escSeq = 0;

export function pushEscape(fn: EscapeHandler, prio?: number): EscapeHandle {
  const h: EscapeHandle = { fn: fn, prio: prio === undefined ? ESC.modal : prio, seq: ++escSeq };
  escStack.push(h);
  return h;
}
export function popEscape(h: EscapeHandle | null | undefined): void {
  const i = h ? escStack.indexOf(h) : -1;
  if (i !== -1) escStack.splice(i, 1);
}
export function initEscape(): void {
  window.addEventListener('keydown', function(e){
    if (e.key !== 'Escape') return;
    const order = escStack.slice().sort(function(a, b){ return (b.prio - a.prio) || (b.seq - a.seq); });
    for (let i = 0; i < order.length; i++){
      if (order[i].fn(e) !== false){
        e.preventDefault();
        e.stopImmediatePropagation();
        return;
      }
    }
  }, true);
}

/* the ONE enclosing-method resolver (GUI review finding 11): relation.js and
   caret.js each carried a copy (code.js:327 a near-variant, smali-scoped).
   Null line ranges — abstract/interface methods emit line:null,endLine:null
   — never match, so callers must handle the no-method case themselves */
export function methodAt(methods: MethodInfo[] | null | undefined, line: number): MethodInfo | null {
  let m: MethodInfo | null = null;
  (methods || []).forEach(function(mm){
    if (mm.line != null && line >= mm.line && line <= (mm.endLine || mm.line)) m = mm;
  });
  return m;
}
