export type { Animal, MilkDay, Session } from './types';

/** A finance record the livestock endpoints created (a sale, stock use or wastage). */
export type RecordRefLike = { documentId: number; number: string; type: string; amount: number };
