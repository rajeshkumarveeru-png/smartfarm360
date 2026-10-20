const mockApiRequest = jest.fn();
jest.mock('../src/services/api', () => {
  const actual = jest.requireActual('../src/services/api');
  return { ...actual, apiRequest: (...args: unknown[]) => mockApiRequest(...args) };
});
jest.mock('expo-secure-store', () => ({ getItemAsync: jest.fn(), setItemAsync: jest.fn(), deleteItemAsync: jest.fn() }));

import { isQuantity, loadActiveCycles, loadInputs, recordActivity, recordHarvest, stageLine, type Cycle } from '../src/crop/cropView';

const cycle = (id: number, status: Cycle['status'], sown: string | null): Cycle => ({ id, name: `C${id}`, cropId: 1, cropName: 'Paddy', farmName: 'F',
  parcelName: 'P', status, plannedSowingDate: '2026-07-01', actualSowingDate: sown, daysSinceSowing: sown ? 30 : null, areaAcres: 2 });

beforeEach(() => mockApiRequest.mockReset());

test('only cycles still in the field are shown, soonest sown first', async () => {
  mockApiRequest.mockResolvedValueOnce({ items: [cycle(1, 'CLOSED', '2026-01-01'), cycle(2, 'GROWING', '2026-07-05'), cycle(3, 'PLANNED', null),
    cycle(4, 'HARVESTING', '2026-06-01'), cycle(5, 'CANCELLED', null)] });
  const list = await loadActiveCycles(7);
  expect(mockApiRequest.mock.calls[0][0]).toBe('/api/v1/tenants/7/cultivation-cycles?size=100');
  expect(list.map(c => c.id)).toEqual([4, 3, 2]);
});

test('field work and harvests are sent with the idempotency key and exact quantity strings', async () => {
  mockApiRequest.mockResolvedValue({ warnings: [] });
  await recordActivity(7, { cycleId: 4, activityType: 'FERTILIZER', activityDate: '2026-07-05', inputs: [{ itemId: 9, quantity: '30.5' }] }, 'key-1');
  expect(mockApiRequest).toHaveBeenLastCalledWith('/api/v1/tenants/7/activities', { method: 'POST', headers: { 'Idempotency-Key': 'key-1' },
    body: { cycleId: 4, activityType: 'FERTILIZER', activityDate: '2026-07-05', notes: null, inputs: [{ itemId: 9, quantity: '30.5' }] } });
  await recordHarvest(7, { cycleId: 4, harvestDate: '2026-10-01', quantity: '40', unit: 'quintal' }, 'key-2');
  expect(mockApiRequest.mock.calls[1][1].headers).toEqual({ 'Idempotency-Key': 'key-2' });
});

test('inputs offered on the phone are items in stock that need no lot', async () => {
  mockApiRequest.mockResolvedValueOnce([{ id: 1, name: 'Urea', quantity: 10, trackLots: false }, { id: 2, name: 'Seed', quantity: 5, trackLots: true },
    { id: 3, name: 'Empty', quantity: 0, trackLots: false }]);
  expect((await loadInputs(7)).map(i => i.name)).toEqual(['Urea']);
});

test('stage lines are translated and quantities are checked', () => {
  expect(stageLine('en', cycle(2, 'GROWING', '2026-07-05'))).toBe('Growing · 30 days since sowing');
  expect(stageLine('ta', cycle(3, 'PLANNED', null))).toBe('திட்டமிடப்பட்டது');
  expect(isQuantity('12.5')).toBe(true);
  expect(isQuantity('0')).toBe(false);
});
