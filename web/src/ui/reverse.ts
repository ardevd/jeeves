// ui/reverse.ts — "reverse with agent": spawn a reversing mission on a symbol
// at a chosen DEPTH. The producers are the code context menu, the tree menu,
// the RELATION rail's ⚡ button and the palette (navigation.ts / palette.ts);
// this module owns what they share — which symbol a gesture reverses, the
// goal/scope/target the mission gets, and the spawn itself.
//   quick — the subject alone: one pass, a rename + a comment, budget 120k
//   deep  — the subject and its call tree (3 hops), budget 500k
// The depth is a mission field (agent.spawn {depth}); the backend turns it into
// the prompt's brief, the turn cap and the default budget (llm.Depth).
import { S, methodAt, hook } from './hub.js';
import { tryOp } from '../api.js';
import { notice, selectCenter } from './chrome.js';
import { symbolAtCaret } from '../views/code.js';
import type { CaretHit, OpParams } from '../types.js';

export type ReverseDepth = 'quick' | 'deep';
/** what a gesture reverses. `fqcn` is the dotted class when the caller has a
 *  better one than the handle gives (a tree row's data-fqcn: its handle may be
 *  a bare simple name under the mock — fix-review reverse r1 M8) */
export interface ReverseSubject { symbol: string; name: string; kind: 'method' | 'class'; fqcn?: string }

/** the two depths, in menu order, with the tooltip each row carries. The
 *  cap that binds is the TURN cap — the token budget only meters http
 *  providers (the claude CLI reports no usage — r2 R2-7). */
export const DEPTHS: { depth: ReverseDepth; tip: string }[] = [
  { depth: 'quick', tip: 'the method alone — one pass: a rename and a comment · cap 20 CLI turns (or 120k tokens on an http provider) · write-scope: its class' },
  { depth: 'deep',  tip: 'the method and its call tree — 3 hops, every member it can name · cap 80 CLI turns (or 500k tokens on an http provider) · write-scope: the viewed target' },
];
/** the pills' tips for THIS subject: a class is not "a method alone" (user 2026-09-20) */
export function depthTip(sub: ReverseSubject, depth: ReverseDepth): string {
  if (sub.kind === 'class')
    return depth === 'quick'
      ? 'the class alone — one pass over its outline and key methods: name the class and its members, a comment each · cap 20 CLI turns (or 120k tokens on an http provider) · write-scope: the class'
      : 'the class and its neighbourhood — every method, its superclass and interfaces, the classes it uses and that use it, 3 hops · cap 80 CLI turns (or 500k tokens on an http provider) · write-scope: the viewed target';
  return DEPTHS.find(function(d){ return d.depth === depth; })!.tip;
}

/* not a subject: a platform type (`String`, `Intent` — not in the dex, every
   op on it is E_NOTFOUND and the loop would spend its cap re-resolving it)
   or a compiler thunk; the gesture falls through to the enclosing method
   (r2 R2-3 / r1 L7). androidx and kotlin ARE in the dex, so they stay. */
const FOREIGN = /^L(java|javax|android|dalvik|sun|org\/(w3c|xml|json))\//;
const SYNTH = /\$\$(ExternalSyntheticLambda|InlinedLambda|Lambda)/;

/* the last segment of a class handle: `LX/000;` → 000, com.x.C → C */
function simpleOf(cls: string): string {
  const s = cls.startsWith('L') && cls.endsWith(';') ? cls.slice(1, -1) : cls;
  return s.split(/[/.$]/).pop() || s;
}

/* the class a handle belongs to, DOTTED — the pkg: scope's grammar
   (AgentManager.checkScope inPrefix: `L<prefix with / for .>` or the dotted
   prefix, boundary-checked) */
