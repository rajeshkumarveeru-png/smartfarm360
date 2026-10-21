const mockApiRequest = jest.fn();
jest.mock('../src/services/api', () => {
  const actual = jest.requireActual('../src/services/api');
  return { ...actual, apiRequest: (...args: unknown[]) => mockApiRequest(...args) };
});
jest.mock('expo-secure-store', () => ({ getItemAsync: jest.fn(), setItemAsync: jest.fn(), deleteItemAsync: jest.fn() }));

import { addStep, isOptional, isQuantity, loadBatches, loadLots, loadSources, moveIn, recordOutput } from '../src/processing/processingView';

beforeEach(() => mockApiRequest.mockReset());

test('running batches, open lots and producing activities are offered', async () => {
  mockApiRequest.mockResolvedValueOnce([{ id: 1, code: 'RM-1', finishedOn: null }, { id: 2, code: 'RM-0', finishedOn: '2026-09-01' }]);
  expect((await loadBatches(7)).map(b => b.code)).toEqual(['RM-1']);
  mockApiRequest.mockResolvedValueOnce([{ id: 4, lotNo: 'SAMBA-2026-H1', quantity: 2000 }, { id: 5, lotNo: 'OLD', quantity: 0 }]);
  expect((await loadLots(7, 2)).map(l => l.lotNo)).toEqual(['SAMBA-2026-H1']);
  expect(mockApiRequest).toHaveBeenLastCalledWith('/api/v1/tenants/7/stock/items/2/lots');
  mockApiRequest.mockResolvedValueOnce([{ id: 1, kind: 'OVERHEAD', name: 'Farm overhead', active: true }, { id: 7, kind: 'CROP_CYCLE', name: 'Samba', active: true },
    { id: 30, kind: 'PROCESSING_BATCH', name: 'RM-1', active: true }]);
  expect((await loadSources(7)).map(a => a.name)).toEqual(['Samba']);
});

test('a step sends only what was entered', async () => {
  mockApiRequest.mockResolvedValue({});
  await addStep(7, 3, '2026-10-02', 'DRYING', ' 2000 ', '1920', '17', '');
  expect(mockApiRequest).toHaveBeenLastCalledWith('/api/v1/tenants/7/processing/batches/3/steps', { method: 'POST',
    body: { date: '2026-10-02', kind: 'DRYING', kgIn: '2000', kgOut: '1920', moistureBefore: '17', moistureAfter: null } });
});

test('output and own produce in carry an idempotency key, lots and exact quantities', async () => {
  mockApiRequest.mockResolvedValue({ lotNo: 'RM-1-1', number: 'ITRF/1', costMoved: 30000 });
  await recordOutput(7, 3, '2026-10-05', 'WASTE', 5, ' Husk ', '340', true, 'k1');
  expect(mockApiRequest).toHaveBeenLastCalledWith('/api/v1/tenants/7/processing/batches/3/outputs', { method: 'POST', headers: { 'Idempotency-Key': 'k1' },
    body: { date: '2026-10-05', role: 'WASTE', itemId: null, description: 'Husk', quantity: '340', finish: true } });
  await recordOutput(7, 3, '2026-10-05', 'MAIN', 5, '', '1300', false, 'k2');
  expect(mockApiRequest.mock.calls[1][1].body).toEqual({ date: '2026-10-05', role: 'MAIN', itemId: 5, description: null, quantity: '1300', finish: false });
  const r = await moveIn(7, '2026-10-01', 2, '2000', 'SAMBA-2026-H1', 9, 30, 'k3');
  expect(mockApiRequest).toHaveBeenLastCalledWith('/api/v1/tenants/7/stock/internal-transfers', { method: 'POST', headers: { 'Idempotency-Key': 'k3' },
    body: { date: '2026-10-01', itemId: 2, quantity: '2000', lotNo: 'SAMBA-2026-H1', fromCostObjectId: 9, toCostObjectId: 30 } });
  expect(r.costMoved).toBe(30000);
  expect(isQuantity('0')).toBe(false);
  expect(isOptional('')).toBe(true);
  expect(isOptional('13.5')).toBe(true);
  expect(isOptional('x')).toBe(false);
});
