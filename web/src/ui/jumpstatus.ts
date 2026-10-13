// jumpstatus.ts — JUMP STATUS card: one floating, NON-modal card for a
// navigation in flight, in the open-status corner (bottom-right of the
// center surface, the #openstatus host openstatus.js owns).
//
// Why this exists (user complaint 2026-09-08, STRINGS): "when clicking on a
// string xref it takes a long time for the navigation to take effect, because
// the class gets decompiled etc. — we need a popup telling users what is
// happening, e.g. 'decompiling <X>', and giving the progress". Every jump in
// the workbench awaits loadSymbol (code.js): the code view sits there showing
// the PREVIOUS class for the seconds-to-minutes a JEB decompile of a big class
// takes, with nothing on screen saying why. segU wired the card into the two
// strings jump paths only; the follow-up directive (segV, same day): "The jump
// progress should be on every xref jump, not just every string jump."
//
// Scope (segV): the card is loadSymbol's OWN. code.js begins it at entry,
// done()s when the load resolves, fail()s on the throw — and every jump origin
// funnels through that one function: RELATION/xref rows, the symbol tree
// (click, ↵, menu Open), map node and chain jumps, bookmark/tag/flow/history
// rows, search hits, dbl-click go-to-definition and jumpToCode (chrome.js →
// the gotoDef hook → loadSymbol), ‹/›, tab switches, the target-switch
// restore, agent proposals, the strings paths, and the in-place reload after
// a rename or comment (honest: that IS a decompile). No origin wraps its own
// call (segU's strings wrap is gone: two cards for one gesture would be the
// newest-gesture rule fighting itself). One call site, every origin covered.
//
// What "progress" honestly is here: code.decompile is ONE request with no
// progress channel (the SSE 'progress' bus carries the analysis phases of a
// target.open, not per-class decompiles), so the card shows what the frontend
// actually knows — the phase it is in, the symbol it asked for, and the
// elapsed clock. No bar. loadSymbol has ONE phase, decompile (its class-info
// and smali fetches ride under it: the same class, normally tens of ms); the
// handle's phase() stays for a caller that knows more than loadSymbol does.
// No origin tag on the head — loadSymbol does not know who called it, and the
// symbol is the story; the open-status card's engine slot stays hidden.
//
// Lifecycle:  loading  mounted only past SHOW_DELAY_MS — a jump that lands
//                      inside that never flashes a card (a warm class decompiles
//                      in tens of ms; every tab switch and tree click on an
//                      already-decompiled class would otherwise blink one)
//             done     the landing IS the receipt (the caret is on the member):
//                      the card goes at once, no green linger
//             error    the shell's vermillion voice, × shown, then a linger —
//                      NOT the open-status card's stay-until-× rule: an analysis
//                      failure is a durable fact about a target, a jump failure
//                      is about a gesture the row is still there to retry
// One card at a time: the newest gesture owns the corner. A jump begun while
// another is in flight supersedes the older card (the older loadSymbol still
// runs — whichever lands last paints the code view, as before — but its card
// is gone and its later phase/done/fail calls are no-ops). The same rule
// carries the owner fallbacks (relation.js rows, map.js jumpNode, strings
// landOnXref: a member that will not decompile → a second loadSymbol on its
// class): the member's card paints its error inside fail(), the throw reaches
// the caller's catch, and the fallback's beginJump dismisses that card — all
// in one microtask run, before any frame paints. No vermillion flash; the
// corner shows the owner's card; only a fallback that ALSO fails lingers.
// Not an escape citizen (hub.js stack untouched) and not a .wbmodal (modalUp()
// must not see it): the workbench stays fully interactive underneath.

const SHOW_DELAY_MS = 300;      // under this, no card — the landing beats the paint
const TICK_MS = 1000;           // the elapsed readout's clock
const ERROR_LINGER_MS = 6000;   // the vermillion voice stays long enough to be read; × exits sooner
const VERB = 'decompiling';     // the message line's verb — the user's own words ("decompiling <X>")

/** the handle beginJump hands its caller (loadSymbol, code.js) */
export interface JumpHandle {
  phase(name: string, msg?: string): void;
  done(): void;
  fail(err: unknown): void;
  dismiss(): void;
}

