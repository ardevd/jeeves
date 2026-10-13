// openstatus.ts — OPEN STATUS cards: one floating, NON-modal card per
// target.open in flight, bottom-right of the center surface.
//
// Why this exists (user complaint 2026-09-08): the launcher kept its modal —
// path input, BROWSE pane and all — up over the whole analysis, painting
// "phase · message · Ns" into a line under the file browser, so a multi-minute
// JEB analysis held the file selector over the workbench. And an Escape
// mid-analysis killed the poll: the target finished server-side and its chip
// surfaced only on the next unrelated state refresh (the orphan hole).
//
// Now target.open's ACCEPT (an id came back) closes the launcher at once and
// hands the payload here. The card owns the target.info poll at the launcher's
// old cadence (1.5s), and READY reaches the strip through the caller's onReady
// from HERE — whatever became of the launcher. Pre-accept refusals (E_SYNTAX /
// E_NOTFOUND / E_UNSUPPORTED / E_BUSY) never come this far: they stay inline
// in the launcher's #launch-err.
//
// Lifecycle:  loading  orange LED (the strip's not-ready colour), live phase +
//                      message + elapsed, repainted per poll
//             link     transport down (E_HTTP) — keeps polling, says so
//             ready    green receipt, the onReady chain fires, self-dismisses —
//                      unless it carries something to read (a re-key, edits that
//                      could not be re-applied): then it stays until × dismisses it
//             error    the shell's vermillion voice; stays until × dismisses it
//             closed   a target.close landed mid-open: neutral, self-dismisses
// Not an escape citizen (hub.js stack untouched) and not a .wbmodal (modalUp()
// must not see it): the workbench stays fully interactive underneath, and the
// launcher reopens for the next target while this one analyzes — cards stack.
//
// No CANCEL, deliberately: op.cancel consumes a TICKET, and the only tickets
// the backend mints are the search index's ("search-index:<target>" —
// JebEngine.cancelTask is the sole implementor). Analysis (JebEngine.doOpen)
// exposes none, and its decompile bursts are not interruptible. A button that
// could not cancel would be a fake affordance.
import { tryOp } from '../api.js';
import { S } from './hub.js';
import type { TargetInfo } from '../types.js';

const POLL_MS = 1500;           // the launcher's cadence, unchanged
const LINK_RETRY_MS = 5000;     // 3 straight transport failures → back off
const READY_LINGER_MS = 2500;   // the green state is a receipt, not a surface
const CLOSED_LINGER_MS = 4000;

/** the caller's READY callback: trackOpen collects one per merged open */
type OnReady = (tid: string, info: TargetInfo) => unknown;

/** trackOpen's options — see its doc comment */
export interface TrackOpts {
  startedAt?: number;
  onReady?: OnReady;
}

/** what trackOpen hands back: the tracked id (null when there was none) and
 *  the settle promise (true on ready, false on error / closed / dismissed) */
export interface OpenTracker {
  id: string | null;
  done: Promise<boolean>;
}

/** the card's textContent sinks, resolved once at mount */
interface CardUi {
  led: HTMLElement;
  name: HTMLElement;
  eng: HTMLElement;
  x: HTMLElement;
  msg: HTMLElement;
  warn: HTMLElement;
  aside: HTMLElement;
  undo: HTMLButtonElement;
  phaseK: HTMLElement;
  phase: HTMLElement;
  state: HTMLElement;
  time: HTMLElement;
}

/** one open in flight: the public handle plus everything the poll owns */
interface Tracker extends OpenTracker {
  seed: TargetInfo;
  info: TargetInfo;
  t0: number;
  t1: number;
  settled: boolean;
  dead: boolean;
  linkFails: number;
  timer: number;
  onReady: OnReady[];
  ui: CardUi | null;
  el: HTMLElement | null;
  /** set once UNDO RE-KEY went through: the server's sentence, shown in place of the receipt */
  undone?: string;
  resolve: (ok: boolean) => void;
}

/** the card's voice: which of the five states it paints */
type Mode = 'loading' | 'link' | 'ready' | 'error' | 'closed';

let host: HTMLElement | null = null, tpl: HTMLTemplateElement | null = null;
const cards = new Map<string, Tracker>();        // target id → tracker: one card per open in flight

export function initOpenStatus(): void {
  host = document.getElementById('openstatus');
  tpl = document.getElementById('oscard-tpl') as HTMLTemplateElement | null;
}

