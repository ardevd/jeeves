// ribbon.ts — the live status ribbon: every readout bound to a real source.
//
// What each item answers, and who tells it:
//   STATE      backend link      — READY after the boot ping, DOWN when an op fetch fails
//   DEV        device.list       — attached devices (boot + DEVICE view open)
//   HEAP       target.info.heap  — active target's heap text, displayed via heapDisplay
//                (GiB — an agent reading target.info over MCP sees the backend's SI
//                text instead), gauge over workspace.sysmem's JVM max
//   UNSAVED    workspace.state   — targets with dirty:true
//   agentchip  presence.whereis  — where the agent is, live over the presence channel
//   FOLLOW     agent.missions    — server follow state; the knob paints from the op RESULT
//
// The Σ host-memory readout and the Zulu clock were removed (2026-09-05) — see
// refreshHeapMax for why the sysmem op is still polled once at boot.
//
// The old ribbon was a mockup: DEV said 1 with two devices attached, UNSAVED said 3
// with no writer in the codebase, Σ and both gauges were hard-coded. Anything this
// module cannot source now reads "—" rather than a plausible number.
import { tryOp, onEvent } from '../api.js';
import { S, modalUp, escHtml as esc, tryOpT } from './hub.js';
import { setGaugeRatio, jumpToCode } from './chrome.js';
import type { Device, Mission, SysMem, Whereis, WorkspaceState } from '../types.js';

/** the presence channel's payload: a position (addr/member/kind) or a follow
 *  flip ({following}) — one loose shape, read defensively either way */
type PresenceEvent = Whereis & { kind?: string; following?: string | null };

/* ---------------- STATE: backend link ---------------- */
/* Only states we can actually source. There is no BUSY: no op ticket in the
   registry reports "a heavy op is running right now" (op.cancel takes a ticket
   but nothing lists live ones), and inventing one would be the same lie in a
   new colour. */
let stateNow: string | null = null;

export function setState(s: string, detail?: string): void {
  if (s === stateNow && !detail) return;
  stateNow = s;
  const v = document.getElementById('state-v');
  const led = document.getElementById('state-led');
  const item = document.getElementById('state-item');
  if (v) v.textContent = s;
  if (led) led.className = 'led ' + (s === 'READY' ? 'g' : s === 'DOWN' ? 'o' : 'b');
  if (item) item.setAttribute('data-tip', s === 'READY'
    ? 'Backend link · ops answering'
    : 'Backend link · ' + (detail || 'last op failed'));
}

/* ---------------- DEV: attached devices ---------------- */
/* ONE paint, two fetchers: refreshDev fetches (boot/hello), device.js's
   loadDevices passes its already-fetched list (no second device.list per open) */
export function paintDev(devs: Device[] | null, err: string | null): void {
  const n = document.getElementById('dev-n');
  const led = document.getElementById('dev-led-ribbon');
  const item = document.getElementById('dev-item');
  if (err){
    /* a course room has no devices by design (the refusal names course=host-only): the item goes
       away instead of glowing orange "adb unavailable" for the whole class */
    if (/course=host-only|class server/.test(err)){
      if (item) (item as HTMLElement).hidden = true;
      return;
    }
    if (n) n.textContent = '—';
    if (led) led.className = 'led o';
    if (item) item.setAttribute('data-tip', 'adb unavailable — ' + err);
    return;
  }
  const list = devs || [];
  if (n) n.textContent = String(list.length);
  if (led) led.className = 'led ' + (list.length ? 'b' : 'o');
  if (item) item.setAttribute('data-tip', list.length
    ? list.map(d => d.serial + (d.model ? ' · ' + d.model : '')).join('\n') + '\nDEVICE ⌃6'
    : 'no device attached — Device ⌃6');
}

export async function refreshDev(): Promise<void> {
  const r = await tryOp('device.list', { probe: false });   // the ribbon count never runs the su probe
  if (r.error){ paintDev(null, r.error.message); return; }
  paintDev((r.data && r.data.devices) || [], null);
}

