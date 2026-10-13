// course.ts — the COURSE ribbon item and its card (classroom integration, WORKBENCH_COURSE=1).
//
// A standalone workbench never sees it: course.status answers {state: "off"} (or "unknown op" on a
// server that predates the feature) and the ribbon item stays hidden. A course room's page knows
// its room before any op answers (window.__WB_COURSE_ROOM, injected by the server into the served
// page): the item is shown from the first paint, because the class edge answers a locked or
// outage-bound student ITSELF — a 404 or 503 page with no JSON — and the room's own words never
// reach the page then (persona pass 2026-10-09 C1). So the item classifies by HTTP status, not by
// prose: 401/403/404 after the page lived = NO ACCESS; 5xx or no answer = NO ANSWER, retried every
// 5 s. The room's own reasons DO reach the page through the stream's `closed` event (while the
// session still validates) and in the instructor's room.
//
// The item reads the health contract — THREE verdicts apart (CourseRuntime.status):
//   access    enabled | disabled | fenced | unknown | starting — closed access is said with its reason
//   state     the CODE verdict (ready | loading | degraded | target-mismatch | unavailable): identity,
//             engine, the course target's bytes, the capture of edits — never "ok" because the web
//             process answered; the item's first LED
//   model     the AGENT route (ready | blocked | degraded | unavailable | unconfigured): the class
//             gateway's scheduler and route, the capture store a strict request must pass — a
//             fixture or an unqualified route is never shown as a verified real model; the second LED
// and the card gives the student the classroom actions — every one of them a HANDOFF to the
// classroom's own page, as links that work even while the room refuses everything else:
//   SHARE      the finding's fields (type, claim, details, original symbol, target bytes, project
//              revision) composed here; TO FINDINGS PAGE opens the classroom's Findings page (the
//              small fields in the URL fragment for a page that reads them) and puts the Details on
//              the clipboard — the student publishes there, under their own account. The room never posts.
//   ASK        the typed words + one context line to the clipboard, the Messages page opened
//   READINESS  one link to the Today page (I'm ready · I need help · Not ready yet live there)
// Nothing is ever claimed sent from here. Server strings land via textContent only — never innerHTML.
import { tryOp, events } from '../api.js';
import { S, ESC, pushEscape, popEscape, withTarget } from './hub.js';
import type { CourseStatus, CourseShareResult, SseData } from '../types.js';

const POLL_READY_MS = 60_000;   // the verdict probes four loopback services; a minute is plenty for a healthy room
const POLL_WAIT_MS = 5_000;     // while access is closed, the answer is missing or the room is not ready: a quick repaint when it opens

let status: CourseStatus | null = null;
let closed: { kind: 'noaccess' | 'noanswer'; message: string; status: number } | null = null;   // the room refused or did not answer course.status itself
let everOk = false;             // a status was seen once: a later 404 is an access answer, not "no room here"
let pollT = 0;
let escHandle: ReturnType<typeof pushEscape> | null = null;
let previewed: { requestId: string; key: string; copy: string; browser: string } | null = null;   // the last PREVIEW: the handoff carries exactly it while the form is unchanged
let lastFields: Record<string, string> | null = null;

const $ = (id: string) => document.getElementById(id);
const put = (el: Element | null, text: unknown) => { if (el) el.textContent = text == null ? '' : String(text); };
const roomFromPage = (): string => (typeof window !== 'undefined' && window.__WB_COURSE_ROOM) || '';

