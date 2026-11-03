import { apiRequest } from '../services/api';
import { record } from '../offline/hub';

/** Poultry batches and goat and sheep herds on the phone (poultry, goats and sheep design, J12): the daily batch record and quick head counts, offline too (N2). */
export type FlockSpecies = 'CHICKEN' | 'GOAT' | 'SHEEP';
export type Flock = { id: number; name: string; species: 'CATTLE' | FlockSpecies; purpose: string; animals: number; closed: boolean };
export type BatchDay = { day: string; deaths: number; culls: number; eggsGood: number; eggsCracked: number; eggsBroken: number; feedItemId: number | null;
  feedKg: number | null; deathCause: string | null; weightBirds: number | null; weightKg: number | null; notes: string | null; headsAtEnd: number; warnings: string[]; version?: string };
export type FeedItem = { id: number; name: string };
export type DayForm = { deaths: string; culls: string; eggsGood: string; eggsCracked: string; eggsBroken: string; feedItemId: number | null; feedKg: string };

const base = (tenantId: number) => `/api/v1/tenants/${tenantId}/livestock`;
const moduleOf = (species: Flock['species']) => (species === 'CHICKEN' ? 'POULTRY' : 'GOAT_SHEEP');

/** Open batches and herds whose module is on. */
export async function loadFlocks(tenantId: number, enabledModules: string[]): Promise<Flock[]> {
  const all = await apiRequest<Flock[]>(`${base(tenantId)}/flocks`);
  return all.filter(f => f.species !== 'CATTLE' && !f.closed && enabledModules.includes(moduleOf(f.species)));
}

export function loadDay(tenantId: number, groupId: number, date: string): Promise<BatchDay> {
  return apiRequest<BatchDay>(`${base(tenantId)}/flocks/${groupId}/days/${date}`);
}

/** Feed that can be issued by weight; empty without access to stock. */
export async function loadFeeds(tenantId: number): Promise<FeedItem[]> {
  try {
    const items = await apiRequest<{ id: number; name: string; kind: string; dimension: string; active: boolean }[]>(`/api/v1/tenants/${tenantId}/stock/items`);
    return items.filter(i => i.active && i.kind === 'INPUT' && i.dimension === 'MASS').map(i => ({ id: i.id, name: i.name }));
  } catch {
    return [];
  }
}

/** A whole number of birds or eggs, or empty. */
export function isWhole(value: string): boolean {
  return value.trim() === '' || /^\d{1,7}$/.test(value.trim());
}

/** Kg of feed, up to three decimals, or empty. */
export function isKg(value: string): boolean {
  return value.trim() === '' || /^\d{1,9}(\.\d{1,3})?$/.test(value.trim());
}

/** Whether the day's entries can be sent: whole numbers, kg with up to three decimals, and feed chosen together with its kg. */
export function dayFormValid(f: DayForm): boolean {
  return [f.deaths, f.culls, f.eggsGood, f.eggsCracked, f.eggsBroken].every(isWhole) && isKg(f.feedKg) && (f.feedItemId != null) === (f.feedKg.trim() !== '');
}

export function toDayForm(d: BatchDay): DayForm {
  const s = (v: number | null) => (v == null || v === 0 ? '' : String(v));
  return { deaths: s(d.deaths), culls: s(d.culls), eggsGood: s(d.eggsGood), eggsCracked: s(d.eggsCracked), eggsBroken: s(d.eggsBroken),
    feedItemId: d.feedItemId, feedKg: s(d.feedKg) };
}

/**
 * Saves the day's record for a batch (J4). Saving replaces the day, so the cause, weight sample and notes of the loaded day (entered on
 * the web) are sent back unchanged. Kg stays a string so nothing is rounded on the phone.
 */
export function saveDay(tenantId: number, groupId: number, date: string, f: DayForm, loaded: BatchDay): Promise<BatchDay> {
  const keep = { deathCause: loaded.deathCause, weightBirds: loaded.weightBirds, weightKg: loaded.weightKg, notes: loaded.notes };
  const n = (v: string) => (v.trim() === '' ? null : Number(v.trim()));
  const path = `${base(tenantId)}/flocks/${groupId}/days/${date}`;
  return record<BatchDay>({ kind: 'flockDay', target: `flockDay:${groupId}:${date}`, summary: date, method: 'PUT', path, day: { readPath: path },
    body: { ...keep, ...(loaded.version ? { baseVersion: loaded.version } : {}), deaths: n(f.deaths), culls: n(f.culls),
    eggsGood: n(f.eggsGood), eggsCracked: n(f.eggsCracked), eggsBroken: n(f.eggsBroken), feedItemId: f.feedKg.trim() === '' ? null : f.feedItemId,
    feedKg: f.feedKg.trim() === '' ? null : f.feedKg.trim() } });
}

/** A quick head change for a herd: died, culled or born (J7). */
export function recordHeads(tenantId: number, groupId: number, date: string, kind: 'DIED' | 'CULLED' | 'BORN', heads: string): Promise<unknown> {
  return record({ kind: 'flockHeads', target: `flock:${groupId}`, summary: `${date} · ${heads.trim()}`, method: 'POST', path: `${base(tenantId)}/flocks/${groupId}/movements`,
    body: { date, kind, heads: Number(heads.trim()) } });
}
