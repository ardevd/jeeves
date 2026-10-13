// views/runs.ts — RUNS view (⌃4) live: device bar (real serial/root/frida),
// BP list (toggle / remove / cond edit), hit feed (backfill + live SSE, filter,
// ⏸ pause), rail hit detail, run history cards + DIFF. Live ops:
// runs.devices/attach/bp.*/hits/runs/diff.
import { $all, S, pushEscape, popEscape, ESC, modalUp, escHtml as esc, tryOpT } from '../ui/hub.js';
import { tryOp, onEvent } from '../api.js';
import { jumpToCode, pushPaneEscape, activeTargetName, updateDockScope, notice } from '../ui/chrome.js';
import { bpSymbolAtCaret } from './code.js';
import { rootKind, rootLabel, rootTip } from './device.js';
import type { Breakpoint, EscapeHandle, Hit, OpParams, RunInfo } from '../types.js';

let wired = false;
let hitRows: Hit[] = [];      // current feed rows (hit objects)
let diffSel: string[] = [];   // execIds selected for diff (max 2)
let selHit: Hit | null = null;   // hit shown in the rail (null → rail shows the run summary)
let bpCount = 0;              // ARMED breakpoints — the rail summary quotes it
let bpTotal = 0;              // every breakpoint, armed or not
let lastRuns: RunInfo[] = []; // last runs.runs payload — rail summary + diff hint read it
let feedQ = '';               // P0.6: feed filter — lower-cased substring over a row's rendered text
let dockSeq = 0;              // RUNTIME dock: the newest refresh wins (a target switch mid-fetch)
let dockRun: string | null = null;      // the run the dock is showing — live hits for it tick in
let inspectedRun: string | null = null; // run-card click = inspect: feed, rail and coverage follow it (batch 2)
let detachArmed = 0;          // DETACH is two-step: first click arms and names the sessions
let rmArmed: string | null = null; let rmTimer = 0;   // a card's ✕ (or the header's "delete ended") is two-step too
let rmArmedProject: string | null = null;   // the arm is void after a project switch (b2-r5 L1)
function activeProjectKey(): string | null { return (S.state as { activeProject?: string } | null)?.activeProject || null; }
/* truncate at a word boundary, never mid-token (persona LOW: "on disk: [frida-server-17.11.0-andro…")
   — cut back to the last space when one sits reasonably close to the limit, else hard-cut */
function clip(s: string, n: number): string {
  if (!s || s.length <= n) return s || '';
  const cut = s.slice(0, n);
  const sp = cut.lastIndexOf(' ');
  return (sp > n * 0.6 ? cut.slice(0, sp) : cut) + '…';
}
/* clear every reference that points at a run that is now gone — shared by the local delete and
   the 'runs' removed event from another client (b2-r5 M5). Returns true if a gutter repaint is due. */
function clearRemovedRefs(gone: string[]): boolean {
  if (!gone.length) return false;
  diffSel = diffSel.filter(id => !gone.includes(id));
  if (inspectedRun && gone.includes(inspectedRun)) inspectedRun = null;
  if (dockRun && gone.includes(dockRun)) dockRun = null;
  let repaint = false;
  if (S.covRun && gone.includes(S.covRun)){ S.covRun = null; repaint = true; }
  return repaint;
}
let paused = false;           // P0.6: ⏸ — live hits queue in pausedBuf instead of moving the feed
let pausedBuf: Hit[] = [];    // hits received while paused, oldest first (capped like the feed)
let pausedN = 0;              // every hit received while paused — the "N new" badge (uncapped)

function fmtTs(ts: string | number | undefined){
  const d = new Date(+ts!);
  if (isNaN(d.getTime())) return ts || '';
  const p = (n: number) => (n < 10 ? '0' : '') + n;
  return p(d.getUTCHours()) + ':' + p(d.getUTCMinutes()) + ':' + p(d.getUTCSeconds()) + 'Z';
}

function hitTime(h: Hit): string {
  return h.monotonic_ns ? '#' + h.sourceSeq + ' · ' + h.monotonic_ns + 'ns' : String(fmtTs(h.ts));
}
/* a run card's time: the date too once it is not today's — 28 runs span days and A/B is
   picked here (persona pass M8) */
function fmtRunTs(ts: string | number | undefined){
  const d = new Date(+ts!);
  if (isNaN(d.getTime())) return String(ts || '');
  const now = new Date();
  const p = (n: number) => (n < 10 ? '0' : '') + n;
  const sameDay = d.getUTCFullYear() === now.getUTCFullYear() && d.getUTCMonth() === now.getUTCMonth() && d.getUTCDate() === now.getUTCDate();
  return (sameDay ? '' : p(d.getUTCMonth() + 1) + '-' + p(d.getUTCDate()) + ' ') + fmtTs(ts);
}

/* ==================================================================
   DEVICE BAR
================================================================== */
async function loadDevices(){
  const r = await tryOp('runs.devices', {});
  if (r.data?.devices?.some(d => d.live) || pickedSerial) (document.getElementById('run-local-controls') as HTMLDetailsElement).open = true;
  const led = document.getElementById('dev-led')!;
  const name = document.getElementById('dev-name')!;
  const meta = document.getElementById('dev-meta')!;
  const state = document.getElementById('dev-state')!;
  if (r.error){
    led.className = 'led o';
    name.textContent = 'frida unavailable';
    meta.textContent = ' · ' + r.error.message;
    state.textContent = '';
    sessionButtons(false);
    return;
  }
  const devs = (r.data && r.data.devices) || [];
  if (!devs.length) pickedSerial = '';
  sessionButtons(devs.length > 0);
  if (!devs.length){
    led.className = 'led o';
    name.textContent = 'no device attached';
    meta.textContent = ' · plug one in or start an emulator';
    state.textContent = '';
    return;
  }
  const selection = document.getElementById('run-device') as HTMLSelectElement;
  selection.replaceChildren(new Option('Select a device…', ''));
  for (const device of devs) selection.add(new Option(device.serial + ' · ' + (device.model || ''), device.serial));
  selection.value = pickedSerial;
  const d = devs.find(x => x.serial === pickedSerial);
  if (!d) {
    pickedSerial = '';
    name.textContent = 'select a device';
    meta.textContent = ' · no automatic selection';
    state.textContent = '';
    sessionButtons(false);
    return;
  }
  sessionButtons(true);
  led.className = 'led ' + (rootKind(d) === 'none' ? 'o' : 'g');
  name.textContent = d.serial;
  meta.textContent = ' · ' + (d.model || '') + ' · Android ' + (d.android || '?') +
    ' · ' + rootLabel(d) +
    (d.fridaServer && d.fridaServer.running ? ' · frida-server ' + (d.fridaServer.version || 'up') : ' · frida-server down');   // version is no longer asserted by the server
  meta.setAttribute('data-tip', rootTip(d));
  state.textContent = d.live ? 'ATTACHED' : '';
  const pkg = document.getElementById('run-pkg') as HTMLInputElement;
  if (!pkg.value){
    const ti = await tryOpT('target.info', {});
    if (ti.data && ti.data.package) pkg.value = ti.data.package;
  }
}

/* the device the session buttons act on — kept here, not scraped from the
   label text (fix-review r1 M9) */
let pickedSerial = '';

/* SPAWN / ATTACH / DETACH act on a device: without one they were enabled and
   posted the literal "no devices" as the serial (UI audit 2026-09-09) */
