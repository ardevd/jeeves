// views/device.ts — DEVICE view (⌃6) live: device bar from device.list (a selector
// past one device, a 10s plug/unplug poll while the view shows, an honest empty
// panel when there is nothing to catalog), catalog from device.catalog (kind tabs +
// free-text filter), rail detail with annotations (accept/reject via agent.review),
// PULL → target opens, ATTACH on processes.
import { $all, S, pushEscape, popEscape, ESC, escHtml as esc, tryOpT } from '../ui/hub.js';
import { tryOp } from '../api.js';
import { pushPaneEscape, notice, selectCenter } from '../ui/chrome.js';
import { paintDev } from '../ui/ribbon.js';
import type { OpError } from '../api.js';
import type { Annotation, Catalog, CatalogEntry, Component, Components, Device, ProcInfo, TargetInfo } from '../types.js';

let wired = false;
let SERIAL: string | null = null;
let rows: CatalogEntry[] = [];  /* live catalog entries + the last refresh's tombstones */
let totalAll = 0;            /* live entries only — tombstones are not inventory */
let kindTotals: Record<string, number> | null = null;  /* per-kind device truth, counted before the scan caps */
let cappedKinds: string[] = [];  /* the kinds that hit a cap — their counts render "N+" */
let tabId = 'all';           // active catalog tab — picks both the rows and the columns
let selEntry: CatalogEntry | null = null;
let selIdx = -1;             // index into rows, so the highlight survives a re-render

const KINDICON: Record<string, string> = { app: '▣', 'system-app': '▤', lib: '⬡', service: '◈', process: '▶', partition: '▦', file: '◻' };

/* Long identity values (paths, AIDL descriptors) wrap in a 264px rail. Offer the line
   breaker the separators as break points: '/' for a path — breaking at its dots too
   would strand the extension ("TeleService." / "apk") — and '.' for a dotted FQCN.
   <wbr> is zero-width and contributes nothing to the text, a copy or a selection. */
function wbr(s: string){
  const sep = String(s).includes('/') ? /(\/)/g : /(\.)/g;
  return esc(s).replace(sep, '$1<wbr>');
}

/* owning-APK label for a service row: basename, extension dropped — the full path
   is width the meta track does not have, and lives in the title + the inspector */
function apkLabel(p: string | undefined){
  if (!p) return '';
  return String(p).split('/').pop()!.replace(/\.(apk|jar)$/, '');
}

/* identity keys worth a click-to-copy affordance — the long, unretypeable ones.
   `path` is one of them: a lib or system-app path is the same /apex/…/priv-app/…
   case as apkPath, and it is the argument every adb/pull command wants next. */
const COPYKEYS = ['iface', 'apkPath', 'host', 'pkg', 'path', 'link'];

/* ==================================================================
   DEVICE BAR — one device.list, three readers
==================================================================
   The ribbon's DEV item, the toolbar identity line (serial · model · Android · root ·
   frida — or the SELECTOR the spec asks for, DEVICE_SPEC §2, once more than one
   device is attached) and the table's empty state all paint from the same response.
   applyDevices is the ONE writer of SERIAL: it sets it from the list, moves it when
   the cataloged device leaves and another stays, and CLEARS it when the list comes
   back empty — P0-2 was SERIAL set once and never unset, so an unplugged phone kept
   its serial, LED, meta and rows on screen, and every later op targeted a ghost. */
let DEVS: Device[] = [];     /* the last device.list — the selector's options, the switch's lookup (P0-3) */
let noDev: { reason: string; err: OpError | null; note: string } | null = null;  /* {reason, err, note} while the table shows the empty panel (P0-1), else null */
let noDevKey = '';           /* what that panel currently renders — a 10s tick repaints nothing unchanged */
let listing: Promise<void> | null = null;  /* the device.list in flight — a caller landing mid-scan shares it */
let switchNote = '';         /* "A disconnected → B" until B's catalog line can carry it */

async function loadDevices(probe?: boolean){
  /* adb devices -l plus getprop/id/ps per device is ~500ms: a second caller (the
     poll tick under a slow USB hub, a re-open mid-scan) rides the same promise
     rather than stacking a second scan. probe=false (the poll) never runs the su
     probe — a Magisk grant prompt comes from REFRESH or an attach, not a timer */
  if (listing) return listing;
  listing = (async function(){
    const r = await tryOp('device.list', probe === false ? { probe: false } : {});
    /* the ribbon's DEV item paints from the same response (one device.list per tick) */
    if (r.error){ paintDev(null, r.error.message); applyDevices([], r.error); return; }
    const devs = (r.data && r.data.devices) || [];
    paintDev(devs, null);
    applyDevices(devs, null);
  })();
  try { await listing; } finally { listing = null; }
}

/* The device the view is ABOUT, from a fresh list. Three outcomes:
     the cataloged device is still listed   → repaint the bar (root/frida can flip)
     it left, or nothing was chosen yet     → the first listed one takes over
     the list is empty / adb failed         → tear everything down, show the reason
   Never loads the catalog itself: the open hook and the poll tick decide that from
   whether SERIAL moved, so one open is one device.catalog, not two. */
function applyDevices(devs: Device[], err: OpError | null){
  DEVS = devs;
  if (!devs.length){
    const lost = SERIAL;                     /* null when there was nothing to lose */
    if (lost) switchTo(null, '');
    noDev = { reason: noDeviceReason(err), err: err || null,
              /* the disconnect note outlives the tick that saw it — until a device returns */
              note: lost ? lost + ' disconnected' : (noDev && noDev.note) || '' };
    paintBar(null, devs, !!err);
    renderNoDevice();
    return;
  }
  const cur = devs.find(d => d.serial === SERIAL) || null;
  const pick = cur || devs[0];
  if (!cur) switchTo(pick, SERIAL ? SERIAL + ' disconnected → ' + pick.serial : '');
  paintBar(pick, devs, false);
}

/* SERIAL's one writer. Everything keyed on the old device goes with it — rows,
   selection, expansions, the two adb caches (keyed on SERIAL, but a stale row would
   still expand into them) and the rail, whose PULL/ATTACH read SERIAL at click time
   and would otherwise fire device A's path at device B (P0-3) or at nothing (P0-2). */
function switchTo(d: Device | null, note: string){
  SERIAL = d ? d.serial : null;
  rows = []; totalAll = 0; kindTotals = null; cappedKinds = [];
  selEntry = null; selIdx = -1;
  compCache.clear(); procCache.clear(); clearExpansions();
  clearRail();
  const meta = document.querySelector('#devicewrap .pmeta');
  if (meta) meta.textContent = note || (d ? 'loading catalog for ' + d.serial + '…' : '');
  /* the swap is the story of this catalog line, not of the second it takes to load:
     loadCatalog keeps it in front of "catalog built" (the openComponent rule —
     a note names the real story and must not be replaced by a confident line) */
  switchNote = d && note ? note : '';
  if (d){
    /* the old rows come down NOW, not when the new catalog lands — device A's rows
       under device B's serial is the open-caret lie refreshCatalog collapses for */
    noDev = null;
    document.getElementById('dev-table')!.innerHTML = '';
    document.getElementById('dev-count')!.textContent = '';
    syncTabs();
  }
}

/* the rail after a switch/disconnect: ENTRY, IDENTITY, COMPONENTS, ANNOTATION and
   PULL all describe an entry that is no longer addressable on any device */
function clearRail(){
  selSeq++; compSeq++;         /* an awaiting selectEntry/loadComponents must not repaint this */
  const nm = document.querySelector<HTMLElement>('#dev-insp .sym-name');
  if (nm){ nm.textContent = '—'; nm.title = ''; }
  const idEl = document.getElementById('dev-id');
  if (idEl){ idEl.textContent = ''; idEl.title = ''; }
  ['dev-identity', 'dev-annot', 'dev-pull', 'dev-comps'].forEach(function(id){
    const el = document.getElementById(id);
    if (el) el.innerHTML = '';
  });
  const cm = document.getElementById('dev-compmeta');
  if (cm) cm.textContent = cm.title = '';
  const ct = document.getElementById('dev-comptitle');
  const cs = document.getElementById('dev-comps');
  if (ct) ct.hidden = true;
  if (cs) cs.hidden = true;
}

/* the toolbar identity line. One device reads as it always did; more than one turns
   the serial into the selector (P0-3 — devs[0] was hard-picked before, so a second
   emulator was listed in the ribbon tip and unreachable from the view); none reads
   the reason, with the empty panel below carrying the fix. */
