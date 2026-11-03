const mockSendRecord = jest.fn();
const mockApiRequest = jest.fn();
jest.mock('../src/services/api', () => {
  const actual = jest.requireActual('../src/services/api');
  return { ...actual, sendRecord: (...a: unknown[]) => mockSendRecord(...a), apiRequest: (...a: unknown[]) => mockApiRequest(...a), setReadCache: jest.fn(), onServerDate: jest.fn() };
});
jest.mock('expo-secure-store', () => ({ getItemAsync: jest.fn(), setItemAsync: jest.fn(), deleteItemAsync: jest.fn() }));
jest.mock('@react-native-async-storage/async-storage', () => require('@react-native-async-storage/async-storage/jest/async-storage-mock'));

import AsyncStorage from '@react-native-async-storage/async-storage';
import { ApiError } from '../src/services/api';
import { __useForTests, adopt, closeStore, discardRecord, forgetTenant, getState, isSavedOnPhone, keepMineAndResend, openForUser, record, records, syncNow } from '../src/offline/hub';
import { memoryStore } from '../src/offline/memoryStore';
import { entryRecord, type FinanceContext } from '../src/finance/quickEntry';
import { loadEntries, saveEntry, scopeKey, type FarmEntry } from '../src/storage/entries';
import type { OfflineStore } from '../src/offline/types';

const milk = (litres: string) => ({ kind: 'milk' as const, target: 'milk:2026-10-20:MORNING:3', summary: '2026-10-20', method: 'PUT' as const,
  path: '/api/v1/tenants/7/livestock/milk/2026-10-20', body: { sessions: [{ session: 'MORNING', groupId: 3, litres, baseVersion: 'v1' }] },
  day: { readPath: '/api/v1/tenants/7/livestock/milk/2026-10-20', session: 'MORNING', groupId: 3 } });
const note = (target = 'cycle:4') => ({ kind: 'observation' as const, target, summary: '', method: 'POST' as const,
  path: '/api/v1/tenants/7/cultivation-cycles/4/observations', body: { observedOn: '2026-10-20', kind: 'PEST', severity: 'LOW' } });

let store: OfflineStore;
beforeEach(async () => {
  mockSendRecord.mockReset();
  mockApiRequest.mockReset();
  store = memoryStore();
  await __useForTests(store, 1);
});

test('online, a record is sent at once with its client id, and the reply comes back to the form', async () => {
  mockSendRecord.mockResolvedValueOnce({ status: 201, body: { id: 55 } });
  await expect(record(note())).resolves.toEqual({ id: 55 });
  const [path, method, , key] = mockSendRecord.mock.calls[0];
  expect([path, method]).toEqual(['/api/v1/tenants/7/cultivation-cycles/4/observations', 'POST']);
  expect(key).toMatch(/^[0-9a-f-]{36}$/);
  expect((await records())[0]).toMatchObject({ id: key, state: 'sent', tenantId: 7, userId: 1 });
});

test('online, a refusal is shown on the form and nothing is kept on the phone', async () => {
  mockSendRecord.mockResolvedValueOnce({ status: 409, code: 'STALE_RECORD', message: 'Someone else changed this' });
  await expect(record(milk('42'))).rejects.toMatchObject({ code: 'STALE_RECORD', status: 409 });
  expect(await records()).toEqual([]);
});

test('offline, the record waits on the phone and the form is told so; reconnecting sends it', async () => {
  mockSendRecord.mockResolvedValueOnce({ status: 0, code: 'NETWORK' });
  const saved = await record(note()).catch(e => e);
  expect(isSavedOnPhone(saved)).toBe(true);
  expect(saved).toBeInstanceOf(ApiError);
  expect(getState()).toMatchObject({ waiting: 1, online: false });
  mockSendRecord.mockResolvedValueOnce({ status: 201, body: {} });
  await syncNow();
  expect(getState()).toMatchObject({ waiting: 0, sent: 1, online: true });
  expect(getState().lastSync).not.toBeNull();
  expect(mockSendRecord.mock.calls[1][3]).toBe(mockSendRecord.mock.calls[0][3]);   // the same Idempotency-Key
});

test('a record waits behind an earlier one of the same target that is still on the phone', async () => {
  mockSendRecord.mockResolvedValueOnce({ status: 0, code: 'NETWORK' });
  await record(milk('42')).catch(() => undefined);
  const second = await record(milk('43')).catch(e => e);
  expect(isSavedOnPhone(second)).toBe(true);
  expect(mockSendRecord).toHaveBeenCalledTimes(1);   // not sent ahead of the first
  // A record for another target is sent at once
  mockSendRecord.mockResolvedValueOnce({ status: 201, body: {} });
  await record(note());
  expect(mockSendRecord).toHaveBeenCalledTimes(2);
});

