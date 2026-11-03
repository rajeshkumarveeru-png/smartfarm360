import { apiRequest } from '../services/api';
import { record } from '../offline/hub';

/** Attendance marking and worker balances on the phone (workforce design, section 10); the day works offline (mobile offline design, N2). */
export type AttendanceStatus = 'PRESENT' | 'HALF_DAY' | 'ABSENT' | 'PAID_LEAVE' | 'UNPAID_LEAVE';
export const ATTENDANCE: AttendanceStatus[] = ['PRESENT', 'HALF_DAY', 'ABSENT', 'PAID_LEAVE', 'UNPAID_LEAVE'];

export type MusterRow = { workerId: number; workerName: string; payType: string; status: AttendanceStatus | null; hours: number | null;
  costObjectId: number | null; costObjectName: string | null; locked: boolean; paidByBill?: boolean };
/** {@code version}: the day's version, sent back as baseVersion so a day changed by someone else is not overwritten (N7). */
export type Muster = { date: string; workers: MusterRow[]; version?: string };
export type WorkerBalance = { id: number; name: string; payType: string; owed: number; advance: number };
export type Activity = { costObjectId: number; name: string };
export type Mark = { status: AttendanceStatus | null; costObjectId: number | null };

const base = (tenantId: number) => `/api/v1/tenants/${tenantId}/workforce`;

export function loadMuster(tenantId: number, date: string): Promise<Muster> {
  return apiRequest<Muster>(`${base(tenantId)}/attendance/${date}`);
}

/** Crop cycles still in the field that work can be charged to; empty without crop access. */
export async function loadActivities(tenantId: number): Promise<Activity[]> {
  const page = await apiRequest<{ items: { name: string; status: string; costObjectId: number | null }[] }>(`/api/v1/tenants/${tenantId}/cultivation-cycles?size=100`);
  return page.items.filter(c => c.costObjectId != null && c.status !== 'CLOSED' && c.status !== 'CANCELLED')
    .map(c => ({ costObjectId: c.costObjectId as number, name: c.name }));
}

/** Marks every worker whose day is not yet in a posted pay run. */
export function markAll(muster: Muster, marks: Record<number, Mark>, status: AttendanceStatus, costObjectId: number | null): Record<number, Mark> {
  const next = { ...marks };
  for (const w of muster.workers) if (!w.locked) next[w.workerId] = { status, costObjectId };
  return next;
}

export function initialMarks(muster: Muster): Record<number, Mark> {
  return Object.fromEntries(muster.workers.map(w => [w.workerId, { status: w.status, costObjectId: w.costObjectId }]));
}

/**
 * Saves the day: only rows that changed and are not locked are sent, so a supervisor never overwrites hours entered on the web.
 * A crop is kept only for days actually worked.
 */
export function saveMuster(tenantId: number, muster: Muster, marks: Record<number, Mark>): Promise<Muster> | null {
  const entries = muster.workers.filter(w => !w.locked).map(w => ({ w, m: marks[w.workerId] ?? { status: w.status, costObjectId: w.costObjectId } }))
    .filter(({ w, m }) => m.status !== w.status || m.costObjectId !== w.costObjectId)
    .map(({ w, m }) => {
      const working = m.status === 'PRESENT' || m.status === 'HALF_DAY';
      return { workerId: w.workerId, status: m.status, costObjectId: working ? m.costObjectId : null, hours: w.hours };
    });
  if (entries.length === 0) return null;
  const path = `${base(tenantId)}/attendance/${muster.date}`;
  return record<Muster>({ kind: 'attendance', target: `attendance:${muster.date}`, summary: muster.date, method: 'PUT', path,
    body: { entries, ...(muster.version ? { baseVersion: muster.version } : {}) }, day: { readPath: path } });
}

export async function loadBalances(tenantId: number): Promise<WorkerBalance[]> {
  const workers = await apiRequest<WorkerBalance[]>(`${base(tenantId)}/workers`);
  return [...workers].sort((a, b) => Number(b.owed) - Number(a.owed) || a.name.localeCompare(b.name));
}

/** yyyy-MM-dd of the day before or after, in local time. */
export function shiftDay(date: string, days: number): string {
  const [y, m, d] = date.split('-').map(Number);
  const x = new Date(y, m - 1, d + days);
  return `${x.getFullYear()}-${String(x.getMonth() + 1).padStart(2, '0')}-${String(x.getDate()).padStart(2, '0')}`;
}
