import { ApiError, apiRequest, sendRecord, setReadCache, onServerDate } from '../services/api';
import { classify, clockSkew, discard, keepMine, resend, summarize, syncOnce, versionFrom, type SyncSummary } from './engine';
import { memoryStore } from './memoryStore';
import type { DayRef, OfflineStore, OutboxRecord, RecordKind } from './types';
import { uuid7 } from './uuid7';

/**
 * The phone's offline layer (mobile offline design, sections 4–8): the signed-in user's store, the outbox, the read cache
 * and the sync state the status line shows. One user's store is open at a time; another user's records are never sent.
 */

export type HubState = SyncSummary & {
  userId: number | null;
  online: boolean;
  sending: { done: number; total: number } | null;
  lastSync: string | null;
  upgradeRequired: boolean;
  sessionExpired: boolean;
  /** Phone clock minus server clock, from the last reply (N9). */
  clockSkewMs: number | null;
  /** The oldest "as of" time of a cached read served since the network went down (N3). */
  cachedAsOf: string | null;
};

const empty: HubState = { waiting: 0, attention: 0, locked: 0, sent: 0, discarded: 0, userId: null, online: true, sending: null, lastSync: null,
  upgradeRequired: false, sessionExpired: false, clockSkewMs: null, cachedAsOf: null };

let store: OfflineStore | null = null;
let state: HubState = { ...empty };
let running: Promise<void> | null = null;
const listeners = new Set<(s: HubState) => void>();
const sentListeners = new Set<() => void>();
let clock: () => number = () => Date.now();

const LAST_SYNC = 'meta:lastSync';
const viewKey = (path: string) => `view:${path}`;

function set(patch: Partial<HubState>) {
  state = { ...state, ...patch };
  for (const l of listeners) l(state);
}

async function refreshSummary() {
  if (!store || state.userId == null) return;
  set(summarize(await store.records(), state.userId));
}

export function getState(): HubState { return state; }

export function subscribe(listener: (s: HubState) => void): () => void {
  listeners.add(listener);
  return () => { listeners.delete(listener); };
}

/** Screens reload their data after records were sent. */
export function onRecordsSent(listener: () => void): () => void {
  sentListeners.add(listener);
  return () => { sentListeners.delete(listener); };
}

/** Opens the user's store and its read cache (N1, N3, N10). */
export async function openForUser(userId: number, open: (userId: number) => Promise<OfflineStore>): Promise<void> {
  try {
    store = await open(userId);
  } catch {
    store = memoryStore(); // the database could not be opened: keep working for this session
  }
  const s = store;
  setReadCache({
    get: path => s.getCached(viewKey(path)),
    put: async (path, value) => {
      await s.setCached(viewKey(path), value, new Date(clock()).toISOString());
      if (state.cachedAsOf && state.online) set({ cachedAsOf: null });
    },
  }, (_path, asOf) => { if (!state.cachedAsOf || asOf < state.cachedAsOf) set({ cachedAsOf: asOf }); });
  onServerDate(date => { const skew = clockSkew(clock(), date); if (skew != null) set({ clockSkewMs: skew }); });
  const last = await s.getCached<string>(LAST_SYNC);
  state = { ...empty, userId, lastSync: last?.value ?? null, online: state.online };
  await refreshSummary();
}

/** Closes the store on sign-out; with {@code clear} the user's records and cache are removed from the phone (N12). */
export async function closeStore(clear: boolean): Promise<void> {
  if (store && clear) await store.clear();
  store = null;
  setReadCache(null);
  set({ ...empty, online: state.online });
}

export function setOnline(online: boolean) {
  set({ online, ...(online ? {} : {}) });
  if (online && state.waiting > 0) void syncNow();
}

/** Thrown to a form when its record was kept on the phone to be sent later; screens show `errors.SAVED_ON_PHONE`. */
export class SavedOnPhone extends ApiError {
  constructor() { super(0, 'SAVED_ON_PHONE', ''); this.name = 'SavedOnPhone'; }
}

export const isSavedOnPhone = (e: unknown): e is SavedOnPhone => e instanceof ApiError && e.code === 'SAVED_ON_PHONE';

export type RecordSpec = {
  kind: RecordKind; target: string; summary: string; method: 'POST' | 'PUT'; path: string; body: Record<string, unknown>;
  day?: DayRef; id?: string;
};

