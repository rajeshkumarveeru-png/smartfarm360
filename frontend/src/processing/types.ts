export const KINDS = ['GRADING', 'DRYING', 'MILLING', 'PRESSING', 'PACKING', 'OTHER'] as const;
export const STEP_KINDS = ['CLEANING', 'DRYING', 'GRADING', 'MILLING', 'PRESSING', 'PACKING', 'OTHER'] as const;
export const ROLES = ['MAIN', 'BYPRODUCT', 'WASTE'] as const;

export type Batch = { id: number; costObjectId: number; code: string; name: string; kind: typeof KINDS[number]; farmId: number; farmName: string; mainItemId: number;
  mainItemName: string; startedOn: string; expectedEnd: string | null; finishedOn: string | null; status: 'RUNNING' | 'OUTPUT' | 'FINISHED'; notes: string | null };
export type Step = { id: number; date: string; kind: typeof STEP_KINDS[number]; kgIn: number | null; kgOut: number | null; lossKg: number | null;
  moistureBefore: number | null; moistureAfter: number | null; grade: string | null; notes: string | null };
export type Output = { id: number; date: string; role: typeof ROLES[number]; itemId: number | null; itemName: string | null; description: string | null; quantity: number;
  unit: string; kg: number | null; grade: string | null; lotNo: string | null; documentId: number | null; documentNumber: string | null; reversed: boolean };
export type OutputYield = { role: typeof ROLES[number]; itemId: number | null; name: string; kg: number; yieldPercent: number | null };
export type Figures = { from: string; to: string; days: number | null; inputKg: number; outputs: OutputYield[]; outputKg: number; lossKg: number | null;
  lossPercent: number | null; mainKg: number; costs: number | null; revenue: number | null; profit: number | null; byproductSales: number | null;
  mainCostPerKg: number | null; rawCostPerKg: number | null };
export type BatchReport = { batch: Batch; period: Figures; whole: Figures | null };

export type Origin = { kind: 'BATCH' | 'PRODUCED' | 'BOUGHT' | 'OTHER'; date: string | null; documentNumber: string | null; costObjectId: number | null;
  activityName: string | null; activityKind: string | null; partyName: string | null; batchCode: string | null };
export type Sale = { date: string; documentNumber: string; partyName: string | null; quantity: number };
export type LotUse = { batchCode: string; batchName: string; batchId: number; quantity: number; outputs: TracedLot[] };
export type TracedLot = { lotId: number; lotNo: string; itemId: number; itemName: string; baseUnit: string; origin: Origin | null; inputs: TracedLot[];
  usedIn: LotUse[]; sales: Sale[] };