function paintBar(d: Device | null, devs: Device[], adbDown: boolean){
  const bar = document.querySelector('#devicewrap .vtoolbar');
  if (!bar) return;              /* panel popped out by a layout — nothing to paint into */
  const nameEl = bar.querySelector('b')!;
  const metaEl = bar.querySelector('span.dev-meta')!;
  const led = bar.querySelector('.led')!;
  const sel = document.getElementById('dev-select') as HTMLSelectElement | null;
  const many = !!d && devs.length > 1 && !!sel;
  nameEl.hidden = many;
  if (sel) sel.hidden = !many;
  /* REFRESH and KEYWORD SCAN act on a device (UI audit 2026-09-09: enabled with none) */
  ['dev-refresh', 'dev-annotate'].forEach(function(id){ const b = document.getElementById(id) as HTMLButtonElement | null; if (b) b.disabled = !d; });
  if (!d){
    nameEl.textContent = adbDown ? 'adb unavailable' : 'no devices';
    metaEl.textContent = '';
    led.className = 'led o';
    return;
  }
  nameEl.textContent = d.serial;
  if (many) fillSelect(sel!, devs, d.serial);
  metaEl.textContent = ' · ' + (d.model || '') + ' · Android ' + (d.android || '?') +
    ' · ' + rootLabel(d) +
    (d.fridaServer && d.fridaServer.running ? ' · frida ' + (d.fridaServer.version || 'up') : ' · frida down');
  metaEl.setAttribute('data-tip', rootTip(d));
  led.className = 'led ' + (rootKind(d) === 'none' ? 'o' : 'g');
}

/* root as a CAPABILITY, not a boolean (audit decision 14): what each kind costs is the
   tooltip — the same words the Runs device bar uses */
export function rootKind(d: Device): string { return d.root || (d.adbdRoot ? 'adbd-root' : 'none'); }
export function rootLabel(d: Device): string {
  const k = rootKind(d);
  return k === 'adbd-root' ? 'root (adbd)' : k === 'su' ? 'root (su)' : k === '?' ? 'root unknown' : 'no root';
}
export function rootTip(d: Device): string {
  const k = rootKind(d);
  return k === 'adbd-root' ? 'adbd itself runs as root (emulator / userdebug after adb root): every command runs plainly, frida-server is pushed and started directly'
       : k === 'su' ? 'root through su (Magisk / KernelSU / APatch): each root command hops through su — slower, and the first one may need a grant on the device'
       : k === '?' ? 'root not probed yet, or the device is not answering shell commands — REFRESH (or an attach) probes it; the poll never does'
       : 'no root: frida-server cannot be provisioned — adb root on an emulator or userdebug build, or root the device';
}

/* the options are rebuilt only when the SET of serials changed: a 10s tick that
   re-created them would collapse a dropdown the user has open */
function fillSelect(sel: HTMLSelectElement, devs: Device[], cur: string){
  const key = devs.map(d => d.serial).join('\n');
  if (sel.getAttribute('data-devs') !== key){
    sel.innerHTML = '';
    devs.forEach(function(d){
      const o = document.createElement('option');
      o.value = d.serial;
      o.textContent = d.serial + (d.model ? ' · ' + d.model : '');
      sel.appendChild(o);
    });
    sel.setAttribute('data-devs', key);
    sel.setAttribute('data-tip', devs.length + ' devices attached — pick the one to catalog');
  }
  if (sel.value !== cur) sel.value = cur;
}

/* ==================================================================
   EMPTY STATE  (P0-1 — no device, no adb)
==================================================================
   Before this the table simply never rendered: loadDevices wrote 'no devices' into
   the toolbar <b> and returned, loadCatalog returned on the null SERIAL, and a new
   user on ⌃6 saw a tab strip of zeros over a blank center. The panel names the
   cause and the one command that fixes it (click = copy, the IDENTITY idiom).

   Two causes are sourced from the op result — the adb binary is missing
   (ProcessBuilder: `Cannot run program "adb": error=2`) or adb ran and failed
   (timeout, server/client mismatch) — and the third is an empty list. An
   unauthorized or offline phone IS that third case: DeviceManager.list keeps only
   the `adb devices -l` lines containing "device", so both arrive here as zero
   devices. The panel says so rather than claiming a state the view cannot see. */
function noDeviceReason(err: OpError | null){
  if (!err) return 'none';
  /* a course room has no devices by design (CourseOps: the refusal's detail is course=host-only) —
     not an adb problem to troubleshoot (persona pass 2026-10-09 H5) */
  if (/course=host-only|class server/.test((err.payload?.error?.detail || '') + ' ' + (err.message || ''))) return 'course';
  return /Cannot run program|No such file|error=2/i.test(err.message || '') ? 'noadb' : 'adbfail';
}

interface NoDevSpec { title: string; lines: string[]; cmd: string }

const NODEV: Record<string, NoDevSpec> = {
  course: {
    title: 'no phones in a course room',
    lines: ['this room runs on the class server; it has no phone, adb or device bridge of yours',
            'the course target is already open in CODE (⌃1) — the morning\'s phone exercise is on your own laptop\'s JEB'],
    cmd: '' },
  noadb: {
    title: 'adb not found',
    lines: ['the backend found no adb binary on PATH, at ~/Library/Android/sdk/platform-tools, or in WORKBENCH_ADB',
            'install Android platform-tools (or set WORKBENCH_ADB to an adb binary before starting the backend)'],
    cmd: 'brew install --cask android-platform-tools' },
  adbfail: {
    title: 'adb is not answering',
    lines: ['adb devices failed — the adb server is down, hung, or mismatched with its client'],
    cmd: 'adb kill-server && adb devices' },
  none: {
    title: 'no device attached',
    lines: ['nothing answers adb devices — plug in a phone with USB debugging on, or boot an emulator',
            'a phone that is UNAUTHORIZED (accept the RSA prompt on its screen) or OFFLINE is dropped from the list until adb reports it as "device"'],
    cmd: 'adb devices' },
};

function renderNoDevice(){
  const host = document.getElementById('dev-table')!;
  const spec = NODEV[noDev!.reason] || NODEV.none;
  /* the op message minus its code prefix (api.js OpError: "E_BUSY: adb failed: …") */
  const detail = noDev!.err ? String(noDev!.err.message || noDev!.err).replace(/^E_[A-Z]+:\s*/, '') : '';
  const key = noDev!.reason + '|' + noDev!.note + '|' + detail;
  if (noDevKey !== key || !host.querySelector('.dvnodev')){
    noDevKey = key;
    host.innerHTML = '<div class="dvnodev">' +
      (noDev!.note ? '<span class="dvnd-note">' + esc(noDev!.note) + '</span>' : '') +
      '<b>' + esc(spec.title) + '</b>' +
      spec.lines.map(l => '<span>' + esc(l) + '</span>').join('') +
      (detail && noDev!.reason !== 'course' ? '<span class="dvnd-err">' + esc(detail) + '</span>' : '') +
      (spec.cmd ? '<span class="xv cpy dvnd-cmd" data-copy="' + esc(spec.cmd) + '" title="click to copy">' + esc(spec.cmd) + '</span>' : '') +
      (noDev!.reason === 'course' ? '' : '<span class="dvnd-dim">re-checked every ' + (POLL_MS / 1000) + ' s while this view is open</span>') +
      '</div>';
    document.getElementById('dev-count')!.textContent = noDev!.reason === 'none' ? 'no device' : noDev!.reason === 'course' ? 'course room' : 'adb unavailable';
  }
  if (noDev!.reason === 'course') stopPoll();   // a refusal that never changes is not re-asked every 10 s
  syncTabs();                 /* honest zeros under the panel — rows is [] */
}

/* ==================================================================
   DEVICE POLL  (P0-2 — plug/unplug detection)
==================================================================
   No SSE channel announces adb plug/unplug (api.js channels: progress, presence,
   activity, proposals, runs — app.js viewOpened says the same), so while this view
   is the center it re-runs device.list every 10 s through the loadDevices path the
   open already pays — bar, ribbon count and empty panel all follow. Gating:
   showCenter flips #devicewrap's `hidden` on every surface swap and fires no
   "closed" hook, so the observer in initDevice drops the timer the moment the wrap
   hides (Esc, ⌃N, a rail click, a component jump), and the tick re-checks S.center
   (the agents.js gate) — nothing polls a view that is not showing. A background
   browser tab skips its ticks: an adb scan nobody can see is a scan for nothing. */
const POLL_MS = 10_000;
let pollT = 0;

function startPoll(){
  if (pollT || S.center !== 'device') return;
  pollT = setInterval(pollTick, POLL_MS);
}

function stopPoll(){
  if (!pollT) return;
  clearInterval(pollT);
  pollT = 0;
}

async function pollTick(){
  if (S.center !== 'device'){ stopPoll(); return; }
  if (document.hidden) return;
  const before = SERIAL;
  await loadDevices(false);   // the 10 s poll: cached root or "?", no su probe (dilemma 4)
  /* the device under the catalog changed — first plug-in after the empty panel, or
     the cataloged one left and another stayed — so the catalog is that device's now */
  if (SERIAL && SERIAL !== before) await loadCatalog();
}

async function refreshCatalog(){
  await loadDevices(true);   // an explicit REFRESH probes root (dilemma 4: the poll never does)
  if (!SERIAL) return;
  const meta = document.querySelector('#devicewrap .pmeta')!;
  meta.textContent = 'refreshing…';
  compCache.clear();          // a re-scan means the device moved under us
  procCache.clear();          // …and the pids behind the /proc detail with it
  clearExpansions();
  renderTable();              /* collapse NOW — clearing state without re-rendering leaves
                                 open carets that do the opposite of what they show */
  const s = SERIAL;
  const r = await tryOp('device.catalog', { serial: s, refresh: true, count: 5000 });
  if (s !== SERIAL) return;     /* the device left or changed under the scan — the bar owns SERIAL (P0-2/P0-3) */
  if (r.error){ meta.textContent = 'refresh failed: ' + r.error.message; return; }
  const d = r.data;
  /* removedTruncated: diff.removed is the true count, the tombstone list a
     TOMB_CAP window — mark the header so the two never read as a contradiction */
  const diff = d.diff ? ' · +' + d.diff.added + ' −' + d.diff.removed
    + (d.removedTruncated ? '+' : '') + ' Δ' + d.diff.changed : '';
  meta.textContent = 'catalog built just now · ' + d.totalAll + ' entries' + diff;
  takeCatalog(d);
  reindexSelection();
  renderTable();
}

