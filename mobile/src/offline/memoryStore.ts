import type { Cached, OfflineStore, OutboxRecord } from './types';

/** An in-memory store, for tests and for phones where the database cannot be opened. */
export function memoryStore(): OfflineStore {
  const records = new Map<string, OutboxRecord>();
  const cache = new Map<string, Cached>();
  const copy = <T>(v: T): T => JSON.parse(JSON.stringify(v)) as T;
  return {
    async records() { return [...records.values()].map(copy); },
    async put(record) { records.set(record.id, copy(record)); },
    async remove(id) { records.delete(id); },
    async getCached<T>(key: string) { const c = cache.get(key); return c ? (copy(c) as Cached<T>) : null; },
    async setCached(key, value, asOf) { cache.set(key, copy({ value, asOf })); },
    async removeCachedPrefix(prefix) { for (const k of [...cache.keys()]) if (k.startsWith(prefix)) cache.delete(k); },
    async clear() { records.clear(); cache.clear(); },
  };
}
