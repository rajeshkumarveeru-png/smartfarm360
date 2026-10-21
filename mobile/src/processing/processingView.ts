import { apiRequest } from '../services/api';

/** Processing on the phone (processing and trading design, M13): steps, output and own produce into a batch. Online only. */
export type Batch = { id: number; costObjectId: number; code: string; name: string; mainItemId: number; mainItemName: string; status: string; finishedOn: string | null };
export type Item = { id: number; name: string; kind: string; baseUnit: string; trackLots: boolean; active: boolean };
export type Lot = { id: number; lotNo: string; quantity: number };
export type Activity = { id: number; kind: string; name: string; active: boolean };

const t = (tenantId: number) => `/api/v1/tenants/${tenantId}`;

export async function loadBatches(tenantId: number): Promise<Batch[]> {
  return (await apiRequest<Batch[]>(`${t(tenantId)}/processing/batches`)).filter(b => b.finishedOn == null);
}

export async function loadProduce(tenantId: number): Promise<Item[]> {
  return (await apiRequest<Item[]>(`${t(tenantId)}/stock/items`)).filter(i => i.active && i.kind === 'PRODUCE');
}

/** Open lots of an item (harvests that track lots are moved lot by lot). */
export async function loadLots(tenantId: number, itemId: number): Promise<Lot[]> {
  return (await apiRequest<Lot[]>(`${t(tenantId)}/stock/items/${itemId}/lots`)).filter(l => l.quantity > 0);
}

/** Activities that produce (crops, herds, batches); empty without access to finance. */
export async function loadSources(tenantId: number): Promise<Activity[]> {
  try {
    return (await apiRequest<Activity[]>(`${t(tenantId)}/finance/cost-objects`)).filter(a => a.active && a.kind !== 'OVERHEAD' && a.kind !== 'PROCESSING_BATCH');
  } catch {
    return [];
  }
}

export const isQuantity = (v: string) => /^\d{1,9}(\.\d{1,3})?$/.test(v.trim()) && Number(v) > 0;
export const isOptional = (v: string) => v.trim() === '' || /^\d{1,9}(\.\d{1,3})?$/.test(v.trim());

const orNull = (v: string) => (v.trim() === '' ? null : v.trim());

export function addStep(tenantId: number, batchId: number, date: string, kind: string, kgIn: string, kgOut: string, moistureBefore: string,
                        moistureAfter: string): Promise<unknown> {
  return apiRequest(`${t(tenantId)}/processing/batches/${batchId}/steps`, { method: 'POST',
    body: { date, kind, kgIn: orNull(kgIn), kgOut: orNull(kgOut), moistureBefore: orNull(moistureBefore), moistureAfter: orNull(moistureAfter) } });
}

/** Output (M5): MAIN and BYPRODUCT need an item; WASTE needs a description. */
export function recordOutput(tenantId: number, batchId: number, date: string, role: 'MAIN' | 'BYPRODUCT' | 'WASTE', itemId: number | null, description: string,
                             quantity: string, finish: boolean, key: string): Promise<{ lotNo: string | null }> {
  return apiRequest(`${t(tenantId)}/processing/batches/${batchId}/outputs`, { method: 'POST', headers: { 'Idempotency-Key': key },
    body: { date, role, itemId: role === 'WASTE' ? null : itemId, description: role === 'WASTE' ? description.trim() : null, quantity: quantity.trim(), finish } });
}

/** Own produce into the batch (M3): an internal transfer that carries the source's average cost by rule. */
export function moveIn(tenantId: number, date: string, itemId: number, quantity: string, lotNo: string | null, fromCostObjectId: number | null,
                       toCostObjectId: number, key: string): Promise<{ number: string; costMoved: number }> {
  return apiRequest(`${t(tenantId)}/stock/internal-transfers`, { method: 'POST', headers: { 'Idempotency-Key': key },
    body: { date, itemId, quantity: quantity.trim(), lotNo, fromCostObjectId, toCostObjectId } });
}