function sessionButtons(on: boolean){
  ['run-spawn', 'run-attach', 'run-detach'].forEach(function(id){
    const b = document.getElementById(id) as HTMLButtonElement | null;
    if (b) b.disabled = !on;
  });
}

async function doAttach(spawn: boolean){
  const serial = pickedSerial;
  const pkg = (document.getElementById('run-pkg') as HTMLInputElement).value.trim();
  const state = document.getElementById('dev-state')!;
  if (!serial){ state.textContent = 'no device — plug one in (⌃6 DEVICE)'; return; }
  if (!pkg){ state.textContent = 'pkg?'; return; }
  state.textContent = spawn ? 'spawning…' : 'attaching…';
  const serverPath = (document.getElementById('run-server-path') as HTMLInputElement).value.trim();
  const ownershipConfirmed = (document.getElementById('run-owner-confirmed') as HTMLInputElement).checked;
  if (!serverPath || !ownershipConfirmed) {
    state.textContent = 'Select the installed server and confirm controller ownership; external controllers use import';
    return;
  }
  const params: OpParams = { device: serial, pkg: pkg, serverPath, ownershipConfirmed };
  const r = await tryOpT(spawn ? 'runs.spawn' : 'runs.attach', params);   // the session binds the VIEWED target, like the breakpoints it installs (fix-review r4 #5)
  if (r.error){ state.textContent = 'failed: ' + r.error.message; return; }
  state.textContent = 'ATTACHED pid ' + r.data.pid;
  await Promise.all([loadRuns(), loadDevices(), backfillHits()]);
}

async function importBundle(){
  const directory = (document.getElementById('run-import-path') as HTMLInputElement).value.trim();
  const message = document.getElementById('run-bundle-result')!;
  if (!directory){ message.textContent = 'Select a canonical bundle directory'; return; }
  const target = S.currentTid;
  const project = (S.state?.projects || []).find(p => p.active)?.name;
  const stillCurrent = () => target === S.currentTid && project === (S.state?.projects || []).find(p => p.active)?.name;
  message.textContent = 'validating selected bundle…';
  const result = await tryOpT('runs.import', { directory });
  if (!stillCurrent()) return;
  if (result.error){ message.textContent = result.error.message; return; }
  message.textContent = 'Imported ' + result.data.execId + ' · evidence ' + result.data.completion + ' · producer ' + result.data.producerMode + ' · cleanup ' + result.data.cleanup;
  await loadRuns();
  if (stillCurrent()) await inspectRun(result.data.execId);
}

async function exportBundle(){
  const destination = (document.getElementById('run-export-path') as HTMLInputElement).value.trim();
  const message = document.getElementById('run-bundle-result')!;
  const selected = lastRuns.find(run => run.execId === inspectedRun);
  if (!selected?.sourceManifestSha256 || !destination){ message.textContent = 'Inspect an imported bundle and select a new export directory'; return; }
  const project = activeProjectKey();
  message.textContent = 'exporting the manifest and its listed files…';
  const result = await tryOpT('runs.export', { run: selected.execId, destination });
  if (project !== activeProjectKey()) return;
  message.textContent = result.error ? result.error.message : result.data.valid && result.data.selected_only
    ? 'Exported manifest-listed set: ' + result.data.files + ' files · SHA-256 ' + result.data.manifest_sha256 + ' · no upload'
    : 'Export was not verified; inspect the destination before using it';
}

/* ==================================================================
   BREAKPOINTS
================================================================== */
async function loadBps(){
  /* target-LESS on purpose (fix-review r2 #1): RunStore.bps(null) is every
     breakpoint in the store, and this dock is built for that — the per-row
     target badge, the cross-target jump and the cond re-add below all read
     bp.target. The RUNS view is project-scoped (chrome.ts projScoped). F9's
     toggle-vs-add decision is the one consumer that wants the VIEWED
     target's list (code.ts toggleBp). */
  const r = await tryOp('runs.bp.list', {});
  const host = document.getElementById('runs-bps')!;
  host.innerHTML = '';
  // QA 13: add-BP affordance (dialog row → runs.bp.add)
  const add = document.createElement('div');
  add.className = 'bprow';
  add.innerHTML = '<button class="hundo" title="add breakpoint on the current code-view symbol">＋ BP</button>';
  add.querySelector('button')!.addEventListener('click', function(){
    if (add.querySelector('input')) return;
    const inp: HTMLInputElement = document.createElement('input');
    inp.type = 'text';
    inp.placeholder = 'DEX symbol · or native lib.so!export / lib.so+0xADDR / lib.so@function · blank = the method at the caret';
    inp.spellcheck = false;
    inp.style.cssText = 'flex:1;font:inherit;font-size:10px;background:var(--paper2);color:var(--ink);border:1px solid var(--blue-line);outline:none;padding:2px 5px';
    add.appendChild(inp);
    inp.focus();
    /* the inline form owns Escape while it is up: menu priority beats the view's
       own owner, so Esc cancels the form instead of closing the whole Runs view.
       It declines once a BP-list rebuild has dropped the form (zombie handle). */
    const formEsc = pushEscape(function(){
      if (!inp.isConnected) return false;
      closeForm();
    }, ESC.menu);
    function closeForm(){
      popEscape(formEsc);
      inp.remove();
    }
    inp.addEventListener('keydown', async function(e){
      if (e.key === 'Escape'){ closeForm(); return; }
      if (e.key !== 'Enter') return;
      /* blank = F9's own symbol (code.ts bpSymbolAtCaret): the method under the
         caret, else the open class's first method — breakpoints are
         method-level and S.currentSym is the CLASS on JEB, so the old class
         fallback could only ever answer "breakpoints are method-level". Bound
         to the viewed target like every other write (review 2026-09-09 MED-20). */
      const typed = inp.value.trim();
      /* native BP syntax: <module>.so!<export> (frida resolves the export), <module>.so+0x<offset>
         (raw offset), or <module>.so@<function> (idalib resolves a non-exported NAME to an offset
         against the pulled copy of the module). Anything else is a DEX symbol; blank = caret method. */
      const nm = typed.match(/^(\S+\.so(?:\.\d+)?)([!+@])(.+)$/);
      let rr;
      if (nm){
        const mod = nm[1], rest = nm[3].trim();
        rr = await tryOpT('runs.bp.add',
          nm[2] === '!' ? { module: mod, export: rest }
          : nm[2] === '@' ? { module: mod, function: rest }
          : { module: mod, offset: rest.indexOf('0x') === 0 ? rest : '0x' + rest });
      } else {
        const hit = typed ? null : bpSymbolAtCaret();
        const sym = typed || (hit ? hit.symbol : null);
        if (!sym){ closeForm(); return; }
        rr = await tryOpT('runs.bp.add', { symbol: sym });
      }
      if (rr.error) inp.value = rr.error.message;
      else { closeForm(); loadBps(); }
    });
  });
  host.appendChild(add);
  const bps = r.data || [];
  bpTotal = bps.length;
  bpCount = bps.filter(b => b.enabled).length;   // ARMED breakpoints — a disabled one must not read as set (persona pass M3)
  const count = document.getElementById('bp-count');
  if (count) count.textContent = bps.length ? bps.length + (bps.length === 1 ? ' bp' : ' bps') : '';
  renderRunRail();              // P0.7: the rail summary quotes bpCount — keep it honest after a remove (also when the last one goes)
  if (!bps.length){
    // hint row, appended — the ＋ BP affordance above it has to survive the empty state
    const none = document.createElement('div');
    none.className = 'bprow';
    none.innerHTML = '<span class="dim">no breakpoints yet — F9 on a method</span>';
    host.appendChild(none);
    return;
  }
  bps.forEach(function(bp){
    const row = document.createElement('div');
    row.className = 'bprow' + (bp.enabled ? '' : ' off');
    row.innerHTML = '<input type="checkbox"' + (bp.enabled ? ' checked' : '') + '>' +
      '<span class="bpn">' + esc(bp.name) + '</span>' +
      /* P0.7: the cond is always a cell — set → "cond: …", unset → a hover-only
         "+cond" — either clicks into the inline editor (editCond) */
      '<span class="cond' + (bp.cond ? '' : ' unset') + '">' +
        (bp.cond ? 'cond: ' + esc(bp.cond) : '+cond') + '</span>' +
      '<span class="tgt dim">' + esc(bp.target) + '</span>' +
      '<span class="hits">' + (bp.hits || 0) + '</span>' +
      '<span class="tx" title="remove breakpoint">×</span>';   // P0.7: hover-× (the .tchip .tx idiom)
    row.querySelector('input')!.addEventListener('change', async function(e){
      await tryOp('runs.bp.toggle', { id: bp.id });
      await loadBps();
    });
    row.querySelector<HTMLElement>('.bpn')!.title = bp.origSig || bp.name;    // QA 25: ellipsis tooltip
    // D4: the BP name opens its method in the code view — resolve FIRST so an
    // unresolvable target/symbol doesn't swap the view and land nowhere.
    // A native BP (libc.so!read) has no JEB code view, and symbol.resolve on its
    // "native:…" origSig always fails — so the name is not a nav target for native.
    const isNative = bp.kind === 'native' || (bp.origSig || '').startsWith('native:');
    if (!isNative) {
      row.querySelector<HTMLElement>('.bpn')!.style.cursor = 'pointer';
      row.querySelector('.bpn')!.addEventListener('click', async function(){
        if (!bp.origSig) return;
        const rs = await tryOp('symbol.resolve', { symbol: bp.origSig, target: bp.target });
        if (rs.error) return;                                     // stay put on failure
        const ok = await jumpToCode(bp.target || null, bp.origSig);
        /* stale persisted target id — surface IN the list (map-r7 F4): the code
           crumb's paint guard suppresses it while the runs pane owns the center */
        if (ok === false) noteRow(host, 'jump failed: target not open — ' + (bp.target || '?'));
      });
    }
    row.querySelector<HTMLElement>('.tgt')!.title = bp.target || '';
    row.querySelector<HTMLElement>('.cond')!.title = bp.origSig
      ? 'click to edit the condition (argN · !argN · argN == null · argN != null)'
      : 'no original signature — condition is read-only';
    row.querySelector('.cond')!.addEventListener('click', function(){ editCond(row, bp, host); });
    row.querySelector('.tx')!.addEventListener('click', function(){ confirmRmBp(row, bp); });
    host.appendChild(row);
  });
}