/* One reader for both load paths. Tombstones (entries the refresh REMOVED) join
   `rows` so a click still lands on the right object and the selection index still
   means something — every tab but CHANGED filters them out in renderTable. */
function takeCatalog(d: Catalog){
  const live = (d && d.entries) || [];
  const gone = (d && d.removed) || [];
  rows = gone.length ? live.concat(gone) : live;
  totalAll = (d && d.totalAll) || live.length;
  kindTotals = (d && d.kindTotals) || null;
  cappedKinds = (d && d.cappedKinds) || [];
}

/* A reload replaces every entry object, so the stored row index means nothing — it
   would highlight whichever entry now sits at that position. Re-find the selection by
   its identity (kind + name, the catalog's own key); drop it if the entry is gone. */
function reindexSelection(){
  if (!selEntry){ selIdx = -1; return; }
  selIdx = rows.findIndex(e => e.kind === selEntry!.kind && e.name === selEntry!.name);
  if (selIdx >= 0) selEntry = rows[selIdx];
}

async function loadCatalog(){
  if (!SERIAL) return;
  const s = SERIAL;
  const r = await tryOp('device.catalog', { serial: s, count: 5000 });
  if (s !== SERIAL) return;     /* the device left or changed under the fetch — the bar owns SERIAL (P0-2/P0-3) */
  const meta = document.querySelector('#devicewrap .pmeta');
  const lead = switchNote ? switchNote + ' · ' : '';
  switchNote = '';
  if (r.error){
    /* say so, and render the grid: after a switch the table still holds the
       no-device panel, and a live serial in the bar over it would contradict itself */
    if (meta) meta.textContent = lead + 'catalog failed: ' + r.error.message;
    renderTable();
    return;
  }
  takeCatalog(r.data);
  reindexSelection();
  if (meta && r.data && r.data.built)
    meta.textContent = lead + 'catalog built ' + String(r.data.built).slice(0, 16).replace('T', ' ') +
      ' · ' + totalAll + ' entries';
  renderTable();
}

/* ==================================================================
   CATALOG TABLE — tabs decide the rows AND the columns
==================================================================
   One tab per catalog kind, plus ALL and the two derived views (ANNOTATED,
   CHANGED) that cut across every kind. Selection is single: a kind's row carries
   different fields, so the columns are a property of the tab, not of the catalog.

   Each tab declares its columns; a column is
     { h: header label, t: grid track, get: e => value, tip?, html?, cls?, num? }
   and the tab's grid is ICON + those tracks + (slack) + FLAGS. renderTable writes that
   one track list into --dvcols, which the header row and every data row read — the two
   cannot drift apart. `cls`/`html` style the data cell only; `num` (right-aligned,
   tabular) applies to the header cell too, so a number column reads as one column.

   Track sizing has one rule behind it: a row is its own grid, so a *content*-sized
   track (auto/max-content) would resolve differently row to row and the columns would
   not line up. Tracks are therefore fr, fixed, or clamp-on-vw. Where a tab's last
   column is short (a version, a uid, a change chip), the tab sets `slack: true` — an
   empty 1fr track before FLAGS — and caps its name track around the p90 value width,
   so the values sit next to each other and the leftover width collects at the right
   edge instead of stranding a 6-character column 1000px from the row it describes. */
const ICON_TRACK = '20px';
const FLAG_TRACK = '124px';
const SLACK_TRACK = 'minmax(0,1fr)';

interface Col {
  h: string;
  t: string;
  get: (e: CatalogEntry) => unknown;
  tip?: (e: CatalogEntry) => string;
  html?: (e: CatalogEntry) => string;
  cls?: string;
  num?: boolean;
}

interface Tab {
  id: string;
  label: string;
  match: (e: CatalogEntry) => boolean;
  slack?: boolean;
  cols: Col[];
}

/* the ALL tab's one free-form column: whatever this kind's own tab would break out */
function detailsOf(e: CatalogEntry){
  switch (e.kind){
    case 'app':
    case 'system-app': return (e.version ? 'v' + e.version + ' · ' : '') + (e.path || '');
    case 'lib':        return (e.size ? e.size + ' B · ' : '') + (e.path || '');
    case 'service':    return [e.iface, apkLabel(e.apkPath), e.host ? 'host ' + e.host : '']
                                .filter(Boolean).join(' · ');
    case 'process':    return 'pid ' + (e.pid || '?') + (e.uid ? ' · ' + e.uid : '');
    case 'partition':  return e.link ? '→ ' + e.link : '';   /* e.size is the symlink target's strlen — not a size */
    case 'file':       return [e.perms, e.size ? e.size + ' B' : '', e.link ? '→ ' + e.link : '']
                                .filter(Boolean).join(' · ');
  }
  return e.path || '';
}

/* the row's own title: every field a cell could have clipped, verbatim, one per line.
   With per-tab columns a value can be off-screen entirely (a service's host on the
   LIB-shaped grid, a path anywhere) — the row tooltip is where it stays reachable,
   alongside the inspector's IDENTITY block. */
function rowTip(e: CatalogEntry){
  const L = [e.name];
  if (e.iface) L.push(e.iface);
  if (e.host) L.push('host ' + e.host);
  if (e.pkg) L.push('pkg ' + e.pkg);
  if (e.apkPath) L.push('apk ' + e.apkPath);
  if (e.version) L.push('version ' + e.version);
  if (e.pid) L.push('pid ' + e.pid + (e.uid ? ' · uid ' + e.uid : ''));
  else if (e.uid) L.push('uid ' + e.uid);
  if (e.perms) L.push('perms ' + e.perms);
  if (e.size && e.kind !== 'partition') L.push('size ' + e.size + ' B');   /* partition 'size' is the symlink target's strlen — not a size */
  if (e.link) L.push('→ ' + e.link);
  if (e.path) L.push(e.path);
  if (pullable(e)) L.push('double-click: pull + open as target');
  return L.join('\n');
}

/* the documented rule (review: the old two-arm OR let a partition's .img path
   and a service's apk path slip through the extension arm): services pull via
   the hosting package's apkPath (openComponent falls back to it), partitions
   are multi-GB block images — neither is ever pullable itself. The extension
   only decides the rail button's size/tip dressing, not eligibility. */
function pullable(e: CatalogEntry | null | undefined){
  return !!e && !e.removed && !!e.path && e.kind !== 'service' && e.kind !== 'partition';
}

function annList(e: CatalogEntry){ return e.annotations || []; }
function annLast(e: CatalogEntry){ const a = annList(e); return a.length ? a[a.length - 1] : null; }
function annTip(e: CatalogEntry){
  return annList(e).map(a => a.note + '  · ' + a.by).join('\n');
}

const SIZECOL: Col = { h: 'SIZE', t: '92px', num: true, get: e => e.size,
                  tip: e => e.size ? e.size + ' bytes' : '' };
const KINDCOL: Col = { h: 'KIND', t: '86px', cls: 'dvc-dim', get: e => e.kind };

