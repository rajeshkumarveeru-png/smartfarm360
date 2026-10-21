import { apiRequest } from '../services/api';

/** Organic manure on the phone (organic resources design, L13): batch log, output, manure collection and compost to a field. Online only. */
export type Batch = { id: number; costObjectId: number; name: string; kind: string; status: 'MAKING' | 'READY' | 'FINISHED'; finishedOn: string | null };
export type Item = { id: number; name: string; kind: string; baseUnit: string; active: boolean };
export type Activity = { id: number; kind: string; name: string; active: boolean };

const t = (tenantId: number) => `/api/v1/tenants/${tenantId}`;

/** Batches still being made (not finished). */
export async function loadBatches(tenantId: number): Promise<Batch[]> {
  return (await apiRequest<Batch[]>(`${t(tenantId)}/organic/batches`)).filter(b => b.finishedOn == null);
}

/** Farm produce items (dung, compost, straw): the only items that move between activities. */
export async function loadProduce(tenantId: number): Promise<Item[]> {
  return (await apiRequest<Item[]>(`${t(tenantId)}/stock/items`)).filter(i => i.active && i.kind === 'PRODUCE');
}

/** Activities other than farm overhead; empty without access to finance. */
export async function loadActivities(tenantId: number): Promise<Activity[]> {
  try {
    return (await apiRequest<Activity[]>(`${t(tenantId)}/finance/cost-objects`)).filter(a => a.active && a.kind !== 'OVERHEAD');
  } catch {
    return [];
  }
}

/** A quantity above zero with up to three decimals (kg). */
export const isQuantity = (v: string) => /^\d{1,9}(\.\d{1,3})?$/.test(v.trim()) && Number(v) > 0;

export function addLog(tenantId: number, batchId: number, date: string, kind: string, kg: string, notes: string): Promise<unknown> {
  return apiRequest(`${t(tenantId)}/organic/batches/${batchId}/logs`, { method: 'POST',
    body: { date, kind, kg: kg.trim() === '' ? null : kg.trim(), notes: notes.trim() === '' ? null : notes.trim() } });
}

/** Output into stock (L7); {@code finish} marks the batch finished with it. */
export function recordOutput(tenantId: number, batchId: number, date: string, itemId: number, quantity: string, finish: boolean, key: string): Promise<unknown> {
  return apiRequest(`${t(tenantId)}/organic/batches/${batchId}/outputs`, { method: 'POST', headers: { 'Idempotency-Key': key },
    body: { date, itemId, quantity: quantity.trim(), finish } });
}

/** Dung or litter into stock as produce of the herd or batch it came from (L3), at nil value. */
export function collectManure(tenantId: number, date: string, fromCostObjectId: number, itemId: number, quantity: string, key: string): Promise<unknown> {
  return apiRequest(`${t(tenantId)}/stock/produce`, { method: 'POST', headers: { 'Idempotency-Key': key },
    body: { docDate: date, costObjectId: fromCostObjectId, lines: [{ itemId, quantity: quantity.trim() }] } });
}

/** Compost spread on a field (L4): an internal transfer at the rule's cost, which comes back in {@code costMoved}. */
export function spread(tenantId: number, date: string, itemId: number, quantity: string, fromCostObjectId: number, toCostObjectId: number,
                       key: string): Promise<{ number: string; costMoved: number }> {
  return apiRequest(`${t(tenantId)}/stock/internal-transfers`, { method: 'POST', headers: { 'Idempotency-Key': key },
    body: { date, itemId, quantity: quantity.trim(), fromCostObjectId, toCostObjectId } });
}