/* P0.7: remove — the projmenu's two-step idiom (chrome.js confirmDeleteMap):
   the row BECOMES the confirm (REMOVE / cancel), no modal, no retyping. The op
   is runs.bp.rm {id}; the backend pulls the live hook as well. Esc cancels the
   armed row at menu priority (beats the view's owner) and, once a list rebuild
   has dropped the row, declines and drops its own zombie handle. */
function confirmRmBp(row: HTMLElement, bp: Breakpoint){
  row.className = 'bprow pm-confirm';
  row.innerHTML = '';
  const name = document.createElement('span');
  name.className = 'bpn';
  name.textContent = 'remove ' + bp.name + ' · ' + (bp.hits || 0) + ' hits';
  name.title = bp.origSig || bp.name;
  const go = document.createElement('span');
  go.className = 'pm-go';
  go.textContent = 'REMOVE';
  const cancel = document.createElement('span');
  cancel.className = 'pm-cancel';
  cancel.textContent = 'cancel';
  row.appendChild(name);
  row.appendChild(go);
  row.appendChild(cancel);
  const armEsc = pushEscape(function(){
    if (!row.isConnected){ popEscape(armEsc); return false; }
    disarm();
  }, ESC.menu);
  function disarm(){ popEscape(armEsc); loadBps(); }
  cancel.addEventListener('click', disarm);
  go.addEventListener('click', async function(){
    go.textContent = '…';
    const r = await tryOp('runs.bp.rm', { id: bp.id });
    if (r.error){ name.textContent = r.error.message; go.textContent = 'REMOVE'; return; }
    disarm();
  });
}

/* P0.7: condition edit in place. There is no runs.bp.set — the write is
   runs.bp.add {symbol: origSig, target, cond} on the SAME origSig+target, which
   RunStore.setBp treats as an update (idempotent by origSig: same id, hits
   kept) and RunsManager syncs onto live sessions; onHit reads the cond from the
   store per hit, so the edit applies at once. bp.add forces enabled=true, so a
   disabled BP is toggled straight back off. cond "" clears (blank = no filter). */
function editCond(row: HTMLElement, bp: Breakpoint, host: HTMLElement){
  if (!bp.origSig) return;                    // nothing to re-add against
  const span = row.querySelector('.cond');
  if (!span || row.querySelector('.condedit')) return;
  const inp = document.createElement('input');
  inp.type = 'text';
  inp.className = 'condedit';
  inp.value = bp.cond || '';
  inp.placeholder = 'argN · !argN · argN == null · argN != null';
  inp.spellcheck = false;
  span.replaceWith(inp);
  inp.focus();
  inp.select();
  const condEsc = pushEscape(function(){
    if (!inp.isConnected){ popEscape(condEsc); return false; }
    closeEdit();
  }, ESC.menu);
  function closeEdit(){ popEscape(condEsc); loadBps(); }
  inp.addEventListener('keydown', async function(e){
    if (e.key === 'Escape'){ closeEdit(); return; }
    if (e.key !== 'Enter') return;
    const cond = inp.value.trim();
    if (cond === (bp.cond || '')){ closeEdit(); return; }   // unchanged → no write
    inp.disabled = true;
    const rr = await tryOp('runs.bp.add', { symbol: bp.origSig, target: bp.target, cond: cond });
    if (rr.error){
      noteRow(host, 'condition not saved: ' + rr.error.message);   // the typed text survives (P0.4's lesson)
      inp.disabled = false;
      inp.focus();
      return;
    }
    if (!bp.enabled) await tryOp('runs.bp.toggle', { id: bp.id });
    closeEdit();
  });
}

/* ==================================================================
   HIT FEED (backfill + live SSE)
================================================================== */
function hitRowHtml(h: Hit){
  const args = h.argsBrief || briefArgs(h.args);
  const name = h.name || h.addr || h.bpId;
  const thread = (h.thread && h.thread.name) || '';
  return '<span class="t">' + esc(hitTime(h)) + '</span>' +
    '<span class="m" title="' + esc(name) + '">' + esc(name) + '</span>' +
    '<span title="' + esc(args) + '">' + esc(args) + '</span>' +
    '<span class="d" title="' + esc(thread) + '">' + esc(thread) + '</span>';
}