const TABS: Tab[] = [
  { id: 'all', label: 'ALL', match: () => true, cols: [
    KINDCOL,
    { h: 'NAME',    t: 'minmax(0,1.1fr)', cls: 'dvc-name', get: e => e.name },
    { h: 'DETAILS', t: 'minmax(0,1.7fr)', cls: 'dvc-dim',  get: detailsOf } ] },

  /* packages: the path is long, constant-prefixed and rarely the thing you scan for —
     it stays in the row title and the inspector, the package name gets the width */
  { id: 'app', label: 'APP', match: e => e.kind === 'app', slack: true, cols: [
    { h: 'PACKAGE', t: 'minmax(0,540px)', cls: 'dvc-name', get: e => e.name,
      tip: e => [e.name, e.path].filter(Boolean).join('\n') },
    { h: 'VERSION', t: '150px', cls: 'dvc-dim', get: e => e.version } ] },

  { id: 'system-app', label: 'SYSTEM-APP', match: e => e.kind === 'system-app', slack: true, cols: [
    /* no VERSION column: the backend only resolves versions for user apps —
       an all-dashes column under a live header is worse than none */
    { h: 'PACKAGE', t: 'minmax(0,540px)', cls: 'dvc-name', get: e => e.name,
      tip: e => [e.name, e.path].filter(Boolean).join('\n') } ] },

  { id: 'lib', label: 'LIB', match: e => e.kind === 'lib', slack: true, cols: [
    { h: 'NAME', t: 'minmax(0,560px)', cls: 'dvc-name', get: e => e.name,
      tip: e => [e.name, e.path].filter(Boolean).join('\n') },
    SIZECOL ] },

  /* The two long columns of the view. INTERFACE keeps the priority the widened meta
     track gave it — a floor of 200px and an equal share of the slack, so an AIDL
     descriptor is readable where it used to ellipsise at 290px — and APK is still the
     one that yields: its track is clamp-on-vw with a 52px floor, so a narrow window
     eats the APK basename first. Both stay in the cell titles, and the full apkPath is
     one row-click away in IDENTITY. */
  { id: 'service', label: 'SERVICE', match: e => e.kind === 'service', cols: [
    { h: 'SERVICE',   t: 'minmax(0,1fr)',     cls: 'dvc-name', get: e => e.name },
    { h: 'INTERFACE', t: 'minmax(200px,1fr)', cls: 'dvc-dim',  get: e => e.iface },
    { h: 'APK',       t: 'minmax(0,clamp(52px,8vw,120px))', cls: 'dvc-dim',
      get: e => apkLabel(e.apkPath),
      tip: e => [e.apkPath ? 'apk ' + e.apkPath : '', e.pkg ? 'pkg ' + e.pkg : ''].filter(Boolean).join('\n') } ] },

  { id: 'process', label: 'PROCESS', match: e => e.kind === 'process', slack: true, cols: [
    { h: 'PROCESS', t: 'minmax(0,330px)', cls: 'dvc-name', get: e => e.name },
    { h: 'PID',     t: '70px',  num: true, get: e => e.pid },
    { h: 'UID',     t: '110px', cls: 'dvc-dim', get: e => e.uid } ] },

  { id: 'partition', label: 'PARTITION', match: e => e.kind === 'partition', cols: [
    { h: 'PARTITION', t: 'minmax(0,240px)', cls: 'dvc-name', get: e => e.name,
      tip: e => [e.name, e.path].filter(Boolean).join('\n') },
    /* no SIZE: ls reports the symlink's target-string length for these rows
       (15 = strlen('/dev/block/vda2')) — a number, not a partition size */
    { h: 'LINKS-TO', t: 'minmax(0,1fr)', cls: 'dvc-dim', get: e => e.link } ] },

  { id: 'file', label: 'FILE', match: e => e.kind === 'file', slack: true, cols: [
    { h: 'NAME', t: 'minmax(0,480px)', cls: 'dvc-name', get: e => e.name,
      tip: e => [e.name, e.path].filter(Boolean).join('\n') },
    SIZECOL,
    { h: 'PERMS', t: '104px', cls: 'dvc-dim', get: e => e.perms } ] },

  /* derived views — a filter over the whole catalog, so they keep the KIND column */
  { id: 'annotated', label: 'ANNOTATED', match: e => annList(e).length > 0, cols: [
    KINDCOL,
    { h: 'NAME',       t: 'minmax(0,1fr)',   cls: 'dvc-name', get: e => e.name },
    { h: 'ANNOTATION', t: 'minmax(0,1.8fr)', cls: 'dvc-dim',
      get: e => (annLast(e) || {} as Annotation).note, tip: annTip } ] },

  { id: 'changed', label: 'CHANGED', match: e => !!e.changed, slack: true, cols: [
    KINDCOL,
    { h: 'NAME',   t: 'minmax(0,520px)', cls: 'dvc-name', get: e => e.name },
    { h: 'CHANGE', t: '110px', get: e => e.changed,
      html: e => '<span class="vchip vc-mixed">' + esc(e.changed) + '</span>',
      tip: e => 'since the last catalog refresh: ' + e.changed } ] },
];

function activeTab(){ return TABS.find(t => t.id === tabId) || TABS[0]; }

/* ==================================================================
   PROCESS ROW EXPANSION  (device.proc — live /proc detail, inline)
==================================================================
   The ▶ on a process row was decoration that read as an expander; it is one now.
   Only process rows get it: no other kind has a per-row detail source (a lib or a
   partition is a path, and the rail already carries everything the catalog knows).

   Two pieces of state, both keyed by the process's identity rather than its row
   index — `rows` is one array for every tab, and the filter re-renders it:
     expanded  — which rows are open. Survives a re-render, so typing in the filter
                 keeps them; a tab switch clears it (a tab is a different row set,
                 and its rows are re-indexed).
     procCache — one device.proc payload per pid, so re-rendering (or collapsing and
                 re-opening) does not re-pay the adb round trip. Cleared on catalog
                 refresh: past that point the pids themselves may be stale. */
const expanded = new Set<string>();
const procCache = new Map<string, { data?: ProcInfo; error?: OpError }>();      // SERIAL|pid -> {data} | {error}

function procKey(e: CatalogEntry){ return String(e.pid) + '|' + e.name; }

function clearExpansions(){ expanded.clear(); }

/* kB → MB with one decimal — /proc reports every memory line in kB */
function mb(kb: number | undefined){
  const v = Number(kb || 0) / 1024;
  return v >= 1024 ? (v / 1024).toFixed(1) + ' GB' : v.toFixed(1) + ' MB';
}

/* seconds → the coarsest unit that still says something: 3d 4h, 4h 12m, 12m 30s, 30s */
function dur(s: number | undefined){
  s = Math.max(0, Math.round(Number(s) || 0));
  const d = Math.floor(s / 86400), h = Math.floor(s % 86400 / 3600),
        m = Math.floor(s % 3600 / 60), sec = s % 60;
  if (d) return d + 'd ' + h + 'h';
  if (h) return h + 'h ' + m + 'm';
  if (m) return m + 'm ' + sec + 's';
  return sec + 's';
}

/* Linux process states, spelled out — the letter is what /proc gives and what the
   thread table shows; the word is what the tooltip needs to be readable. */
const PSTATE: Record<string, string> = { R: 'running', S: 'sleeping', D: 'uninterruptible', Z: 'zombie',
                 T: 'stopped', t: 'tracing stop', X: 'dead', I: 'idle', K: 'wakekill' };

function stateTip(st: string | undefined){ return st + (PSTATE[st!] ? ' — ' + PSTATE[st!] : ''); }

function expNote(text: string, cls?: string){
  return '<div class="dvxnote' + (cls ? ' ' + cls : '') + '">' + esc(text) + '</div>';
}

/* The panel body for one loaded process: summary line, thread table, memory block. */
function expBody(d: ProcInfo){
  const mem: Record<string, number> = d.mem || {};
  const sum = [
    'state ' + (d.state || '?'),
    'ppid ' + (d.ppid != null ? d.ppid : '?'),
    'threads ' + (d.threads != null ? d.threads : '?'),
    mb(mem.VmRSS) + ' / ' + mb(mem.VmSize),
    'oom_adj ' + (d.oomScoreAdj != null ? d.oomScoreAdj : '?'),
    'up ' + dur(d.uptime),
  ].join(' · ');
  const extra = [
    'uid ' + (d.uid || '?') + (d.gid ? ':' + d.gid : ''),
    'cpu ' + (d.cpuSeconds != null ? d.cpuSeconds + 's' : '?') +
      ' (u ' + (d.utime != null ? d.utime : '?') + ' / s ' + (d.stime != null ? d.stime : '?') + ')',
    'fds ' + (d.fds != null ? d.fds : '?'),
    'ctxt ' + (d.ctxtVoluntary || 0) + ' vol / ' + (d.ctxtNonvoluntary || 0) + ' invol',
    d.processor != null ? 'cpu#' + d.processor : '',
    'oom ' + (d.oomScore != null ? d.oomScore : '?'),
  ].filter(Boolean).join(' · ');

  const threads = d.threadList || [];
  const tcells = threads.map(function(t){
    /* comm in the tooltip too — it's kernel-truncated at 15 chars and CSS-ellipsised,
       so the tooltip is the only full-fidelity path (every other cell has one) */
    return '<span class="dvxt" title="' + esc(t.comm) + ' · tid ' + esc(t.tid) + ' · ' + esc(stateTip(t.state)) + '">' +
      '<b class="dvxts s-' + esc(t.state) + '">' + esc(t.state) + '</b>' +
      '<span class="dvxtc' + (t.main ? ' dvxmain' : '') + '">' + esc(t.comm) + '</span></span>';
  }).join('');
  const tmore = d.threadsTruncated
    ? '<div class="dvxnote">showing ' + d.threadsShown + ' of ' + d.threads +
      ' threads (capped)</div>' : '';

  /* render only the keys the backend carried — absent (kernel threads) is not 0.0 MB */
  const memKeys = Object.keys(mem);
  const mrows = memKeys.map(function(k){
    return '<span class="dvxm"><span class="dvxk">' + esc(k) + '</span>' +
      '<span class="dvxv" title="' + esc(mem[k] || 0) + ' kB">' + esc(mb(mem[k])) + '</span></span>';
  }).join('') || '<span class="dvxk">no memory data for this process</span>';

  return '<div class="dvxsum" title="' + esc(stateTip(d.state || '?')) + '">' + esc(sum) + '</div>' +
    '<div class="dvxsub">' + esc(extra) + '</div>' +
    (d.cmdline ? '<div class="dvxcmd" title="' + esc(d.cmdline) + '">' + esc(d.cmdline) + '</div>' : '') +
    (d.exe || d.cwd ? '<div class="dvxsub">' +
      esc([d.exe ? 'exe ' + d.exe : '', d.cwd ? 'cwd ' + d.cwd : ''].filter(Boolean).join(' · ')) +
      '</div>' : '') +
    /* memory first: it is always exactly six rows, so it takes the fixed track and the
       thread grid — 1 row for zygote, 64 for system_server — collects the slack. The
       other way round strands the six numbers a thousand pixels from the summary. */
    '<div class="dvxcols">' +
      '<div class="dvxsec dvxmem"><div class="dvxh">MEMORY</div>' +
        '<div class="dvxmg">' + mrows + '</div></div>' +
      '<div class="dvxsec dvxthr"><div class="dvxh">THREADS<b>' + esc(threads.length) + '</b></div>' +
        '<div class="dvxtg">' + (tcells || expNote('no thread data')) + '</div>' + tmore + '</div>' +
    '</div>';
}