/* ---------------- UNSAVED: dirty targets ---------------- */
/* The count is of TARGETS with unsaved edits, not of edits: the backend's unit of
   save is the target (target.save persists one session). markDirty in code.js is
   the client-side choke — it calls through here so an edit shows up before the
   next workspace.state round trip. */
export async function refreshUnsaved(state?: WorkspaceState): Promise<void> {
  const b = document.getElementById('unsaved-n');
  if (!b) return;
  let st = state;
  if (!st){
    const r = await tryOp('workspace.state', {});
    if (r.error){ b.textContent = '—'; return; }
    st = r.data;
  }
  const dirty = (st.targets || []).filter(t => t.dirty);
  b.textContent = String(dirty.length);
  const item = b.closest('.sitem');
  if (item){
    item.classList.toggle('clean', !dirty.length);
    item.setAttribute('data-tip', dirty.length
      ? 'unsaved edits in: ' + dirty.map(t => t.name).join(', ') + ' — ⌘S saves the active target'
      : 'no unsaved edits');
    const led = item.querySelector('.led');
    if (led) led.className = 'led ' + (dirty.length ? 'o' : 'g');
  }
}

/* ---------------- HEAP gauge ---------------- */
/* The HEAP text was already live (app.js renderRibbon writes target.info.heap);
   the gauge beside it was data-fill="4" forever. Same source, one denominator:
   the JVM's own maxMemory (WORKBENCH_XMX is a launch flag the browser can't see —
   workspace.sysmem reports what it actually became). No target heap → empty. */
let heapMaxBytes = 0;
let lastHeapText: string | null | undefined = null;

const HEAP_UNITS: Record<string, number> = { k: 1e3, m: 1e6, g: 1e9, t: 1e12 };

/* "3.2G" / "812M" / "1.4 GiB" → bytes; unparseable → NaN (gauge empties) */
function parseHeap(text: string | null | undefined){
  const m = /^\s*(\d+(?:\.\d+)?)\s*([kmgt])?i?b?\s*$/i.exec(String(text || ''));
  if (!m) return NaN;
  const n = parseFloat(m[1]);
  if (!Number.isFinite(n)) return NaN;
  return n * (m[2] ? HEAP_UNITS[m[2].toLowerCase()] : 1);
}

/* one unit for the whole HEAP item: the backend's heap text is SI ("1.1g"),
   the gauge max is GiB — readout, tip numerator, and % must share one
   divisor or the tip contradicts the readout 6px away (opus r3 R4) */
export function heapDisplay(text: string | undefined): string {
  const b = parseHeap(text);
  if (Number.isFinite(b)) return gb(b);
  return String(text || '—').toUpperCase();
}

export function setHeapGauge(heapText: string | null | undefined): void {
  const g = document.getElementById('heap-gauge');
  if (!g) return;
  lastHeapText = heapText;
  const used = parseHeap(heapText);
  const item = g.closest('.sitem');
  if (!heapMaxBytes || !Number.isFinite(used)){
    setGaugeRatio(g, 0);
    if (item) item.setAttribute('data-tip', heapText
      ? 'workbench heap ' + heapText + ' — max not known yet'
      : 'no heap reading — no target open');
    return;
  }
  setGaugeRatio(g, used / heapMaxBytes);
  /* the reading arrives on the active target's info, but it is the workbench
     JVM's heap — every open target reports the same number, so say so.
     heapText is the backend's SI text (1e9) — pass it through the same GiB
     unit as the max, or the sentence mixes two units and contradicts the %
     (opus r2 F10) */
  if (item) item.setAttribute('data-tip', 'workbench JVM heap ' + gb(used) + ' of ' +
    gb(heapMaxBytes) + ' max (' + Math.round(100 * used / heapMaxBytes) +
    '%) — reported by the active target');
}