/** boot: ask once; show the item only in a room (the page says so before the first answer) */
export async function initCourse(): Promise<void> {
  const item = $('course-item');
  if (!item) return;
  item.addEventListener('click', openCourse);
  item.addEventListener('keydown', e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); openCourse(); } });
  ($('crs-close') as HTMLButtonElement | null)?.addEventListener('click', closeCourse);
  ($('crs-preview') as HTMLButtonElement | null)?.addEventListener('click', () => { void share(true); });
  ($('crs-publish') as HTMLAnchorElement | null)?.addEventListener('click', e => { void publishClick(e); });
  ($('crs-send') as HTMLAnchorElement | null)?.addEventListener('click', e => { void helpClick(e); });
  ($('crs-copy') as HTMLButtonElement | null)?.addEventListener('click', () => { void copyFallback(); });
  for (const id of ['crs-kind', 'crs-title-in', 'crs-symbol', 'crs-body'])
    $(id)?.addEventListener('input', () => { previewed = null; lastFields = null; paintFields(null); });
  if (roomFromPage()) { item.hidden = false; put($('course-room'), roomFromPage()); }
  events(onEvent);
  await refreshCourse();
  schedule();
}

function schedule(): void {
  if (pollT) clearTimeout(pollT);
  const healthy = !closed && status && status.state !== 'off' && status.access?.state === 'enabled' && status.state === 'ready';
  const inRoom = !!roomFromPage() || !!closed || (status != null && status.state !== 'off');
  if (!inRoom) return;
  pollT = window.setTimeout(() => { void refreshCourse().then(schedule); }, healthy ? POLL_READY_MS : POLL_WAIT_MS);
}

/** the stream's last word: the room closed this page's access (the reason reaches us this way while
 *  the session still validates); and the stream coming back */
function onEvent(name: string, data: SseData): void {
  if (name === 'closed') {
    const reason = data && typeof data === 'object' ? String((data as { reason?: unknown }).reason || '') : '';
    closed = { kind: 'noaccess', message: reason || 'the room closed this page\'s access', status: 403 };
    paintClosed();
    if (!$('coursemodal')?.hidden) paintCard();
    schedule();
  }
}

/** course.status → the ribbon item (and the card's status block when it is up) */
export async function refreshCourse(): Promise<void> {
  const r = await tryOp('course.status', {});
  const item = $('course-item');
  if (r.error) {
    const st = r.error.status;
    const msg = r.error.payload?.error?.message || r.error.message || '';
    if (!everOk && !roomFromPage()) {
      /* a standalone server that predates the feature ("unknown op"), or a first load that failed on a
         page that is not a room: nothing to show */
      if (st === 0 && /unknown op/.test(msg)) { status = null; if (item) item.hidden = true; return; }
    }
    if (st === 401 || st === 403 || st === 404) closed = { kind: 'noaccess', message: msg, status: st };
    else closed = { kind: 'noanswer', message: msg, status: st };
    if (item) item.hidden = false;
    paintClosed();
    if (!$('coursemodal')?.hidden) paintCard();
    return;
  }
  if (!r.data || r.data.state === 'off') { status = r.data || null; if (item) item.hidden = true; return; }
  status = r.data;
  everOk = true;
  closed = null;
  if (item) item.hidden = false;
  paintItem(status);
  paintLinks(status);
  if (!$('coursemodal')?.hidden) paintCard();
}

/* the shell's colours: green live, blue working, orange caution (degraded — the room works with a
   gap named), vermillion error (target-mismatch: no comparison run; unavailable: nobody can be told
   apart; access closed) */
function ledClass(state: string): string {
  return state === 'ready' ? 'led g' : state === 'loading' || state === 'starting' ? 'led b' : state === 'degraded' || state === 'unconfigured' ? 'led o' : 'led v';
}

function accessLine(st: CourseStatus | null): string {
  const a = st?.access;
  if (!a) return '';
  if (a.realm === 'admin') return 'instructor preview — open for you · students: ' + (a.policyEnabled ? 'OPEN' : 'LOCKED') + (a.policyRevision != null ? ' (release rev ' + a.policyRevision + ')' : '');
  return a.state === 'enabled' ? 'access open' : 'ACCESS ' + a.state.toUpperCase() + (a.reason ? ' — ' + a.reason : '');
}

