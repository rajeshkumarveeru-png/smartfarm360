import AsyncStorage from '@react-native-async-storage/async-storage';

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
const STORAGE_KEY = '@smartfarm360/entries/v1';
export async function loadEntries(): Promise<FarmEntry[]> {
  try {
    const raw = await AsyncStorage.getItem(STORAGE_KEY);
    return raw ? JSON.parse(raw) as FarmEntry[] : [];
  } catch {
    return [];
  }
}
export async function saveEntry(entry: FarmEntry): Promise<void> {
  const entries = await loadEntries();
  await AsyncStorage.setItem(STORAGE_KEY, JSON.stringify([entry, ...entries]));
}
export async function clearEntries(): Promise<void> {
  await AsyncStorage.removeItem(STORAGE_KEY);
}