/* What the panel shows right now: cached payload, cached failure, or the placeholder
   the fetch replaces. A pid that raced away answers E_NOTFOUND — that is a normal
   outcome of clicking a row the catalog built minutes ago, not an error to shout about. */
function expHTML(e: CatalogEntry){
  const c = procCache.get(SERIAL + '|' + e.pid);
  if (!c) return expNote('loading /proc/' + e.pid + '…');
  if (c.error){
    const p = c.error.payload;
    if (p && p.error && p.error.code === 'E_NOTFOUND')
      return expNote('process exited — pid ' + e.pid + ' is gone (refresh the catalog)', 'gone');
    return expNote('device.proc failed: ' + c.error.message, 'gone');
  }
  return expBody(c.data!);
}

function expansionEl(e: CatalogEntry){
  const x = document.createElement('div');
  x.className = 'dvexp';
  x.setAttribute('data-k', procKey(e));
  x.innerHTML = expHTML(e);
  return x;
}

/* Fetch (once per pid) and repaint whichever panel is currently showing this row —
   the node the click created may already have been replaced by a re-render. */
async function loadProc(e: CatalogEntry){
  const key = SERIAL + '|' + e.pid;
  if (!procCache.has(key)){
    const r = await tryOp('device.proc', { serial: SERIAL, pid: e.pid });
    procCache.set(key, r.error ? { error: r.error } : { data: r.data });
  }
  const k = procKey(e);
  $all('#dev-table .dvexp').forEach(function(x){
    if (x.getAttribute('data-k') === k) x.innerHTML = expHTML(e);
  });
}

function toggleExpand(i: number){
  const e = rows[i];
  if (!e || e.kind !== 'process') return;
  const hadFocus = document.activeElement && document.activeElement.classList.contains('dvc-exp');
  const k = procKey(e);
  if (expanded.has(k)) expanded.delete(k);
  else expanded.add(k);
  renderTable();
  /* the full re-render destroyed the caret node — hand focus back so Enter/Space
     keeps toggling (keyboard toggle was one-shot otherwise, review finding) */
  if (hadFocus){
    const c = document.querySelector<HTMLElement>('#dev-table .dvrow[data-i="' + i + '"] .dvc-exp');
    if (c) c.focus();
  }
  if (expanded.has(k)) loadProc(e);
}

function gridOf(tab: Tab){
  return [ICON_TRACK].concat(tab.cols.map(c => c.t),
    tab.slack ? [SLACK_TRACK] : [], [FLAG_TRACK]).join(' ');
}

const SLACK_CELL = '<span class="dvc"></span>';

function cellHTML(col: Col, e: CatalogEntry | null, head: boolean){
  if (head)
    return '<span class="dvc' + (col.num ? ' dvc-num' : '') + '">' + esc(col.h) + '</span>';
  const v = col.get(e!);
  const s = v == null ? '' : String(v);
  const cls = 'dvc' + (col.num ? ' dvc-num' : '') + (col.cls ? ' ' + col.cls : '') +
    (s ? '' : ' dvc-none');
  const tip = s ? String((col.tip ? col.tip(e!) : s) || s) : '';
  return '<span class="' + cls + '"' + (tip ? ' title="' + esc(tip) + '"' : '') + '>' +
    (s ? (col.html ? col.html(e!) : esc(s)) : '—') + '</span>';
}

/* The kind icon. On a process row it is also the expander: same glyph, rotated 90°
   when open (▶ → ▼), which is why `process` was given the caret in the first place.
   Every other kind keeps a static, non-interactive icon — there is nothing to open. */
function iconHTML(e: CatalogEntry){
  /* a removed process has no /proc to read — tombstones never expand */
  if (e.kind !== 'process' || e.removed)
    return '<span class="dvc dvc-icon">' + (KINDICON[e.kind] || '◻') + '</span>';
  const open = expanded.has(procKey(e));
  /* tabindex -1, not 0: the table holds 144 process rows and had no tab stop at all
     before this — putting one per caret would bury the rail behind 144 Tabs. The
     caret still takes focus on click (so Enter/Space toggle it), and screen readers
     reach it by role, which is what aria-expanded is for. */
  return '<span class="dvc dvc-icon dvc-exp' + (open ? ' open' : '') + '" role="button"' +
    ' tabindex="-1" aria-expanded="' + (open ? 'true' : 'false') + '"' +
    ' title="' + (open ? 'collapse' : 'expand') + ' — live /proc detail for pid ' + esc(e.pid) + '">' +
    KINDICON.process + '</span>';
}

/* FLAGS: the per-row state every tab wants regardless of its columns — annotation
   badge (🤖 agent / ✎ human), the refresh-diff chip, and pull provenance. The chip is
   dropped on the CHANGED tab, where the change is a column of its own. */
function flagsHTML(e: CatalogEntry, tab: Tab){
  const last = annLast(e);
  const anns = annList(e);
  const badge = last ? (String(last.by).startsWith('agent') ? '🤖' : '✎') : '';
  const chip = e.changed && tab.id !== 'changed'
    ? '<span class="vchip vc-mixed">' + esc(e.changed) + '</span>' : '';
  const pulled = e.pulled ? '<span class="dvfp">→ pulled</span>' : '';
  const tip = [anns.length ? anns.length + ' annotation' + (anns.length > 1 ? 's' : '') : '',
    e.changed || '', e.pulled ? 'pulled → ' + e.pulled : ''].filter(Boolean).join('\n');
  return '<span class="dvc dvc-flags"' + (tip ? ' title="' + esc(tip) + '"' : '') + '>' +
    (badge ? '<span class="dvfb">' + badge + '</span>' : '') + chip + pulled + '</span>';
}

function headRow(tab: Tab){
  const h = document.createElement('div');
  h.className = 'dvhead';
  h.innerHTML = SLACK_CELL +                                   /* the icon gutter */
    tab.cols.map(c => cellHTML(c, null, true)).join('') +
    (tab.slack ? SLACK_CELL : '') +
    '<span class="dvc dvc-flags">FLAGS</span>';
  return h;
}

/* the filter searches what the tab shows (plus name and path, always) — typing
   "mediaserver" on SERVICE hits the interface column too, not just the name */
function haystack(e: CatalogEntry, tab: Tab){
  return ([e.name, e.path] as unknown[]).concat(tab.cols.map(c => c.get(e)))
    .filter(Boolean).join('\n').toLowerCase();
}

function renderTable(){
  if (noDev){ renderNoDevice(); return; }    /* P0-1: no device — the panel, not an empty grid */
  const tab = activeTab();
  const host = document.getElementById('dev-table')!;
  host.style.setProperty('--dvcols', gridOf(tab));
  const q = (document.getElementById('dev-filter') as HTMLInputElement).value.trim().toLowerCase();
  const frag = document.createDocumentFragment();
  frag.appendChild(headRow(tab));
  let shown = 0;
  rows.forEach(function(e, i){
    if (e.removed && tab.id !== 'changed') return;   /* tombstones live on CHANGED only */
    if (!tab.match(e)) return;
    if (q && !haystack(e, tab).includes(q)) return;
    shown++;
    const r = document.createElement('div');
    r.className = 'dvrow' + (i === selIdx ? ' sel' : '');
    r.setAttribute('data-i', String(i));
    r.setAttribute('title', rowTip(e));
    r.innerHTML = iconHTML(e) +
      tab.cols.map(c => cellHTML(c, e, false)).join('') +
      (tab.slack ? SLACK_CELL : '') +
      flagsHTML(e, tab);
    frag.appendChild(r);
    if (e.kind === 'process' && expanded.has(procKey(e))) frag.appendChild(expansionEl(e));
  });
  if (!shown){
    const n = document.createElement('div');
    n.className = 'dvempty';
    n.textContent = q ? 'no ' + tab.label + ' entry matches “' + q + '”'
                      : 'no ' + tab.label + ' entries in this catalog';
    frag.appendChild(n);
  }
  host.innerHTML = '';
  host.appendChild(frag);
  document.getElementById('dev-count')!.textContent = totalAll + ' entries · ' + shown + ' shown';
  syncTabs();
}

/* tab counts are of the catalog, not of the filter: they answer "what does this device
   have", and stay put while you type. The filtered number is the `N shown` readout.

   The scans are capped, so "what this device has" and "what we scanned" are two
   different numbers (review F3). A kind whose scan hit its cap shows the DEVICE
   total with a "+" and says so in the title — 606+ is honest where 300 was not. */