/* Track an accepted open. `seed` is target.open's payload — the engine's
   info map: id, name, path, engine, engineVersion, state, phase, message,
   and on a dedupe deduped/note (plus basisWarn on a fresh open whose sibling
   jdb2 has a foreign pull basis). opts.startedAt: when the caller SENT
   target.open — the elapsed readout counts the accept latency too (the
   Workspace pre-scan hashes and can wait out a lock for seconds).
   opts.onReady(tid, info): runs once on READY. Returns the tracker; `done`
   resolves true on ready, false on error / closed / target-gone — the device
   pull chain (app.js targetReady) waits on that instead of polling a second
   time. A tid already in flight MERGES: one card, every onReady. */
export function trackOpen(seed: TargetInfo | null | undefined, opts?: TrackOpts): OpenTracker {
  const o = opts || {};
  const tid = seed && seed.id;
  if (!tid) return { id: null, done: Promise.resolve(false) };
  let tr = cards.get(tid);
  if (tr && !tr.settled){
    if (o.onReady) tr.onReady.push(o.onReady);
    return tr;
  }
  if (tr) dismiss(tr);            // a settled card (lingering receipt) yields to the new open
  let resolve!: (ok: boolean) => void;
  const done = new Promise<boolean>(function(res){ resolve = res; });
  tr = { id: tid, seed: seed, info: seed, t0: o.startedAt || Date.now(), t1: 0,
         settled: false, dead: false, linkFails: 0, timer: 0,
         onReady: o.onReady ? [o.onReady] : [], ui: null, el: null, resolve, done };
  cards.set(tid, tr);
  if (host && tpl) mount(tr);     // no host (bare harness) → the poll still runs, headless
  const st = seed.state;
  if (st === 'ready') finish(tr, 'ready');                 // dedupe onto a live session
  else if (st === 'error') finish(tr, 'error', seed.error || 'analysis failed');
  else if (st === 'closed') finish(tr, 'closed');
  else { paint(tr, 'loading'); schedule(tr, POLL_MS); }   // the accept payload already carries phase/message
  return tr;
}

function mount(tr: Tracker){
  const el = tpl!.content.firstElementChild!.cloneNode(true) as HTMLElement;
  el.dataset.tid = tr.id!;
  const q = function(c: string){ return el.querySelector<HTMLElement>(c)!; };
  tr.ui = { led: q('.led'), name: q('.os-name'), eng: q('.os-eng'), x: q('.os-x'),
            msg: q('.os-msg'), warn: q('.os-warn'), aside: q('.os-aside:not(.os-warn)'),
            undo: q('.os-undo') as HTMLButtonElement, phaseK: q('.os-phasek'),
            phase: q('.os-phase'), state: q('.os-state'), time: q('.os-time') };
  /* server strings (name, path, message, error, note) land via textContent
     only — never innerHTML */
  put(tr.ui.name, tr.seed.name || tr.seed.path || tr.id);
  tr.ui.name.setAttribute('data-tip', (tr.seed.path || tr.seed.name || tr.id) + ' · ' + tr.id);
  put(tr.ui.eng, engineLabel(tr.seed));
  tr.ui.x.addEventListener('click', function(){ dismiss(tr); });
  tr.ui.undo.addEventListener('click', function(){ undoRekey(tr); });
  tr.el = el;
  host!.appendChild(el);
}

function schedule(tr: Tracker, ms: number){
  clearTimeout(tr.timer);
  tr.timer = setTimeout(function(){ poll(tr); }, ms);
}

/* ONE request in flight per card: the next tick is scheduled after the answer
   lands. The launcher's setInterval could stack overlapping target.info calls
   behind a Workspace monitor held for seconds (a sibling open's hashing). */
