import { apiRequest } from '../services/api';
import { tr, type Language } from '../i18n/translations';

/** Crop cycles and their figures as the web app sees them (crop design, F12). Online only; offline recording is Phase 13. */
export type CycleStatus = 'PLANNED' | 'GROWING' | 'HARVESTING' | 'CLOSED' | 'CANCELLED';
export type Cycle = { id: number; name: string; cropId: number; cropName: string; farmName: string; parcelName: string; status: CycleStatus;
  plannedSowingDate: string; actualSowingDate: string | null; daysSinceSowing: number | null; areaAcres: number | null };
export type CycleReport = { revenueTotal: number; totalCost: number; profit: number; costPerAcre: number | null; costUnit: string | null;
  harvested: { quantity: number; baseUnit: string }[]; unsold: number };
export type StockInput = { itemId: number; quantity: string; unit?: string | null };
export type StockItem = { id: number; name: string; baseUnit: string; customUnitName: string | null; quantity: number; kind: string; trackLots: boolean };

export const ACTIVITY_TYPES = ['LAND_PREPARATION', 'SOWING', 'TRANSPLANTING', 'FERTILIZER', 'PESTICIDE', 'IRRIGATION', 'WEEDING', 'HARVESTING', 'OTHER'] as const;
export const OBSERVATION_KINDS = ['PEST', 'DISEASE', 'NUTRIENT', 'WEED', 'WATER_STRESS', 'OTHER'] as const;
export const SEVERITIES = ['LOW', 'MEDIUM', 'HIGH'] as const;

const base = (tenantId: number) => `/api/v1/tenants/${tenantId}`;

/** Cycles still in the field (planned, growing, harvesting), soonest sown first. */
export async function loadActiveCycles(tenantId: number): Promise<Cycle[]> {
  const page = await apiRequest<{ items: Cycle[] }>(`${base(tenantId)}/cultivation-cycles?size=100`);
  return page.items.filter(c => c.status !== 'CLOSED' && c.status !== 'CANCELLED')
    .sort((a, b) => (a.actualSowingDate ?? a.plannedSowingDate).localeCompare(b.actualSowingDate ?? b.plannedSowingDate));
}

export function loadReport(tenantId: number, cycleId: number): Promise<CycleReport> {
  return apiRequest<CycleReport>(`${base(tenantId)}/cultivation-cycles/${cycleId}/report`);
}

/** Stock the phone can offer as inputs: items in stock that do not need a lot number (lots are chosen on the web). */
export async function loadInputs(tenantId: number): Promise<StockItem[]> {
  const items = await apiRequest<StockItem[]>(`${base(tenantId)}/stock/items`);
  return items.filter(i => Number(i.quantity) > 0 && !i.trackLots);
}

/** {@code key} is the Idempotency-Key, so a retry after a timeout never records the work twice. */
export function recordActivity(tenantId: number, body: { cycleId: number; activityType: string; activityDate: string; notes?: string | null;
  inputs?: StockInput[] }, key: string): Promise<{ warnings: string[] }> {
  return apiRequest(`${base(tenantId)}/activities`, { method: 'POST', headers: { 'Idempotency-Key': key },
    body: { ...body, notes: body.notes || null, inputs: body.inputs ?? [] } });
}

export function recordHarvest(tenantId: number, body: { cycleId: number; harvestDate: string; quantity: string; unit: string }, key: string): Promise<unknown> {
  return apiRequest(`${base(tenantId)}/harvests`, { method: 'POST', headers: { 'Idempotency-Key': key }, body });
}

export function addObservation(tenantId: number, cycleId: number, body: { observedOn: string; kind: string; severity: string; notes?: string | null }): Promise<unknown> {
  return apiRequest(`${base(tenantId)}/cultivation-cycles/${cycleId}/observations`, { method: 'POST', body: { ...body, notes: body.notes || null } });
}

/** "Growing · 30 days since sowing" */
export function stageLine(language: Language, c: Cycle): string {
  const stage = tr(language, `crop.stage.${c.status}`);
  return c.daysSinceSowing == null ? stage : `${stage} · ${tr(language, 'crop.daysSinceSowing', { days: String(c.daysSinceSowing) })}`;
}

/** A positive quantity with at most four decimals. */
export function isQuantity(value: string): boolean {
  return /^\d{1,14}(\.\d{1,4})?$/.test(value.trim()) && Number(value) > 0;
}