function paintClosed(): void {
  const c = closed!;
  const led = $('course-led'), led2 = $('course-led2'), item = $('course-item');
  const room = status?.config?.room || roomFromPage() || 'room';
  put($('course-room'), room);
  if (led) led.className = 'led v';
  if (led2) led2.className = 'led v';
  put($('course-v'), c.kind === 'noaccess' ? 'NO ACCESS' : 'NO ANSWER');
  put($('course-v2'), '');
  if (item) item.setAttribute('data-tip', 'Course room ' + room + '\n' + closedText(c) + '\nclick for the classroom links');
}

function closedText(c: { kind: 'noaccess' | 'noanswer'; message: string; status: number }): string {
  if (c.kind === 'noaccess')
    return 'The class server stopped letting this page into the room — the instructor closed Jeeves, or your classroom session ended. Your names and comments are kept. Your course home (Today) says which.'
      + (c.message && !/^HTTP \d+$/.test(c.message) ? '\n' + c.message : '');
  return 'The class server is not answering (' + (c.message || 'no answer') + '). Agent runs stop after 5 s without it; your work is kept. Retrying every 5 s.';
}

function paintItem(st: CourseStatus): void {
  const led = $('course-led'), led2 = $('course-led2'), item = $('course-item');
  const accessClosed = !!st.access && st.access.state !== 'enabled';
  put($('course-room'), st.config?.room || roomFromPage() || 'room');
  if (accessClosed) {
    if (led) led.className = 'led v';
    if (led2) led2.className = 'led v';
    put($('course-v'), 'ACCESS ' + String(st.access?.state).toUpperCase());
    put($('course-v2'), '');
  } else {
    if (led) led.className = ledClass(st.state);
    put($('course-v'), 'CODE ' + st.state.toUpperCase());
    const ms = st.model?.state || 'unknown';
    if (led2) led2.className = ledClass(ms === 'unavailable' || ms === 'blocked' ? 'unavailable' : ms);
    put($('course-v2'), 'AGENT ' + ms.toUpperCase());
  }
  if (item) item.setAttribute('data-tip', 'Course room ' + (st.config?.room || '') + ' · activity ' + (st.activity?.id || '—') + (st.activity?.stale ? ' (stale)' : '') + '\n'
    + accessLine(st) + '\n'
    + 'CODE ' + st.state + ' — ' + (st.summary || '') + (st.fixCode ? ' (code: ' + st.fixCode + ')' : '')
    + (st.model ? '\nAGENT ' + st.model.state + ' — ' + (st.model.summary || '') : '')
    + '\nclick: share a finding · ask the instructor · readiness');
}

/** the classroom links on the card follow the status (so they work when the room later refuses) */
function paintLinks(st: CourseStatus): void {
  const h = st.handoffs || {};
  const pub = $('crs-publish') as HTMLAnchorElement | null;
  if (pub && !previewed) pub.href = h.findings || pub.href || '#';
  const send = $('crs-send') as HTMLAnchorElement | null;
  if (send) send.href = h.help || send.href || '#';
  const ready = $('crs-ready-link') as HTMLAnchorElement | null;
  if (ready) ready.href = h.ready || ready.href || '#';
  const admin = st.access?.realm === 'admin';
  const sec = $('crs-ready-sec'), row = $('crs-ready-row');
  if (sec) sec.hidden = admin;   // the instructor's room: readiness is the students'
  if (row) row.hidden = admin;
}