function syncTabs(){
  const host = document.getElementById('dev-tabs')!;
  if (!host.children.length) buildTabs();
  TABS.forEach(function(t, k){
    const b = host.children[k] as HTMLElement;
    let n = 0;
    rows.forEach(e => { if (!e.removed && t.match(e)) n++; });
    if (t.id === 'changed') rows.forEach(e => { if (e.removed) n++; });
    /* a kind tab (id === the catalog kind) can be told what the device really holds */
    const capped = cappedKinds.indexOf(t.id) >= 0;
    const total = kindTotals && kindTotals[t.id] != null ? kindTotals[t.id] : null;
    b.querySelector('.n')!.textContent = String(capped && total != null ? total + '+' : n);
    b.classList.toggle('on', t.id === tabId);
    b.classList.toggle('empty', n === 0);
    b.classList.toggle('capped', capped);
    b.setAttribute('aria-selected', t.id === tabId ? 'true' : 'false');
    b.tabIndex = t.id === tabId ? 0 : -1;   /* roving: the strip is one tab stop */
    b.title = t.label + ' · ' + n + ' entr' + (n === 1 ? 'y' : 'ies') +
      (capped && total != null
        ? ' scanned of ' + total + ' on the device (scan capped — the rest were not collected)'
        : '') +
      ' · columns: ' + t.cols.map(c => c.h).join(' · ');
  });
}

function buildTabs(){
  const host = document.getElementById('dev-tabs')!;
  host.innerHTML = '';
  TABS.forEach(function(t){
    const b = document.createElement('button');
    b.className = 'dvtab';
    b.setAttribute('role', 'tab');
    b.setAttribute('data-dtab', t.id);
    b.innerHTML = esc(t.label) + '<span class="n">0</span>';
    host.appendChild(b);
  });
}

let selSeq = 0;                /* selectEntry request guard — see the proposals await below */

/* ==================================================================
   RAIL DETAIL
================================================================== */
async function selectEntry(i: number){
  const mySel = ++selSeq;      /* the proposals fetch below awaits — a newer selection owns the rail */
  const e = rows[i];
  if (!e) return;
  selEntry = e;
  selIdx = i;
  $all('#dev-table .dvrow').forEach(r => r.classList.toggle('sel', +r.getAttribute('data-i')! === i));
  /* the head ellipsises on one line — title carries the rest (identity below is verbatim) */
  const nm = document.querySelector<HTMLElement>('#dev-insp .sym-name')!;
  nm.textContent = e.name;
  nm.title = e.name;
  /* services identify by their host process + the package behind it, not by a path */
  const idline = e.kind + (e.pid ? ' · pid ' + e.pid : '') + (e.host ? ' · ' + e.host : '') +
    (e.pkg ? ' · ' + e.pkg : '') + (e.path ? ' · ' + e.path : '');
  const idEl = document.getElementById('dev-id')!;
  idEl.textContent = idline;
  idEl.title = idline;
  /* IDENTITY is the full-fidelity surface for this view: values wrap rather than
     ellipsise, and the long ones copy on click (same grammar as the class-crumb FQCN). */
  const idn = document.getElementById('dev-identity')!;
  idn.innerHTML = '';
  Object.entries(e).forEach(function(kv){
    const k = kv[0], v = String(kv[1] ?? '');
    if (['annotations', 'name', 'kind'].includes(k)) return;
    if (k === 'size' && e.kind === 'partition') return;   /* symlink strlen, not a size — see rowTip */
    const cp = COPYKEYS.includes(k) && v;
    const r = document.createElement('div');
    r.className = 'xrow';
    r.innerHTML = '<span class="led b"></span><span class="xk">' + esc(k) + ':</span>' +
      '<span class="xv' + (cp ? ' cpy' : '') + '" title="' + esc(v) + (cp ? ' — click to copy' : '') + '"' +
      (cp ? ' data-copy="' + esc(v) + '"' : '') + '>' + wbr(v) + '</span>';
    idn.appendChild(r);
  });
  loadComponents(e);
  // annotations (+ proposal accept/reject)
  const an = document.getElementById('dev-annot')!;
  an.innerHTML = '';
  (e.annotations || []).forEach(function(a){
    const r = document.createElement('div');
    r.className = 'xrow';
    r.innerHTML = '<span class="led ' + (String(a.by).startsWith('agent') ? 'p' : 'g') + '"></span><span>' +
      esc(a.note) + ' <span class="dim">· ' + esc(a.by) + '</span></span>';
    an.appendChild(r);
  });
  /* a human note (audit decision 12): the op existed, the rail had no way to call it */
  if (!e.removed){
    const form = document.createElement('div');
    form.className = 'xrow annot-form';
    form.innerHTML = '<span class="led b"></span><input class="annot-in" placeholder="add a note (↵)" spellcheck="false"><button class="mbtn annot-go" data-tip="save the note on this entry (device.annotate)">NOTE</button>';
    const inp = form.querySelector<HTMLInputElement>('.annot-in')!;
    const go = form.querySelector<HTMLButtonElement>('.annot-go')!;
    const submit = async function(){
      const note = inp.value.trim();
      if (!note || !SERIAL) return;
      go.disabled = true;
      const r = await tryOp('device.annotate', { serial: SERIAL, kind: e.kind, name: e.name, note });
      go.disabled = false;
      if (r.error){ notice('note not saved — ' + r.error.message, { err: true }); return; }
      inp.value = '';
      await loadCatalog();
    };
    go.addEventListener('click', submit);
    inp.addEventListener('keydown', function(ev){ if (ev.key === 'Enter'){ ev.preventDefault(); submit(); } ev.stopPropagation(); });
    an.appendChild(form);
  }
  // pending catalog proposals for this entry (reviewable)
  const pr = await tryOp('agent.proposals', { kind: 'catalog' });
  if (mySel !== selSeq) return;   /* a newer selection already owns the rail */
  (pr.data || []).filter(p => p.name === e.name).forEach(function(p){
    const r = document.createElement('div');
    r.className = 'xrow';
    r.innerHTML = '<span class="led o"></span><span>' + esc(p.note) +
      ' <span class="dim">⏳ conf ' + p.confidence + '</span></span>' +
      '<span><button class="pok">✓</button><button class="pno">✗</button></span>';
    r.querySelector('.pok')!.addEventListener('click', async function(){
      await tryOp('agent.review', { id: p.id, action: 'accept' });
      await loadCatalog();
    });
    r.querySelector('.pno')!.addEventListener('click', async function(){
      await tryOp('agent.review', { id: p.id, action: 'reject' });
      await loadCatalog();
    });
    an.appendChild(r);
  });
  // actions: PULL (files) / ATTACH (processes)
  const pl = document.getElementById('dev-pull')!;
  pl.innerHTML = '';
  /* a tombstone is a record of something that is GONE — every action here talks to
     the live device, so it gets none of them, and says why instead of failing later */
  if (e.removed){
    const note = document.createElement('span');
    note.className = 'dim';
    note.textContent = 'removed since the last refresh — nothing to pull or attach';
    pl.appendChild(note);
    return;
  }
  /* services have no artifact of their own — apkPath belongs to the hosting package,
     so no PULL affordance for them (same for processes: ATTACH only). Partitions are
     block-device symlinks: PULL would stream a multi-GB raw image. ONE predicate
     (pullable) decides here, on the row tooltip, and on the double-click. */
  if (pullable(e)){
    const extKnown = /\.(apk|so|bin|img|dat|xml)$/.test(e.path!);
    const b = document.createElement('button');
    b.className = 'mbtn';
    b.textContent = 'PULL' + (extKnown && e.size ? ' (' + e.size + ')' : '');
    if (extKnown) b.setAttribute('data-tip', 'adb pull → open as JEB target');
    b.addEventListener('click', function(){ pullEntry(e, b); });
    pl.appendChild(b);
  }
  if (e.kind === 'process'){
    const b = document.createElement('button');
    b.className = 'mbtn';
    b.textContent = 'ATTACH';
    b.setAttribute('data-tip', 'frida attach (Runs view)');
    b.addEventListener('click', async function(){
      const r = await tryOpT('runs.attach', { device: SERIAL, pkg: e.name });   // the VIEWED target's session (fix-review r4 #5)
      b.textContent = r.error ? 'failed: ' + r.error.message : 'ATTACHED pid ' + r.data.pid;
    });
    pl.appendChild(b);
  }
}

/* ==================================================================
   COMPONENTS  (device.components — declared Android components of a package)
================================================================== */
const KINDLABEL: Record<string, string> = { service: 'services', receiver: 'receivers', provider: 'providers', activity: 'activities' };

/* How many rows of one kind render up front. GmsCore declares 701 activities;
   dropping all of them into a 264px rail buries the four services that matter.
   The rest are one click away — capped, never lost (same rule as IDENTITY). */
const COMP_PAGE = 40;

/* device.components is a live adb round-trip; a re-selected row must not re-pay it */
const compCache = new Map<string, Components>();
let compSeq = 0;

/* Which package a catalog row is "about": app/system-app rows ARE the package,
   service rows carry the package their host process was resolved to. Everything
   else (lib, process, partition, file) has no manifest, so no COMPONENTS. */
function entryPkg(e: CatalogEntry){
  if (e.kind === 'app' || e.kind === 'system-app') return e.name;
  return e.pkg || null;
}

