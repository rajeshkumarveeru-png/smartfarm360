export type PayType = 'DAILY' | 'MONTHLY' | 'PIECE';
export const PAY_TYPES: PayType[] = ['DAILY', 'MONTHLY', 'PIECE'];
export type AttendanceStatus = 'PRESENT' | 'HALF_DAY' | 'ABSENT' | 'PAID_LEAVE' | 'UNPAID_LEAVE';
export const ATTENDANCE: AttendanceStatus[] = ['PRESENT', 'HALF_DAY', 'ABSENT', 'PAID_LEAVE', 'UNPAID_LEAVE'];

export type Rate = { effectiveFrom: string; rate: number; pieceUnit: string | null };
export type Worker = {
  id: number; partyId: number; name: string; phone: string | null; village: string | null; payType: PayType; rate: number | null; pieceUnit: string | null;
  joinedOn: string; leftOn: string | null; active: boolean; usualWork: string | null; notes: string | null; owed: number; advance: number; rates: Rate[];
};
export type AttendanceRow = { workerId: number; workerName: string; payType: PayType; status: AttendanceStatus | null; hours: number | null;
  costObjectId: number | null; costObjectName: string | null; notes: string | null; locked: boolean; paidByBill: boolean };
/** {@code version}: sent back as baseVersion, so a day someone else changed meanwhile is not overwritten (N7). */
export type Muster = { date: string; workers: AttendanceRow[]; version?: string };
export type MonthRow = { workerId: number; workerName: string; payType: PayType; present: number; halfDays: number; absent: number; paidLeave: number;
  unpaidLeave: number; days: number };
export type MonthSheet = { month: string; workers: MonthRow[] };
export type PieceWork = { id: number; workerId: number; workerName: string; workDate: string; quantity: number; unit: string; rate: number; amount: number;
  costObjectId: number | null; costObjectName: string | null; notes: string | null; locked: boolean };
export type CostShare = { costObjectId: number | null; costObjectName: string | null; days: number; amount: number };
export type PayRunWorker = { workerId: number; name: string; payType: PayType; days: number; timePay: number; piecePay: number; extraPay: number; gross: number;
  costs: CostShare[]; advance: number; owedBefore: number; notes: string[] };
export type PayRunPreview = { from: string; to: string; workers: PayRunWorker[]; total: number; skipped: string[] };
export type RecordRef = { documentId: number; number: string; workerId: number; amount: number };
export type StatementLine = { documentId: number; documentType: string; number: string; date: string; wages: number; paid: number; advanceGiven: number;
  advanceRecovered: number; balance: number; reversal: boolean };
export type Statement = { workerId: number; name: string; from: string; to: string; opening: number; lines: StatementLine[]; closing: number; owed: number; advance: number };
export type LabourRow = { costObjectId: number; name: string; kind: string; days: number; cost: number };
export type LabourByActivity = { from: string; to: string; rows: LabourRow[]; totalCost: number };
export type Reconciliation = { workersOwed: number; ledgerWagesPayable: number; workersAdvance: number; ledgerAdvances: number };

/** Crop cycles that work can be assigned to: their cost objects, from the crop API (no finance access needed). */
export type Activity = { costObjectId: number; name: string };
