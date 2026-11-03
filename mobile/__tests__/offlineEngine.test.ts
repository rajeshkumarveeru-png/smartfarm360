import { BACKOFF_MS, backoffMs, classify, discard, keepMine, MIN_GAP_MS, prune, resend, sendable, statusLine, summarize, syncOnce, versionFrom,
  type Reply } from '../src/offline/engine';
import { applyEdits, dayFigures, editableFields, kindKey, sections, whenText } from '../src/offline/figures';
import { memoryStore } from '../src/offline/memoryStore';
import type { OutboxRecord } from '../src/offline/types';
import { uuid7, uuid7Time } from '../src/offline/uuid7';

const T0 = Date.parse('2026-10-20T05:30:00Z');
let seq = 0;
const rec = (over: Partial<OutboxRecord> = {}): OutboxRecord => {
  seq++;
  return { id: `id-${String(seq).padStart(3, '0')}`, userId: 1, tenantId: 7, kind: 'observation', target: `t${seq}`, summary: '', method: 'POST',
    path: '/api/v1/tenants/7/x', body: {}, createdAt: new Date(T0 + seq * 1000).toISOString(), state: 'waiting', attempts: 0, ...over };
};

/** A fake clock that the engine's sleeps advance. */
function clock(start = T0) {
  let now = start;
  const sleeps: number[] = [];
  return { now: () => now, sleep: async (ms: number) => { sleeps.push(ms); now += ms; }, advance: (ms: number) => { now += ms; }, sleeps };
}

beforeEach(() => { seq = 0; });

test('client ids are UUIDv7: version 7, the variant bits, and ordered by the time they were made', () => {
  const a = uuid7(T0);
  const b = uuid7(T0 + 1);
  expect(a).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
  expect(uuid7Time(a)).toBe(T0);
  expect(a < b).toBe(true);
  expect(new Set(Array.from({ length: 500 }, () => uuid7(T0))).size).toBe(500);
  expect(a).toMatch(/^[A-Za-z0-9._:-]{8,100}$/); // accepted as an Idempotency-Key by the server
});

test('replies are sorted into sent, retried, refused, and the stops for sign-in, upgrade and lost membership', () => {
  const c = (status: number, code?: string) => classify({ status, code });
  expect(c(200)).toBe('sent');
  expect(c(201)).toBe('sent');
  expect(c(0, 'NETWORK')).toBe('retry');
  expect(c(503)).toBe('retry');
  expect(c(429)).toBe('retry');
  expect(c(409, 'IDEMPOTENCY_IN_PROGRESS')).toBe('retry');
  expect(c(409, 'STALE_RECORD')).toBe('refused');
  expect(c(400, 'INSUFFICIENT_STOCK')).toBe('refused');
  expect(c(403, 'PERMISSION_DENIED')).toBe('refused');
  expect(c(401)).toBe('session');
  expect(c(426, 'CLIENT_UPGRADE_REQUIRED')).toBe('upgrade');
  expect(c(404, 'TENANT_NOT_FOUND')).toBe('membership');
});

test('records go oldest first; a target that needs attention holds back only its own later records', () => {
  const a1 = rec({ target: 'milk:A' });
  const b1 = rec({ target: 'pond:B', state: 'attention' });
  const a2 = rec({ target: 'milk:A' });
  const b2 = rec({ target: 'pond:B' });
  const c1 = rec({ target: 'cycle:C', nextAt: new Date(T0 + 3_600_000).toISOString() });
  const c2 = rec({ target: 'cycle:C' });
  const other = rec({ target: 'milk:A', userId: 2 });
  const sent = rec({ target: 'x', state: 'sent' });
  expect(sendable([c2, b2, other, a2, sent, c1, b1, a1], 1, T0 + 60_000).map(r => r.id)).toEqual([a1.id, a2.id]);
  // Once the wait is over the held target goes, oldest first
  expect(sendable([c2, c1], 1, T0 + 7_200_000).map(r => r.id)).toEqual([c1.id, c2.id]);
});