/* ---------------- HEAP gauge denominator ---------------- */
/* The Σ host-memory readout was removed (2026-09-05): on macOS the MXBean's
   "free" excludes cached/inactive pages, so the gauge read permanently hot and
   told the user nothing. What survives is the one thing the HEAP gauge still
   needs from that op — the JVM's maxMemory, invisible to the browser otherwise.
   Fixed at launch (-Xmx) → fetched once at boot, no polling. The backend op
   itself stays (MCP_SPEC documents it; headless agents can still read it). */
function gb(bytes: number){
  const g = bytes / 1073741824;   /* GiB — 1e9 printed the JVM's 16 GiB max as '17.2G' */
  return (g >= 100 ? Math.round(g) : g.toFixed(1)) + 'G';
}

let heapMaxInFlight = false;
let heapMaxStale = false;
export async function refreshHeapMax(): Promise<void> {
  /* Once known, never fetch again — except that a hello zeroes heapMaxBytes
     (a reconnect can mean a NEW JVM with a different -Xmx). If a fetch is
     already in flight when that happens, it would latch the OLD JVM's
     answer; the stale flag makes it loop and refetch instead (r4 F9). */
  if (heapMaxInFlight){ heapMaxStale = true; return; }
  if (heapMaxBytes && !heapMaxStale) return;
  heapMaxInFlight = true;
  try {
    do {
      heapMaxStale = false;
      const r = await tryOp('workspace.sysmem', {});
      const d: SysMem = r.data || {};
      if (d.heapMaxBytes && d.heapMaxBytes !== heapMaxBytes){
        /* this fetch races the first renderRibbon — whoever lands second has to
           repaint, or a page that painted HEAP before the max answered keeps an
           empty gauge until the next target switch */
        heapMaxBytes = d.heapMaxBytes;
        if (lastHeapText != null) setHeapGauge(lastHeapText);
      }
    } while (heapMaxStale);
  } finally { heapMaxInFlight = false; }
}

/* ---------------- agentchip: where the agent is ---------------- */
const STALE_MS = 60_000;      // dim past a minute
const GONE_MS  = 300_000;     // hide past five

let lastWhereis: Whereis | null = null;

function paintAgentChip(w: Whereis | null | undefined){
  const item = document.getElementById('agentchip-item');
  const chip = document.getElementById('agentchip');
  if (!item || !chip) return;
  const age = w && typeof w.ageMs === 'number' ? w.ageMs : null;
  if (!w || !w.present || !w.addr || (age !== null && age > GONE_MS)){
    item.hidden = true;                       // no agent → no chip, no LED
    chip.removeAttribute('data-addr');
    chip.removeAttribute('data-tip');         /* a hidden chip must not keep selling the departed agent's location (opus r4 F5) */
    lastWhereis = null;                       /* a hidden chip must not resurrect on the next 15s repaint */
    return;
  }
  /* age is anchored to the LOCAL clock at receipt: the server's ts is the server's
     wall clock, and the dim/hide thresholds must not turn on clock skew */
  lastWhereis = { ...w, localTs: Date.now() - (age || 0) };
  const label = w.memberName || w.name || w.addr;
  item.hidden = false;
  chip.classList.toggle('stale', age !== null && age > STALE_MS);
  chip.innerHTML = '<span class="led p"></span>🤖 at ' + esc(label);
  chip.setAttribute('data-addr', w.member || w.addr);
  chip.setAttribute('data-tip', 'Agent is viewing ' + (w.member || w.addr) +
    ' — click to jump' + (age !== null ? ' · ' + ago(age) : ''));
}

function ago(ms: number){
  if (ms < 5_000) return 'just now';
  if (ms < 90_000) return Math.round(ms / 1000) + 's ago';
  return Math.floor(ms / 60000) + 'm ago';   /* floor: 90s is '1m', not '2m' */
}