async function poll(tr: Tracker){
  if (tr.dead || tr.settled) return;
  const r = await tryOp('target.info', { target: tr.id });
  if (tr.dead || tr.settled) return;         // dismissed / superseded while the request flew
  if (r.error){
    /* the OpError wrap: the code lives at payload.error.code (the ribbon.js
       refreshAgentChip trap — a bare r.error.code is always undefined).
       E_HTTP is TRANSPORT: the target may well be fine — keep polling and
       say so (api.js already painted STATE DOWN). A CODED error (E_NOTFOUND:
       the id is unknown — closed and unregistered, or the backend restarted
       and reminted ids) is terminal: this open will never report ready. */
    const code = r.error.payload && r.error.payload.error && r.error.payload.error.code;
    if (code === 'E_HTTP'){
      tr.linkFails++;
      paint(tr, 'link');
      schedule(tr, tr.linkFails >= 3 ? LINK_RETRY_MS : POLL_MS);
      return;
    }
    finish(tr, 'error', r.error.message);
    return;
  }
  tr.linkFails = 0;
  tr.info = r.data || ({} as TargetInfo);
  const st = tr.info.state;
  if (st === 'ready') finish(tr, 'ready');
  else if (st === 'error') finish(tr, 'error', tr.info.error || 'analysis failed');
  else if (st === 'closed') finish(tr, 'closed');
  else { paint(tr, 'loading'); schedule(tr, POLL_MS); }
}

/* terminal: settle the promise, run the READY chain, arrange the exit */
function finish(tr: Tracker, mode: 'ready' | 'error' | 'closed', text?: string){
  clearTimeout(tr.timer); tr.timer = 0;
  tr.t1 = Date.now();
  tr.settled = true;
  paint(tr, mode, text);
  const ok = mode === 'ready';
  tr.resolve(ok);
  if (ok){
    /* the strip refresh + chip activation (app.js's launcher callback) runs
       from HERE — the launcher's fate is irrelevant to it (the orphan hole).
       Deferred a microtask so a synchronous ready (dedupe) never re-enters
       the caller before trackOpen has returned its tracker. */
    tr.onReady.forEach(function(fn){
      Promise.resolve().then(function(){ return fn(tr.id!, tr.info); })
        .catch(function(e){ console.warn('open status: onReady', e); });
    });
  }
  /* a receipt with something to READ does not vanish in 2.5 s: a re-key replaced this
     target's database (the note names the old one, kept), and edits that could not be
     re-applied are a fact the user must be able to see — both wait for the × */
  if (mode === 'ready' && !tr.seed.rekeyed && !tr.info.replayFailed) tr.timer = setTimeout(function(){ dismiss(tr); }, READY_LINGER_MS);
  else if (mode === 'closed') tr.timer = setTimeout(function(){ dismiss(tr); }, CLOSED_LINGER_MS);
  /* error stays: the × is the only exit — a failure that vanished on its own
     would be a failure nobody saw */
}

/* UNDO RE-KEY: the way back the card promises. target.rekeyUndo closes the target without
   writing the new database and puts the old one (kept beside it) back; the two share their
   JEB key again, so this target stays closed until the other one is. The card keeps the
   server's sentence and waits for its ×. */
async function undoRekey(tr: Tracker){
  const ui = tr.ui;
  if (!ui || ui.undo.disabled) return;
  ui.undo.disabled = true;
  put(ui.undo, 'UNDOING…');
  const r = await tryOp('target.rekeyUndo', { target: tr.id });
  if (tr.dead) return;
  if (r.error){
    ui.undo.disabled = false;
    put(ui.undo, 'UNDO RE-KEY');
    ui.warn.hidden = false;
    put(ui.warn, 'undo not applied — ' + r.error.message);
    return;
  }
  tr.undone = r.data.note;
  paint(tr, 'closed');
  /* the target is gone: the strip follows, and a shell that was showing it lands on a live one */
  if (S.hooks.reseedTarget) S.hooks.reseedTarget().catch(function(e){ console.warn('open status: after undo', e); });
}

function dismiss(tr: Tracker){
  tr.dead = true;
  clearTimeout(tr.timer); tr.timer = 0;
  if (!tr.settled){ tr.settled = true; tr.resolve(false); }   // unreachable via × (hidden while loading); a waiter must never hang
  if (tr.el) tr.el.remove();
  if (cards.get(tr.id!) === tr) cards.delete(tr.id!);
}

/* mode: loading | link | ready | error | closed — the card's class and LED
   voice; text: the error message for `error` */