const tenantOf = (path: string) => Number(/^\/api\/v1\/tenants\/(\d+)\//.exec(path)?.[1] ?? 0);
const isOpen = (r: OutboxRecord) => r.state === 'waiting' || r.state === 'attention' || r.state === 'locked';

/**
 * Records something the user entered (N2). It goes into the outbox and is sent at once: online use is as before (the
 * reply comes back, a refusal is shown on the form and nothing is kept). When there is no reply, or an earlier record of
 * the same target is still waiting, the record stays on the phone and {@link SavedOnPhone} is thrown.
 */
export async function record<T>(spec: RecordSpec): Promise<T> {
  if (!store || state.userId == null) {
    return apiRequest<T>(spec.path, { method: spec.method, body: spec.body, headers: { 'Idempotency-Key': spec.id ?? uuid7(clock()) } });
  }
  const r: OutboxRecord = { id: spec.id ?? uuid7(clock()), userId: state.userId, tenantId: tenantOf(spec.path), kind: spec.kind, target: spec.target,
    summary: spec.summary, method: spec.method, path: spec.path, body: spec.body, day: spec.day, createdAt: new Date(clock()).toISOString(),
    state: 'waiting', attempts: 0 };
  const s = store;
  const earlier = (await s.records()).some(x => x.id !== r.id && x.target === r.target && isOpen(x));
  await s.put(r);
  if (earlier) { await refreshSummary(); throw new SavedOnPhone(); }
  const reply = await sendRecord(r.path, r.method, r.body, r.id);
  switch (classify(reply)) {
    case 'sent':
      await s.put({ ...r, state: 'sent', sentAt: new Date(clock()).toISOString() });
      await refreshSummary();
      set({ online: true });
      return reply.body as T;
    case 'refused':
    case 'membership':
      await s.remove(r.id);
      await refreshSummary();
      throw new ApiError(reply.status, reply.code ?? 'REQUEST_FAILED', reply.message ?? '');
    case 'upgrade': set({ upgradeRequired: true }); break;
    case 'session': set({ sessionExpired: true }); break;
    case 'retry':
      if (reply.status === 0) set({ online: false });
      else await s.put({ ...r, attempts: 1, nextAt: new Date(clock() + 30_000).toISOString() });
      break;
  }
  await refreshSummary();
  throw new SavedOnPhone();
}

/** Sends the outbox now (N6): on open, on reconnect, on Sync now and every 5 minutes. Concurrent calls share one run. */
export function syncNow(): Promise<void> {
  if (!running) {
    running = (async () => {
      if (!store || state.userId == null) return;
      const result = await syncOnce({
        store, userId: state.userId, now: clock, sleep: ms => new Promise(res => setTimeout(res, ms)),
        transport: r => sendRecord(r.path, r.method, r.body, r.id),
        onProgress: (done, total) => set({ sending: done < total ? { done, total } : null }),
        onMembershipLost: forgetTenant,
      });
      const patch: Partial<HubState> = { sending: null, upgradeRequired: result.stopped === 'upgrade', sessionExpired: result.stopped === 'session' };
      if (result.stopped === 'offline') patch.online = false;
      else if (result.attempted > 0 || state.online) {
        const at = new Date(clock()).toISOString();
        await store.setCached(LAST_SYNC, at, at);
        patch.lastSync = at;
        patch.online = true;
      }
      set(patch);
      await refreshSummary();
      if (result.sent > 0) for (const l of sentListeners) l();
    })().finally(() => { running = null; });
  }
  return running;
}

/** The user's records, newest first. */
export async function records(): Promise<OutboxRecord[]> {
  if (!store || state.userId == null) return [];
  return (await store.records()).filter(r => r.userId === state.userId).sort((a, b) => b.createdAt.localeCompare(a.createdAt));
}

/** The server's current copy of a day record, with its version (N7). */
export async function serverCopy(r: OutboxRecord): Promise<{ body: unknown; version: string }> {
  if (!r.day) throw new ApiError(400, 'NOT_A_DAY_RECORD', '');
  const body = await apiRequest<unknown>(r.day.readPath);
  return { body, version: versionFrom(r.day, body) ?? '0' };
}

async function replace(next: OutboxRecord) {
  if (!store) return;
  await store.put(next);
  await refreshSummary();
}

/** Keep mine: resend based on the server's current version. */
export async function keepMineAndResend(r: OutboxRecord): Promise<void> {
  const { version } = await serverCopy(r);
  await replace(keepMine(r, version));
  await syncNow();
}

/** Edit and resend. */
export async function editAndResend(r: OutboxRecord, body: Record<string, unknown>): Promise<void> {
  await replace(resend(r, body));
  await syncNow();
}

/** Use the server's, or discard: kept 30 days in the discard list (N8). */
export async function discardRecord(r: OutboxRecord): Promise<void> {
  await replace(discard(r, clock()));
}

/** The user is no longer a member of a farm business: its cached data goes, its records stay locked for discard (N12). */
export async function forgetTenant(tenantId: number): Promise<void> {
  if (!store) return;
  await store.removeCachedPrefix(viewKey(`/api/v1/tenants/${tenantId}/`));
  for (const r of await store.records()) {
    if (r.tenantId === tenantId && (r.state === 'waiting' || r.state === 'attention')) {
      await store.put({ ...r, state: 'locked', error: { status: 404, code: 'TENANT_NOT_FOUND', message: '' } });
    }
  }
  await refreshSummary();
}

/** Adds records made before this phase (quick entries) without sending them; an id already in the outbox is skipped. */
export async function adopt(list: OutboxRecord[]): Promise<number> {
  if (!store) return 0;
  const known = new Set((await store.records()).map(r => r.id));
  let added = 0;
  for (const r of list) if (!known.has(r.id)) { await store.put(r); added++; }
  await refreshSummary();
  return added;
}

/** For tests only. */
export async function __useForTests(s: OfflineStore, userId: number, now: () => number = () => Date.now()) {
  clock = now;
  await openForUser(userId, async () => s);
}
