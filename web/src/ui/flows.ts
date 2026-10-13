// flows.ts — FLOWS dock tab: flow rows, add-flow dialog, detail strip with
// clickable hop chain, PROMOTE seeds a Map (M6). Live from flow.list/get/add/rm.
import { $all, S, pushEscape, popEscape, ESC, escHtml as esc, tryOpT } from './hub.js';
import { showCenter, dockUnavailable } from '../ui/chrome.js';
import { symbolAtCaret, loadSymbol } from '../views/code.js';
import type { OpParams } from '../types.js';

let wired = false;

export async function loadFlows(): Promise<void>{
  const r = await tryOpT('flow.list', {});
  if (r.error){ dockUnavailable('flows-rows', 'flows', r.error.message); document.getElementById('flow-detail')!.innerHTML = ''; return; }
  const host = document.getElementById('flows-rows')!;
  host.innerHTML = '';
  const flows = r.data || [];
  if (!flows.length){
    host.innerHTML = '<div class="drow"><span class="dnote">no saved flows — + ADD FLOW seeds a source→sink investigation</span></div>';
    document.getElementById('flow-detail')!.innerHTML = '';
    return;
  }
  flows.forEach(function(f){
    const row = document.createElement('div');
    row.className = 'drow';
    const cross = f.crossTarget ? '<span class="vchip vc-mixed">⇄ CROSS-TGT</span>' : '';
    row.innerHTML = '<span class="led b"></span><span>' + esc(f.name) + '</span>' +
      '<span class="dnote">' + esc(f.sourceLabel) + ' → ' + esc(f.sinkLabel) + '</span>' +
      '<span class="file">' + (f.hopCount || 0) + ' hops</span>' + cross +
      '<button class="hundo frm" title="remove flow">✕</button>';
    const promote = document.createElement('button');
    promote.className = 'hundo';
    promote.textContent = 'PROMOTE';
    promote.title = 'Seed a Map from this flow (GRAPHS_SPEC §2)';
    promote.addEventListener('click', async function(e){
      e.stopPropagation();
      const pr = await tryOpT('flow.promote', { id: f.id });   // flow ids are per-target: the row's own target (fix-review r4 #1)
      if (pr.error){ console.warn('flow.promote', pr.error.message); return; }
      /* reloadMap renders the chip (no chip in a mapless project) BEFORE the
         surface shows — showCenter refuses a chipless map. It fits + focuses
         the NEW map (QA m9 crumb; map-r1 #13) */
      if (S.hooks.reloadMap) await S.hooks.reloadMap(pr.data.map!);
      showCenter('map');
    });
    row.insertBefore(promote, row.lastChild);
    row.addEventListener('click', async function(e){
      const t = e.target as HTMLElement;
      if (t.closest('.hundo')) return;
      $all('#flows-rows .drow').forEach(x => x.classList.remove('sel'));
      row.classList.add('sel');
      await showFlowDetail(f.id);
    });
    /* ✕ only — NOT '.hundo': PROMOTE is inserted before ✕ and shares that class,
       so querySelector('.hundo') double-wired PROMOTE to flow.rm (the race behind
       the intermittent "flow not found" on promote). */
    row.querySelector('.frm')!.addEventListener('click', async function(e){
      e.stopPropagation();
      await tryOpT('flow.rm', { id: f.id });
      await loadFlows();
    });
    host.appendChild(row);
  });
}

async function showFlowDetail(id: string){
  const r = await tryOpT('flow.get', { id });
  const host = document.getElementById('flow-detail')!;
  if (r.error){ host.innerHTML = ''; return; }
  const f = r.data;
  let html = '<div class="dockhint" style="margin-top:4px">' + esc(f.name) +
    ' · ' + esc(f.status || 'draft') + (f.crossTarget ? ' · ⇄ cross-target (' + esc(f.otherTarget || '') + ')' : '') + '</div>';
  html += '<div class="drow"><span class="dnote">' + esc(f.sourceLabel) + '</span></div>';
  (f.hopsResolved || []).forEach(function(h){
    html += '<div class="drow flowhop" data-addr="' + esc(h.addr || '') + '" style="cursor:' +
      (h.addr ? 'pointer' : 'default') + '"><span class="led g"></span><span class="dsym">' +
      esc(h.label) + '</span>' + (h.stale ? '<span class="vchip vc-gap">STALE</span>' : '') + '</div>';
  });
  html += '<div class="drow"><span class="dnote">' + esc(f.sinkLabel) + '</span></div>';
  host.innerHTML = html;
  $all('.flowhop', host).forEach(function(row){
    row.addEventListener('click', async function(){
      const addr = row.getAttribute('data-addr');
      if (addr) await loadSymbol(addr);
    });
  });
}

function openFlowDialog(){
  const modal = document.getElementById('flowmodal')!;
  const name = document.getElementById('flowm-name') as HTMLInputElement;
  const source = document.getElementById('flowm-source') as HTMLInputElement;
  const sink = document.getElementById('flowm-sink') as HTMLInputElement;
  const hops = document.getElementById('flowm-hops') as HTMLInputElement;
  const err = document.getElementById('flowm-err')!;
  const ok = document.getElementById('flowm-ok') as HTMLButtonElement;
  const hit = symbolAtCaret();
  name.value = '';
  source.value = hit ? hit.symbol! : '';
  sink.value = '';
  hops.value = '';
  err.textContent = '';
  modal.hidden = false;
  name.focus();
  const esc = pushEscape(function(){ if (modal.hidden) return false; close(); }, ESC.modal);
  function close(){
    modal.hidden = true;
    ok.onclick = null;
    popEscape(esc);
  }
  const submit = async function(){
    const params: OpParams = { name: name.value.trim(), source: source.value.trim(), sink: sink.value.trim() };
    if (hops.value.trim()) params.hops = hops.value.trim();
    const r = await tryOpT('flow.add', params);   // writes bind the viewed target (GUI review finding 3)
    if (r.error){ err.textContent = r.error.message; return; }
    close();
    await loadFlows();
  };
  ok.onclick = submit;
  document.getElementById('flowm-cancel')!.onclick = close;
  [name, source, sink, hops].forEach(function(inp){
    inp.onkeydown = function(e){
      if (e.key === 'Enter'){ e.preventDefault(); submit(); }
    };
  });
}

export function initFlows(): void {
  if (wired) return;
  wired = true;
  document.getElementById('flow-add-btn')!.addEventListener('click', openFlowDialog);
  // dock tabs switch on pointerup (the drag handler preventDefaults pointerdown,
    // killing click) — refresh on pointerup or the panel shows boot-stale data
  document.addEventListener('pointerup', function(e){
    const t = e.target as HTMLElement;
    const tab = t.closest('.dtab');
    if (tab && tab.getAttribute('data-panel') === 'flows') loadFlows();
  });
  loadFlows();
}
