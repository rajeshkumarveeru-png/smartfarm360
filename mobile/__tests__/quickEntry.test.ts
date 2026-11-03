const mockApiRequest = jest.fn();
jest.mock('../src/services/api', () => {
  const actual = jest.requireActual('../src/services/api');
  return { ...actual, apiRequest: (...args: unknown[]) => mockApiRequest(...args) };
});
jest.mock('expo-secure-store', () => ({ getItemAsync: jest.fn(), setItemAsync: jest.fn(), deleteItemAsync: jest.fn() }));

import { ApiError } from '../src/services/api';
import { CATEGORY_CODES, FinanceContext, postEntry, syncPending } from '../src/finance/quickEntry';
import type { FarmEntry } from '../src/storage/entries';

const finance: FinanceContext = {
  summary: { monthRevenue: 0, monthCosts: 0, cashTotal: 0, receivableTotal: 0, payableTotal: 0 },
  categories: [{ id: 11, code: 'SEEDS', active: true }, { id: 12, code: 'MILK_SALE', active: true }, { id: 13, code: 'OTHER_EXPENSE', active: true }],
  cashAccountId: 5,
};
const entry = (id: string, kind: 'expense' | 'income', category: string, amount = 120.5): FarmEntry => ({
  id, kind, amount, description: 'd', category, date: '2026-07-02', createdAt: '2026-07-02T00:00:00Z', syncStatus: 'pending',
});

beforeEach(() => mockApiRequest.mockReset());

test('every mobile category maps to a system finance category', () => {
  expect(Object.values(CATEGORY_CODES.expense)).toEqual(expect.arrayContaining(['SEEDS', 'LABOUR', 'OTHER_EXPENSE']));
  expect(CATEGORY_CODES.income.milk).toBe('MILK_SALE');
});

test('an expense is posted as a cash bill with the entry id as idempotency key and an exact amount string', async () => {
  mockApiRequest.mockResolvedValueOnce({});
  await postEntry(3, entry('e-1', 'expense', 'seed'), finance);
  const [url, init] = mockApiRequest.mock.calls[0];
  expect(url).toBe('/api/v1/tenants/3/finance/bills');
  expect(init.headers['Idempotency-Key']).toBe('e-1');
  expect(init.body).toMatchObject({ financialAccountId: 5, lines: [{ categoryId: 11, amount: '120.50' }] });
});

test('income goes to sales', async () => {
  mockApiRequest.mockResolvedValueOnce({});
  await postEntry(3, entry('e-2', 'income', 'milk'), finance);
  expect(mockApiRequest.mock.calls[0][0]).toBe('/api/v1/tenants/3/finance/sales');
});

test('offline retries stop at the first network failure and keep rejected entries', async () => {
  mockApiRequest
    .mockResolvedValueOnce({})                                          // a: recorded
    .mockRejectedValueOnce(new ApiError(409, 'PERIOD_LOCKED', ''))      // b: rejected, stays on phone
    .mockRejectedValueOnce(new ApiError(0, 'NETWORK', ''));             // c: offline, stop
  const done = await syncPending(3, [entry('a', 'expense', 'seed'), entry('b', 'expense', 'seed'), entry('c', 'expense', 'seed'), entry('d', 'expense', 'seed')], finance);
  expect(done).toEqual(['a']);
  expect(mockApiRequest).toHaveBeenCalledTimes(3);
});
