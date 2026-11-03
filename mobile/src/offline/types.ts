/** The phone's outbox and local store (mobile offline design, sections 4 and 5). */

/** What the user recorded; the kind picks the label `mobile.sync.kind.<kind>`. */
export type RecordKind =
  | 'expense' | 'income' | 'attendance' | 'activity' | 'harvest' | 'observation'
  | 'milk' | 'heat' | 'treatment' | 'flockDay' | 'flockHeads'
  | 'pondDay' | 'reading' | 'sample' | 'compostLog' | 'manure' | 'processingStep';

/**
 * waiting: not sent yet, or the network failed; sent: confirmed by the server (kept 7 days); attention: refused by the
 * server; locked: belongs to a farm business the user is no longer a member of (can only be discarded); discarded: kept
 * 30 days for the user's own audit.
 */
export type RecordState = 'waiting' | 'sent' | 'attention' | 'locked' | 'discarded';

/** How to read the server's current version of a day record (N7). */
export type DayRef = { readPath: string; session?: string; groupId?: number };

export type RecordError = { status: number; code: string; message: string };

export type OutboxRecord = {
  /** UUIDv7, also the Idempotency-Key (N4). */
  id: string;
  userId: number;
  tenantId: number;
  kind: RecordKind;
  /** Records of one target (a day, a batch, a crop cycle) are sent in order (N5). */
  target: string;
  /** Data the user entered, shown next to the kind (for example a date and a group name). Never translated text. */
  summary: string;
  method: 'POST' | 'PUT';
  path: string;
  body: Record<string, unknown>;
  day?: DayRef;
  createdAt: string;
  state: RecordState;
  attempts: number;
  nextAt?: string;
  error?: RecordError;
  sentAt?: string;
  discardedAt?: string;
};

/** A cached server read ("last view" or reference list) with the time it was fetched (N3). */
export type Cached<T = unknown> = { value: T; asOf: string };

/** One store per signed-in user (N1, N10). */
export interface OfflineStore {
  records(): Promise<OutboxRecord[]>;
  put(record: OutboxRecord): Promise<void>;
  remove(id: string): Promise<void>;
  getCached<T>(key: string): Promise<Cached<T> | null>;
  setCached(key: string, value: unknown, asOf: string): Promise<void>;
  removeCachedPrefix(prefix: string): Promise<void>;
  /** Removes everything this user keeps on the phone. */
  clear(): Promise<void>;
}