export function dottedClassOf(sym: string): string {
  let cls = sym.includes('->') ? sym.slice(0, sym.indexOf('->')) : sym;
  if (cls.startsWith('L') && cls.endsWith(';')) return cls.slice(1, -1).replace(/\//g, '.');
  /* dotted (mock) Class.member → Class, the relation.ts classOf rule */
  const last = cls.slice(cls.lastIndexOf('.') + 1);
  if (cls.includes('.') && /^[a-z<]/.test(last)) cls = cls.slice(0, cls.lastIndexOf('.'));
  return cls;
}

/* what a bare handle reverses: a method → itself; a field → its class (a
   field has no body to reverse); a class → itself. `name` is the display
   name when the caller has one (a tree row's, the RELATION pane's). */
export function subjectOfHandle(sym: string | null | undefined, name?: string): ReverseSubject | null {
  if (!sym) return null;
  const s = String(sym);
  if (/->[^:]*\(/.test(s))
    return { symbol: s, name: name || s.slice(s.indexOf('->') + 2).split('(')[0], kind: 'method' };
  if (s.includes('->')){                                  // a field: reverse the class that holds it
    const cls = s.slice(0, s.indexOf('->'));
    return { symbol: cls, name: simpleOf(cls), kind: 'class' };
  }
  const last = s.slice(s.lastIndexOf('.') + 1);
  if (!s.endsWith(';') && s.includes('.') && /^[a-z<]/.test(last))
    return { symbol: s, name: name || last, kind: 'method' };   // dotted mock member
  return { symbol: s, name: name || simpleOf(s), kind: 'class' };
}

/* what a right-click / the caret reverses, in order: the METHOD token under
   it (a call site reverses the callee — the token is the method, like Go to
   definition); a CLASS token under it (a type name in a body is as explicit
   a subject as a method name — r1 M10: the enclosing method used to win over
   it, so the menu's head and its action named different symbols); else the
   method enclosing the caret line (a local, a field, a keyword in a body);
   else the open class. */
export function subjectOfHit(hit: CaretHit | null): ReverseSubject | null {
  if (hit && hit.symbol && hit.kind === 'method')
    return { symbol: hit.symbol, name: hit.name || subjectOfHandle(hit.symbol)!.name, kind: 'method' };
  if (hit && hit.symbol && hit.line !== undefined && (hit.kind === 'class' || hit.kind === 'interface' || hit.kind === 'type')
      && !FOREIGN.test(hit.symbol) && !SYNTH.test(hit.symbol))
    return { symbol: hit.symbol, name: hit.name || simpleOf(hit.symbol), kind: 'class' };
  const m = S.caretReady ? methodAt(S.javaMethods, S.caret.line + 1) : null;
  if (m && m.addr) return { symbol: m.addr, name: m.name, kind: 'method' };
  const cls = S.currentSym.addr || S.currentSym.fqcn || S.currentFqcn;
  return cls ? { symbol: cls, name: S.currentSym.name || simpleOf(cls), kind: 'class' } : null;
}

/** the caret's subject (the palette, the ⚡ button's fallback): the code surface only */
export function subjectAtCaret(): ReverseSubject | null {
  if (S.center !== 'code' || !S.caretReady || !S.codeLines.length) return null;
  return subjectOfHit(symbolAtCaret());
}

/** menu row text — the subject is named, the head may be a different token;
 *  the depth is the row's inline pills, not part of the label */
export function reverseLabel(sub: ReverseSubject): string {
  return '⚡ Reverse ' + sub.name;
}

/* spawn the mission. Goal: the exact handle every op takes plus the dotted
   name for the human reading the card. Target: the VIEWED one — the backend
   pins every tool call to it (Mission.target), so a chip click mid-mission
   cannot re-aim the agent. Write-scope: quick stays inside the subject's
   class; deep may name anything in the viewed target, and degrades to the
   class when no target is viewed (never to NO scope — r1 M1). Model, effort
   and caps: NOT chosen here — the REVERSING AGENT window's settings for the
   depth (TaskSettings; no default: an unconfigured depth spawns nothing). The outcome lands in
   a notice (the mission runs in AGENTS, not here): a spawn the backend refused,
   or a mission that blocked on the spot (no CLI, no key, the scripted
   provider), says why. */
let spawning = false;
export async function spawnReverse(sub: ReverseSubject, depth: ReverseDepth): Promise<void> {
  if (spawning) return;                                   // a double-click is one mission, not two
  const cls = dottedClassOf(sub.fqcn || sub.symbol);
  const goal = 'reverse ' + sub.kind + ' ' + sub.symbol
    + (sub.kind === 'method' ? ' (' + cls + '.' + sub.name + ')' : '');
  const scope = depth === 'deep' && S.currentTid ? 'target:' + S.currentTid : 'pkg:' + cls;
  const params: OpParams = { goal, depth, scope };
  if (S.currentTid) params.target = S.currentTid;
  spawning = true;
  try {
    const r = await tryOp('agent.spawn', params);
    if (r.error){
      /* the depth has no model (nothing is assumed installed): the notice is
         the way to the REVERSING AGENT window */
      const detail = r.error.payload && r.error.payload.error ? String(r.error.payload.error.detail || '') : '';
      const unconfigured = /^task-unconfigured/.test(detail);   // the backend's contract (AgentManager.spawn), not its prose
      notice(unconfigured ? '⚡ ' + depth + ' reverse is not configured — click to pick its provider and model' : 'reverse ' + sub.name + ': ' + r.error.message,
        { err: true, onClick: unconfigured ? function(){ hook('openReverseSettings'); } : undefined });
      return;
    }
    const m = (r.data || {}) as { id?: string; state?: string; reason?: string };
    const id = m.id || '?';
    const open = function(){ selectCenter('agents'); };
    if (m.state === 'blocked'){
      notice('mission ' + id + ' blocked: ' + (m.reason || 'see AGENTS'), { err: true, onClick: open });
      return;
    }
    /* "spawned", not "running": a claude-mode child can die seconds later
       (auth, turn cap) — the card is the truth, this is the receipt */
    notice('⚡ ' + depth + ' reverse of ' + sub.name + ' — mission ' + id + ' spawned · click or ⌃5 to watch', { onClick: open });
  } finally { spawning = false; }
}

/* "Rename tree branch" (RELATION, user 2026-09-20): one QUICK mission per method of a branch — the
   row and every method shown under it — run one after the other (each names its method and the
   class that holds it). Framework classes are skipped — nothing to rename there. A chain runs at most one
   at a time; a second request while one runs is refused with a notice. */
let chainRunning = false;
export async function spawnReverseChain(subjects: ReverseSubject[]): Promise<void> {
  if (chainRunning){ notice('a tree-branch rename is already running — watch it in AGENTS ⌃5', { err: true }); return; }
  const todo = subjects.filter(function(sub){ return sub.kind === 'method' && !FOREIGN.test(sub.symbol) && !SYNTH.test(sub.symbol); });
  const skipped = subjects.length - todo.length;
  if (!todo.length){ notice('nothing to rename in this branch' + (skipped ? ' — framework methods only' : '')); return; }
  chainRunning = true;
  const open = function(){ selectCenter('agents'); };
  try {
    for (let i = 0; i < todo.length; i++){
      const sub = todo[i];
      const cls = dottedClassOf(sub.fqcn || sub.symbol);
      const goal = 'reverse method ' + sub.symbol + ' (' + cls + '.' + sub.name + ')';
      const params: OpParams = { goal, depth: 'quick', scope: 'pkg:' + cls };
      if (S.currentTid) params.target = S.currentTid;
      const r = await tryOp('agent.spawn', params);
      if (r.error){
        const detail = r.error.payload && r.error.payload.error ? String(r.error.payload.error.detail || '') : '';
        const unconfigured = /^task-unconfigured/.test(detail);
        notice(unconfigured ? '⚡ quick reverse is not configured — click to pick its provider and model' : 'tree branch stopped at ' + sub.name + ': ' + r.error.message,
          { err: true, onClick: unconfigured ? function(){ hook('openReverseSettings'); } : undefined });
        return;
      }
      const m = (r.data || {}) as { id?: string; state?: string; reason?: string };
      if (m.state === 'blocked'){ notice('tree branch stopped: mission ' + m.id + ' blocked — ' + (m.reason || 'see AGENTS'), { err: true, onClick: open }); return; }
      notice('⚡ tree branch ' + (i + 1) + '/' + todo.length + ': ' + cls + '.' + sub.name + ' — mission ' + m.id + ' · ⌃5 to watch', { onClick: open });
      /* wait for it: the next method's tree reads better once this one is named */
      const done = await waitMission(String(m.id || ''));
      if (!done){ notice('tree branch stopped at ' + sub.name + ' — mission ' + m.id + ' did not finish (see AGENTS)', { err: true, onClick: open }); return; }
    }
    notice('⚡ tree branch done — ' + todo.length + ' method' + (todo.length > 1 ? 's' : '') + ' reversed' + (skipped ? ' · ' + skipped + ' framework method' + (skipped > 1 ? 's' : '') + ' skipped' : ''), { onClick: open });
  } finally { chainRunning = false; }
}
/* true when the mission ended (done) or parked at its turn/budget cap (paused — a quick mission
   files its names early, so a cap is a finish for the chain's purpose); false when it stopped,
   blocked, or took over 20 minutes */
async function waitMission(id: string): Promise<boolean> {
  const t0 = Date.now();
  while (Date.now() - t0 < 20 * 60 * 1000){
    await new Promise(function(r){ setTimeout(r, 3000); });
    const r = await tryOp('agent.missions', {});
    if (r.error) continue;
    const m = ((r.data || []) as { id?: string; state?: string }[]).find(function(x){ return x.id === id; });
    if (!m) return false;
    if (m.state === 'done' || m.state === 'paused') return true;
    if (m.state === 'blocked' || m.state === 'stopped' || m.state === 'interrupted') return false;
  }
  return false;
}

/* "⚡ Reverse and annotate call" (user 2026-09-20): a DEEP-capped mission on a call site — the callee
   in depth, every symbol within ten lines of the call in the caller, every argument named, and a
   comment on the call site itself. The goal carries the callee, the caller and the call's text so
   the agent finds the site without line numbers (a method-only decompile numbers differently). */
export async function spawnAnnotateCall(callee: ReverseSubject, caller: { symbol: string; name: string; line?: number | null }, lineText: string, caretLine?: number): Promise<void> {
  const calleeCls = dottedClassOf(callee.fqcn || callee.symbol);
  const line = lineText.replace(/\s+/g, ' ').trim().slice(0, 220);
  /* where the call sits in the CALLER's method-only decompile (its declaration is line 1 there):
     a long caller exceeds the decompile cap and the agent had to window blind (agent-13, 2026-09-20) */
  const rel = caretLine != null && caller.line ? Math.max(1, caretLine - caller.line + 1) : null;
  const goal = 'reverse call ' + callee.symbol + ' from ' + caller.symbol + ' at: `' + line + '`'
    + (rel ? ' (about line ' + rel + ' of the caller — decompile it with window "' + Math.max(1, rel - 15) + ',' + (rel + 15) + '" when the method is long)' : '')
    + ' (' + calleeCls + '.' + callee.name + ' called in ' + caller.name + ')';
  const params: OpParams = { goal, depth: 'deep' };
  if (S.currentTid) params.target = S.currentTid;
  params.scope = S.currentTid ? 'target:' + S.currentTid : 'pkg:' + calleeCls;
  const r = await tryOp('agent.spawn', params);
  const open = function(){ selectCenter('agents'); };
  if (r.error){
    const detail = r.error.payload && r.error.payload.error ? String(r.error.payload.error.detail || '') : '';
    const unconfigured = /^task-unconfigured/.test(detail);
    notice(unconfigured ? '⚡ deep reverse is not configured — click to pick its provider and model' : 'annotate call ' + callee.name + ': ' + r.error.message,
      { err: true, onClick: unconfigured ? function(){ hook('openReverseSettings'); } : undefined });
    return;
  }
  const m = (r.data || {}) as { id?: string; state?: string; reason?: string };
  if (m.state === 'blocked'){ notice('mission ' + m.id + ' blocked: ' + (m.reason || 'see AGENTS'), { err: true, onClick: open }); return; }
  notice('⚡ reverse and annotate the call to ' + callee.name + ' — mission ' + m.id + ' spawned · click or ⌃5 to watch', { onClick: open });
}
