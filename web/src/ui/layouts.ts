// layouts.ts — named user layouts (WORKBENCH_SPEC §4 roadmap): snapshot/restore
// the panel + dock arrangement, persisted per workspace via the layout.* ops.
// The snapshot is DOM-sourced (panels are re-parented by drag, so the DOM is
// the state); applying re-parents and re-runs the chrome sync. Unknown panel
// ids are skipped with a console.warn — a layout must survive UI evolution.
import { $all, S, pushEscape, popEscape, ESC } from './hub.js';
import { tryOp } from '../api.js';
import { ui, applyLayout, prefsChanged } from './chrome.js';
import type { FloatPanel, LayoutSnapshot, TabdockState } from '../types.js';

/* ==================================================================
   SNAPSHOT / APPLY
================================================================== */
function zonePanels(hostId: string): (string | null)[] {
  return $all(':scope > .panel', document.getElementById(hostId))
    .map(function(p){ return p.getAttribute('data-panel'); });
}

function tabdockState(hostId: string): TabdockState {
  const active = document.getElementById(hostId)!.querySelector(':scope > .panel.active');
  return { panels: zonePanels(hostId), active: active ? active.getAttribute('data-panel') : null };
}

export function snapshot(): LayoutSnapshot {
  return {
    ui: { side: ui.side, dock: ui.dock, sideW: ui.sideW, rightW: ui.rightW, dockH: ui.dockH },
    zones: {
      side: zonePanels('col-side'),
      right: zonePanels('col-right'),
      dock: tabdockState('dock-panels'),
      cdock: tabdockState('cdock-panels'),
      float: $all(':scope > .panel', document.getElementById('float-layer')).map(function(p): FloatPanel {
        return {
          id: p.getAttribute('data-panel'),
          left: parseInt(p.style.left, 10) || 0,
          top: parseInt(p.style.top, 10) || 0,
        };
      }),
    },
  };
}

function panelFor(id: string | null | undefined){
  const p = id ? document.querySelector<HTMLElement>('.panel[data-panel="' + id + '"]') : null;
  if (!p) console.warn('layout: unknown panel skipped:', id);
  return p;
}

/* re-parent into a dock zone, clearing any floating residue (movePanel parity) */
function dockInto(hostId: string, id: string | null){
  const p = panelFor(id);
  if (!p) return null;
  p.classList.remove('floating');
  p.style.left = p.style.top = '';
  document.getElementById(hostId)!.appendChild(p);
  return p;
}

export function apply(layout: LayoutSnapshot | null | undefined): void {
  if (!layout || typeof layout !== 'object'){
    console.warn('layout: nothing to apply');
    return;
  }
  const zones: NonNullable<LayoutSnapshot['zones']> = layout.zones || {};
  (zones.side || []).forEach(function(id){ dockInto('col-side', id); });
  (zones.right || []).forEach(function(id){ dockInto('col-right', id); });
  ([['dock', 'dock-panels'], ['cdock', 'cdock-panels']] as ['dock' | 'cdock', string][]).forEach(function(pair){
    const z: Partial<TabdockState> = zones[pair[0]] || {};
    (z.panels || []).forEach(function(id){ dockInto(pair[1], id); });
    // restore the active tab; with none recorded, syncTabs picks the first
    $all(':scope > .panel', document.getElementById(pair[1])).forEach(function(p){
      p.classList.toggle('active', p.getAttribute('data-panel') === z.active);
    });
  });
  (zones.float || []).forEach(function(f){
    const p = panelFor(f && f.id);
    if (!p) return;
    document.getElementById('float-layer')!.appendChild(p);
    p.classList.add('floating');
    p.style.left = Math.max(0, f.left || 0) + 'px';
    p.style.top = Math.max(0, f.top || 0) + 'px';
  });
  if (layout.ui){
    ui.side = layout.ui.side !== false;
    ui.dock = layout.ui.dock !== false;
    // splitter sizes (panel-splitters addition) — older layouts lack them: keep
    // current; applyLayout clamps out-of-range persisted values at render
    if (typeof layout.ui.sideW === 'number')  ui.sideW  = layout.ui.sideW;
    if (typeof layout.ui.rightW === 'number') ui.rightW = layout.ui.rightW;
    if (typeof layout.ui.dockH === 'number')  ui.dockH  = layout.ui.dockH;
  prefsChanged();                                            // the applied layout is now the current chrome (decision 17)
  }
  applyLayout();   // grid columns, dock visibility, tab strips (syncTabs inside)
}

