const mockApiRequest = jest.fn();
jest.mock('../src/services/api', () => {
  const actual = jest.requireActual('../src/services/api');
  return { ...actual, apiRequest: (...args: unknown[]) => mockApiRequest(...args) };
});
jest.mock('expo-secure-store', () => ({ getItemAsync: jest.fn(), setItemAsync: jest.fn(), deleteItemAsync: jest.fn() }));

import { loadStock, quantityLabel, type StockItem } from '../src/stock/stockView';

const urea: StockItem = { id: 3, name: 'Urea', kind: 'INPUT', baseUnit: 'kg', customUnitName: null, quantity: 348, value: 2165.33, unitCost: 6.2222,
  reorderLevel: 400, lowStock: true, packUnits: [{ name: 'bag', factor: 45 }] };

beforeEach(() => mockApiRequest.mockReset());

test('stock is loaded from the on-hand and alerts reports of the tenant', async () => {
  mockApiRequest.mockImplementation((path: string) => Promise.resolve(path.includes('on-hand')
    ? { items: [{ item: urea, locations: [] }], totalValue: '2165.33' }
    : { lowStock: [urea], expiringLots: [] }));
  const view = await loadStock(7);
  expect(mockApiRequest.mock.calls.map(c => c[0])).toEqual(['/api/v1/tenants/7/stock/reports/on-hand', '/api/v1/tenants/7/stock/reports/alerts']);
  expect(view.totalValue).toBe(2165.33);
  expect(view.lowStock[0].name).toBe('Urea');
});

test('quantities show the base unit in the chosen language and the first pack unit', () => {
  expect(quantityLabel('en', urea)).toBe('348 kg (7.73 bag)');
  expect(quantityLabel('ta', urea)).toBe('348 கிலோ (7.73 bag)');
  expect(quantityLabel('en', { ...urea, baseUnit: 'piece', customUnitName: 'bundle', packUnits: [], quantity: 5 })).toBe('5 bundle');
});