function briefArgs(args: unknown){
  if (!Array.isArray(args)) return '';
  return '(' + args.slice(0, 3).map(function(a){
    const v = a && a.repr !== undefined ? a.repr : JSON.stringify(a);
    return String(v).slice(0, 40);
  }).join(', ') + (args.length > 3 ? ', …' : '') + ')';
}

/* transient in-list failure note (auto-expires) — for jumps that can't run
   (stale persisted target ids — map-r7 F4). Single-cell: the .rtab grid clips
   the message in the 280px bp column (map-r8 #4) */
function noteRow(hostEl: HTMLElement | null, msg: string){
  if (!hostEl) return;
  const n = document.createElement('div');
  n.className = 'bprow';
  n.innerHTML = '<span class="dim" style="color:#e07060">' + esc(msg) + '</span>';
  hostEl.insertBefore(n, hostEl.firstChild);
  setTimeout(function(){ n.remove(); }, 5000);
}

/* the feed's own empty state — the centre column is otherwise a blank void */
function renderFeedEmpty(){
  const feed = document.getElementById('hit-feed')!;
  /* inspect- and device-aware (persona pass M2): a recorded run with no hits is a fact,
     not an invitation to "trigger the app" on a device that is not there */
  const html = inspectedRun
    ? '<b>' + esc(inspectedRun) + ' recorded no hits</b><span>click the card again for every run</span>'
    : !pickedSerial
      ? '<b>no recorded hits</b><span>import a canonical bundle, or select a device to record locally</span>'
      : '<b>no hits yet</b><span>set a breakpoint (F9 on a method) and trigger the app</span>';
  feed.innerHTML = '<div class="hitempty">' + html + '</div>';
}

/* P0.6(a): client-side filter — the str-filter idiom minus the round-trip: the
   feed is a 300-row window, so a substring test over each row's rendered text
   (time · method · args · thread) beats any refetch. Hidden rows stay in
   hitRows and in the DOM (.fout), so clearing the box is instant. */
function rowMatches(r: HTMLElement){
  return !feedQ || r.textContent!.toLowerCase().indexOf(feedQ) !== -1;
}

function applyFeedFilter(){
  $all('#hit-feed .hrow').forEach(function(r){ r.classList.toggle('fout', !rowMatches(r)); });
  renderFeedCount();
}

/* toolbar count ("N hits" / "M / N shown") + the all-hidden case: a filter that
   matches nothing says so IN the feed rather than leaving a blank list */
function renderFeedCount(){
  const feed = document.getElementById('hit-feed')!;
  const n = hitRows.length;
  const shown = feedQ ? feed.querySelectorAll('.hrow:not(.fout)').length : n;
  const c = document.getElementById('hit-count');
  if (c) c.textContent = !n ? '' : (feedQ ? shown + ' / ' + n + ' shown' : n + (n === 1 ? ' hit' : ' hits'));
  let nm = feed.querySelector('.hitnomatch');
  if (n && feedQ && !shown){
    if (!nm){
      nm = document.createElement('div');
      nm.className = 'hitempty hitnomatch';
      feed.appendChild(nm);
    }
    nm.innerHTML = '<b>no hits match</b><span>' + esc(feedQ) + '</span>';
  } else if (nm) nm.remove();
}

/* P0.6(b): ⏸ — the SSE entry point. Paused hits queue here (only the newest
   300 could ever show, so the queue is capped like the feed) and the badge
   counts every one; RESUME flushes oldest-first so the newest lands on top. */
function liveHit(h: Hit){
  const ev = h as Hit & { execId?: string; target?: string };
  /* the feed is the PROJECT's: a hit for a target this project does not hold (another
     project's live session) never enters it (r4 M1) */
  if (ev.target && S.state && S.state.targets && !S.state.targets.some(t => t.id === ev.target)) return;
  if (inspectedRun && ev.execId){
    /* execIds are per-store sequences (ex1 in every project): the run's TARGET disambiguates (r3 M1) */
    const run = lastRuns.find(x => x.execId === inspectedRun);
    if (ev.execId !== inspectedRun || (run && run.target && ev.target && ev.target !== run.target)) return;
  }
  if (!paused){ prependHit(h, true); return; }
  pausedBuf.push(h);
  if (pausedBuf.length > 300) pausedBuf.shift();
  pausedN++;
  renderPause();
}

function setPaused(on: boolean){
  paused = on;
  if (!on){
    const buf = pausedBuf;
    pausedBuf = [];
    pausedN = 0;
    buf.forEach(function(h){ prependHit(h, true); });
  }
  renderPause();
}

function renderPause(){
  const b = document.getElementById('hit-pause');
  if (!b) return;
  b.classList.toggle('on', paused);
  b.innerHTML = paused
    ? '⏵ RESUME' + (pausedN ? ' <b class="pnew">' + pausedN + ' new</b>' : '')
    : '⏸ PAUSE';
}

function prependHit(h: Hit, animate: boolean){
  hitRows.unshift(h);
  const feed = document.getElementById('hit-feed')!;
  const empty = feed.querySelector('.hitempty:not(.hitnomatch)');
  if (empty) empty.remove();                  // first hit clears the placeholder
  const r = document.createElement('div');
  r.className = 'rtab hrow';
  r.innerHTML = hitRowHtml(h);
  if (!rowMatches(r)) r.classList.add('fout');   // P0.6(a): the live filter applies to new rows too
  r.addEventListener('click', function(){ selectHit(h, r); });
  r.addEventListener('dblclick', async function(){
    if (!h.addr) return;
    // a native hit's addr is the "native:module!export" origSig — no JEB code view; jumpToCode would
    // swap the center away and land nowhere (returns true, so no note fires). Same guard as loadBps.
    if (h.addr.startsWith('native:')) return;
    const ok = await jumpToCode(h.target || null, h.addr);
    if (ok === false) noteRow(feed, 'jump failed: target not open — ' + (h.target || '?'));   // map-r7 F4
  });
  /* P0.6(c): stick-to-top ONLY when the feed already sits at the top. Anywhere
     else the new row's height is added back to scrollTop so what the user is
     reading (or has selected) stays put; .hitfeed opts out of the browser's own
     scroll anchoring so this is the one deterministic compensation. A hidden
     (.fout) row measures 0 and moves nothing. */
  const atTop = feed.scrollTop <= 1;
  feed.insertBefore(r, feed.firstChild);
  if (!atTop) feed.scrollTop += r.offsetHeight;
  if (hitRows.length > 300){
    hitRows.pop();
    /* evict the oldest ROW, not the last child — the no-match note sits below the rows */
    let last = feed.lastElementChild;
    while (last && !last.classList.contains('hrow')) last = last.previousElementSibling;
    if (last) last.remove();
  }
  renderFeedCount();
}

async function backfillHits(){
  const feed = document.getElementById('hit-feed')!;
  feed.innerHTML = '';
  hitRows = [];
  selHit = null;                              // the feed was rebuilt — nothing is selected
  /* P0.6: the store re-read below already holds whatever queued while paused —
     flushing the queue would only duplicate it. The pause itself is the user's
     choice and stays. */
  pausedBuf = [];
  pausedN = 0;
  renderPause();
  const hp: OpParams = { limit: 100 };
  if (inspectedRun) hp.run = inspectedRun;                  // an inspected card narrows the feed to its run
  const r = await tryOp('runs.hits', hp);
  const hits = (r.data && r.data.hits) || [];
  for (let i = hits.length - 1; i >= 0; i--) prependHit(hits[i], false);
  if (!hitRows.length) renderFeedEmpty();
  renderFeedCount();                          // also clears a stale count when the rebuild came back empty
  renderRunRail();
  const d = document.getElementById('hit-detail')!;
  d.hidden = true;
  d.innerHTML = '';
}