test('network and server failures wait 30 s, 2 min, 10 min, then every 30 min', () => {
  expect([1, 2, 3, 4, 5, 9].map(backoffMs)).toEqual([BACKOFF_MS[0], 120_000, 600_000, 1_800_000, 1_800_000, 1_800_000]);
  expect(BACKOFF_MS[0]).toBe(30_000);
});

test('a sync sends in order, at most 5 a second, keeps refusals with the server reason and holds their target', async () => {
  const store = memoryStore();
  const c = clock();
  const list = [rec({ target: 'A' }), rec({ target: 'B' }), rec({ target: 'B' }), rec({ target: 'C' }), rec({ target: 'D' })];
  for (const r of list) await store.put(r);
  const replies: Record<string, Reply> = { [list[1].id]: { status: 400, code: 'INSUFFICIENT_STOCK', message: 'Not enough Urea in stock: 10 kg' } };
  const sentOrder: string[] = [];
  const result = await syncOnce({ store, userId: 1, now: c.now, sleep: c.sleep,
    transport: async r => { sentOrder.push(r.id); return replies[r.id] ?? { status: 201, body: {} }; } });
  expect(sentOrder).toEqual([list[0].id, list[1].id, list[3].id, list[4].id]);   // B's second record waits behind the refused one
  expect(result).toMatchObject({ sent: 3, attention: 1, stopped: null });
  expect(c.sleeps).toEqual([MIN_GAP_MS, MIN_GAP_MS, MIN_GAP_MS]);
  const after = Object.fromEntries((await store.records()).map(r => [r.id, r]));
  expect(after[list[1].id]).toMatchObject({ state: 'attention', error: { status: 400, code: 'INSUFFICIENT_STOCK', message: 'Not enough Urea in stock: 10 kg' } });
  expect(after[list[2].id].state).toBe('waiting');
  expect(after[list[0].id].state).toBe('sent');
});

test('losing the network stops the run and the record waits with a growing delay; the rest are untouched', async () => {
  const store = memoryStore();
  const c = clock();
  const [a, b] = [rec({ target: 'A' }), rec({ target: 'B' })];
  await store.put(a); await store.put(b);
  const result = await syncOnce({ store, userId: 1, now: c.now, sleep: c.sleep, transport: async () => ({ status: 0, code: 'NETWORK' }) });
  expect(result.stopped).toBe('offline');
  const [ra, rb] = [(await store.records()).find(r => r.id === a.id)!, (await store.records()).find(r => r.id === b.id)!];
  expect(ra.attempts).toBe(1);
  expect(Date.parse(ra.nextAt!) - c.now()).toBe(30_000);
  expect(rb).toMatchObject({ attempts: 0, state: 'waiting' });
  // A server error on the retry doubles the wait to 2 minutes and the run carries on with other targets
  c.advance(31_000);
  const order: string[] = [];
  let failedAt = 0;
  await syncOnce({ store, userId: 1, now: c.now, sleep: c.sleep, transport: async r => {
    order.push(r.id);
    if (r.id !== a.id) return { status: 200 };
    failedAt = c.now();
    return { status: 503 };
  } });
  expect(order).toEqual([a.id, b.id]);
  const again = (await store.records()).find(r => r.id === a.id)!;
  expect(again.attempts).toBe(2);
  expect(Date.parse(again.nextAt!) - failedAt).toBe(120_000);
});

test('a record whose reply was lost is sent again under the same id and recorded once (the server replays)', async () => {
  const store = memoryStore();
  const c = clock();
  const r = rec({ target: 'attendance:2026-10-20', kind: 'attendance' });
  await store.put(r);
  const server = new Map<string, number>();   // Idempotency-Key -> times recorded
  let dropReply = true;
  const transport = async (x: OutboxRecord): Promise<Reply> => {
    if (!server.has(x.id)) server.set(x.id, 1);          // the server records it (or replays the stored reply)
    if (dropReply) { dropReply = false; return { status: 0, code: 'NETWORK' }; }   // the phone never got the answer
    return { status: 200, body: { version: 'v1' } };
  };
  await syncOnce({ store, userId: 1, now: c.now, sleep: c.sleep, transport });
  c.advance(30_001);
  await syncOnce({ store, userId: 1, now: c.now, sleep: c.sleep, transport });
  expect(server.get(r.id)).toBe(1);
  expect((await store.records())[0].state).toBe('sent');
});