function paint(tr: Tracker, mode: Mode, text?: string){
  const ui = tr.ui;
  if (!ui) return;
  const info = tr.info || {};
  const name = tr.seed.name || tr.seed.path || tr.id;
  tr.el!.className = 'oscard ' + mode;
  ui.led.className = 'led ' + (mode === 'ready' ? 'g' : mode === 'error' ? 'v' : mode === 'loading' ? 'o' : '');
  let msg: string, state = info.state || 'loading';
  if (mode === 'ready'){
    const n = Number(info.classes) || 0;
    msg = (tr.seed.deduped ? 'already open' : 'ready') + ' · ' + n.toLocaleString('en-US') + ' classes';
    const rk = tr.seed.rekeyed;
    if (rk && rk.edits)
      msg += ' · re-applied ' + (info.recoveredEdits || 0) + ' of ' + rk.edits + ' edit' + (rk.edits === 1 ? '' : 's') + ' from history';
    else if (info.recoveredEdits)
      msg += ' · recovered ' + info.recoveredEdits + ' unsaved edit' + (info.recoveredEdits === 1 ? '' : 's');
    state = 'ready';
  } else if (mode === 'error'){
    msg = text || 'analysis failed';
    state = 'error';
  } else if (mode === 'closed'){
    msg = tr.undone ? 're-key undone' : 'closed before analysis finished';
    state = 'closed';
  } else if (mode === 'link'){
    msg = 'backend link down — retrying';
  } else {
    msg = info.message || ('analyzing ' + name + '…');
  }
  put(ui.msg, msg);
  /* the phase is the engine's own (analyze / load / index / xrefs …): live
     while loading, 'done' at READY, and at a failure it names where it died */
  const phase = mode === 'closed' ? '' : (info.phase || (mode === 'ready' ? 'done' : '…'));
  ui.phaseK.hidden = ui.phase.hidden = !phase;
  put(ui.phase, phase);
  put(ui.state, state);
  put(ui.time, elapsed(tr.t0, tr.t1 || Date.now()));
  /* the accept payload's asides, each on its own line. Caution voice: basisWarn (the
     sibling jdb2's recorded pull basis differs from this artifact) and warning (the heap
     may not hold this open — said, not refused). Plain voice: note — the dedupe /
     content-twin explanation, or what a re-key did (the database shared its JEB key with
     an open target: set aside and kept, the artifact analysed again, its history re-applied) */
  /* … and edits the open could not re-apply: a fact about what the user now has, so it
     speaks in the caution voice on its own line, not inside the green receipt */
  const lost = info.replayFailed
    ? info.replayFailed + ' edit' + (info.replayFailed === 1 ? '' : 's') + ' could not be re-applied — logs/workbench.log names ' + (info.replayFailed === 1 ? 'it' : 'them')
    : '';
  const warn = tr.undone ? '' : [tr.seed.basisWarn, tr.seed.warning, lost].filter(Boolean).join(' · ');
  const note = tr.undone || tr.seed.note || info.note;
  ui.warn.hidden = !warn;
  put(ui.warn, warn);
  ui.aside.hidden = !note;
  /* a re-key's note is the one thing on this card the user has to read (he clicked OPEN and
     got an analysis): body voice, not the faint aside of a dedupe remark */
  ui.aside.classList.toggle('act', !!(tr.seed.rekeyed || tr.undone));
  put(ui.aside, note || '');
  /* the way back, once the target has settled (while it analyses the close would wait out the
     whole stage — the same reason there is no ×) and until it has been used */
  ui.undo.hidden = !(tr.seed.rekeyed && tr.settled && !tr.undone && (mode === 'ready' || mode === 'error'));
  ui.x.hidden = !tr.settled;   // no × while loading: it would read as CANCEL, which does not exist
}

/* textContent writes only, and only on change: the card is a role=status
   live region — the 1.5s poll must not re-announce an unchanged line */
function put(el: HTMLElement, text: string | null | undefined){
  const s = text == null ? '' : String(text);
  if (el.textContent !== s) el.textContent = s;
}

/* whole seconds under a minute, m+ss above — the launcher said "42s" */
function elapsed(t0: number, t1: number){
  const s = Math.max(0, Math.round((t1 - t0) / 1000));
  return s < 60 ? s + 's' : Math.floor(s / 60) + 'm ' + String(s % 60).padStart(2, '0') + 's';
}

/* "JEB 5.42" — renderRibbon's ENGINE readout, the same major.minor cut */
function engineLabel(info: TargetInfo | null | undefined){
  if (!info || !info.engine) return '';
  const v = info.engineVersion ? ' ' + String(info.engineVersion).split('.').slice(0, 2).join('.') : '';
  return info.engine + v;
}