/* ==================================================================
   DIALOG — one .wbmodal in three modes: save (name input) /
   apply·delete (picker rows fed by layout.list)
================================================================== */
const MODES: Record<'save' | 'apply' | 'delete', { title: string; sym: string; ok: string | null }> = {
  save:   { title: 'SAVE LAYOUT',   sym: 'name this panel/dock arrangement · same name overwrites', ok: 'SAVE' },
  apply:  { title: 'APPLY LAYOUT',  sym: 'pick a saved layout — panels re-dock, active tabs restore', ok: null },
  delete: { title: 'DELETE LAYOUT', sym: 'pick a saved layout to remove', ok: null },
};

function openLayoutModal(mode: 'save' | 'apply' | 'delete'){
  const m = MODES[mode];
  const modal = document.getElementById('layoutmodal')!;
  const input = document.getElementById('laym-input') as HTMLInputElement;
  const list = document.getElementById('laym-list')!;
  const err = document.getElementById('laym-err')!;
  const ok = document.getElementById('laym-ok') as HTMLButtonElement;
  document.getElementById('laym-title')!.textContent = m.title;
  document.getElementById('laym-sym')!.textContent = m.sym;
  input.hidden = !m.ok;
  ok.hidden = !m.ok;
  if (m.ok) ok.innerHTML = m.ok + ' <kbd>⏎</kbd>';
  list.innerHTML = '';
  err.textContent = '';
  modal.hidden = false;

  // picker modes have no input to own Esc; the hidden check keeps a handle from a
  // re-opened dialog inert instead of eating one Escape
  const esc = pushEscape(function(){ if (modal.hidden) return false; close(); }, ESC.modal);
  function close(){
    modal.hidden = true;
    ok.onclick = input.onkeydown = null;
    popEscape(esc);
  }

  async function renderRows(){
    const r = await tryOp('layout.list', {});
    if (r.error){ err.textContent = r.error.message; return; }
    list.innerHTML = '';
    if (!r.data.length){
      err.textContent = 'no saved layouts yet — use Save Layout… first';
      return;
    }
    r.data.forEach(function(row){
      const item = document.createElement('div');
      item.className = 'pm-item';
      const name = document.createElement('span');
      name.className = 'pm-name';
      name.textContent = row.name;
      const ts = document.createElement('span');
      ts.className = 'pm-n num';
      ts.textContent = String(row.ts || '').slice(0, 16).replace('T', ' ');
      item.appendChild(name);
      item.appendChild(ts);
      item.addEventListener('click', async function(){
        if (mode === 'apply'){
          const a = await tryOp('layout.apply', { name: row.name });
          if (a.error){ err.textContent = a.error.message; return; }
          close();
          apply(a.data.layout);
        } else {
          const d = await tryOp('layout.delete', { name: row.name });
          if (d.error){ err.textContent = d.error.message; return; }
          renderRows();   // stay open — several deletes in one visit
        }
      });
      list.appendChild(item);
    });
  }

  if (m.ok){
    input.value = '';
    input.focus();
    const submit = async function(){
      const name = input.value.trim();
      if (!name){ err.textContent = 'name required'; return; }
      const r = await tryOp('layout.save', { name, layout: snapshot() });
      if (r.error){ err.textContent = r.error.message; return; }
      close();
    };
    ok.onclick = submit;
    input.onkeydown = function(e){
      if (e.key === 'Enter'){ e.preventDefault(); submit(); }
    };
  } else {
    renderRows();
  }
  document.getElementById('laym-cancel')!.onclick = close;
}

export function initLayouts(): void {
  S.hooks.layoutSaveDialog = function(){ openLayoutModal('save'); };
  S.hooks.layoutApplyDialog = function(){ openLayoutModal('apply'); };
  S.hooks.layoutDeleteDialog = function(){ openLayoutModal('delete'); };
}