test('an old app version or an ended session stops sending and keeps the outbox as it is', async () => {
  for (const reply of [{ status: 426, code: 'CLIENT_UPGRADE_REQUIRED' }, { status: 401, code: 'UNAUTHORIZED' }]) {
    const store = memoryStore();
    const r = rec();
    await store.put(r);
    const c = clock();
    const result = await syncOnce({ store, userId: 1, now: c.now, sleep: c.sleep, transport: async () => reply });
    expect(result.stopped).toBe(reply.status === 426 ? 'upgrade' : 'session');
    expect(await store.records()).toEqual([r]);
  }
});

test('when the server no longer knows the user in a farm business its records are locked and other businesses carry on', async () => {
  const store = memoryStore();
  const c = clock();
  const gone = [rec({ tenantId: 7 }), rec({ tenantId: 7 })];
  const kept = rec({ tenantId: 9, path: '/api/v1/tenants/9/x' });
  for (const r of [...gone, kept]) await store.put(r);
  const lost: number[] = [];
  const sent: string[] = [];
  await syncOnce({ store, userId: 1, now: c.now, sleep: c.sleep, onMembershipLost: async t => { lost.push(t); },
    transport: async r => { sent.push(r.id); return r.tenantId === 7 ? { status: 404, code: 'TENANT_NOT_FOUND' } : { status: 201 }; } });
  expect(lost).toEqual([7]);
  expect(sent).toEqual([gone[0].id, kept.id]);
  const states = Object.fromEntries((await store.records()).map(r => [r.id, r.state]));
  expect([states[gone[0].id], states[gone[1].id], states[kept.id]]).toEqual(['locked', 'locked', 'sent']);
});

test('a later save of the same day is based on what the phone just sent, not on the older version it first saw', async () => {
  const store = memoryStore();
  const c = clock();
  const day = { readPath: '/api/v1/tenants/7/livestock/milk/2026-10-20', session: 'MORNING', groupId: 3 };
  const body = (litres: string) => ({ sessions: [{ session: 'MORNING', groupId: 3, litres, baseVersion: 'v1' }] });
  const first = rec({ kind: 'milk', target: 'milk:2026-10-20:MORNING:3', day, body: body('42') });
  const second = rec({ kind: 'milk', target: 'milk:2026-10-20:MORNING:3', day, body: body('43') });
  await store.put(first); await store.put(second);
  const seen: unknown[] = [];
  await syncOnce({ store, userId: 1, now: c.now, sleep: c.sleep, transport: async r => {
    seen.push(r.body);
    return { status: 200, body: { sessions: [{ session: 'MORNING', groupId: 3, version: r.id === first.id ? 'v2' : 'v3' }] } };
  } });
  expect((seen[1] as { sessions: { baseVersion: string }[] }).sessions[0].baseVersion).toBe('v2');
  expect(versionFrom(day, { sessions: [] })).toBe('0');
  expect(versionFrom({ readPath: 'x' }, { version: 'abc' })).toBe('abc');
});

test('sent records are kept 7 days and discarded ones 30 days', async () => {
  const store = memoryStore();
  const now = T0 + 40 * 86_400_000;
  const old = (days: number) => new Date(now - days * 86_400_000).toISOString();
  const keep = [rec({ state: 'sent', sentAt: old(6) }), rec({ state: 'discarded', discardedAt: old(29) }), rec({ state: 'attention' })];
  const drop = [rec({ state: 'sent', sentAt: old(8) }), rec({ state: 'discarded', discardedAt: old(31) })];
  for (const r of [...keep, ...drop]) await store.put(r);
  await prune(store, now);
  expect((await store.records()).map(r => r.id).sort()).toEqual(keep.map(r => r.id).sort());
});