function selectHit(h: Hit, rowEl: HTMLElement){
  selHit = h;
  $all('#hit-feed .hrow').forEach(function(r){ r.classList.toggle('sel', r === rowEl); });
  const args = h.args || [];
  const d = document.getElementById('hit-detail')!;
  d.hidden = false;
  d.innerHTML = '<b style="color:var(--ink)">' + esc(h.name || h.addr) + '</b> · ' + esc(hitTime(h)) +
    ' · ret ' + esc(h.ret == null ? 'null' : (h.ret.repr || JSON.stringify(h.ret))) +
    '<br><span style="color:var(--ink-faint)">args: ' + esc(h.argsBrief || briefArgs(args)) + '</span>';
  const rail = document.getElementById('rund-body')!;
  let html = '<div class="xrow"><span class="led g"></span><span>' + esc(h.name || h.addr) + '</span></div>' +
    '<div class="xrow"><span class="led b"></span><span>' + esc(hitTime(h)) + ' · ' +
      esc((h.thread && h.thread.name) || '') + '</span></div>';
  args.forEach(function(a, i){
    html += '<div class="xrow"><span class="cn" style="color:var(--blue)">arg' + i + '</span><span>' +
      esc(a && a.repr !== undefined ? a.repr : JSON.stringify(a)) +
      (a && a.type ? ' <span class="dim">' + esc(a.type) + '</span>' : '') + '</span></div>';
  });
  if (h.ret != null)
    html += '<div class="xrow"><span class="cn" style="color:var(--grn)">ret</span><span>' +
      esc(h.ret.repr || JSON.stringify(h.ret)) + '</span></div>';
  if (h.frames && h.frames.length){
    html += '<div class="dockhint">backtrace</div>';
    h.frames.slice(0, 12).forEach(function(f, i){
      // native frames arrive as pre-symbolized strings ("libc.so!read+0x1c"); Java frames are {owner,method}
      const label = typeof f === 'string' ? f : (f.owner + '.' + f.method);
      if (typeof f !== 'string' && i < 2 && /VMStack|getStackTrace/.test(f.owner!)) return;   // skip the capture frames
      html += '<div class="xrow"><span class="cn" style="color:var(--blue)">' + (i + 1) + '</span><span>' +
        esc(label) + '</span></div>';
    });
  }
  rail.innerHTML = html;
}

/* rail RUN inspector with nothing selected: the run summary + how to fill it,
   the same treatment the map overview uses when no node is picked */
function renderRunRail(){
  if (selHit) return;                         // a hit owns the rail while one is selected
  const rail = document.getElementById('rund-body');
  if (!rail) return;
  const latest = (inspectedRun && lastRuns.find(x => x.execId === inspectedRun)) || lastRuns.find(x => x.endTs == null) || lastRuns[0];
  const hint = '<span class="dockhint">click a hit in the feed to inspect args and backtrace</span>';
  if (!latest){
    rail.innerHTML = '<div class="xrow"><span class="led o"></span><span class="dim">no run yet</span></div>' +
      '<span class="dockhint">Import a bundle or select a device to record</span>' + hint;
    return;
  }
  rail.innerHTML =
    '<div class="xrow"><span class="led ' + (latest.endTs == null ? 'g' : 'b') + '"></span><span>' +
      esc(latest.execId) + (latest.sourceRunId ? ' · imported' : latest.endTs == null ? ' · LIVE' : ' · ended') + '</span></div>' +
    '<div class="xrow"><span class="led b"></span><span>' +
      esc(latest.device || 'no device') + ' · ' + esc(latest.package || '') +
      (latest.targetName ? ' · ' + esc(latest.targetName) : '') + '</span></div>' +
    '<div class="xrow"><span class="cn" style="color:var(--blue)">hits</span><span>' +
      (latest.hitCount || 0) + ' hit' + ((latest.hitCount || 0) === 1 ? '' : 's') + ' · ' + bpTotal + ' breakpoint' + (bpTotal === 1 ? '' : 's') +
      (bpTotal && !bpCount ? ' (all disabled)' : bpTotal > bpCount ? ' (' + bpCount + ' armed)' : '') + '</span></div>' +
    '<div class="xrow"><span class="cn" style="color:var(--grn)">cov</span><span>' +
      ((latest.coverage || []).length) + ' observed methods · hooked sites only</span></div>' +
    (latest.producerMode ? '<p>evidence ' + esc(latest.completion || 'unknown') + ' · cleanup ' + esc(latest.cleanup || 'unknown') + ' · producer ' + esc(latest.producerMode) + '</p>' : '') +
    (latest.sourceRunId ? '<p>Recording started ' + esc(fmtRunTs(latest.startTs)) + '</p><p>Source run<br><span style="overflow-wrap:anywhere">' + esc(latest.sourceRunId) + '</span></p>' : '') +
    (latest.sourceManifestSha256 ? '<p>Manifest SHA-256<br><span style="overflow-wrap:anywhere">' + esc(latest.sourceManifestSha256) + '</span></p>' : '') +
    (latest.fixtureBinding ? '<p>Synthetic fixture binding: displayed under this target for testing; not target evidence.</p>' : '') +
    (latest.projectionTruncated ? '<p>Displayed hits are a limited projection. Export retains all manifest-listed evidence.</p>' : '') + hint;
}

/* dock RUNTIME tab (WORKBENCH_SPEC §8): the VIEWED target's subset — its live or latest
   run and the last hits — labelled with the target's name; "no target open" without one.
   Refetched on target/project switch (app.ts hooks) and after every session event; a
   live hit for the shown run ticks in over SSE. */
function dockHitRow(h: Hit){
  const row = document.createElement('div');
  row.className = 'rtab dhit';
  row.innerHTML = '<span class="t">' + esc(hitTime(h)) + '</span><span class="m">' +
    esc(h.name || h.addr || '') + '</span><span>' + esc(h.argsBrief || briefArgs(h.args)) + '</span>' +
    '<span class="d">' + esc((h.thread && h.thread.name) || '') + '</span>';
  return row;
}

