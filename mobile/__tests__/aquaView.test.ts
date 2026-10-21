const mockApiRequest = jest.fn();
jest.mock('../src/services/api', () => {
  const actual = jest.requireActual('../src/services/api');
  return { ...actual, apiRequest: (...args: unknown[]) => mockApiRequest(...args) };
});
jest.mock('expo-secure-store', () => ({ getItemAsync: jest.fn(), setItemAsync: jest.fn(), deleteItemAsync: jest.fn() }));

import { isKg, isMeasure, isWhole, loadSpecies, recordReading, recordSample, saveDay, type PondDay } from '../src/aquaculture/aquaView';

const loaded: PondDay = { day: '2026-10-21', feeds: [{ itemId: 8, kg: 20 }], deaths: [{ species: 'ROHU', otherName: null, count: 2, cause: 'Birds' }], notes: 'Rain' };

beforeEach(() => mockApiRequest.mockReset());

test('stocked species are listed once, without reversed stockings', async () => {
  mockApiRequest.mockResolvedValue([
    { species: 'ROHU', otherName: null, reversed: false }, { species: 'ROHU', otherName: null, reversed: false },
    { species: 'CATLA', otherName: null, reversed: true }, { species: 'OTHER', otherName: 'Koi', reversed: false }]);
  expect(await loadSpecies(7, 4)).toEqual([{ species: 'ROHU', otherName: null }, { species: 'OTHER', otherName: 'Koi' }]);
  expect(mockApiRequest).toHaveBeenCalledWith('/api/v1/tenants/7/aquaculture/cycles/4/stockings');
});

test('saving from the phone keeps the day\'s other entries, replaces the same feed and adds deaths seen', async () => {
  mockApiRequest.mockResolvedValue(loaded);
  await saveDay(7, 4, '2026-10-21', loaded, { itemId: 8, kg: ' 25.5 ' }, { species: { species: 'CATLA', otherName: null }, count: ' 3 ' });
  expect(mockApiRequest).toHaveBeenCalledWith('/api/v1/tenants/7/aquaculture/cycles/4/days/2026-10-21', { method: 'PUT', body: {
    feeds: [{ itemId: 8, kg: '25.5' }],
    deaths: [{ species: 'ROHU', otherName: null, count: 2, cause: 'Birds' }, { species: 'CATLA', otherName: null, count: 3, cause: null }],
    notes: 'Rain' } });
  await saveDay(7, 4, '2026-10-21', loaded, { itemId: 9, kg: '5' }, null);
  expect(mockApiRequest.mock.calls[1][1].body.feeds).toEqual([{ itemId: 8, kg: '20' }, { itemId: 9, kg: '5' }]);
});

test('a water reading sends only the values entered, as strings, with the time', async () => {
  mockApiRequest.mockResolvedValue({ id: 1, outOfLimits: ['DISSOLVED_OXYGEN'] });
  const now = new Date('2026-10-21T00:30:00Z');
  await recordReading(7, 2, { dissolvedOxygen: ' 3.2 ', ph: '', temperature: '29', ammonia: '' }, now);
  expect(mockApiRequest).toHaveBeenCalledWith('/api/v1/tenants/7/aquaculture/ponds/2/readings', { method: 'POST', body: {
    takenAt: '2026-10-21T00:30:00.000Z', dissolvedOxygen: '3.2', ph: null, temperature: '29', ammonia: null } });
});

test('samples and validation', async () => {
  mockApiRequest.mockResolvedValue({ averageGrams: 600 });
  await recordSample(7, 4, '2026-08-01', '50', '30');
  expect(mockApiRequest).toHaveBeenCalledWith('/api/v1/tenants/7/aquaculture/cycles/4/samples', { method: 'POST', body: { date: '2026-08-01', fishWeighed: 50, kg: '30' } });
  expect(isWhole('0')).toBe(false);
  expect(isWhole('12')).toBe(true);
  expect(isKg('1.2345')).toBe(false);
  expect(isKg('25.5')).toBe(true);
  expect(isMeasure('')).toBe(true);
  expect(isMeasure('7.85')).toBe(true);
  expect(isMeasure('abc')).toBe(false);
});
