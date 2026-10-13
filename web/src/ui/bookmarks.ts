// bookmarks.ts — BOOKMARKS dock tab: rows grouped by class (collapsible),
// click jumps to symbol:line, ⌘D toggles a bookmark on the caret line.
import { S, escHtml as esc, tryOpT } from './hub.js';
import { dockUnavailable } from './chrome.js';
import { loadSymbol, gotoLine } from '../views/code.js';
import type { Bookmark } from '../types.js';

let wired = false;
let cache: Bookmark[] = [];

export async function loadBookmarks(): Promise<void>{
  const r = await tryOpT('bookmark.list', {});
  if (r.error){ cache = []; dockUnavailable('bm-rows', 'bookmarks', r.error.message); return; }
  cache = r.data || [];
  const host = document.getElementById('bm-rows')!;
  host.innerHTML = '';
  if (!cache.length){
    host.innerHTML = '<div class="drow"><span class="dnote">no bookmarks — ⌘D toggles one at the caret</span></div>';
    return;
  }
  // group by class label
  const groups = new Map<string, Bookmark[]>();
  cache.forEach(function(b){
    const g = b.label || String(b.symbol);
    if (!groups.has(g)) groups.set(g, []);
    groups.get(g)!.push(b);
  });
  for (const [label, rows] of groups){
    const gh = document.createElement('div');
    gh.className = 'sgh';
    gh.setAttribute('data-t', '');
    gh.innerHTML = '<span class="caret">▾</span><span>' + esc(label) + '</span>' +
      '<span class="eng">' + rows.length + '</span>';
    const kids = document.createElement('div');
    rows.forEach(function(b){
      const row = document.createElement('div');
      row.className = 'drow';
      row.innerHTML = '<span class="led b"></span><span class="dsym" style="cursor:pointer">:' +
        esc(b.line) + '</span><span class="dnote">' + esc(b.note || '') + '</span>' +
        '<span class="dt">' + esc(String(b.ts || '').slice(11, 19) + 'Z') + '</span>' +
        '<button class="hundo" title="remove bookmark">✕</button>';
      row.querySelector('.dsym')!.addEventListener('click', async function(){
        if (b.addr) {
          await loadSymbol(b.addr);
          gotoLine(b.line as number);
        }
      });
      row.querySelector('.hundo')!.addEventListener('click', async function(){
        await tryOpT('bookmark.rm', { id: b.id });
        await loadBookmarks();
        if (S.hooks.marksChanged) S.hooks.marksChanged();
      });
      kids.appendChild(row);
    });
    gh.addEventListener('click', function(){
      kids.style.display = kids.style.display === 'none' ? '' : 'none';
      gh.querySelector('.caret')!.classList.toggle('cl', kids.style.display === 'none');
    });
    host.appendChild(gh);
    host.appendChild(kids);
  }
}

/* ⌘D — toggle a bookmark on the caret line of the open class */
export async function toggleBookmark(): Promise<void>{
  const classAddr = S.currentSym && (S.currentSym.addr || S.currentSym.fqcn);
  if (!classAddr || !S.caretReady) return;
  const line = S.caret.line + 1;
  const existing = cache.find(function(b){
    const bClass = String(b.addr || b.symbol).split('->')[0];
    return bClass === classAddr && +b.line === line;
  });
  if (existing) {
    await tryOpT('bookmark.rm', { id: existing.id });
  } else {
    await tryOpT('bookmark.add', { symbol: classAddr, line: line });   // writes bind the viewed target (GUI review finding 3)
  }
  await loadBookmarks();
  if (S.hooks.marksChanged) S.hooks.marksChanged();
}

export function initBookmarks(): void {
  if (wired) return;
  wired = true;
  S.hooks.toggleBookmark = toggleBookmark;
  // dock tabs switch on pointerup (the drag handler preventDefaults pointerdown,
    // killing click) — refresh on pointerup or the panel shows boot-stale data
  document.addEventListener('pointerup', function(e){
    const t = e.target as HTMLElement;
    const tab = t.closest('.dtab');
    if (tab && tab.getAttribute('data-panel') === 'bookmarks') loadBookmarks();
  });
  loadBookmarks();
}