function paintCard(): void {
  const st = status;
  const act = st?.activity;
  const room = st?.config?.room || roomFromPage() || '—';
  put($('crs-title'), 'COURSE · ' + room + ' · activity ' + (act?.id || '—') + (act?.revision != null ? ' rev ' + act.revision : '') + (act?.stale ? ' (stale)' : ''));
  const led = $('crs-led');
  if (closed) {
    if (led) led.className = 'led v';
    put($('crs-state'), closed.kind === 'noaccess' ? 'NO ACCESS' : 'NO ANSWER');
    put($('crs-summary'), closedText(closed));
    put($('crs-fix'), 'the classroom pages below still open; the instructor releases Jeeves access from the classroom');
    put($('crs-deps'), '');
    put($('crs-sym'), 'the classroom room this page belongs to — its access is closed or unconfirmed right now; the classroom links below still work');
    return;
  }
  if (!st) return;
  const accessClosed = !!st.access && st.access.state !== 'enabled';
  if (led) led.className = accessClosed ? 'led v' : ledClass(st.state);
  put($('crs-state'), accessClosed ? 'ACCESS ' + String(st.access?.state).toUpperCase() : 'CODE ' + st.state.toUpperCase() + ' · AGENT ' + String(st.model?.state || '—').toUpperCase());
  put($('crs-summary'), accessClosed ? (st.access?.reason || '') : (st.summary || ''));
  /* the fix is the operator's; the student quotes the code to the instructor (the operator's command is in the tooltip) */
  const fixEl = $('crs-fix');
  put(fixEl, st.fixCode ? 'tell the instructor — code: ' + st.fixCode : '');
  if (fixEl) (fixEl as HTMLElement).title = st.fix ? (st.fixAudience ? st.fixAudience + ': ' : '') + st.fix : '';
  const deps = st.dependencies || {};
  const cap = st.capture || {};
  const tgt = st.target || {};
  const parts: string[] = [];
  parts.push(accessLine(st));
  if (st.model) parts.push('agent route ' + st.model.state + (st.model.summary ? ' — ' + st.model.summary : '') + (st.model.verified ? '' : ' · not verified from here'));
  if (act) parts.push('activity ' + (act.id || '—') + (act.revision != null ? ' rev ' + act.revision : '') + ' (' + (act.source || '?') + (act.stale ? ', stale' : '') + ')');
  const depParts: string[] = [];
  for (const k of Object.keys(deps)) depParts.push(k + ' ' + deps[k]);
  parts.push(depParts.join(' · '));
  const home = cap.homeDurable ? 'durable' : (cap.homeDurableWhy && /checked at/.test(String(cap.homeDurableWhy)) ? 'not yet checked' : cap.homeDurableWhy && /declared/.test(String(cap.homeDurableWhy)) ? 'declared, fsync FAILS' : 'not declared durable');
  parts.push('capture ' + (cap.mode || '—') + (cap.pending ? ' · ' + cap.pending + ' pending' : '') + (cap.parked ? ' · PARKED' : '') + (cap.refused ? ' · ' + cap.refused + ' refused' : '')
    + ' · home ' + home + ' · exports ' + (cap.eventExportDurable ? 'acknowledged' : 'behind'));
  if (tgt.name) parts.push('target ' + tgt.name + ' · manifest ' + (tgt.resolution?.match || 'unknown'));
  else parts.push('no target open');
  put($('crs-deps'), parts.join('\n'));
  const preset = st.config?.preset;
  put($('crs-sym'), 'the classroom room this workbench serves — its access and health, a finding for the Findings page, a word to the instructor'
    + (preset && preset.model ? ' · Day 1 preset: ' + preset.model + ', ' + preset.turns + ' turns' : ''));
}

function openCourse(): void {
  const modal = $('coursemodal');
  if (!modal || !modal.hidden) return;
  paintCard();
  if (status) paintLinks(status);
  /* prefill the open symbol: the class or method the code view holds */
  const sym = $('crs-symbol') as HTMLInputElement | null;
  if (sym && !sym.value) sym.value = (S.currentSym && (S.currentSym.addr || S.currentSym.fqcn)) || '';
  put($('crs-err'), '');
  modal.hidden = false;
  escHandle = pushEscape(() => { if (modal.hidden) return false; closeCourse(); return true; }, ESC.modal);
  void refreshCourse();
  ($('crs-title-in') as HTMLInputElement | null)?.focus();
}

function closeCourse(): void {
  const modal = $('coursemodal');
  if (!modal || modal.hidden) return;
  modal.hidden = true;
  if (escHandle) { popEscape(escHandle); escHandle = null; }
}

function shareForm(): { kind: string; title: string; symbol: string; body: string } {
  return {
    kind: ($('crs-kind') as HTMLSelectElement).value,
    title: ($('crs-title-in') as HTMLInputElement).value.trim(),
    symbol: ($('crs-symbol') as HTMLInputElement).value.trim(),
    body: ($('crs-body') as HTMLTextAreaElement).value,
  };
}

