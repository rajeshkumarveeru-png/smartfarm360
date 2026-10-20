const mockApiRequest = jest.fn();
jest.mock('../src/services/api', () => {
  const actual = jest.requireActual('../src/services/api');
  return { ...actual, apiRequest: (...args: unknown[]) => mockApiRequest(...args) };
});
jest.mock('expo-secure-store', () => ({ getItemAsync: jest.fn(), setItemAsync: jest.fn(), deleteItemAsync: jest.fn() }));

import { initialMarks, loadActivities, loadBalances, markAll, saveMuster, shiftDay, type Muster } from '../src/workforce/workforceView';

const muster: Muster = { date: '2026-10-20', workers: [
  { workerId: 1, workerName: 'Murugan', payType: 'DAILY', status: null, hours: null, costObjectId: null, costObjectName: null, locked: false },
  { workerId: 2, workerName: 'Lakshmi', payType: 'DAILY', status: 'PRESENT', hours: 6, costObjectId: 11, costObjectName: 'Samba', locked: false },
  { workerId: 3, workerName: 'Selvi', payType: 'MONTHLY', status: 'ABSENT', hours: null, costObjectId: null, costObjectName: null, locked: true }] };

beforeEach(() => mockApiRequest.mockReset());

test('marking everyone present leaves a day already in a posted pay run alone', () => {
  const marks = markAll(muster, initialMarks(muster), 'PRESENT', 11);
  expect(marks[1]).toEqual({ status: 'PRESENT', costObjectId: 11 });
  expect(marks[3]).toEqual({ status: 'ABSENT', costObjectId: null });
});

test('only changed, unlocked days are saved, keeping hours entered on the web and dropping the crop for days not worked', async () => {
  mockApiRequest.mockResolvedValue(muster);
  const marks = { ...initialMarks(muster), 1: { status: 'PRESENT' as const, costObjectId: 11 }, 2: { status: 'PAID_LEAVE' as const, costObjectId: 11 },
    3: { status: 'PRESENT' as const, costObjectId: null } };
  await saveMuster(7, muster, marks);
  expect(mockApiRequest).toHaveBeenCalledWith('/api/v1/tenants/7/workforce/attendance/2026-10-20', { method: 'PUT', body: { entries: [
    { workerId: 1, status: 'PRESENT', costObjectId: 11, hours: null },
    { workerId: 2, status: 'PAID_LEAVE', costObjectId: null, hours: 6 }] } });
});

test('nothing is sent when nothing changed', () => {
  expect(saveMuster(7, muster, initialMarks(muster))).toBeNull();
  expect(mockApiRequest).not.toHaveBeenCalled();
});

test('balances list the workers owed the most first, and only open crop cycles are offered', async () => {
  mockApiRequest.mockResolvedValueOnce([{ id: 1, name: 'B', payType: 'DAILY', owed: 100, advance: 0 }, { id: 2, name: 'A', payType: 'DAILY', owed: 900, advance: 50 },
    { id: 3, name: 'C', payType: 'DAILY', owed: 100, advance: 0 }]);
  expect((await loadBalances(7)).map(w => w.id)).toEqual([2, 1, 3]);
  mockApiRequest.mockResolvedValueOnce({ items: [{ name: 'Samba', status: 'GROWING', costObjectId: 11 }, { name: 'Old', status: 'CLOSED', costObjectId: 12 },
    { name: 'Draft', status: 'PLANNED', costObjectId: null }] });
  expect(await loadActivities(7)).toEqual([{ costObjectId: 11, name: 'Samba' }]);
});

test('days move across month ends', () => {
  expect(shiftDay('2026-10-31', 1)).toBe('2026-11-01');
  expect(shiftDay('2026-03-01', -1)).toBe('2026-02-28');
});
