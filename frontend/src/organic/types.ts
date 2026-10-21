export const KINDS = ['COMPOST', 'VERMICOMPOST', 'LIQUID', 'OTHER'] as const;
export const LOG_KINDS = ['TURNED', 'WATERED', 'ADDED', 'CHECKED', 'WORMS', 'OTHER'] as const;

export type Batch = { id: number; costObjectId: number; name: string; kind: typeof KINDS[number]; farmId: number; farmName: string; startedOn: string;
  readyBy: string | null; finishedOn: string | null; status: 'MAKING' | 'READY' | 'FINISHED'; notes: string | null };
export type Log = { id: number; date: string; kind: typeof LOG_KINDS[number]; kg: number | null; temperature: number | null; moisture: 'DRY' | 'RIGHT' | 'WET' | null;
  notes: string | null };
export type Output = { documentId: number; number: string; date: string; itemId: number; itemName: string; quantity: number; unit: string; reversed: boolean };
export type Figures = { from: string; to: string; days: number | null; inputKg: number; output: number; outputUnit: 'kg' | 'litre'; yieldPercent: number | null;
  costs: number | null; movedOut: number | null; revenue: number | null; profit: number | null; costPerUnit: number | null; inStock: number };
export type BatchReport = { batch: Batch; period: Figures; whole: Figures | null };