/** the Findings form's fields, one chip each — click copies that one value */
function paintFields(fields: Record<string, string> | null): void {
  const host = $('crs-fields');
  if (!host) return;
  host.textContent = '';
  if (!fields) { host.hidden = true; return; }
  host.hidden = false;
  const order = ['type', 'title', 'summary', 'details', 'original_symbol', 'symbol', 'artifact_sha256', 'project_revision', 'activity_id', 'activity_revision'];
  for (const k of order) {
    const v = fields[k];
    if (!v) continue;
    const chip = document.createElement('button');
    chip.className = 'btn crs-chip';
    chip.type = 'button';
    chip.textContent = k + ': ' + (v.length > 48 ? v.slice(0, 45) + '…' : v);
    chip.title = 'copy ' + k + ' — ' + v;
    chip.addEventListener('click', async () => { try { await navigator.clipboard.writeText(v); put($('crs-share-note'), k + ' copied — paste it into the Findings form\'s ' + k + ' field'); } catch (e) { put($('crs-err'), 'the clipboard refused (' + String(e) + ')'); } });
    host.appendChild(chip);
  }
}

/* PREVIEW composes the finding's fields (nothing sent) and arms TO FINDINGS PAGE with the page URL
   carrying the small fields; without a preview the link opens the plain page and the fields appear
   after the compose. Either way the student publishes on the page. */
async function share(preview: boolean): Promise<CourseShareResult | null> {
  const f = shareForm();
  const note = $('crs-share-note'), err = $('crs-err');
  put(err, '');
  if (!f.title) { put(err, 'a finding needs a title (its claim)'); return null; }
  const key = JSON.stringify(f);
  const params: Record<string, unknown> = { kind: f.kind, title: f.title, body: f.body };
  if (f.symbol) params.symbol = f.symbol;
  if (preview) params.preview = true;
  else if (previewed && previewed.key === key) params.requestId = previewed.requestId;
  const r = await tryOp('course.share', withTarget(params));
  if (r.error) { put(err, r.error.payload?.error?.message || r.error.message); return null; }
  const d: CourseShareResult = r.data;
  lastFields = d.fields || null;
  paintFields(lastFields);
  const pub = $('crs-publish') as HTMLAnchorElement | null;
  if (pub && d.browser) pub.href = d.browser;
  if (preview) {
    previewed = d.requestId && d.browser ? { requestId: d.requestId, key, copy: d.copy || '', browser: d.browser } : null;
    const refs = d.refs || {};
    const prov = d.provenance || {};
    const act = prov.activity as { id?: string; revision?: number; stale?: boolean } | undefined;
    put(note, 'to ' + (d.destination || "the classroom's Findings page") + (d.as ? '\nas ' + d.as : '') + '\n' + d.kind + ' · "' + d.title + '" · ' + (d.bytes || 0) + ' of ' + (d.limitBytes || 20000) + ' bytes'
      + (refs.symbol_addr ? '\noriginal symbol: ' + refs.symbol_addr : '')
      + (act ? '\nactivity ' + (act.id || '—') + (act.revision != null ? ' rev ' + act.revision : '') + (act.stale ? ' (stale — the classroom has not confirmed it)' : '') : '')
      + (d.identity ? '\n' + d.identity : '')
      + '\nDetails as carried:\n—\n' + (d.copy || '').trim() + '\n—'
      + '\nrequest id ' + (d.requestId || '—') + ' — nothing was sent; TO FINDINGS PAGE opens the page with these fields and puts Details on the clipboard');
  }
  return d;
}

/** TO FINDINGS PAGE: the link opens the page (the browser's own navigation, never blocked as a popup);
 *  the Details go to the clipboard — synchronously from the preview when there is one, else after the
 *  compose (a browser may refuse that one: COPY TEXT remains) */
