import { apiRequest } from '../services/api';
import { record } from '../offline/hub';

/** Fish farming on the phone (aquaculture design, K13): the daily pond record, water readings and weight samples, offline too (N2). */
export type OpenCycle = { id: number; name: string; status: 'PREPARING' | 'GROWING' | 'HARVESTED'; stockedOn: string | null };
export type Pond = { id: number; name: string; active: boolean; openCycle: OpenCycle | null;
  latestReading: { takenAt: string; outOfLimits: string[] } | null };
export type Species = { species: string; otherName: string | null };
export type PondDay = { day: string; feeds: { itemId: number; kg: number }[]; deaths: { species: string; otherName: string | null; count: number; cause: string | null }[];
  notes: string | null; version?: string };
export type Reading = { id: number; outOfLimits: string[] };
export type ReadingForm = { dissolvedOxygen: string; ph: string; temperature: string; ammonia: string };

const base = (tenantId: number) => `/api/v1/tenants/${tenantId}/aquaculture`;

export async function loadPonds(tenantId: number): Promise<Pond[]> {
  return (await apiRequest<Pond[]>(`${base(tenantId)}/ponds`)).filter(p => p.active);
}

/** The species stocked in a cycle (stockings whose bill was not reversed), once each. */
export async function loadSpecies(tenantId: number, cycleId: number): Promise<Species[]> {
  const list = await apiRequest<(Species & { reversed: boolean })[]>(`${base(tenantId)}/cycles/${cycleId}/stockings`);
  const seen = new Map<string, Species>();
  for (const s of list) if (!s.reversed) seen.set(`${s.species}|${(s.otherName ?? '').toLowerCase()}`, { species: s.species, otherName: s.otherName });
  return [...seen.values()];
}

export function loadDay(tenantId: number, cycleId: number, date: string): Promise<PondDay> {
  return apiRequest<PondDay>(`${base(tenantId)}/cycles/${cycleId}/days/${date}`);
}

/** A whole number above zero, or a kg value with up to three decimals; empty is allowed where noted by the caller. */
export const isWhole = (v: string) => /^\d{1,9}$/.test(v.trim()) && Number(v) > 0;
export const isKg = (v: string) => /^\d{1,9}(\.\d{1,3})?$/.test(v.trim()) && Number(v) > 0;
export const isMeasure = (v: string) => v.trim() === '' || /^\d{1,5}(\.\d{1,3})?$/.test(v.trim());

/**
 * Saves today's feed and deaths seen for a cycle (K5). Saving replaces the day, so the loaded day's other feed lines, deaths
 * and notes are kept and the phone's entry is added to them. Kg stays a string so nothing is rounded on the phone.
 */
export function saveDay(tenantId: number, cycleId: number, date: string, loaded: PondDay, feed: { itemId: number; kg: string } | null,
                        deaths: { species: Species; count: string } | null): Promise<PondDay> {
  const feeds = loaded.feeds.map(f => ({ itemId: f.itemId, kg: String(f.kg) }));
  if (feed) {
    const same = feeds.find(f => f.itemId === feed.itemId);
    if (same) same.kg = feed.kg.trim(); else feeds.push({ itemId: feed.itemId, kg: feed.kg.trim() });
  }
  const list = loaded.deaths.map(d => ({ species: d.species, otherName: d.otherName, count: d.count, cause: d.cause }));
  if (deaths) list.push({ species: deaths.species.species, otherName: deaths.species.otherName, count: Number(deaths.count.trim()), cause: null });
  const path = `${base(tenantId)}/cycles/${cycleId}/days/${date}`;
  return record<PondDay>({ kind: 'pondDay', target: `pondDay:${cycleId}:${date}`, summary: date, method: 'PUT', path, day: { readPath: path },
    body: { feeds, deaths: list, notes: loaded.notes, ...(loaded.version ? { baseVersion: loaded.version } : {}) } });
}

/** A water reading taken now (K7); the out-of-limit flags come back with it. */
export function recordReading(tenantId: number, pondId: number, form: ReadingForm, now: Date = new Date()): Promise<Reading> {
  const v = (s: string) => (s.trim() === '' ? null : s.trim());
  return record<Reading>({ kind: 'reading', target: `pond:${pondId}`, summary: now.toISOString().slice(0, 16).replace('T', ' '), method: 'POST',
    path: `${base(tenantId)}/ponds/${pondId}/readings`, body: { takenAt: now.toISOString(),
    dissolvedOxygen: v(form.dissolvedOxygen), ph: v(form.ph), temperature: v(form.temperature), ammonia: v(form.ammonia) } });
}

/** A mixed weight sample (K6): fish weighed and their total kg; the average comes back in grams. */
export function recordSample(tenantId: number, cycleId: number, date: string, fishWeighed: string, kg: string): Promise<{ averageGrams: number }> {
  return record({ kind: 'sample', target: `pondCycle:${cycleId}`, summary: `${date} · ${kg.trim()} kg`, method: 'POST', path: `${base(tenantId)}/cycles/${cycleId}/samples`,
    body: { date, fishWeighed: Number(fishWeighed.trim()), kg: kg.trim() } });
}