test('keep mine resends on the server version; edit resends the corrected record; discard keeps it for the discard list', () => {
  const r = rec({ state: 'attention', attempts: 3, error: { status: 409, code: 'STALE_RECORD', message: '' }, body: { entries: [], baseVersion: 'v1' } });
  expect(keepMine(r, 'v2')).toMatchObject({ state: 'waiting', attempts: 0, error: undefined, body: { baseVersion: 'v2' } });
  expect(resend(r, { entries: [1] })).toMatchObject({ state: 'waiting', body: { entries: [1] } });
  expect(discard(r, T0)).toMatchObject({ state: 'discarded', discardedAt: new Date(T0).toISOString() });
});

test('the status line says offline and waiting, sending, needs attention, or all sent', () => {
  const base = { waiting: 0, attention: 0, locked: 0, sent: 0, discarded: 0, online: true, sending: null, lastSync: null, upgradeRequired: false, sessionExpired: false };
  expect(statusLine({ ...base, online: false, waiting: 6 })).toEqual({ key: 'mobile.syncOfflineWaiting', params: { count: '6' } });
  expect(statusLine({ ...base, waiting: 6, sending: { done: 2, total: 6 } })).toEqual({ key: 'mobile.syncSending', params: { done: '3', total: '6' } });
  expect(statusLine({ ...base, attention: 2, waiting: 1 }).key).toBe('mobile.syncAttention');
  expect(statusLine({ ...base, lastSync: '2026-10-20T05:32:00Z' }).key).toBe('mobile.syncAllSent');
  expect(statusLine({ ...base, upgradeRequired: true, waiting: 3 }).key).toBe('mobile.syncUpgrade');
  expect(summarize([rec(), rec({ state: 'attention' }), rec({ userId: 2 })], 1)).toMatchObject({ waiting: 1, attention: 1 });
});

test('day records are compared figure by figure, and only amounts, dates and notes can be edited', () => {
  const day = { readPath: 'x', session: 'MORNING', groupId: 3 };
  expect(dayFigures('milk', day, { sessions: [{ session: 'MORNING', groupId: 3, litres: '42' }] })).toEqual([{ label: 'mobile.syncFigureLitres', value: '42' }]);
  expect(dayFigures('milk', day, { sessions: [{ session: 'MORNING', groupId: 3, litres: 40, yields: [{ litres: 25 }, { litres: '15.5' }] }] }))
    .toEqual([{ label: 'mobile.syncFigureLitres', value: '40.5' }]);
  expect(dayFigures('attendance', undefined, { workers: [{ status: 'PRESENT' }, { status: 'PRESENT' }, { status: null }, { status: 'ABSENT' }] }))
    .toEqual([{ label: 'workforce.status.ABSENT', value: '1' }, { label: 'workforce.status.PRESENT', value: '2' }]);
  const activity = { cycleId: 4, activityType: 'FERTILIZER', activityDate: '2026-10-20', notes: null, inputs: [{ itemId: 9, quantity: 50 }] };
  const fields = editableFields(activity);
  expect(fields.map(f => f.path.join('.'))).toEqual(['activityDate', 'notes', 'inputs.0.quantity']);
  const edited = applyEdits(activity, [{ path: ['inputs', 0, 'quantity'], value: '10' }, { path: ['notes'], value: ' ' }]);
  expect(edited).toEqual({ ...activity, inputs: [{ itemId: 9, quantity: 10 }], notes: null });
  expect(activity.inputs[0].quantity).toBe(50);   // the original is not changed
  expect(kindKey('flockDay')).toBe('mobile.syncKindFlockDay');
  expect(whenText('2026-10-20T06:10:00', new Date('2026-10-20T11:00:00'))).toBe('06:10');
  expect(whenText('2026-10-19T19:40:00', new Date('2026-10-20T11:00:00'))).toBe('2026-10-19 19:40');
  expect(sections([rec({ state: 'locked' }), rec(), rec({ state: 'sent' })]).attention).toHaveLength(1);
});
