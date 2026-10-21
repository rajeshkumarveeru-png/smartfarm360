const mockApiRequest = jest.fn();
jest.mock('../src/services/api', () => {
  const actual = jest.requireActual('../src/services/api');
  return { ...actual, apiRequest: (...args: unknown[]) => mockApiRequest(...args) };
});
jest.mock('expo-secure-store', () => ({ getItemAsync: jest.fn(), setItemAsync: jest.fn(), deleteItemAsync: jest.fn() }));

import { flags, isLitres, milkingGroups, recordTreatment, saveSession, type Animal } from '../src/livestock/livestockView';

const animal = (over: Partial<Animal['breeding']> = {}, groupId = 1): Animal => ({ id: 5, tag: 'L-01', name: 'Lakshmi', sex: 'FEMALE', groupId, groupName: 'Milking herd',
  breeding: { repro: 'OPEN', checkDueFrom: null, expectedCalving: null, dryOffDue: null, inMilk: true, withheldUntil: null, ...over } });

beforeEach(() => mockApiRequest.mockReset());

test('a session with per-animal litres sends the yields; without them, the group total, as exact strings', async () => {
  mockApiRequest.mockResolvedValue({});
  await saveSession(7, '2026-07-22', 'MORNING', 1, '', { 5: '5.5', 6: '' });
  expect(mockApiRequest).toHaveBeenLastCalledWith('/api/v1/tenants/7/livestock/milk/2026-07-22', { method: 'PUT',
    body: { sessions: [{ session: 'MORNING', groupId: 1, yields: [{ animalId: 5, litres: '5.5' }] }] } });
  await saveSession(7, '2026-07-22', 'EVENING', 1, ' 18 ', {});
  expect(mockApiRequest.mock.calls[1][1].body).toEqual({ sessions: [{ session: 'EVENING', groupId: 1, litres: '18', yields: [] }] });
});

test('a treatment carries its withdrawal days and the idempotency key', async () => {
  mockApiRequest.mockResolvedValue({});
  await recordTreatment(7, 5, '2026-07-20', ' Mastitis ', '4', 'key-1');
  expect(mockApiRequest).toHaveBeenCalledWith('/api/v1/tenants/7/livestock/health', { method: 'POST', headers: { 'Idempotency-Key': 'key-1' },
    body: { animalId: 5, kind: 'TREATMENT', date: '2026-07-20', description: 'Mastitis', withdrawalDays: 4 } });
});

test('flags show withheld milk and what is due soon', () => {
  expect(flags(animal({ withheldUntil: '2026-07-23' }), '2026-07-22')).toEqual(['WITHHELD']);
  expect(flags(animal({ withheldUntil: '2026-07-21' }), '2026-07-22')).toEqual([]);
  expect(flags(animal({ repro: 'INSEMINATED', checkDueFrom: '2026-09-08' }), '2026-09-03')).toEqual(['CHECK']);
  expect(flags(animal({ repro: 'PREGNANT', expectedCalving: '2027-04-19', dryOffDue: '2027-02-18' }), '2027-02-15')).toEqual(['DRY_OFF']);
  expect(flags(animal({ repro: 'PREGNANT', expectedCalving: '2027-04-19', inMilk: false }), '2027-04-10')).toEqual(['CALVING']);
});

test('milked groups and litres validation', () => {
  const groups = [{ id: 1, name: 'Milking herd', purpose: 'MILKING', active: true }, { id: 2, name: 'Calves', purpose: 'CALF', active: true },
    { id: 3, name: 'Mixed', purpose: 'OTHER', active: true }];
  expect(milkingGroups(groups, [animal({}, 3)]).map(g => g.id)).toEqual([1, 3]);
  expect(isLitres('12.5')).toBe(true);
  expect(isLitres('1.255')).toBe(false);
  expect(isLitres('-1')).toBe(false);
});
