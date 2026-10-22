import * as SQLite from 'expo-sqlite';
import type { Cached, OfflineStore, OutboxRecord } from './types';

/** One database file per signed-in user (N1, N10): another user's records are never opened, so never sent. */
export const databaseName = (userId: number) => `sf360-user-${userId}.db`;

export async function sqliteStore(userId: number): Promise<OfflineStore> {
  const name = databaseName(userId);
  const db = await SQLite.openDatabaseAsync(name);
  await db.execAsync(`
    PRAGMA journal_mode = WAL;
    CREATE TABLE IF NOT EXISTS outbox (id TEXT PRIMARY KEY NOT NULL, created_at TEXT NOT NULL, record TEXT NOT NULL);
    CREATE TABLE IF NOT EXISTS cache (key TEXT PRIMARY KEY NOT NULL, as_of TEXT NOT NULL, value TEXT NOT NULL);`);
  return {
    async records() {
      const rows = await db.getAllAsync<{ record: string }>('SELECT record FROM outbox ORDER BY created_at, id');
      return rows.map(r => JSON.parse(r.record) as OutboxRecord);
    },
    async put(record) {
      await db.runAsync('INSERT OR REPLACE INTO outbox (id, created_at, record) VALUES (?, ?, ?)', record.id, record.createdAt, JSON.stringify(record));
    },
    async remove(id) { await db.runAsync('DELETE FROM outbox WHERE id = ?', id); },
    async getCached<T>(key: string) {
      const row = await db.getFirstAsync<{ as_of: string; value: string }>('SELECT as_of, value FROM cache WHERE key = ?', key);
      return row ? ({ value: JSON.parse(row.value) as T, asOf: row.as_of } as Cached<T>) : null;
    },
    async setCached(key, value, asOf) {
      await db.runAsync('INSERT OR REPLACE INTO cache (key, as_of, value) VALUES (?, ?, ?)', key, asOf, JSON.stringify(value));
    },
    async removeCachedPrefix(prefix) {
      await db.runAsync("DELETE FROM cache WHERE substr(key, 1, length(?)) = ?", prefix, prefix);
    },
    async clear() {
      await db.execAsync('DELETE FROM outbox; DELETE FROM cache;');
    },
  };
}