async function loadComponents(e: CatalogEntry){
  const title = document.getElementById('dev-comptitle')!;
  const meta = document.getElementById('dev-compmeta')!;
  const host = document.getElementById('dev-comps')!;
  /* a tombstone's package is gone from the device — dumpsys would answer nothing,
     so the section clears and hides rather than showing the last row's components */
  const pkg = e.removed ? null : entryPkg(e);
  const seq = ++compSeq;
  host.innerHTML = '';
  meta.textContent = meta.title = '';        // never let a prior package's numbers linger
  title.hidden = host.hidden = !pkg;
  if (!pkg || !SERIAL) return;

  const key = SERIAL + '|' + pkg;
  if (compCache.has(key)){ renderComponents(compCache.get(key)!); return; }
  host.innerHTML = '<div class="dvcnote">loading…</div>';
  const r = await tryOp('device.components', { serial: SERIAL, pkg });
  if (seq !== compSeq) return;                       // a later row won the race
  if (r.error){
    host.innerHTML = '';
    host.appendChild(note('device.components failed: ' + r.error.message));
    return;
  }
  compCache.set(key, r.data);
  renderComponents(r.data);

  function renderComponents(d: Components){
    host.innerHTML = '';
    const counts: Record<string, number> = d.counts || {};
    meta.textContent = String(d.total || 0);
    meta.title = pkg + (d.versionName ? ' · v' + d.versionName : '') + '\n' +
      (Object.keys(counts).map(k => counts[k] + ' ' + KINDLABEL[k]).join(' · ') || 'no components');
    if (!d.found){
      host.appendChild(note(d.note || 'package not installed on this device'));
      return;
    }
    if (d.note) host.appendChild(note(d.note));
    if (!d.total){
      host.appendChild(note('package declares no components'));
      return;
    }
    Object.keys(counts).forEach(function(kind){
      const list = (d.components || []).filter(c => c.kind === kind);
      const h = document.createElement('div');
      h.className = 'dvck';
      h.innerHTML = esc(KINDLABEL[kind] || kind) + '<b>' + list.length + '</b>';
      host.appendChild(h);
      list.slice(0, COMP_PAGE).forEach(c => host.appendChild(compRow(c)));
      if (list.length > COMP_PAGE){
        const more = document.createElement('div');
        more.className = 'dvcmore';
        more.textContent = '+ ' + (list.length - COMP_PAGE) + ' more ' + (KINDLABEL[kind] || kind);
        more.addEventListener('click', function(){
          const after = more.nextSibling;
          list.slice(COMP_PAGE).forEach(c => host.insertBefore(compRow(c), after));
          more.remove();
        });
        host.appendChild(more);
      }
    });
  }
}

function note(text: string){
  const n = document.createElement('div');
  n.className = 'dvcnote';
  n.textContent = text;
  return n;
}

/* One component: the FQCN verbatim (wrapped at its dots, click-to-copy, full string
   in the title) over whatever attributes the dump actually carried. dumpsys prints
   no per-component process name, so no row claims one. */
function compRow(c: Component){
  const cls = String(c['class'] || '');
  const attrs = [];
  if (c.permission) attrs.push('perm ' + c.permission);
  if (c.authorities && c.authorities.length) attrs.push('authority ' + c.authorities.join(', '));
  if (c.fromFilterOnly) attrs.push('(intent-filter only — not in the declared list)');
  const tip = [cls, c.permission ? 'permission ' + c.permission : '',
    c.authorities && c.authorities.length ? 'authorities ' + c.authorities.join(', ') : '',
    c.actions && c.actions.length ? 'actions:\n  ' + c.actions.join('\n  ') : '',
    '— click: copy the class name · double-click: open in the code view'].filter(Boolean).join('\n');
  const r = document.createElement('div');
  r.className = 'xrow';
  r.innerHTML = '<span class="led b"></span><span class="dvcbody">' +
    '<span class="xv cpy" title="' + esc(tip) + '" data-copy="' + esc(cls) + '">' +
      wbr(cls) + '</span>' +
    (attrs.length ? '<span class="dvcattr">' + esc(attrs.join(' · ')) + '</span>' : '') +
    (c.actions && c.actions.length ? '<span class="dvcattr">' + c.actions.length + ' action' +
      (c.actions.length > 1 ? 's' : '') + '</span>' : '') +
    '</span>';
  /* the inspector idiom: double-click a component = open ITS CLASS in the code
     view — pulling the host apk and opening the target first when needed */
  r.addEventListener('dblclick', function(){ openComponent(cls); });
  return r;
}

/* Double-click on a component row: land on the component's class in the code
   view. The host artifact is the selected row's own path when pullable, else
   the hosting package's apkPath (service rows). Already-pulled copies open via
   target.open (dedupes into the live session); otherwise device.pull pulls and
   opens. The class is RESOLVED before any view moves: a framework/library-
   provided component isn't in the apk, and jumping anyway tore the device pane
   down for a silent nowhere (review). The pull guard covers the whole chain. */
async function openComponent(cls: string){
  const e = selEntry;
  if (!e || !cls) return;
  const sig = 'L' + cls.replace(/\./g, '/') + ';';   // $ inner classes keep the $
  const meta = document.querySelector('#devicewrap .pmeta');
  const hostPath = pullable(e) ? e.path : (e.apkPath || null);
  /* a row can have e.pulled with NO pullable path of its own — the guard keys
     on the pulled path then, never on null (review R9/F6: two such rows would
     otherwise block each other on a null key) */
  if (!hostPath && !e.pulled){ if (meta) meta.textContent = 'no pullable apk holds ' + cls; return; }
  const pullKey = (hostPath || e.pulled)!;
  if (pulling.has(pullKey)){ if (meta) meta.textContent = 'already pulling ' + (e.pkg || e.name) + '…'; return; }
  pulling.add(pullKey);
  try {
    let target: TargetInfo | null | undefined = null, note: string | null = null, err: string | null = null;
    if (meta) meta.textContent = 'opening ' + cls + '…';
    if (e.pulled){
      const r = await tryOp('target.open', { path: e.pulled, rekey: true });   // the user's gesture: see launcher.ts doOpen
      if (!r.error) target = r.data;
      else err = r.error.message;
      if (r.data && r.data.note) note = r.data.note;
    } else {
      if (meta) meta.textContent = 'pulling ' + (e.pkg || e.name) + '…';
      const r = await tryOp('device.pull', { serial: SERIAL, path: hostPath, rekey: true });   // its open is the user's gesture too
      if (!r.error){
        target = r.data.target;
        if (target && target.path) e.pulled = target.path;
        /* ONE precedence everywhere (round-23 F7): openError > warn > note */
        note = r.data.openError || r.data.warn || r.data.note || (target && target.note) || null;
        /* an openTarget that failed after the pull committed carries its reason
           here (round-19 F4: it used to surface as 'no pullable apk holds') */
        if (r.data.openError) err = r.data.openError;
      } else err = r.error.message;
    }
    /* E_BUSY is a NORMAL outcome now (live .jdb2, cross-project, bytes-differ)
       and its message carries the actionable hatch — the generic 'no pullable
       apk' line would bury it (review #5) */
    if (!target || !target.id){
      if (meta) meta.textContent = err || ('no pullable apk holds ' + cls);
      return;
    }
    if (S.hooks.targetReady && !await S.hooks.targetReady(target)){
      if (meta) meta.textContent = target.name + ' failed to analyze';
      return;
    }
    const rs = await tryOp('symbol.resolve', { symbol: sig, target: target.id });
    if (rs.error || !rs.data){
      /* the note stays on the line — it names the real story (a stale/wrong
         artifact) and must not be replaced by a confident wrong one (review R8) */
      if (meta) meta.textContent = (note ? note + ' · ' : '') + cls + ' — class not in this apk (framework-provided?)';
      return;
    }
    /* the note (skippedPull / stale-artifact warn) IS the status — render it,
       or the line stands on 'pulling …' for a pull that never ran (review F7) */
    if (meta) meta.textContent = note || ('opening ' + target.name + '…');
    /* targetOpened owns the strip + the landing jump — one awaited chain, no
       default-class race */
    if (S.hooks.targetOpened) await S.hooks.targetOpened(target, sig);
  } finally {
    pulling.delete(pullKey);
  }
}

const pulling = new Set<string>();   /* host paths with a pull+open chain in flight —
                                keyed on the ARTIFACT path so the rail button and
                                the double-clicks can't double the same pull */