test('keep mine resends with the server version; use the server\'s discards it into the 30-day list', async () => {
  mockSendRecord.mockResolvedValueOnce({ status: 0, code: 'NETWORK' });
  await record(milk('42')).catch(() => undefined);
  mockSendRecord.mockResolvedValueOnce({ status: 409, code: 'STALE_RECORD', message: '' });
  await syncNow();
  const [stale] = await records();
  expect(stale).toMatchObject({ state: 'attention', error: { code: 'STALE_RECORD' } });
  mockApiRequest.mockResolvedValueOnce({ sessions: [{ session: 'MORNING', groupId: 3, litres: 40, version: 'v2' }] });
  mockSendRecord.mockResolvedValueOnce({ status: 200, body: {} });
  await keepMineAndResend(stale);
  expect(mockApiRequest).toHaveBeenCalledWith('/api/v1/tenants/7/livestock/milk/2026-10-20');
  expect(mockSendRecord.mock.calls[2][2]).toEqual({ sessions: [{ session: 'MORNING', groupId: 3, litres: '42', baseVersion: 'v2' }] });
  expect((await records())[0].state).toBe('sent');

  mockSendRecord.mockResolvedValueOnce({ status: 409, code: 'STALE_RECORD', message: '' });
  await record(milk('41')).catch(() => undefined);   // refused online: not kept
  mockSendRecord.mockResolvedValueOnce({ status: 0, code: 'NETWORK' });
  await record(milk('41')).catch(() => undefined);
  mockSendRecord.mockResolvedValueOnce({ status: 409, code: 'STALE_RECORD', message: '' });
  await syncNow();
  const refused = (await records()).find(r => r.state === 'attention')!;
  await discardRecord(refused);
  expect(getState()).toMatchObject({ attention: 0, discarded: 1 });
});

test('another user on the same phone gets their own store; the first user\'s records are never sent as them', async () => {
  mockSendRecord.mockResolvedValue({ status: 0, code: 'NETWORK' });
  await record(note()).catch(() => undefined);
  const first = store;
  // Murugan signs out keeping his records; Selvi signs in
  await closeStore(false);
  const second = memoryStore();
  await openForUser(2, async () => second);
  mockSendRecord.mockReset();
  mockSendRecord.mockResolvedValue({ status: 201, body: {} });
  await syncNow();
  expect(mockSendRecord).not.toHaveBeenCalled();
  expect(getState()).toMatchObject({ userId: 2, waiting: 0 });
  expect((await first.records())[0]).toMatchObject({ userId: 1, state: 'waiting' });
  // Even a record of user 1 inside user 2's store is not sent
  await second.put({ ...(await first.records())[0] });
  await syncNow();
  expect(mockSendRecord).not.toHaveBeenCalled();
});

test('signing out can keep the records for the next sign-in or discard them', async () => {
  mockSendRecord.mockResolvedValue({ status: 0, code: 'NETWORK' });
  await record(note()).catch(() => undefined);
  await closeStore(false);
  expect(await store.records()).toHaveLength(1);
  await openForUser(1, async () => store);
  expect(getState().waiting).toBe(1);
  await closeStore(true);
  expect(await store.records()).toEqual([]);
});

test('losing a farm business clears its cached data and locks its waiting records', async () => {
  mockSendRecord.mockResolvedValue({ status: 0, code: 'NETWORK' });
  await record(note()).catch(() => undefined);
  await store.setCached('view:/api/v1/tenants/7/livestock/groups', [1], '2026-10-20T00:00:00Z');
  await store.setCached('view:/api/v1/tenants/9/livestock/groups', [2], '2026-10-20T00:00:00Z');
  await forgetTenant(7);
  expect(await store.getCached('view:/api/v1/tenants/7/livestock/groups')).toBeNull();
  expect(await store.getCached('view:/api/v1/tenants/9/livestock/groups')).not.toBeNull();
  expect((await records())[0]).toMatchObject({ state: 'locked' });
  expect(getState()).toMatchObject({ waiting: 0, locked: 1 });
});

test('quick entries saved before the outbox move into it once, with their ids, and are never added twice', async () => {
  await AsyncStorage.clear();
  const finance: FinanceContext = { summary: { monthRevenue: 0, monthCosts: 0, cashTotal: 0, receivableTotal: 0, payableTotal: 0 },
    categories: [{ id: 11, code: 'SEEDS', active: true }], cashAccountId: 5 };
  const entry = (id: string): FarmEntry => ({ id, kind: 'expense', amount: 600, description: 'Diesel', category: 'seed', date: '2026-10-20',
    createdAt: '2026-10-20T02:15:00Z', syncStatus: 'pending' });
  const key = scopeKey(1, 7);
  await saveEntry(key, entry('old-entry-1'));
  await saveEntry(key, entry('old-entry-2'));
  const pending = await loadEntries(key);
  expect(await adopt(pending.map(e => entryRecord(1, 7, e, finance)))).toBe(2);
  expect(await adopt(pending.map(e => entryRecord(1, 7, e, finance)))).toBe(0);
  const list = await records();
  expect(list.map(r => r.id).sort()).toEqual(['old-entry-1', 'old-entry-2']);
  expect(list[0]).toMatchObject({ kind: 'expense', state: 'waiting', path: '/api/v1/tenants/7/finance/bills',
    body: { financialAccountId: 5, lines: [{ categoryId: 11, amount: '600.00' }] } });
});
