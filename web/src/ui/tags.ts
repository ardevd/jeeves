// tags.ts — TAGS dock tab: family filter chips, tag rows, T adds a tag on the
// caret symbol, ✕ removes, click jumps. Live from tag.list/tag.add/tag.rm.
import { S, pushEscape, popEscape, ESC, escHtml as esc, tryOpT, isHexColor } from './hub.js';
import { dockUnavailable } from './chrome.js';
import { symbolAtCaret, loadSymbol, gotoLine } from '../views/code.js';
import type { OpParams, TagList } from '../types.js';

let wired = false;
let familyFilter: string | null = null;         // null = ALL

export async function loadTags(): Promise<void>{
  const r = await tryOpT('tag.list', { family: familyFilter || '' });
  if (r.error){ dockUnavailable('tags-rows', 'tags', r.error.message); return; }
  const d: TagList = r.data || { tags: [], families: [] };
  // family chips
  const famHost = document.getElementById('tags-families')!;
  famHost.innerHTML = '';
  const all = document.createElement('button');
  all.className = 'fchip' + (familyFilter ? '' : ' on');
  all.textContent = 'ALL';
  all.addEventListener('click', function(){ familyFilter = null; loadTags(); });
  famHost.appendChild(all);
  (d.families || []).forEach(function(f){
    const c = document.createElement('button');
    c.className = 'fchip' + (familyFilter === f.name ? ' on' : '');
    c.textContent = String(f.name).toUpperCase();
    if (f.color) c.style.borderColor = f.color;
    c.addEventListener('click', function(){
      familyFilter = familyFilter === f.name ? null : f.name;
      loadTags();
    });
    famHost.appendChild(c);
  });
  // rows
  const host = document.getElementById('tags-rows')!;
  host.innerHTML = '';
  if (!(d.tags || []).length && !(d.pendingCount! > 0)){
    host.innerHTML = '<div class="drow"><span class="dnote">no tags yet — T tags the caret symbol as family:tag</span></div>';
    return;
  }
  (d.tags || []).forEach(function(t){
    const row = document.createElement('div');
    row.className = 'drow';
    const actor = t.actor && t.actor !== 'human' ? ' <span class="led p" title="tagged by an agent"></span>' : '';
    const was = t.renamed && t.origName ? ' (was: ' + t.origName + ')' : '';
    row.innerHTML =
      '<span class="tdot" style="background:' + (isHexColor(t.color) ? t.color : 'var(--blue)') + '"></span>' +
      '<span>' + esc(t.family + ':' + t.name) + '</span>' +
      '<span class="dsym" title="' + esc(t.symbol) + '">' +
        esc(t.label || t.symbol) + esc(was) + '</span>' + actor +
      '<span class="dnote">' + esc(t.note || '') + '</span>' +
      '<button class="hundo" title="remove tag">✕</button>' +
      '<span class="dt">' + esc(String(t.ts || '').slice(11, 19) + 'Z') + '</span>';
    /* navigate: a click on the symbol, or a double-click anywhere on the row (user 2026-09-19:
       "double clicking on a tag should immediately navigate to the tag location") — one in-flight
       navigation per row, so click + click + dblclick do not open the class three times */
    let going = false;
    const go = async function(){
      if (!t.addr || going) return;
      going = true;
      try {
        await loadSymbol(t.addr);
        if (t.line! > 0) gotoLine(t.line!);
      } finally { going = false; }
    };
    row.querySelector('.dsym')!.addEventListener('click', go);
    row.addEventListener('dblclick', function(e){
      if ((e.target as HTMLElement).closest('.hundo')) return;   // the ✕ is not a place to go
      e.preventDefault();
      const sel = window.getSelection(); if (sel) sel.removeAllRanges();   // the second mousedown selected a word before dblclick fired
      go();
    });
    row.style.cursor = 'default';
    row.querySelector('.hundo')!.addEventListener('click', async function(){
      await tryOpT('tag.rm', { id: t.id });
      await loadTags();
      if (S.hooks.marksChanged) S.hooks.marksChanged();
    });
    host.appendChild(row);
  });
  if (d.pendingCount! > 0){
    const p = document.createElement('div');
    p.className = 'drow';
    p.innerHTML = '<span class="led p"></span><span class="dnote">' + d.pendingCount +
      ' agent tag(s) waiting for review — the EDITS pane in Agents ⌃5</span>';
    host.appendChild(p);
  }
}

/* T → tag dialog on the caret symbol */
export function openTagDialog(): void {
  const hit = symbolAtCaret();
  /* a LOCAL hit is truthy with an empty symbol (GUI review finding 4): the
     modal opened with a blank SYMBOL line and APPLY could only ever E_SYNTAX.
     Locals aren't taggable (no addr) — no dialog */
  if (!hit || !hit.symbol) return;
  const modal = document.getElementById('tagmodal')!;
  const name = document.getElementById('tagm-name') as HTMLInputElement;
  const note = document.getElementById('tagm-note') as HTMLInputElement;
  const err = document.getElementById('tagm-err')!;
  const ok = document.getElementById('tagm-ok') as HTMLButtonElement;
  document.getElementById('tagm-sym')!.textContent = hit.symbol;
  name.value = '';
  note.value = '';
  err.textContent = '';
  modal.hidden = false;
  name.focus();
  const esc = pushEscape(function(){ if (modal.hidden) return false; close(); }, ESC.modal);
  function close(){
    modal.hidden = true;
    ok.onclick = null;
    popEscape(esc);
    document.getElementById('code')!.focus({ preventScroll: true });
  }
  const submit = async function(){
    // QA M8: a tag added from the code view carries the caret line (1-based,
    // like bookmarks/gotoLine) so the glyph-margin square can render
    const params: OpParams = { symbol: hit.symbol, name: name.value.trim(),
      note: note.value.trim(), line: S.caret.line + 1 };
    const r = await tryOpT('tag.add', params);   // writes bind the viewed target (GUI review finding 3)
    if (r.error){ err.textContent = r.error.message; return; }
    close();
    await loadTags();
    if (S.hooks.marksChanged) S.hooks.marksChanged();
  };
  ok.onclick = submit;
  document.getElementById('tagm-cancel')!.onclick = close;
  name.onkeydown = note.onkeydown = function(e){
    if (e.key === 'Enter'){ e.preventDefault(); submit(); }
  };
}

export function initTags(): void {
  if (wired) return;
  wired = true;
  S.hooks.addTag = openTagDialog;
  // dock tabs switch on pointerup (the drag handler preventDefaults pointerdown,
    // killing click) — refresh on pointerup or the panel shows boot-stale data
  document.addEventListener('pointerup', function(e){
    const t = e.target as HTMLElement;
    const tab = t.closest('.dtab');
    if (tab && tab.getAttribute('data-panel') === 'tags') loadTags();
  });
  loadTags();
}