export async function refreshAgentChip(): Promise<void> {
  const r = await tryOp('presence.whereis', { who: 'agent' });
  /* a CODED error (no project, bad param) means the pin is wrong — clear it
     (r1 #9). A TRANSPORT blip (server mid-restart → E_HTTP) keeps the pin: the
     15s poll repaints (r2b #6). NB the OpError wrap: the code lives at
     payload.error.code — a bare r.error.code is ALWAYS undefined (the
     map.js:779 trap; r3 #1 caught this arm dead) */
  if (r.error){
    const c = r.error.payload && r.error.payload.error && r.error.payload.error.code;
    if (c && c !== 'E_HTTP') paintAgentChip(null);
    return;
  }
  paintAgentChip(r.data);
}

/* ---------------- FOLLOW ---------------- */
/* Server state (AgentManager.follow), so the knob is painted from agent.missions
   at boot and from each op's RESULT after — never from the click. */
let followedAgent: string | null = null;
let followInFlight = false;   /* one follow navigation at a time — presence bursts coalesce */
let pendingFollow: PresenceEvent | null = null;     /* the NEWEST event stashed while a jump is in flight (F4) */

/* FOLLOW, delivered WITHOUT hostility: only when the code view is already the
   visible surface and the user isn't typing — a followed agent's event must
   never tear down another view or steal focus mid-keystroke. The gates live
   INSIDE the dispatch so a stashed replay re-samples them (a modal that opened
   while the previous jump was in flight must win). A burst keeps only the
   newest event: intermediate positions are dead on arrival, the LAST one is
   the agent's whereabouts — dropping it showed a stale location that never
   self-corrected (review F4). */
function followJump(data: PresenceEvent){
  const tag = document.activeElement && document.activeElement.tagName;
  const typing = tag === 'INPUT' || tag === 'TEXTAREA';
  /* a modal (tag/launcher/keyhelp/…) or the ⌘K overlay leaves S.center 'code'
     with code technically visible — jumping under it steals the modal's
     focus and desyncs what it points at (review F2). ONE shared predicate. */
  const codeUp = S.center === 'code';
  if (!codeUp || typing || modalUp()) return;
  /* a PROGRAMMATIC jump owns the code view right now (component dblclick, map
     node, launcher) — stash the newest position and let jumpDone replay it,
     never interleave two jumpToCodes (review R13) */
  if (S.jumpInFlight){ pendingFollow = data; return; }
  if (followInFlight){ pendingFollow = data; return; }
  /* NO resolve gate here: an await between the gates and the jump is a TOCTOU
     window (a modal opened mid-resolve still got hijacked), and the round-trip
     per presence event is waste — a dead pin just no-ops. */
  followInFlight = true;
  jumpToCode(null, data.member || data.addr)
    .catch(function(){}).finally(function(){
      followInFlight = false;
      if (pendingFollow){
        const d = pendingFollow; pendingFollow = null;
        /* the follow may have ENDED or moved to another agent while the jump
           flew — a stashed event is only replayable if its actor is still the
           followed one, else an unfollow lands one last navigation (review) */
        if (followedAgent && String(d.actor || '').replace(/^llm:/, '') === followedAgent)
          followJump(d);
      }
    });
}

function paintFollow(agentId: string | null | undefined, missions: Mission[] | null){
  const sw = document.getElementById('follow-sw');
  if (!sw) return;
  /* the follow ENDED or moved: any stashed burst event belongs to the old
     follow and must die with it (the replay re-validates too — belt and
     suspenders for the unfollow-during-flight window) */
  if ((agentId || null) !== followedAgent) pendingFollow = null;
  followedAgent = agentId || null;
  sw.classList.toggle('on', !!followedAgent);
  /* the off-state tip must know if anything is FOLLOWABLE (running/paused),
     not merely whether missions exist — 'no missions running' was unreachable
     while done/stopped missions filled the list. missions===null means UNKNOWN
     (a refetch hasn't landed): paint the neutral tip, never a false claim
     that no missions are running (review F9) */
  const live = (missions || []).filter(m => m.state === 'running' || m.state === 'paused').length;
  sw.setAttribute('data-tip', followedAgent
    ? 'Following ' + followedAgent + ' — the code view jumps with it. Click to stop'
    : (missions == null ? 'Follow agent'
      : (live ? 'Follow agent — the code view jumps to where an agent is working'
              : 'Follow agent — no missions running')));
}