export async function refreshRuntimeDock(): Promise<void> {
  const host = document.getElementById('dock-run-rows');
  if (!host) return;
  const seq = ++dockSeq;
  if (!S.currentTid){
    dockRun = null;
    host.innerHTML = '<span class="dockhint">no target open — RUNTIME shows the viewed target\'s runs</span>';
    return;
  }
  const r = await tryOp('runs.runs', { target: S.currentTid });
  if (seq !== dockSeq) return;
  const tname = activeTargetName() || 'this target';
  if (r.error){
    dockRun = null;
    host.innerHTML = '<span class="dockhint">runs unavailable — ' + esc(r.error.message) + '</span>';
    return;
  }
  const runs: RunInfo[] = r.data || [];
  const live = runs.find(x => x.endTs == null);
  const latest = live || runs[0];
  if (!latest){
    dockRun = null;
    /* dilemma 5: the target has none, but the project may hold runs with no target identity
       (pre-batch-2 records) — say where they are instead of reading as a regression */
    const all = await tryOp('runs.runs', {});
    if (seq !== dockSeq) return;
    const orphans = ((all.data || []) as RunInfo[]).filter(x => !x.targetKey && x.target !== S.currentTid).length;
    host.innerHTML = '<span class="dockhint">no runs for ' + esc(tname) +
      (orphans ? ' · ' + orphans + ' run' + (orphans === 1 ? '' : 's') + ' in this project carr' + (orphans === 1 ? 'ies' : 'y') + ' no target identity — ⌃4 lists them' : ' — ⌃4 Runs view to import or record') + '</span>';
    return;
  }
  dockRun = latest.execId;
  host.innerHTML = '<span class="dockhint dockrun">' + esc(tname) + ' · ' + (live ? 'LIVE ' : 'latest ') + esc(latest.execId) +
    ' · ' + esc(latest.device || '') + ' · ' + esc(latest.package || '') +
    (latest.sourceRunId ? ' · imported' : '') + (latest.fixtureBinding ? ' · synthetic, not target evidence' : '') +
    ' · ' + (latest.hitCount || 0) + ' hits · cov ' + ((latest.coverage || []).length) + ' methods' +
    (runs.length > 1 ? ' · ' + runs.length + ' runs of this target' : '') + '</span>';
  const hr = await tryOp('runs.hits', { run: latest.execId, limit: 5 });
  if (seq !== dockSeq) return;
  const hits: Hit[] = (hr.data && hr.data.hits) || [];
  hits.slice(-5).reverse().forEach(function(h){ host.appendChild(dockHitRow(h)); });
}

/* a live hit for the dock's run: newest under the summary line, at most five rows */
function dockTick(h: Hit){
  const host = document.getElementById('dock-run-rows');
  if (!host) return;
  const head = host.querySelector('.dockrun');
  if (!head) return;
  head.insertAdjacentElement('afterend', dockHitRow(h));
  const rows = host.querySelectorAll('.dhit');
  for (let i = 5; i < rows.length; i++) rows[i].remove();
}

/* ==================================================================
   RUN HISTORY + DIFF
================================================================== */
const RUN_COLORS = ['#0072B2', '#D55E00', '#009E73', '#E69F00', '#CC79A7', '#56B4E9'];

/* the ⇄ DIFF button needs exactly two picked cards — say so instead of just
   sitting there looking broken */
function renderDiffAffordance(){
  const btn = document.getElementById('diff-btn') as HTMLButtonElement | null;
  const hint = document.getElementById('diff-hint');
  if (!btn || !hint) return;
  const ready = diffSel.length === 2;
  btn.disabled = !ready;
  hint.textContent = ready
    ? diffSel[0] + ' ⇄ ' + diffSel[1]
    : (diffSel.length === 1 ? '⌘-click one more run card to pick B' : '⌘-click two run cards to pick A and B');
}

let noProject = false;   // runs.runs answered E_NOTFOUND: no project open (a legal state, not an outage — b2-r1 M5)
async function loadRuns(){
  document.getElementById('run-import-target')!.textContent = 'Import into viewed target: ' + (activeTargetName() || 'no target open — open an analysis target first');
  const r = await tryOp('runs.runs', {});
  noProject = !!(r.error && (r.error as { code?: string }).code === 'E_NOTFOUND');
  lastRuns = r.data || [];                                   // the PROJECT's runs (batch 2) — every one reachable
  /* a ✕ armed in one project must not carry its "confirm" into another on switch (b2-r5 L1):
     the new project may have a run of the same id, and one click would delete it */
  if (rmArmed && rmArmedProject !== activeProjectKey()){
    rmArmed = null; rmArmedProject = null;
    if (rmTimer) clearTimeout(rmTimer);
  }
  if (S.covRun && !lastRuns.some(x => x.execId === S.covRun)){ S.covRun = null; updateDockScope(); }   // a covRun that left the project (b2-r5 M5)
  refreshRuntimeDock();                                      // the dock shows the viewed target's subset
  if (inspectedRun && !lastRuns.some(x => x.execId === inspectedRun)) inspectedRun = null;
  if (dockRun && !lastRuns.some(x => x.execId === dockRun)) dockRun = null;
  renderRunCards();
  renderRunRail();
}

/* run-card click = INSPECT (feed filter, rail, coverage follow the run); ⌘/⇧-click =
   pick for DIFF — the picked cards say which is A and which is B (runs review 2/A-B) */
function renderRunCards(){
  const selectedForExport = lastRuns.find(run => run.execId === inspectedRun);
  (document.getElementById('run-export') as HTMLButtonElement).disabled = !selectedForExport?.sourceManifestSha256;
  document.getElementById('run-export-reason')!.textContent = selectedForExport?.sourceManifestSha256 ? ''
    : selectedForExport ? 'This local view has no canonical manifest; use backend/runs/local_run.py for exportable capture'
    : 'Inspect an imported bundle to export';
  const host = document.getElementById('runs-cards')!;
  host.innerHTML = '';
  const count = document.getElementById('run-count');
  if (count){
    count.textContent = lastRuns.length ? String(lastRuns.length) : '';
    if (inspectedRun){                                        // the way out is visible (persona pass M9)
      count.appendChild(document.createTextNode(' · inspecting ' + inspectedRun));
      const x = document.createElement('span');
      x.className = 'run-uninspect'; x.textContent = 'show all runs'; x.title = 'stop inspecting: the feed, rail and coverage go back to every run';
      x.addEventListener('click', function(){ inspectRun(null); });
      count.appendChild(x);
    }
    const ended = lastRuns.filter(x => x.endTs != null).length;
    if (ended){                                               // delete every ENDED run — two-step (runs.rm all)
      const d = document.createElement('span');
      d.className = 'run-rmall' + (rmArmed === 'all' ? ' arm' : '');
      d.textContent = rmArmed === 'all' ? 'confirm: delete ' + ended + ' ended run' + (ended === 1 ? '' : 's') : '✕ delete ended';
      d.title = 'delete every ended run of this project (hit logs are parked beside the store, never unlinked); a live run is skipped — click twice';
      d.addEventListener('click', function(ev){ ev.stopPropagation(); removeRuns('all'); });
      count.appendChild(d);
    }
  }
  renderDiffAffordance();
  if (!lastRuns.length){
    host.innerHTML = '<div class="dockhint">' + (noProject ? 'no project open — runs are project-scoped' : 'no runs in this project yet — import a bundle or select a device to record') + '</div>';
    return;
  }
  lastRuns.forEach(function(x){
    const card = document.createElement('div');
    const ab = diffSel.indexOf(x.execId);
    card.className = 'rcard' + (ab >= 0 ? ' sel' : '') + (inspectedRun === x.execId ? ' insp' : '');
    const live = x.endTs == null;
    const color = RUN_COLORS[(x.color || 0) % RUN_COLORS.length];
    card.title = x.execId + (x.targetName ? ' · ' + x.targetName : '') + ' — click to inspect (hits, rail, coverage) · ⌘-click to pick for DIFF';
    /* the dot is the run's STATE (live / ended), like the rail's; the identity colour sits
       on the id text (persona pass M4: one glyph meant two things) */
    card.innerHTML = '<div class="rt"><span class="led ' + (live ? 'g' : 'b') + '"></span> RUN · <span class="rid" style="color:' + color + '">' + esc(x.execId) + '</span>' +
      (x.sourceRunId ? ' · imported' : '') + (x.fixtureBinding ? ' · synthetic, not target evidence' : '') +
      ' · recorded ' + esc(fmtRunTs(x.startTs)) + (live ? ' · LIVE' : '') +
      (x.completion ? ' · evidence ' + esc(x.completion) : '') + (x.cleanup ? ' · cleanup ' + esc(x.cleanup) : '') +
      (x.producerMode ? ' · producer ' + esc(x.producerMode) : '') + (x.projectionTruncated ? ' · bounded projection' : '') +
      (ab >= 0 ? '<span class="ab">' + (ab === 0 ? 'A' : 'B') + '</span>' : '') +
      (live ? '' : '<span class="rx' + (rmArmed === x.execId ? ' arm' : '') + '" title="delete this run (two-step; its hit log is parked, not unlinked)">' + (rmArmed === x.execId ? 'confirm ✕' : '✕') + '</span>') + '</div>' +
      '<div class="mc-line"><span>' + (x.hitCount || 0) + ' hit' + ((x.hitCount || 0) === 1 ? '' : 's') + '</span>' +
      (x.targetName ? '<span class="tbadge" title="target">' + esc(x.targetName) + '</span>' : '') +
      '<span style="margin-left:auto">cov ' + ((x.coverage || []).length) + ' methods</span></div>';
    card.addEventListener('click', function(ev: MouseEvent){
      if ((ev.target as HTMLElement).closest('.rx')){ ev.stopPropagation(); removeRuns(x.execId); return; }
      if (ev.metaKey || ev.ctrlKey || ev.shiftKey){
        if (diffSel.includes(x.execId)) diffSel = diffSel.filter(id => id !== x.execId);
        else { diffSel.push(x.execId); if (diffSel.length > 2) diffSel.shift(); }
        renderRunCards();
        return;
      }
      inspectRun(inspectedRun === x.execId ? null : x.execId);
    });
    host.appendChild(card);
  });
}