/** the card's textContent sinks, resolved once at mount */
interface CardUi {
  led: HTMLElement;
  name: HTMLElement;
  x: HTMLElement;
  msg: HTMLElement;
  phase: HTMLElement;
  state: HTMLElement;
  time: HTMLElement;
}

/** the ONE live jump's state (the module's `cur`) */
interface Tracker {
  sym: string;
  label: string;
  short: string;
  t0: number;
  t1: number;
  phase: string;
  msg: string;
  settled: boolean;
  dead: boolean;
  el: HTMLElement | null;
  ui: CardUi | null;
  showTimer: number;
  tick: number;
  exitTimer: number;
}

let host: HTMLElement | null = null, tpl: HTMLTemplateElement | null = null;
let cur: Tracker | null = null;                 // the ONE live tracker: the newest gesture owns the corner

export function initJumpStatus(): void {
  host = document.getElementById('openstatus');   // the open-status cards' host: one corner to look at
  tpl = document.getElementById('jumpcard-tpl') as HTMLTemplateElement | null;
}

/* Begin tracking a jump to `sym` (a JEB addr, a dotted name, a 'from' site —
   jumpLabel formats it: the head carries the SHORT form (Class.member — a
   deep package would push the informative tail out of the head's ellipsis),
   the message line the full one, the raw form rides the head's tooltip).
   Returns a handle:
     phase(name, msg)  a new phase, an optional message replacing the verb line
     done()            landed — the card is dismissed at once
     fail(err)         the error voice (mounts even under the delay: a refused
                       symbol must be seen) and the linger; err is the thrown
                       OpError — its message is the line — or a string
     dismiss()         drop the card, no voice
   Never throws; every method is a no-op once the tracker is dead. The ONE
   caller is loadSymbol (code.js) — read the header before adding another. */
export function beginJump(sym: unknown): JumpHandle {
  if (cur) dismiss(cur);          // the newest gesture owns the corner — a failed card too (the owner fallbacks)
  const tr: Tracker = { sym: String(sym == null ? '' : sym), label: jumpLabel(sym), short: jumpLabel(sym, true),
               t0: Date.now(), t1: 0, phase: 'decompile', msg: '',
               settled: false, dead: false, el: null, ui: null,
               showTimer: 0, tick: 0, exitTimer: 0 };
  cur = tr;
  if (host && tpl)                // no host (bare harness) → the handle still works, headless
    tr.showTimer = setTimeout(function(){ if (!tr.dead && !tr.settled) mount(tr); }, SHOW_DELAY_MS);
  return {
    phase: function(name, msg){
      if (tr.dead || tr.settled) return;
      tr.phase = name || tr.phase;
      tr.msg = msg || '';
      paint(tr, 'loading');
    },
    done: function(){ finish(tr, 'done'); },
    fail: function(text){ finish(tr, 'error', text); },
    dismiss: function(){ dismiss(tr); },
  };
}

/* "pkg.C.m" for a JEB addr `Lpkg/C;->m(desc)ret`, "pkg.C" for a class addr,
   a 'from' site minus its +offset; a dotted name passes through. `short`:
   "C.m" / "C" — the class's simple name plus the member (a dotted name keeps
   its last segment, two when the last is lowercase: the tree's member rule).
   The raw symbol stays in the tooltip — this is the readable line, not the
   identity. */