async function loadFollow(){
  const r = await tryOp('agent.missions', {});
  if (r.error) return;
  const ms = r.data || [];
  const f = ms.find(m => m.following);
  paintFollow(f ? f.agentId : null, ms);
}

/* Which mission to follow when the switch is turned on: the one whose agent is
   present (the chip's actor), else the newest LIVE mission. A done/stopped
   mission moves nothing — with no live mission the knob stays off and says why. */
async function pickMission(): Promise<Mission | null> {
  const r = await tryOp('agent.missions', {});
  if (r.error) return null;
  const ms = r.data || [];
  const live = ms.filter(m => m.state === 'running' || m.state === 'paused');   /* allowlist: blocked/queued never emit presence */
  if (!live.length) return null;
  const actor = lastWhereis && lastWhereis.actor
    ? String(lastWhereis.actor).replace(/^llm:/, '') : null;
  const byActor = actor && live.find(m => m.agentId === actor);
  return byActor || live[live.length - 1];
}

export async function toggleFollow(): Promise<void> {      /* also the palette's AGENT command */
  const sw = document.getElementById('follow-sw');
  if (!sw) return;
  if (followedAgent){
    const r = await tryOp('agent.unfollow', {});
    if (r.error){ flash(sw); return; }
    paintFollow(null, null);
    await loadFollow();
    return;
  }
  const m = await pickMission();
  if (!m){ paintFollow(null, []); flash(sw); return; }
  const r = await tryOp('agent.follow', { id: m.id });
  if (r.error){ flash(sw); return; }
  paintFollow((r.data && r.data.following) || m.agentId, [m]);
}

function flash(el: HTMLElement){
  el.style.outline = '1px solid var(--verm)';
  setTimeout(function(){ el.style.outline = ''; }, 500);
}