async function publishClick(e: Event): Promise<void> {
  const f = shareForm();
  const note = $('crs-share-note'), err = $('crs-err');
  const pub = e.currentTarget as HTMLAnchorElement;
  if (!f.title) { e.preventDefault(); put(err, 'a finding needs a title (its claim)'); return; }
  const key = JSON.stringify(f);
  const copyBtn = $('crs-copy') as HTMLButtonElement | null;
  if (previewed && previewed.key === key) {
    pub.href = previewed.browser;
    let clip = '';
    try { await navigator.clipboard.writeText(previewed.copy); clip = 'the Details are on the clipboard'; } catch (e2) { clip = 'the clipboard refused — use the field chips below to copy'; }
    put(note, 'Opened the Findings page in a new tab — paste into Details, check the prefilled fields, press Publish finding there. Nothing is published from Jeeves. ' + clip);
    void share(false);   // the draft is journaled privately; the chips refresh
    return;
  }
  /* no preview: the page opens plain right away (the link's own navigation); the compose follows */
  if (!pub.href || pub.href.endsWith('#')) pub.href = status?.handoffs?.findings || '#';
  const d = await share(false);
  if (!d) return;
  let clip = '';
  try { await navigator.clipboard.writeText(d.copy || ''); clip = 'the Details are on the clipboard'; }
  catch (e2) { clip = 'the clipboard refused after the compose — COPY TEXT or the field chips copy them'; if (copyBtn) { copyBtn.hidden = false; copyBtn.dataset.copy = d.copy || ''; copyBtn.dataset.href = ''; } }
  put(note, 'Opened the Findings page in a new tab — paste into Details and the fields from the chips, press Publish finding there. Nothing is published from Jeeves. ' + clip
    + (d.draftNotJournaled ? '\n(the private draft could not be journaled: ' + d.draftNotJournaled + ')' : ''));
}

/** TO MESSAGES: the typed words + one context line to the clipboard, the Messages page opens (the link);
 *  the op composes the same line when the room answers, and is not needed when it does not */
async function helpClick(e: Event): Promise<void> {
  const text = ($('crs-help') as HTMLTextAreaElement).value.trim();
  const note = $('crs-help-note'), err = $('crs-err');
  const link = e.currentTarget as HTMLAnchorElement;
  put(err, '');
  if (!text) { e.preventDefault(); put(err, 'say where you are stuck'); return; }
  if (!link.href || link.href.endsWith('#')) link.href = status?.handoffs?.help || '#';
  const sym = ($('crs-symbol') as HTMLInputElement).value.trim();
  const act = status?.activity?.id || '—';
  const tgt = status?.target?.name || '';
  const local = text + '\n\ncontext: activity ' + act + (tgt ? ' · ' + tgt : '') + (sym ? ' · ' + sym : '');
  let copy = local;
  try { await navigator.clipboard.writeText(local); put(note, 'Opened the Messages page in a new tab — paste and send it there (your words and the context line are on the clipboard). Nothing is sent from Jeeves.'); }
  catch (e2) { put(note, 'Opened the Messages page — the clipboard refused; COPY TEXT copies your words and the context line.'); }
  const copyBtn = $('crs-copy') as HTMLButtonElement | null;
  if (copyBtn) { copyBtn.hidden = false; copyBtn.dataset.copy = copy; copyBtn.dataset.href = ''; }
  const params: Record<string, unknown> = { text };
  if (sym) params.symbol = sym;
  const r = await tryOp('course.help', params);
  if (!r.error && r.data && r.data.copy) { copy = r.data.copy; if (copyBtn) copyBtn.dataset.copy = copy; }
}

async function copyFallback(): Promise<void> {
  const b = $('crs-copy') as HTMLButtonElement | null;
  const text = b?.dataset.copy || '';
  try { await navigator.clipboard.writeText(text); put($('crs-help-note'), 'copied — paste it on the classroom page'); }
  catch (e) { put($('crs-err'), 'the clipboard refused (' + String(e) + ') — select the text above and copy it'); }
}
