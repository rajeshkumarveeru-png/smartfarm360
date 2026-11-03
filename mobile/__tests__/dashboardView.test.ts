const mockApiRequest = jest.fn();
jest.mock('../src/services/api', () => {
  const actual = jest.requireActual('../src/services/api');
  return { ...actual, apiRequest: (...args: unknown[]) => mockApiRequest(...args) };
});
jest.mock('expo-secure-store', () => ({ getItemAsync: jest.fn(), setItemAsync: jest.fn(), deleteItemAsync: jest.fn() }));
jest.mock('@react-native-async-storage/async-storage', () => require('@react-native-async-storage/async-storage/jest/async-storage-mock'));

import AsyncStorage from '@react-native-async-storage/async-storage';
import { attentionText, loadDashboard } from '../src/dashboard/dashboardView';

const dashboard = { asOf: '2026-07-31', money: { cashTotal: 130000, receivableTotal: 40000, overdueReceivables: 0, payableTotal: 50000, monthRevenue: 90000,
  monthCosts: 8000, yearNetProfit: 30000 }, attention: [{ code: 'WAGES_OWED', count: 1, amount: 4000, detail: null }], trend: { months: [] }, crops: [] };

beforeEach(async () => { mockApiRequest.mockReset(); await AsyncStorage.clear(); });

test('the dashboard is fetched for the tenant and a copy is kept for offline use', async () => {
  mockApiRequest.mockResolvedValueOnce(dashboard);
  const online = await loadDashboard(7, 'scope-1', '2026-07-31', () => new Date('2026-07-31T04:00:00Z'));
  expect(mockApiRequest).toHaveBeenCalledWith('/api/v1/tenants/7/dashboard?asOf=2026-07-31');
  expect(online?.offline).toBe(false);
  expect(online?.dashboard.money?.receivableTotal).toBe(40000);

  mockApiRequest.mockRejectedValueOnce(new Error('offline'));
  const offline = await loadDashboard(7, 'scope-1', '2026-08-01');
  expect(offline?.offline).toBe(true);
  expect(offline?.fetchedAt).toBe('2026-07-31T04:00:00.000Z');
  expect(offline?.dashboard.attention[0].code).toBe('WAGES_OWED');
});

test('copies are kept per user and tenant, and nothing shows offline without one', async () => {
  mockApiRequest.mockResolvedValueOnce(dashboard);
  await loadDashboard(7, 'scope-1', '2026-07-31');
  mockApiRequest.mockRejectedValueOnce(new Error('offline'));
  expect(await loadDashboard(8, 'scope-2', '2026-07-31')).toBeNull();
});

test('attention lines use the shared catalog text in both languages', () => {
  const a = { code: 'WAGES_OWED', count: 1, amount: 4000, detail: null };
  expect(attentionText('en', a)).toBe('Wages owed to workers: ₹4,000');
  expect(attentionText('ta', a)).toContain('₹4,000');
  expect(attentionText('ta', a)).not.toBe(attentionText('en', a));
});
