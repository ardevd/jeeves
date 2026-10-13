// history.ts — HISTORY dock live from history.list; per-row UNDO/REDO; ⌘Z undoes, ⇧⌘Z redoes.
import { S, hook, escHtml as esc, tryOpT } from './hub.js';
import { dockUnavailable, notice } from './chrome.js';
import { loadSymbol, followIdentityChange } from '../views/code.js';
import type { HistoryEntry } from '../types.js';

/* an undo or a redo of a CLASS rename moves the class's identity like the rename did: the tabs, the
   tree row and the ‹ › history follow before the code reloads (persona pass 3, H1). Whether the name
   that comes back is the ORIGINAL is the rename.revert entry's own word (revert); for a plain rename
   the tree row decides (it remembers the original) — `restored` is left open for it. */
function followEntry(res: unknown, undo: boolean, tidAtCall: string | null): void {
  const r = res as { undone?: HistoryEntry; redone?: HistoryEntry } | undefined;
  const e = r ? (undo ? r.undone : r.redone) : undefined;
  if (!e || e.op !== 'rename' || e.kind !== 'class') return;
  const from = undo ? e.newAddr : e.addr, to = undo ? e.addr : e.newAddr;
  if (!from || !to) return;
  const restored = e.revert ? !undo : undefined;
  followIdentityChange({ kind: 'class', addr: from, newAddr: to, newName: undo ? e.oldName : e.newName, oldName: undo ? e.newName : e.oldName, restored }, from, tidAtCall);
}

/* the code pane after an undo or a redo: awaited and caught — a failed reload is said, not an
   unhandled rejection (fix-review 3 U2) */
async function reloadAfter(what: string): Promise<void> {
  try { await hook('reloadCode'); }
  catch (e){ notice(what + ' — but the view could not be refreshed: ' + String((e as Error).message || e) + '; reopen the class', { err: true }); }
}

function fmtTs(ts: string | number | undefined){
  const d = new Date(ts!);
  if (isNaN(d.getTime())) return ts || '';
  const p = (n: number) => (n < 10 ? '0' : '') + n;
  return p(d.getUTCHours()) + ':' + p(d.getUTCMinutes()) + ':' + p(d.getUTCSeconds()) + 'Z';
}

/* a parameters entry (an analyst's argument mapping): the per-index changes, positions first — not
   two comma lists that read as one rename; the full list is the row's title (persona pass 2026-10-08) */
function paramsRow(e: HistoryEntry): { html: string; title: string } {
  const ch = e.changes || [];
  const parts = ch.map(c => c.index + ' ' + (c.oldName || '?') + '→' + (c.cleared ? '(JEB)' : (c.newName || '?')) + (c.adopted ? ' (adopted)' : ''));
  const shown = parts.slice(0, 3).join(', ') + (parts.length > 3 ? ' +' + (parts.length - 3) : '');
  return {
    html: '<b>args</b> ' + esc(shown) + ' <span class="dnote">retained, unverified</span>',
    title: 'argument mapping — ' + parts.join(', ') + (e.source ? ' · source ' + e.source : '') + ' · analyst-selected, not compiler-verified',
  };
}

