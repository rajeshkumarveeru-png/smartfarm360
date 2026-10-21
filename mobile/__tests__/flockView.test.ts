const mockApiRequest = jest.fn();
jest.mock('../src/services/api', () => {
  const actual = jest.requireActual('../src/services/api');
  return { ...actual, apiRequest: (...args: unknown[]) => mockApiRequest(...args) };
});
jest.mock('expo-secure-store', () => ({ getItemAsync: jest.fn(), setItemAsync: jest.fn(), deleteItemAsync: jest.fn() }));

import { dayFormValid, isKg, isWhole, loadFlocks, recordHeads, saveDay, toDayForm, type BatchDay } from '../src/livestock/flockView';

const day: BatchDay = { day: '2026-10-21', deaths: 2, culls: 0, eggsGood: 850, eggsCracked: 10, eggsBroken: 0, feedItemId: 8, feedKg: 110,
  deathCause: 'Heat', weightBirds: 20, weightKg: 39.5, notes: 'Shed 1', headsAtEnd: 994, warnings: [] };

beforeEach(() => mockApiRequest.mockReset());

test('only open batches and herds whose module is on are listed', async () => {
  mockApiRequest.mockResolvedValue([
    { id: 1, name: 'Milking herd', species: 'CATTLE', purpose: 'MILKING', animals: 4, closed: false },
    { id: 2, name: 'Layers', species: 'CHICKEN', purpose: 'LAYER', animals: 1000, closed: false },
    { id: 3, name: 'Broilers May', species: 'CHICKEN', purpose: 'BROILER', animals: 0, closed: true },
    { id: 4, name: 'Goat herd', species: 'GOAT', purpose: 'BREEDING_HERD', animals: 22, closed: false }]);
  expect((await loadFlocks(7, ['POULTRY'])).map(f => f.name)).toEqual(['Layers']);
  expect((await loadFlocks(7, ['POULTRY', 'GOAT_SHEEP'])).map(f => f.name)).toEqual(['Layers', 'Goat herd']);
});

test('saving the day sends the numbers, keeps kg as a string and keeps what was entered on the web', async () => {
  mockApiRequest.mockResolvedValue(day);
  const form = { ...toDayForm(day), deaths: '3', feedKg: ' 112.5 ' };
  await saveDay(7, 2, '2026-10-21', form, day);
  expect(mockApiRequest).toHaveBeenCalledWith('/api/v1/tenants/7/livestock/flocks/2/days/2026-10-21', { method: 'PUT', body: {
    deathCause: 'Heat', weightBirds: 20, weightKg: 39.5, notes: 'Shed 1', deaths: 3, culls: null, eggsGood: 850, eggsCracked: 10, eggsBroken: null,
    feedItemId: 8, feedKg: '112.5' } });
});

test('the day form needs whole numbers and the feed together with its kg', () => {
  const form = toDayForm(day);
  expect(dayFormValid(form)).toBe(true);
  expect(dayFormValid({ ...form, deaths: '2.5' })).toBe(false);
  expect(dayFormValid({ ...form, feedItemId: null })).toBe(false);
  expect(dayFormValid({ ...form, feedItemId: null, feedKg: '' })).toBe(true);
  expect(isWhole('')).toBe(true);
  expect(isKg('1.2345')).toBe(false);
});

test('a herd head change is a movement of whole heads', async () => {
  mockApiRequest.mockResolvedValue({});
  await recordHeads(7, 4, '2026-10-21', 'BORN', ' 2 ');
  expect(mockApiRequest).toHaveBeenCalledWith('/api/v1/tenants/7/livestock/flocks/4/movements', { method: 'POST', body: { date: '2026-10-21', kind: 'BORN', heads: 2 } });
});
