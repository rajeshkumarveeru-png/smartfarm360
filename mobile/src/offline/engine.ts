import type { DayRef, OfflineStore, OutboxRecord } from './types';

/** The sync engine (mobile offline design, N5, N6, N8). Pure apart from the store, the transport and the clock passed in. */

const MINUTE = 60_000;
const DAY = 24 * 60 * MINUTE;
/** Waits after a network or server failure: 30 s, 2 min, 10 min, then every 30 min (N6). */
export const BACKOFF_MS = [30_000, 2 * MINUTE, 10 * MINUTE, 30 * MINUTE];
/** At most 5 requests a second (N6). */
export const MIN_GAP_MS = 200;
export const SENT_KEPT_MS = 7 * DAY;
export const DISCARDED_KEPT_MS = 30 * DAY;
/** Phone clocks further than this from the server's get a warning (N9). */
export const CLOCK_WARNING_MS = 10 * MINUTE;
/** Cached lists older than this show a warning (N3). */
export const STALE_CACHE_MS = 7 * DAY;

export const backoffMs = (attempts: number) => BACKOFF_MS[Math.min(Math.max(attempts, 1), BACKOFF_MS.length) - 1];

/** A server reply; status 0 means the request never got a reply (no network, timeout). */
export type Reply = { status: number; code?: string; message?: string; body?: unknown };
export type Transport = (record: OutboxRecord) => Promise<Reply>;

export type Outcome = 'sent' | 'retry' | 'session' | 'upgrade' | 'membership' | 'refused';

export function classify(reply: Reply): Outcome {
  const { status, code } = reply;
  if (status >= 200 && status < 300) return 'sent';
  if (status === 0 || status === 429 || status >= 500) return 'retry';
  if (status === 401) return 'session';
  if (status === 426 || code === 'CLIENT_UPGRADE_REQUIRED') return 'upgrade';
  if (status === 409 && code === 'IDEMPOTENCY_IN_PROGRESS') return 'retry';
  if (status === 404 && code === 'TENANT_NOT_FOUND') return 'membership';
  return 'refused';
}

const byAge = (a: OutboxRecord, b: OutboxRecord) => a.createdAt.localeCompare(b.createdAt) || a.id.localeCompare(b.id);

/**
 * The records to send now, oldest first. A target with a record that needs attention (or is still waiting out a
 * retry) holds back its later records; other targets carry on (N5). Only the signed-in user's records are sent (N10).
 */
export function sendable(records: OutboxRecord[], userId: number, now: number): OutboxRecord[] {
  const held = new Set<string>();
  const out: OutboxRecord[] = [];
  for (const r of records.filter(x => x.userId === userId).sort(byAge)) {
    if (r.state === 'attention' || r.state === 'locked') { held.add(r.target); continue; }
    if (r.state !== 'waiting' || held.has(r.target)) continue;
    if (r.nextAt && Date.parse(r.nextAt) > now) { held.add(r.target); continue; }
    out.push(r);
  }
  return out;
}

/** The version of a day record in a server reply: the day's own, or one milking session's (N7). */
export function versionFrom(day: DayRef, body: unknown): string | null {
  if (!body || typeof body !== 'object') return null;
  if (day.session) {
    const sessions = (body as { sessions?: { session: string; groupId: number; version?: string }[] }).sessions ?? [];
    return sessions.find(s => s.session === day.session && s.groupId === day.groupId)?.version ?? '0';
  }
  const v = (body as { version?: unknown }).version;
  return typeof v === 'string' ? v : null;
}

/** The record with its base version replaced (milk records carry it on their one session). */
export function withBaseVersion(record: OutboxRecord, version: string): OutboxRecord {
  const body = { ...record.body };
  if (record.day?.session && Array.isArray(body.sessions)) {
    body.sessions = (body.sessions as Record<string, unknown>[]).map(s => ({ ...s, baseVersion: version }));
  } else {
    body.baseVersion = version;
  }
  return { ...record, body };
}

const hasBaseVersion = (r: OutboxRecord) => r.day?.session
  ? Array.isArray(r.body.sessions) && (r.body.sessions as Record<string, unknown>[]).some(s => s.baseVersion != null)
  : r.body.baseVersion != null;

export type SyncDeps = {
  store: OfflineStore;
  userId: number;
  transport: Transport;
  now: () => number;
  sleep: (ms: number) => Promise<void>;
  onProgress?: (done: number, total: number) => void;
  /** The server no longer knows the user in this farm business: clear its data from the phone (N12). */
  onMembershipLost?: (tenantId: number) => Promise<void>;
};

export type SyncResult = { sent: number; attention: number; attempted: number; stopped: null | 'offline' | 'upgrade' | 'session' | 'rateLimited' };