function jumpLabel(sym: unknown, short?: boolean){
  const s = String(sym == null ? '' : sym).split('+')[0];
  const m = /^L([^;]+);(?:->([^(]*).*)?$/.exec(s);
  if (m){
    const cls = m[1].replace(/\//g, '.');
    const c = short ? cls.slice(cls.lastIndexOf('.') + 1) : cls;
    return c + (m[2] ? '.' + m[2] : '');
  }
  if (!short) return s;
  const segs = s.split('.');
  const last = segs[segs.length - 1] || '';
  return segs.slice(-((segs.length > 1 && /^[a-z<]/.test(last)) ? 2 : 1)).join('.');
}

function mount(tr: Tracker){
  const el = tpl!.content.firstElementChild!.cloneNode(true) as HTMLElement;
  const q = function(c: string){ return el.querySelector<HTMLElement>(c)!; };
  tr.ui = { led: q('.led'), name: q('.os-name'), x: q('.os-x'),
            msg: q('.os-msg'), phase: q('.os-phase'), state: q('.os-state'), time: q('.os-time') };
  /* symbol text lands via textContent only — never innerHTML (openstatus.js's rule) */
  put(tr.ui.name, tr.short || tr.sym);
  tr.ui.name.setAttribute('data-tip', tr.sym);
  /* the open-status card's engine slot: nothing honest to put there (no origin
     tag since segV — loadSymbol does not know its caller; the engine is known
     only once the decompile answers) — hidden, not a blank flex gap */
  q('.os-eng').hidden = true;
  tr.ui.x.addEventListener('click', function(){ dismiss(tr); });
  tr.el = el;
  host!.appendChild(el);
  paint(tr, tr.settled ? 'error' : 'loading', tr.msg);
  /* the clock ticks only while a card shows and the jump is in flight */
  if (!tr.settled) tr.tick = setInterval(function(){ if (tr.ui) put(tr.ui.time, elapsed(tr.t0, Date.now())); }, TICK_MS);
}

/* terminal: done → the landing is the receipt, gone at once (a card never
   shown never shows); error → mount if the delay had not, paint, linger */
function finish(tr: Tracker, mode: 'done' | 'error', text?: unknown){
  if (tr.dead || tr.settled) return;
  clearTimeout(tr.showTimer); tr.showTimer = 0;
  clearInterval(tr.tick); tr.tick = 0;
  tr.settled = true;
  tr.t1 = Date.now();
  if (mode === 'done'){ dismiss(tr); return; }
  tr.msg = errText(text);
  if (!tr.el && host && tpl) mount(tr);
  else paint(tr, 'error', tr.msg);
  tr.exitTimer = setTimeout(function(){ dismiss(tr); }, ERROR_LINGER_MS);
}

/* the thrown OpError's message ("E_NOTFOUND: …"), a plain string, or the
   default — the card's error line (segU kept this helper in the strings view;
   the one fail() caller hands over whatever loadSymbol threw) */
function errText(e: unknown){
  if (e && typeof e === 'object' && 'message' in e && e.message) return String(e.message);
  return e ? String(e) : 'jump failed';
}

function dismiss(tr: Tracker){
  tr.dead = true;
  clearTimeout(tr.showTimer); clearInterval(tr.tick); clearTimeout(tr.exitTimer);
  tr.showTimer = tr.tick = tr.exitTimer = 0;
  if (tr.el) tr.el.remove();
  tr.el = null; tr.ui = null;
  if (cur === tr) cur = null;
}

/* mode: loading | error — the card's class and LED voice (the open-status
   card's: orange pulse = in flight, vermillion = failed); text: the error */
function paint(tr: Tracker, mode: 'loading' | 'error', text?: string){
  const ui = tr.ui;
  if (!ui) return;
  tr.el!.className = 'oscard jcard ' + mode;
  ui.led.className = 'led ' + (mode === 'error' ? 'v' : 'o');
  /* the message is the verb line the user asked for — "decompiling <X>…",
     X in full — or a phase's own text (phase(name, msg)), or the error */
  put(ui.msg, mode === 'error' ? (text || 'jump failed') : (tr.msg || (VERB + ' ' + (tr.label || tr.sym) + '…')));
  put(ui.phase, tr.phase);
  put(ui.state, mode === 'error' ? 'failed' : 'loading');
  put(ui.time, elapsed(tr.t0, tr.t1 || Date.now()));
  ui.x.hidden = mode !== 'error';   // no × while loading: it would read as CANCEL, and a decompile is not interruptible
}

/* textContent writes only, and only on change: the card is a role=status
   live region — the 1s tick must not re-announce an unchanged line */
function put(el: HTMLElement, text: string | null | undefined){
  const s = text == null ? '' : String(text);
  if (el.textContent !== s) el.textContent = s;
}

/* whole seconds under a minute, m+ss above — the open-status card's readout */
function elapsed(t0: number, t1: number){
  const s = Math.max(0, Math.round((t1 - t0) / 1000));
  return s < 60 ? s + 's' : Math.floor(s / 60) + 'm ' + String(s % 60).padStart(2, '0') + 's';
}
