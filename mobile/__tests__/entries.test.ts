jest.mock('@react-native-async-storage/async-storage', () => require('@react-native-async-storage/async-storage/jest/async-storage-mock'));

import AsyncStorage from '@react-native-async-storage/async-storage';
import { claimLegacyEntries, FarmEntry, LEGACY_KEY, loadEntries, newEntryId, saveEntry, scopeKey } from '../src/storage/entries';

const entry = (id: string): FarmEntry => ({ id, kind: 'expense', amount: 10, description: 'd', category: 'seed', date: '2026-10-20', createdAt: '2026-10-20T00:00:00Z', syncStatus: 'local-only' });

beforeEach(async () => { await AsyncStorage.clear(); });

test('entries are kept separately per user and tenant', async () => {
  await saveEntry(scopeKey(1, 10), entry('a'));
  await saveEntry(scopeKey(1, 20), entry('b'));
  await saveEntry(scopeKey(2, 10), entry('c'));
  expect((await loadEntries(scopeKey(1, 10))).map(e => e.id)).toEqual(['a']);
  expect((await loadEntries(scopeKey(1, 20))).map(e => e.id)).toEqual(['b']);
  expect((await loadEntries(scopeKey(2, 10))).map(e => e.id)).toEqual(['c']);
});

test('pre-sign-in entries are claimed once, without duplicates', async () => {
  await AsyncStorage.setItem(LEGACY_KEY, JSON.stringify([entry('old1'), entry('old2')]));
  await saveEntry(scopeKey(1, 10), entry('old1'));
  expect(await claimLegacyEntries(scopeKey(1, 10))).toBe(2);
  expect((await loadEntries(scopeKey(1, 10))).map(e => e.id).sort()).toEqual(['old1', 'old2']);
  expect(await claimLegacyEntries(scopeKey(1, 20))).toBe(0); // nothing left for a second tenant
  expect(await AsyncStorage.getItem(LEGACY_KEY)).toBeNull();
});

test('entry ids are unique', () => {
  const ids = new Set(Array.from({ length: 1000 }, () => newEntryId()));
  expect(ids.size).toBe(1000);
});