/** Sends what can be sent now, once. A record that was sent but whose reply was lost is replayed by the server (N4). */
export async function syncOnce(deps: SyncDeps): Promise<SyncResult> {
  const { store, userId, transport, now, sleep } = deps;
  const list = sendable(await store.records(), userId, now());
  const result: SyncResult = { sent: 0, attention: 0, attempted: 0, stopped: null };
  const held = new Set<string>();
  const lostTenants = new Set<number>();
  let lastStart = Number.NEGATIVE_INFINITY;
  for (let i = 0; i < list.length && !result.stopped; i++) {
    // Read it again: an earlier send in this run may have carried its day version forward
    const r = (await store.records()).find(x => x.id === list[i].id);
    if (!r || r.state !== 'waiting' || held.has(r.target) || lostTenants.has(r.tenantId)) continue;
    const wait = lastStart + MIN_GAP_MS - now();
    if (wait > 0) await sleep(wait);
    lastStart = now();
    deps.onProgress?.(i, list.length);
    result.attempted++;
    const reply = await transport(r);
    const at = new Date(now()).toISOString();
    switch (classify(reply)) {
      case 'sent': {
        await store.put({ ...r, state: 'sent', sentAt: at, error: undefined, nextAt: undefined });
        await carryVersion(store, r, reply.body);
        result.sent++;
        break;
      }
      case 'retry': {
        const attempts = r.attempts + 1;
        await store.put({ ...r, attempts, nextAt: new Date(now() + backoffMs(attempts)).toISOString() });
        held.add(r.target);
        if (reply.status === 0) result.stopped = 'offline';
        else if (reply.status === 429) result.stopped = 'rateLimited';
        break;
      }
      case 'session': result.stopped = 'session'; break;
      case 'upgrade': result.stopped = 'upgrade'; break;
      case 'membership': {
        lostTenants.add(r.tenantId);
        for (const x of await store.records()) {
          if (x.tenantId === r.tenantId && (x.state === 'waiting' || x.state === 'attention')) {
            await store.put({ ...x, state: 'locked', error: { status: reply.status, code: reply.code ?? 'TENANT_NOT_FOUND', message: reply.message ?? '' } });
          }
        }
        await deps.onMembershipLost?.(r.tenantId);
        break;
      }
      case 'refused': {
        await store.put({ ...r, state: 'attention', error: { status: reply.status, code: reply.code ?? 'REQUEST_FAILED', message: reply.message ?? '' } });
        held.add(r.target);
        result.attention++;
        break;
      }
    }
  }
  deps.onProgress?.(list.length, list.length);
  await prune(store, now());
  return result;
}

/** A later record of the same day was based on what the user just sent, not on the server's older version. */
async function carryVersion(store: OfflineStore, sent: OutboxRecord, body: unknown) {
  if (!sent.day) return;
  const version = versionFrom(sent.day, body);
  if (!version) return;
  for (const later of await store.records()) {
    if (later.id !== sent.id && later.target === sent.target && later.state === 'waiting' && byAge(sent, later) < 0 && hasBaseVersion(later)) {
      await store.put(withBaseVersion(later, version));
    }
  }
}

/** Sent records are kept 7 days and discarded ones 30 days (N8). */
export async function prune(store: OfflineStore, now: number) {
  for (const r of await store.records()) {
    if (r.state === 'sent' && r.sentAt && now - Date.parse(r.sentAt) > SENT_KEPT_MS) await store.remove(r.id);
    if (r.state === 'discarded' && r.discardedAt && now - Date.parse(r.discardedAt) > DISCARDED_KEPT_MS) await store.remove(r.id);
  }
}

// ---- Needs attention (N7, N8) -------------------------------------------------------------------------------------------

const ready = (r: OutboxRecord): OutboxRecord => ({ ...r, state: 'waiting', attempts: 0, nextAt: undefined, error: undefined });

/** Keep mine: resend based on the server's current version. */
export const keepMine = (r: OutboxRecord, serverVersion: string): OutboxRecord => ready(withBaseVersion(r, serverVersion));

/** Edit and resend with what the user changed. */
export const resend = (r: OutboxRecord, body: Record<string, unknown>): OutboxRecord => ready({ ...r, body });

/** Use the server's, or discard: kept 30 days in the discard list. */
export const discard = (r: OutboxRecord, now: number): OutboxRecord => ({ ...r, state: 'discarded', discardedAt: new Date(now).toISOString() });

// ---- Status (N13) -------------------------------------------------------------------------------------------------------

export type SyncSummary = { waiting: number; attention: number; locked: number; sent: number; discarded: number };

export function summarize(records: OutboxRecord[], userId: number): SyncSummary {
  const mine = records.filter(r => r.userId === userId);
  const count = (s: OutboxRecord['state']) => mine.filter(r => r.state === s).length;
  return { waiting: count('waiting'), attention: count('attention'), locked: count('locked'), sent: count('sent'), discarded: count('discarded') };
}

export type StatusInput = SyncSummary & { online: boolean; sending: { done: number; total: number } | null; lastSync: string | null;
  upgradeRequired: boolean; sessionExpired: boolean };

/** The status line's catalog key and parameters (`mobile.sync*`); {@code time} is filled in by the screen. */
export function statusLine(s: StatusInput): { key: string; params: Record<string, string> } {
  if (s.upgradeRequired) return { key: 'mobile.syncUpgrade', params: { count: String(s.waiting) } };
  if (s.sessionExpired) return { key: 'mobile.syncSignIn', params: { count: String(s.waiting) } };
  if (s.sending) return { key: 'mobile.syncSending', params: { done: String(Math.min(s.sending.done + 1, s.sending.total)), total: String(s.sending.total) } };
  if (s.attention > 0) return { key: 'mobile.syncAttention', params: { count: String(s.attention) } };
  if (!s.online) return { key: s.waiting > 0 ? 'mobile.syncOfflineWaiting' : 'mobile.syncOffline', params: { count: String(s.waiting) } };
  if (s.waiting > 0) return { key: 'mobile.syncWaiting', params: { count: String(s.waiting) } };
  return { key: s.lastSync ? 'mobile.syncAllSent' : 'mobile.syncNothing', params: {} };
}

/** Milliseconds between the phone's clock and the server's (from the reply's Date header); positive when the phone is ahead. */
export function clockSkew(phoneNow: number, serverDate: string | null): number | null {
  if (!serverDate) return null;
  const server = Date.parse(serverDate);
  return Number.isFinite(server) ? phoneNow - server : null;
}
