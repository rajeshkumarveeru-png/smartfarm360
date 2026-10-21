const mockApiRequest = jest.fn();
jest.mock('../src/services/api', () => {
  const actual = jest.requireActual('../src/services/api');
  return { ...actual, apiRequest: (...args: unknown[]) => mockApiRequest(...args) };
});
jest.mock('expo-secure-store', () => ({ getItemAsync: jest.fn(), setItemAsync: jest.fn(), deleteItemAsync: jest.fn() }));

import { addLog, collectManure, isQuantity, loadActivities, loadBatches, loadProduce, recordOutput, spread } from '../src/organic/organicView';

beforeEach(() => mockApiRequest.mockReset());

test('only unfinished batches, farm produce and real activities are offered', async () => {
  mockApiRequest.mockResolvedValueOnce([{ id: 1, name: 'Pit 1', finishedOn: null }, { id: 2, name: 'Pit 0', finishedOn: '2026-05-01' }]);
  expect((await loadBatches(7)).map(b => b.name)).toEqual(['Pit 1']);
  mockApiRequest.mockResolvedValueOnce([{ id: 8, name: 'Vermicompost', kind: 'PRODUCE', active: true }, { id: 9, name: 'Urea', kind: 'INPUT', active: true }]);
  expect((await loadProduce(7)).map(i => i.name)).toEqual(['Vermicompost']);
  mockApiRequest.mockResolvedValueOnce([{ id: 1, kind: 'OVERHEAD', name: 'Farm overhead', active: true }, { id: 40, kind: 'CROP_CYCLE', name: 'Groundnut', active: true }]);
  expect((await loadActivities(7)).map(a => a.name)).toEqual(['Groundnut']);
  mockApiRequest.mockRejectedValueOnce(new Error('403'));
  expect(await loadActivities(7)).toEqual([]);
});

test('a log entry sends kg and notes only when entered', async () => {
  mockApiRequest.mockResolvedValue({});
  await addLog(7, 3, '2026-10-21', 'ADDED', ' 40 ', ' Dry leaves ');
  expect(mockApiRequest).toHaveBeenLastCalledWith('/api/v1/tenants/7/organic/batches/3/logs', { method: 'POST',
    body: { date: '2026-10-21', kind: 'ADDED', kg: '40', notes: 'Dry leaves' } });
  await addLog(7, 3, '2026-10-21', 'TURNED', '', '');
  expect(mockApiRequest.mock.calls[1][1].body).toEqual({ date: '2026-10-21', kind: 'TURNED', kg: null, notes: null });
});

test('output, manure and compost to a field carry an idempotency key and exact quantities', async () => {
  mockApiRequest.mockResolvedValue({ number: 'ITRF/2026-27/0001', costMoved: 6000 });
  await recordOutput(7, 3, '2026-10-15', 8, '1000', true, 'k1');
  expect(mockApiRequest).toHaveBeenLastCalledWith('/api/v1/tenants/7/organic/batches/3/outputs', { method: 'POST', headers: { 'Idempotency-Key': 'k1' },
    body: { date: '2026-10-15', itemId: 8, quantity: '1000', finish: true } });
  await collectManure(7, '2026-07-31', 20, 5, ' 2000 ', 'k2');
  expect(mockApiRequest).toHaveBeenLastCalledWith('/api/v1/tenants/7/stock/produce', { method: 'POST', headers: { 'Idempotency-Key': 'k2' },
    body: { docDate: '2026-07-31', costObjectId: 20, lines: [{ itemId: 5, quantity: '2000' }] } });
  const r = await spread(7, '2026-10-20', 8, '600', 30, 40, 'k3');
  expect(mockApiRequest).toHaveBeenLastCalledWith('/api/v1/tenants/7/stock/internal-transfers', { method: 'POST', headers: { 'Idempotency-Key': 'k3' },
    body: { date: '2026-10-20', itemId: 8, quantity: '600', fromCostObjectId: 30, toCostObjectId: 40 } });
  expect(r.costMoved).toBe(6000);
  expect(isQuantity('0')).toBe(false);
  expect(isQuantity('12.5')).toBe(true);
  expect(isQuantity('1.2345')).toBe(false);
});