export async function loadHistory(): Promise<void>{
  const host = document.getElementById('history-rows');
  if (!host) return;
  const r = await tryOpT('history.list', {});
  if (r.error){ dockUnavailable('history-rows', 'history', r.error.message); return; }
  const rows = r.data || [];
  host.innerHTML = '';
  if (!rows.length){
    host.innerHTML = '<div class="drow"><span class="dnote">no edits yet — N renames land here</span></div>';
    return;
  }
  rows.forEach(function(e){
    const d = document.createElement('div');
    d.className = 'drow' + (e.undone ? ' undone' : '');
    /* a comment row: the glyph and the text, ellipsized by the row (not cut at 24 chars); the
       symbol column names the member when the owner is blank */
    const pr = e.kind === 'parameters' ? paramsRow(e) : null;
    const what = e.op === 'comment'
      ? '<b>✎</b> ' + esc(String(e.newText || '').replace(/\s+/g, ' '))
      : pr ? pr.html
      : '<s>' + esc(e.oldName || '') + '</s> → <b>' + esc(e.newName || '') + '</b>' + (e.revert ? ' <span class="dnote">original</span>' : '') + (e.matchesRetainedName ? ' <span class="dnote" title="an agent\'s argument name equal to a retained MethodParameters entry — metadata, unverified">= retained</span>' : '');
    const addr = String(e.newAddr || e.addr || '');
    const member = addr.includes('->') ? addr.replace(/^.*->/, '').replace(/\(.*$/, '').replace(/:.*$/, '') : '';
    /* dotted, as everywhere else in the app: Lm76/RootSpan; → m76.RootSpan, a member appended */
    const dotted = function(a: string){ const cls = a.replace(/->.*$/, ''); return /^L.*;$/.test(cls) ? cls.slice(1, -1).replace(/\//g, '.').replace(/\$/g, '.') : cls; };
    const where = (e.owner ? String(e.owner) : dotted(addr)) + (member ? '.' + member : '');
    const actor = e.actor && e.actor !== 'human' ? ' <span class="led p" title="an agent\'s edit"></span>' : '';
    d.innerHTML = '<span class="dren"' + (pr ? ' title="' + esc(pr.title) + '"' : '') + '>' + what + '</span>' +
      '<span class="dsym" title="' + esc(addr) + '">' + esc(where) + '</span>' + actor +
      '<button class="hundo">' + (e.undone ? 'REDO' : 'UNDO') + '</button>' +
      '<span class="dt">' + fmtTs(e.ts) + '</span>';
    // D1: clicking the owner symbol jumps to the edited class. Use the OWNER
    // (class FQCN), not the raw member addr — loadSymbol keys tabs on it and
    // a raw addr defeats tab dedupe (verify-round regression)
    d.querySelector('.dsym')!.addEventListener('click', async function(){
      if (!e.owner) return;
      try { await loadSymbol(e.owner); }
      catch (err){ notice('cannot open ' + e.owner + ' — ' + String((err as Error).message || err) + ' (the class may carry another name now: open it from the tree)', { err: true }); }
    });
    d.querySelector('.hundo')!.addEventListener('click', async function(){
      const undo = !e.undone;
      const tidAtCall = S.currentTid;
      const rr = await tryOpT(undo ? 'history.undo' : 'history.redo', { id: e.id });
      if (rr.error){ notice((undo ? 'not undone — ' : 'not redone — ') + rr.error.message, { err: true }); return; }   // said, not only logged (a refused move-back used to vanish)
      if (tidAtCall !== S.currentTid) return;   // the viewed target changed meanwhile: not this view's to reload
      followEntry(rr.data, undo, tidAtCall);
      await loadHistory();
      await reloadAfter(undo ? 'undone' : 'redone');
    });
    host.appendChild(d);
  });
}

/* ⌘Z: undo the latest applied edit on the active target */
export async function undoLatest(): Promise<void>{
  const tidAtCall = S.currentTid;
  const r = await tryOpT('history.undo', {});
  if (r.error){
    if (/nothing to (undo|redo)/.test(r.error.message)){   // the empty stack — a dead cursor (also E_NOTFOUND) is not (fix-review r3 #4)
      blinkHistoryTab();
      return;
    }
    notice('not undone — ' + r.error.message, { err: true });
    return;
  }
  if (tidAtCall !== S.currentTid) return;
  followEntry(r.data, true, tidAtCall);
  await loadHistory();
  await reloadAfter('undone');
}

/* ⇧⌘Z: re-apply the newest undone entry (the manual promised the key; the HISTORY row button is the other door) */
export async function redoLatest(): Promise<void>{
  const tidAtCall = S.currentTid;
  const r = await tryOpT('history.redo', {});
  if (r.error){
    if (/nothing to (undo|redo)/.test(r.error.message)){ blinkHistoryTab(); return; }
    notice('not redone — ' + r.error.message, { err: true });
    return;
  }
  if (tidAtCall !== S.currentTid) return;
  followEntry(r.data, false, tidAtCall);
  await loadHistory();
  await reloadAfter('redone');
}

// QA 17: empty stack → silent, just blink the HISTORY dock tab
function blinkHistoryTab(): void {
  const tab = document.querySelector<HTMLElement>('.dtab[data-panel="history"]');
  if (tab){ tab.style.outline = '1px solid var(--org)'; setTimeout(function(){ tab.style.outline = ''; }, 400); }
}

export function initHistory(): void {
  S.hooks.historyChanged = loadHistory;
  S.hooks.undoHistory = undoLatest;
  S.hooks.redoHistory = redoLatest;
  // refresh when the HISTORY dock tab gets focus (cheap: on every dock tab click)
  // dock tabs switch on pointerup (the drag handler preventDefaults pointerdown,
    // killing click) — refresh on pointerup or the panel shows boot-stale data
  document.addEventListener('pointerup', function(e){
    const t = e.target as HTMLElement;
    const tab = t.closest('.dtab');
    if (tab && tab.getAttribute('data-panel') === 'history') loadHistory();
  });
}
