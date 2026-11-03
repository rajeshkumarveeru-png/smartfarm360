import AsyncStorage from '@react-native-async-storage/async-storage';
import { uuid7 } from '../offline/uuid7';

export type EntryKind = 'expense' | 'income';
export type FarmEntry = {
  id: string;
  kind: EntryKind;
  amount: number;
  description: string;
  category: string;
  date: string;
  createdAt: string;
  syncStatus: 'local-only' | 'pending' | 'synced';
};

/** Device-only entries from before sign-in existed. They are claimed once by the first signed-in user and tenant. */
export const LEGACY_KEY = '@smartfarm360/entries/v1';

/**
 * Entries are stored per user and tenant so that switching farm business or account on a shared phone never shows
 * another tenant's records. Entries waiting to be posted move into the outbox (mobile offline design, section 4).
 */
export function scopeKey(userId: number, tenantId: number) {
  return `@smartfarm360/entries/v2/${userId}/${tenantId}`;
}

export async function loadEntries(key: string): Promise<FarmEntry[]> {
  try {
    const raw = await AsyncStorage.getItem(key);
    return raw ? (JSON.parse(raw) as FarmEntry[]) : [];
  } catch {
    return [];
  }
}

export async function saveEntry(key: string, entry: FarmEntry): Promise<void> {
  const entries = await loadEntries(key);
  await AsyncStorage.setItem(key, JSON.stringify([entry, ...entries]));
}

/** Removes entries (for example once they are recorded in the accounts). */
export async function removeEntries(key: string, ids: string[]): Promise<void> {
  if (!ids.length) return;
  const remaining = (await loadEntries(key)).filter(e => !ids.includes(e.id));
  await AsyncStorage.setItem(key, JSON.stringify(remaining));
}

/** Moves pre-sign-in entries into the given scope exactly once. Returns how many were moved. */
export async function claimLegacyEntries(key: string): Promise<number> {
  const legacy = await loadEntries(LEGACY_KEY);
  if (!legacy.length) return 0;
  const current = await loadEntries(key);
  const known = new Set(current.map(e => e.id));
  const merged = [...current, ...legacy.filter(e => !known.has(e.id))];
  await AsyncStorage.setItem(key, JSON.stringify(merged));
  await AsyncStorage.removeItem(LEGACY_KEY);
  return legacy.length;
}

/** A time-ordered client id (UUIDv7) for anything recorded on the phone; it is the record's Idempotency-Key (N4). */
export function newEntryId(): string {
  return uuid7();
}
