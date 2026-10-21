import { apiRequest } from '../services/api';

/** Livestock on the phone (livestock design, I15): milk by session, quick heat and treatment notes, the animal list. Online only. */
export type Session = 'MORNING' | 'EVENING';
export type Group = { id: number; name: string; purpose: string; active: boolean };
export type Animal = { id: number; tag: string; name: string | null; sex: 'FEMALE' | 'MALE'; groupId: number | null; groupName: string | null;
  breeding: { repro: string | null; checkDueFrom: string | null; expectedCalving: string | null; dryOffDue: string | null; inMilk: boolean; withheldUntil: string | null } };
export type MilkDay = { date: string; total: number; withheld: { animalId: number; tag: string; until: string }[];
  sessions: { session: Session; groupId: number; litres: number; yields: { animalId: number; litres: number }[] }[] };

const base = (tenantId: number) => `/api/v1/tenants/${tenantId}/livestock`;

export async function loadHerd(tenantId: number): Promise<{ groups: Group[]; animals: Animal[] }> {
  const [groups, animals] = await Promise.all([apiRequest<Group[]>(`${base(tenantId)}/groups`), apiRequest<Animal[]>(`${base(tenantId)}/animals`)]);
  return { groups: groups.filter(g => g.active), animals };
}

export function loadMilkDay(tenantId: number, date: string): Promise<MilkDay> {
  return apiRequest<MilkDay>(`${base(tenantId)}/milk/${date}`);
}

/** Groups that are milked: milking groups, and any group with an animal in milk. */
export function milkingGroups(groups: Group[], animals: Animal[]): Group[] {
  return groups.filter(g => g.purpose === 'MILKING' || animals.some(a => a.groupId === g.id && a.breeding.inMilk));
}

/** A positive or zero amount of litres, up to two decimals. */
export function isLitres(value: string): boolean {
  return /^\d{1,6}(\.\d{1,2})?$/.test(value.trim());
}

/**
 * Saves one session for one group: the group total, or per-animal litres when any are entered (their sum is the total).
 * Litres stay strings so nothing is rounded on the phone.
 */
export function saveSession(tenantId: number, date: string, session: Session, groupId: number, total: string, perAnimal: Record<number, string>): Promise<MilkDay> {
  const yields = Object.entries(perAnimal).filter(([, v]) => v.trim() !== '').map(([id, v]) => ({ animalId: Number(id), litres: v.trim() }));
  const entry = yields.length > 0 ? { session, groupId, yields } : { session, groupId, litres: total.trim() === '' ? null : total.trim(), yields: [] };
  return apiRequest<MilkDay>(`${base(tenantId)}/milk/${date}`, { method: 'PUT', body: { sessions: [entry] } });
}

/** Heat seen: the quickest breeding note from the field. */
export function recordHeat(tenantId: number, animalId: number, date: string): Promise<unknown> {
  return apiRequest(`${base(tenantId)}/animals/${animalId}/breeding`, { method: 'POST', body: { kind: 'HEAT', date } });
}

/** A treatment with its milk withdrawal days (I9); {@code key} is the Idempotency-Key. */
export function recordTreatment(tenantId: number, animalId: number, date: string, description: string, withdrawalDays: string, key: string): Promise<unknown> {
  return apiRequest(`${base(tenantId)}/health`, { method: 'POST', headers: { 'Idempotency-Key': key },
    body: { animalId, kind: 'TREATMENT', date, description: description.trim(), withdrawalDays: withdrawalDays.trim() === '' ? null : Number(withdrawalDays) } });
}

/** Flags shown on an animal: milk withheld, a pregnancy check or dry-off due within 7 days, calving due within 14. */
export function flags(a: Animal, today: string): ('WITHHELD' | 'CHECK' | 'DRY_OFF' | 'CALVING')[] {
  const within = (d: string | null, days: number) => d != null && diffDays(today, d) <= days;
  const out: ('WITHHELD' | 'CHECK' | 'DRY_OFF' | 'CALVING')[] = [];
  if (a.breeding.withheldUntil && a.breeding.withheldUntil >= today) out.push('WITHHELD');
  if (a.breeding.repro === 'INSEMINATED' && within(a.breeding.checkDueFrom, 7)) out.push('CHECK');
  if (a.breeding.inMilk && within(a.breeding.dryOffDue, 7)) out.push('DRY_OFF');
  if (within(a.breeding.expectedCalving, 14)) out.push('CALVING');
  return out;
}

function diffDays(from: string, to: string): number {
  return Math.round((Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / 86_400_000);
}