async function pullEntry(e: CatalogEntry, btn: HTMLButtonElement | null){
  const hostPath = pullable(e) ? e.path : (e.apkPath || null);
  if (!hostPath || pulling.has(hostPath)) return;
  pulling.add(hostPath);
  const meta = document.querySelector('#devicewrap .pmeta');
  try {
    if (btn){ btn.textContent = 'pulling…'; btn.disabled = true; }
    else if (meta) meta.textContent = 'pulling ' + e.name + '…';
    const r = await tryOp('device.pull', { serial: SERIAL, path: hostPath, rekey: true });   // its open is the user's gesture too
    if (r.error){
      if (btn){ btn.textContent = 'failed: ' + r.error.message; btn.disabled = false; }
      else if (meta) meta.textContent = 'pull failed: ' + r.error.message;
      return;
    }
    const tname = r.data.target && r.data.target.name;
    /* opened:false — the pull landed, only the open failed: say that, with the
       reason on the tip AND on the meta line (round-20 F4: an unbounded server
       message in a nowrap tooltip clipped off the viewport — the pmeta has the
       width); openError outranks warn on the button-less path (round-20 F5);
       and the tips are cleared on the success sub-branch (round-21 N8: a
       fail-then-succeed button kept showing the old error) */
    if (btn){
      if (tname){
        btn.textContent = 'pulled → ' + tname;
        btn.removeAttribute('data-tip');
        btn.removeAttribute('data-tip-wrap');
      }
      else {
        btn.textContent = 'pulled (open failed)';
        btn.disabled = false;
        btn.setAttribute('data-tip', r.data.openError || 'open failed');
        btn.setAttribute('data-tip-wrap', '');
      }
      /* F8's unrestored-copies warn rides the button path too (round-21 N4:
         the primary affordance never saw it) — and the meta is ALWAYS
         (re)written, so a success clears the previous failure's text
         (round-22 F3); openError > warn > note (F5/F4) */
      if (meta)
        meta.textContent = r.data.openError || r.data.warn || r.data.note || '';
    }
    else if (meta) meta.textContent = r.data.openError || r.data.warn || r.data.note
        || ((r.data.target && r.data.target.deduped ? 'already open → ' : 'pulled + opened → ') + tname);
    e.pulled = r.data.target && r.data.target.path;
    renderTable();
    // the new chip appears via workspace.state — target strip listens to nothing;
    // reload the strip by a no-op state refresh through the app hook
    if (S.hooks.targetOpened) await S.hooks.targetOpened(r.data.target!);
  } finally {
    pulling.delete(hostPath);
  }
}

/* ==================================================================
   INIT
================================================================== */
export function initDevice(): void {
  if (wired) return;
  wired = true;

  const bar = document.querySelector('#devicewrap .vtoolbar');
  /* a layout-popped panel can drop the toolbar — one missing node must never
     decapitate boot's remaining init chain (review finding: uncaught here,
     every later init in boot() silently never ran) */
  if (bar){
    const metaSpan = bar.querySelector('span:not(.led):not(b)');
    if (metaSpan) metaSpan.className = 'dev-meta';
    const refreshBtn = bar.querySelector('button.mbtn');
    if (refreshBtn) refreshBtn.addEventListener('click', refreshCatalog);
  }

  /* P0-3: the selector. A change is a device switch — the same teardown a
     disconnect gets, then that device's catalog */
  const sel = document.getElementById('dev-select') as HTMLSelectElement | null;
  if (sel) sel.addEventListener('change', function(){
    const d = DEVS.find(x => x.serial === sel.value);
    if (!d || d.serial === SERIAL) return;
    switchTo(d, '');
    paintBar(d, DEVS, false);
    loadCatalog();
  });

  /* P0-2: the wrap's `hidden` flips in updateCenter on every surface swap — the
     exact moment to drop the poll timer (there is no view-closed hook to hang it
     on; chrome.js's tooltip refresh watches attributes the same way) */
  const wrap = document.getElementById('devicewrap');
  if (wrap) new MutationObserver(function(){ if (wrap.hidden) stopPoll(); })
    .observe(wrap, { attributes: true, attributeFilter: ['hidden'] });

  document.getElementById('dev-filter')!.addEventListener('input', renderTable);

  /* the tab strip and the rows are both rebuilt on every render — delegate, so no
     listener is attached (or leaked) per tab or per one of 1368 rows */
  buildTabs();
  function selectTab(id: string){
    tabId = id;
    clearExpansions();          /* a tab is a different row set — nothing to keep open */
    renderTable();
    document.getElementById('dev-table')!.scrollTop = 0;   // a new tab starts at its top
  }
  document.getElementById('dev-tabs')!.addEventListener('click', function(ev){
    const b = (ev.target as HTMLElement).closest('.dvtab');
    if (!b) return;
    selectTab(b.getAttribute('data-dtab')!);
  });
  /* the tablist keyboard pattern: ←/→/Home/End move AND select (roving tabindex
     above keeps the strip a single Tab stop) */
  document.getElementById('dev-tabs')!.addEventListener('keydown', function(ev){
    const keys = ['ArrowLeft', 'ArrowRight', 'Home', 'End'];
    if (keys.indexOf(ev.key) === -1) return;
    ev.preventDefault();
    const ids = TABS.map(t => t.id);
    let i = ids.indexOf(tabId);
    if (ev.key === 'ArrowRight') i = (i + 1) % ids.length;
    else if (ev.key === 'ArrowLeft') i = (i - 1 + ids.length) % ids.length;
    else if (ev.key === 'Home') i = 0;
    else i = ids.length - 1;
    selectTab(ids[i]);
    const b = document.querySelector<HTMLElement>('#dev-tabs .dvtab[data-dtab="' + ids[i] + '"]');
    if (b) b.focus();
  });
  document.getElementById('dev-table')!.addEventListener('click', function(ev){
    const r = (ev.target as HTMLElement).closest('.dvrow');
    if (!r) return;                       /* a click inside an open panel selects nothing */
    /* the expander is a control inside the row, not part of it: it must not also
       hijack the row's own click, which still selects into the rail */
    if ((ev.target as HTMLElement).closest('.dvc-exp')){ toggleExpand(+r.getAttribute('data-i')!); return; }
    selectEntry(+r.getAttribute('data-i')!);
  });
  /* the file-manager idiom: double-click a pullable row = pull it off the device
     AND open it as a JEB target (the rail PULL button stays for discoverability) */
  document.getElementById('dev-table')!.addEventListener('dblclick', function(ev){
    const r = (ev.target as HTMLElement).closest('.dvrow');
    if (!r || (ev.target as HTMLElement).closest('.dvc-exp')) return;
    const e = rows[+r.getAttribute('data-i')!];
    if (!pullable(e)) return;
    selectEntry(+r.getAttribute('data-i')!);   /* the rail shows what got pulled */
    pullEntry(e, null);
  });
  /* role=button on the caret means it answers Enter/Space like one */
  document.getElementById('dev-table')!.addEventListener('keydown', function(ev){
    if (ev.key !== 'Enter' && ev.key !== ' ') return;
    const x = (ev.target as HTMLElement).closest('.dvc-exp');
    if (!x) return;
    ev.preventDefault();
    const r = x.closest('.dvrow');
    if (r) toggleExpand(+r.getAttribute('data-i')!);
  });
  document.getElementById('dev-annotate')!.addEventListener('click', openAnnotateDialog);

  /* Identity/component value copy: the row carries the full string in data-copy (the
     rendered text is <wbr>-broken), a click writes it, acked by the outline flash the
     rest of the shell uses. Delegated — both sections re-render on every selection.
     The table is in the list for the empty panel's fix-it command (P0-1). */
  ['dev-identity', 'dev-comps', 'dev-table'].forEach(function(id){
    document.getElementById(id)!.addEventListener('click', function(ev){
      const v = (ev.target as HTMLElement).closest<HTMLElement>('.xv[data-copy]');
      if (!v) return;
      const s = v.getAttribute('data-copy')!;
      if (navigator.clipboard && navigator.clipboard.writeText)
        navigator.clipboard.writeText(s).catch(function(err){ console.warn('clipboard', err); });
      v.style.outline = '2px solid var(--blue)';
      setTimeout(function(){ v.style.outline = ''; }, 500);
    });
  });

  /* Escape on the central dispatcher, keyed on S.center — ⌃6 and the rail button
     never focus #devicewrap, so the old wrap listener never fired (F5). */
  pushPaneEscape('device');

  S.hooks.deviceOpened = async function(){
    startPoll();                /* P0-2: first, so a leave mid-scan can stop it */
    await loadDevices();
    await loadCatalog();
  };
}

/* QA 24: app-styled modal (same .wbmodal component as rename/tag/flow), not prompt() */
function openAnnotateDialog(){
  const modal = document.getElementById('devmodal')!;
  const input = document.getElementById('devm-input') as HTMLInputElement;
  const err = document.getElementById('devm-err')!;
  const ok = document.getElementById('devm-ok')!;
  input.value = '';                                            // the user's keywords, not another investigation's
  err.textContent = '';
  modal.hidden = false;
  input.focus();
  input.select();
  const esc = pushEscape(function(){ if (modal.hidden) return false; close(); }, ESC.modal);
  function close(){
    modal.hidden = true;
    ok.onclick = input.onkeydown = null;
    popEscape(esc);
  }
  ok.onclick = async function(){
    const goal = input.value.trim();
    if (!goal){ err.textContent = 'goal required'; return; }
    const r = await tryOp('device.spawn-mission', { serial: SERIAL, goal });
    if (r.error){ err.textContent = r.error.message; return; }
    close();
    const id = r.data && (r.data as { id?: string }).id;
    notice('keyword scan' + (id ? ' ' + id : '') + ' started — flagged entries arrive as proposals in AGENTS ⌃5', { onClick: function(){ selectCenter('agents'); } });
  };
  document.getElementById('devm-cancel')!.onclick = close;
  input.onkeydown = function(e){
    if (e.key === 'Enter'){ e.preventDefault(); (ok.onclick as () => void)(); }
  };
}
