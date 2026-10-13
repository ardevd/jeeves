// api.ts — op client for the workbench backend (127.0.0.1:7800).
// Wire: POST /api/<op>  {op,params,actor:"human"} → {ok,data,error,meta}
// Events: SSE /api/events.
import type { OpParams, OpResult, SseChannel, SseData, SseHandler } from './types.js';

const BASE = '';

/** the error half of the wire envelope */
export interface OpErrorBody {
  code: string;
  message: string;
  detail?: string;
}

/** the wire envelope an op answers with (ok:false carries `error`) */
export interface OpPayload {
  ok?: boolean;
  data?: unknown;
  error?: OpErrorBody;
  meta?: unknown;
}

export class OpError extends Error {
  payload: OpPayload;
  /** the HTTP status when the failure was the transport's (a non-2xx) — 0 for an ok:false answer, -1 for no answer at all */
  status: number;
  constructor(payload: OpPayload, status = 0){
    super(payload && payload.error ? (payload.error.code + ': ' + payload.error.message) : 'op failed');
    this.payload = payload;
    this.status = status;
  }
}

/** tryOp's answer: exactly one of `data` / `error` is set — narrow on `error` */
export type TryResult<T> = { data: T; error?: undefined } | { data?: undefined; error: OpError };

export type LinkObserver = (up: boolean, detail?: string) => void;

// Backend-link observers (the STATE ribbon item). This is about the TRANSPORT,
// not the op: an op answering ok:false (unknown symbol, unsupported cap) proves
// the backend is alive and healthy — only a failed fetch or a non-2xx is DOWN.
const linkSubs: LinkObserver[] = [];
export function onLink(fn: LinkObserver): void { linkSubs.push(fn); }
function link(up: boolean, detail?: string): void {
  for (const fn of linkSubs){
    try { fn(up, detail); } catch (e) { /* an observer must not break the op */ }
  }
}

// Call an op. Returns data on ok:true; throws OpError otherwise.
export async function op<K extends string>(name: K, params: OpParams = {}): Promise<OpResult<K>>{
  let res: Response;
  try {
    res = await fetch(`${BASE}/api/${name}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ op: name, params, actor: 'human' }),
    });
  } catch (e) {
    link(false, 'no answer from the backend');
    throw new OpError({ error: { code: 'E_HTTP', message: String((e as { message?: unknown } | null)?.message || e) } }, -1);
  }
  if (!res.ok){
    /* a non-2xx with a JSON error body is the backend (or a course room's gate) speaking: keep its
       code and words. A 401/403/404 on a course page is an ACCESS answer — the class edge or the
       room refusing this session — not a dead backend link; 5xx and no-answer are. */
    let body: OpPayload | null = null;
    try { body = await res.clone().json() as OpPayload; } catch { body = null; }
    if (res.status >= 500 || res.status === 0) link(false, `HTTP ${res.status} on ${name}`);
    if (body && body.error && body.error.code) throw new OpError(body, res.status);
    throw new OpError({ error: { code: 'E_HTTP', message: `HTTP ${res.status}` } }, res.status);
  }
  const body = await res.json() as OpPayload;
  link(true);
  if (!body.ok) throw new OpError(body);
  return body.data as OpResult<K>;
}

// Safe variant: returns {data} or {error} — never throws (for optional ops).
export async function tryOp<K extends string>(name: K, params: OpParams = {}): Promise<TryResult<OpResult<K>>>{
  try { return { data: await op(name, params) }; }
  catch (e) { return { error: e as OpError }; }
}

// Subscribe to the SSE event bus. handler(evName, data) per message.
// Multiple subscribers supported via onEvent(); the connection opens lazily.
const subs: SseHandler[] = [];
let es: EventSource | null = null;
let backoffMs = 2000;        // the reconnect's backoff after a stream the server ENDED (2 s → 30 s)
let reconnectT = 0;

function fanout(type: SseChannel, data: SseData): void {
  subs.forEach(fn => { try { fn(type, data); } catch (e) { /* one subscriber must not stop the others */ } });
}

function ensureEvents(): void {
  if (es) return;
  const s = new EventSource(`${BASE}/api/events`);
  es = s;
  // NB: onmessage only fires for UNNAMED events — our channels are named
  // ("event: runs" etc.), which only reach type-specific listeners.
  const dispatch = (type: SseChannel, m: MessageEvent): void => {
    let data: SseData = null;
    try { data = JSON.parse(m.data) as SseData; } catch { data = m.data as string; }
    fanout(type, data);
  };
  ['hello', 'heartbeat', 'progress', 'presence', 'activity', 'proposals', 'runs', 'closed']
    .forEach(ch => s.addEventListener(ch, m => dispatch(ch, m)));
  s.onmessage = (m) => dispatch(m.type || 'message', m);
  s.onopen = () => { backoffMs = 2000; fanout('connection', { state: 'open' }); };
  s.onerror = () => {
    /* two different failures: the browser's own retry (readyState CONNECTING — a dropped socket, a
       backend restart: it comes back by itself) and a stream the server ENDED with a non-200 (readyState
       CLOSED — a course room whose access closed, the class edge's denial page, an auth outage past
       its window): the browser never retries that one. Re-create it ourselves with backoff; the hello
       on the new stream resyncs the page (app.ts). The STATE item says "live updates paused" meanwhile. */
    if (s.readyState === EventSource.CLOSED){
      if (es === s) es = null;
      fanout('connection', { state: 'reconnecting', inMs: backoffMs });
      if (!reconnectT) reconnectT = window.setTimeout(() => { reconnectT = 0; ensureEvents(); }, backoffMs);
      backoffMs = Math.min(backoffMs * 2, 30_000);
    } else {
      fanout('connection', { state: 'retrying' });
    }
  };
}

export function events(handler: SseHandler): EventSource | null {
  subs.push(handler);
  ensureEvents();
  return es;
}

/* Register an additional event handler without opening a second connection. */
export function onEvent(fn: SseHandler): void {
  subs.push(fn);
  ensureEvents();
}