/* runs.rm — one card's ✕ or the header's "delete ended": the first click arms for 6 s and
   the card/header says what a second click does; the store parks the hit log */
async function removeRuns(which: string){
  if (rmArmed !== which){
    rmArmed = which;
    rmArmedProject = activeProjectKey();
    if (rmTimer) clearTimeout(rmTimer);
    rmTimer = window.setTimeout(function(){ rmArmed = null; renderRunCards(); }, 6000);
    renderRunCards();
    return;
  }
  rmArmed = null;
  if (rmTimer) clearTimeout(rmTimer);
  const r = await tryOpT('runs.rm', { run: which });
  if (r.error){
    /* a stale card (the run was already deleted elsewhere) → E_NOTFOUND: resync the whole
       view rather than just repaint the dead card (b2-r5 M5) */
    notice('delete failed — ' + r.error.message, { err: true });
    if ((r.error as { code?: string }).code === 'E_NOTFOUND') await reloadRuns({ preflight: false });
    else renderRunCards();
    return;
  }
  const d = (r.data || {}) as { count?: number; skippedLive?: number; removed?: { execId: string }[] };
  const gone = (d.removed || []).map(x => x.execId);
  if (clearRemovedRefs(gone)){ updateDockScope(); if (S.hooks.marksChanged) S.hooks.marksChanged(); }
  notice('deleted ' + (d.count || 0) + ' run' + (d.count === 1 ? '' : 's') + (d.skippedLive ? ' · ' + d.skippedLive + ' live run kept (detach first)' : '') + ' — hit logs parked beside the store');
  await reloadRuns({ preflight: false });
}

async function inspectRun(execId: string | null){
  inspectedRun = execId;
  /* the feed may inspect ANY project run, but the code gutter coverage only follows a run of the
     VIEWED target — a run of another target would stripe this class with foreign coverage (b2-r5
     M6). null resets to the latest. */
  const run = execId ? lastRuns.find(x => x.execId === execId) : null;
  /* match the viewed target by live id OR durable store key — a run recorded in a PREVIOUS boot
     keeps the reminted target id, so a live-id match alone would drop the viewed target's own
     older runs and stripe the gutter with the latest run instead (b2-r6 L3). */
  const viewedKey = (S.state as { targets?: { id: string; key?: string }[] } | null)?.targets?.find(t => t.id === S.currentTid)?.key;
  const onViewed = !execId || (!!run && (run.target === S.currentTid || (!!run.targetKey && run.targetKey === viewedKey)));
  S.covRun = onViewed ? execId : null;
  updateDockScope();
  if (S.hooks.marksChanged) S.hooks.marksChanged();
  renderRunCards();
  await backfillHits();
}

async function runDiff(){
  if (diffSel.length !== 2) return;
  const r = await tryOp('runs.diff', { a: diffSel[0], b: diffSel[1] });
  const p = document.getElementById('diff-panel')!;
  if (r.error){ p.hidden = false; p.textContent = r.error.message; return; }
  const d = r.data;
  const names = (rows: { label?: string; addr?: string }[] | undefined) => (rows || []).map(x => x.label || x.addr).join(', ') || '—';
  /* coverage delta (spec backlog): the counts and the net change, not just the method lists —
     A covered = onlyA+both, B = onlyB+both; B gained onlyB methods and dropped onlyA vs A. */
  const nA = (d.onlyA || []).length, nB = (d.onlyB || []).length, nBoth = (d.both || []).length;
  const covA = nA + nBoth, covB = nB + nBoth, net = nB - nA;
  const netStr = net > 0 ? '+' + net : net < 0 ? '−' + Math.abs(net) : '0';   // U+2212, matching "dropped"
  p.hidden = false;
  p.innerHTML =
    '<div class="diff-delta"><b>' + esc(d.a) + '</b> ' + covA + ' methods · <b>' + esc(d.b) + '</b> ' + covB +
      ' methods · <span class="d-net">Δ ' + netStr + '</span> · ' + nBoth + ' shared' +
      ' · <span class="d-gain">+' + nB + ' new in ' + esc(d.b) + '</span> · <span class="d-loss">−' + nA + ' dropped</span></div>' +
    '<b>only in ' + esc(d.a) + ':</b> ' + esc(names(d.onlyA)) + '<br>' +
    '<b>only in ' + esc(d.b) + ':</b> ' + esc(names(d.onlyB)) + '<br>' +
    '<b>both:</b> ' + esc(nBoth + ' methods');
}

/* ==================================================================
   INIT
================================================================== */
/* preflight (audit decision 13): the rows system.doctor / provision.sh report — adb, the
   frida sidecar, the attached devices with their root capability, the engine — with the fix
   line for whatever is not ok, so an attach that would fail says why BEFORE the click */
let preflightSeq = 0;
async function loadPreflight(){
  const host = document.getElementById('run-preflight');
  if (!host) return;
  const seq = ++preflightSeq;
  const r = await tryOpT('system.doctor', { rows: 'adb,devices,frida,jeb-engine' });   // only what the row shows (b2-r2 M2)
  if (seq !== preflightSeq) return;
  host.hidden = false;
  if (r.error){ host.innerHTML = '<span class="pf warn">preflight unavailable — ' + esc(r.error.message) + '</span>'; return; }
  const data = (r.data || {}) as { rows?: { id: string; status: string; found: string; fix?: string }[] };
  const rows = data.rows || [];
  const rank = (s: string) => s === 'missing' ? 0 : s === 'warn' ? 1 : 2;
  const want = rows.filter(x => ['adb', 'devices', 'frida', 'jeb-engine'].includes(x.id)).sort((a, b) => rank(a.status) - rank(b.status));   // not-ok first (persona pass M1)
  host.innerHTML = want.map(function(x){
    const g = x.status === 'ok' ? '✓' : x.status === 'warn' ? '!' : '✗';
    const tip = (x.fix ? 'fix: ' + x.fix + ' — ' : '') + x.found;
    const fix = x.status !== 'ok' && x.fix ? '<span class="fixt">— fix: ' + esc(clip(x.fix, 70)) + '</span>' : '';
    return '<span class="pf ' + esc(x.status) + '" data-tip="' + esc(tip) + '">' + g + ' ' + esc(x.id) + ' · ' + esc(clip(x.found, 80)) + fix + '</span>';
  }).join('');
}