/* ---------------- boot ---------------- */
export function initRibbon(): void {
  const chip = document.getElementById('agentchip');
  if (chip){
    /* no target argument: presence is project-scoped and the agent works in the
       active target — jumpToCode(null, sym) loads the symbol without a chip switch.
       Gate on resolvability: a pin can outlive its symbol (rename-back) and a click
       that silently does nothing is a lie (review F4). */
    const jump = async function(){
      const addr = chip.getAttribute('data-addr');
      if (!addr) return;
      const rr = await tryOpT('symbol.resolve', { symbol: addr });   // the VIEWED target — the jump lands there (fix-review r3 #5)
      if (rr.error || !rr.data){
        chip.style.outline = '1px solid var(--verm)';
        chip.setAttribute('data-tip', 'pin target no longer resolves — the symbol moved or was renamed');
        setTimeout(function(){ chip.style.outline = ''; refreshAgentChip(); }, 1500);
        return;
      }
      jumpToCode(null, addr).catch(function(e){ console.warn('presence jump', e); });
    };
    chip.addEventListener('click', jump);
    chip.addEventListener('keydown', function(e){
      if (e.key === 'Enter' || e.key === ' '){ e.preventDefault(); jump(); }
    });
  }
  const sw = document.getElementById('follow-sw');
  if (sw){
    sw.addEventListener('click', toggleFollow);
    sw.addEventListener('keydown', function(e){
      if (e.key === 'Enter' || e.key === ' '){ e.preventDefault(); toggleFollow(); }
    });
  }
  /* a programmatic jump (component dblclick, map node, launcher) just landed —
     drain the follow position stashed behind its jumpInFlight gate, through the
     same re-validated replay path as an in-flight burst (R13) */
  S.hooks.jumpDone = function(){
    if (!pendingFollow) return;
    const d = pendingFollow; pendingFollow = null;
    if (followedAgent && String(d.actor || '').replace(/^llm:/, '') === followedAgent)
      followJump(d);
  };

  refreshHeapMax();
  /* DEV paints on the SSE hello (api.js opens the stream at boot) — calling
     refreshDev() here too doubles a ~490ms adb scan per page load (review F10) */
  refreshAgentChip();
  loadFollow();
  /* a stale pin has to DIM without any new event arriving — re-paint the age */
  setInterval(function(){
    if (lastWhereis && lastWhereis.localTs)
      paintAgentChip({ ...lastWhereis, ageMs: Date.now() - lastWhereis.localTs });
  }, 15_000);

  /* hello handling (r4 F1): one hello per SSE connection, and EventSource
     reconnects ~3s — so a 5s LEADING-edge throttle alone DROPS a reconnect
     hello landing inside the window (the only copy with current state), and
     nothing replays it. Lead immediately when idle; inside the window,
     schedule a trailing flush so the last hello of a burst always replays. */
  let lastHello = 0;
  let helloT = 0;
  function doHello(){
    lastHello = Date.now();
    refreshDev();
    refreshAgentChip();
    loadFollow();
    /* a hello can mean a NEW JVM (backend restarted with a different -Xmx)
       — drop the latched max and mark it stale so an in-flight fetch loops
       instead of latching the old JVM's answer (r4 F9) */
    heapMaxBytes = 0;
    heapMaxStale = true;
    refreshHeapMax();
  }
  onEvent(async function(channel, data){
    if (channel === 'presence'){
      /* the channel carries two shapes: a position ({type, addr}) and a follow
         flip ({following}) — AgentManager publishes the latter so a second
         browser repaints its switch */
      const d = data as PresenceEvent;
      if (d && d.addr){
        if (d.kind === 'human') return;                 // our own ping echoing back
        paintAgentChip({ present: true, actor: d.actor, addr: d.addr,
          member: d.member, name: d.name, memberName: d.memberName,
          ts: d.ts, ageMs: 0 });
        if (followedAgent && String(d.actor || '').replace(/^llm:/, '') === followedAgent)
          followJump(d);
      } else if (d && 'following' in d){
        if (d.following) paintFollow(d.following, null);
        else {
          paintFollow(null, null);   /* paint OFF first — a failed loadFollow must not leave the switch on */
          loadFollow();
        }
      }
      return;
    }
    /* the live stream's own state (api.ts): a stream the server ended (a course room's access
       closing, the class edge's denial, an auth outage) is re-created with backoff — until then
       the page gets no progress, no presence, no proposals, and the STATE item must say so
       instead of a calm READY (persona pass 2026-10-09 C2) */
    if (channel === 'connection'){
      const st = data && typeof data === 'object' ? String((data as { state?: unknown }).state || '') : '';
      if (st === 'reconnecting' || st === 'retrying') setState('LIVE PAUSED', 'live updates paused — reconnecting to the backend' + (st === 'reconnecting' ? ' (the stream was closed; retrying with backoff)' : ''));
      else if (st === 'open' && stateNow === 'LIVE PAUSED') setState('READY');
      return;
    }
    /* follow state changes only via agent.follow/unfollow (the switch paints from
       those results) or a second browser (the presence channel's flip shape) —
       activity events can't change it, so don't refetch per event */
    if (channel === 'hello'){
      const wait = 5000 - (Date.now() - lastHello);
      if (wait > 0){          /* inside the window: flush the burst's LAST hello */
        if (!helloT) helloT = setTimeout(function(){ helloT = 0; doHello(); }, wait);
        return;
      }
      doHello();
    }
  });
}