export async function reloadRuns(opts?: { preflight?: boolean }): Promise<void> {
  const jobs = [loadDevices(), loadBps(), loadRuns()];
  if (!opts || opts.preflight !== false) jobs.push(loadPreflight());   // on view open; a detach does not re-probe the host (b2-r2 M2)
  await Promise.all(jobs);
  await backfillHits();
}

export function initRuns(): void {
  if (wired) return;
  wired = true;
  document.getElementById('run-import')!.addEventListener('click', () => { void importBundle(); });
  document.getElementById('run-export')!.addEventListener('click', () => { void exportBundle(); });
  document.getElementById('run-device')!.addEventListener('change', () => {
    pickedSerial = (document.getElementById('run-device') as HTMLSelectElement).value;
    (document.getElementById('run-owner-confirmed') as HTMLInputElement).checked = false;
    void loadDevices();
  });
  document.getElementById('run-attach')!.addEventListener('click', function(){ doAttach(false); });
  document.getElementById('run-spawn')!.addEventListener('click', function(){ doAttach(true); });
  /* DETACH ends every live session of THIS project (the server scopes it) — a two-step
     confirm that names them, the idiom the BP row already uses (runs review 5) */
  document.getElementById('run-detach')!.addEventListener('click', async function(){
    const btn = this as HTMLButtonElement;
    const disarm = function(){ if (detachArmed) clearTimeout(detachArmed); detachArmed = 0; btn.textContent = 'DETACH'; btn.classList.remove('arm'); btn.dataset.tip = 'Detach every live session of this project (two-step)'; };
    if (!detachArmed){
      const r = await tryOp('runs.devices', { probe: false });   // the confirm only needs the sessions
      const activeProject = ((S.state && S.state.projects) || []).find(p => p.active);
      const activeName = activeProject ? activeProject.name : null;
      /* the detach is project-scoped on the server: count and name only THIS project's sessions (b2-r1 L3) */
      const live = (((r.data && r.data.devices) || []) as { serial?: string; package?: string; target?: string; project?: string; live?: boolean }[])
        .filter(d => d.live && (!d.project || !activeName || d.project === activeName));
      if (!live.length){ notice('no live session to detach'); return; }
      btn.textContent = 'CONFIRM ✕ ' + live.length;
      btn.classList.add('arm');
      btn.dataset.tip = 'click again within 6 s to detach ' + live.map(d => (d.package || '?') + ' on ' + (d.serial || '?')).join(', ');
      detachArmed = window.setTimeout(disarm, 6000);
      return;
    }
    disarm();
    const r = await tryOp('runs.detach', {});
    if (r.error) notice('detach failed: ' + r.error.message, { err: true });
    await reloadRuns({ preflight: false });
  });
  document.getElementById('diff-btn')!.addEventListener('click', runDiff);
  /* P0.6(a): the filter box — input event like str-filter. Esc inside it clears
     the text, or blurs when already empty, instead of closing the Runs view:
     the owner is pushed on focus at menu priority and popped on blur. */
  const filt = document.getElementById('hit-filter') as HTMLInputElement;
  let filtEsc: EscapeHandle | null = null;
  filt.addEventListener('input', function(){
    feedQ = filt.value.trim().toLowerCase();
    applyFeedFilter();
  });
  filt.addEventListener('focus', function(){
    filtEsc = pushEscape(function(){
      if (filt.value){ filt.value = ''; feedQ = ''; applyFeedFilter(); }
      else filt.blur();
    }, ESC.menu);
  });
  filt.addEventListener('blur', function(){ popEscape(filtEsc); filtEsc = null; });
  /* P0.6(b): ⏸ button, plus Space while the Runs view owns the center and
     nothing is being typed (mockup runs.html:446 "Pause the live feed (Space)").
     palette.js binds no Space; a focused control keeps its own (button click). */
  document.getElementById('hit-pause')!.addEventListener('click', function(){ setPaused(!paused); });
  document.addEventListener('keydown', function(e){
    if (e.key !== ' ' || S.center !== 'runs' || e.metaKey || e.ctrlKey || e.altKey || modalUp()) return;
    const t = e.target as HTMLElement;
    if (t && t.closest && t.closest('input,textarea,select,button,[contenteditable],[role="button"]')) return;
    e.preventDefault();
    setPaused(!paused);
  });
  /* Escape on the central dispatcher, keyed on S.center — ⌃4 and the rail button
     never focus #runswrap, so the old wrap listener never fired (F5). The inline
     ＋BP form pushes its own owner at menu priority and still cancels itself. */
  pushPaneEscape('runs');
  // live hits over SSE (the runs channel)
  onEvent(function(channel, data){
    if (channel !== 'runs' || !data) return;
    const d = data as Hit & { type?: string };
    if (d.type === 'hit'){
      liveHit(d);                                     // P0.6(b): honours ⏸
      const ev = d as Hit & { execId?: string; target?: string };
      if (dockRun && ev.execId === dockRun && (!ev.target || ev.target === S.currentTid)) dockTick(d);   // its run AND its target (r3 M1)
    }
    if (d.type === 'session') { loadDevices(); loadRuns(); }
    if (d.type === 'removed') {                        // another client deleted runs (b2-r5 M5)
      /* execIds are per-PROJECT sequences (ex1 exists in every project), and the SSE bus is one
         global stream — a delete in project A must not clear a same-named ex<N> in the viewed
         project B (b2-r6 M1). The event carries the project NAME; match it to the active one. */
      const rm = data as { execIds?: string[]; project?: string };
      const activeName = ((S.state as { projects?: { name: string; active?: boolean }[] } | null)?.projects || []).find(p => p.active)?.name || null;
      if (rm.project && activeName && rm.project !== activeName) return;
      if (clearRemovedRefs(rm.execIds || [])){ updateDockScope(); if (S.hooks.marksChanged) S.hooks.marksChanged(); }
      loadRuns();
    }
    if (d.type === 'hitcount') { /* rate-cap heartbeat — feed stays lean by design */ }
    if (d.type === 'native') {   // native bp arm outcome — surface a refused/unverified bind (build-id guard)
      const n = data as { kind?: string; module?: string; expected?: string; actual?: string; error?: string };
      const feed = document.getElementById('hit-feed');
      if (n.kind === 'native-mismatch')
        noteRow(feed, '⚠ ' + (n.module || 'native bp') + ' not armed — build-id mismatch (loaded '
          + (n.actual || '?') + ' ≠ expected ' + (n.expected || '?') + ')');
      else if (n.kind === 'native-unverified')
        noteRow(feed, (n.module || 'native bp') + ' armed — build-id unverified in the loaded module');
      else if (n.kind === 'native-armfail')
        noteRow(feed, '⚠ ' + (n.module || 'native bp') + ' not armed — ' + (n.error || 'bind failed'));
    }
  });
  reloadRuns();
}
