import type { DayRef, OutboxRecord, RecordKind } from './types';

/** What the Sync screen shows for a record (mobile offline design, sections 5, 6 and 8). Pure, so it is tested directly. */

/** The catalog key of a record kind's label, e.g. `mobile.syncKindMilk`. */
export const kindKey = (kind: RecordKind) => `mobile.syncKind${kind[0].toUpperCase()}${kind.slice(1)}`;

/** A figure of a day record: a catalog key for its label and the value as entered. */
export type Figure = { label: string; value: string };

const num = (v: unknown) => (v == null || v === '' ? 0 : Number(v));
const plain = (n: number) => String(Math.round(n * 1000) / 1000);

/**
 * The figures of a day record, from what the phone sends or from the server's copy, so the two can be shown side by side
 * (N7). Attendance shows how many workers have each status; the other days show their main entries.
 */
export function dayFigures(kind: RecordKind, day: DayRef | undefined, body: unknown): Figure[] {
  const b = (body ?? {}) as Record<string, unknown>;
  switch (kind) {
    case 'attendance': {
      const rows = ((b.entries ?? b.workers ?? []) as { status?: string | null }[]).filter(r => r.status);
      const counts = new Map<string, number>();
      for (const r of rows) counts.set(r.status as string, (counts.get(r.status as string) ?? 0) + 1);
      return [...counts.entries()].sort().map(([status, n]) => ({ label: `workforce.status.${status}`, value: String(n) }));
    }
    case 'milk': {
      const sessions = (b.sessions ?? []) as { session: string; groupId: number; litres?: unknown; yields?: { litres: unknown }[] }[];
      const s = sessions.find(x => !day?.session || (x.session === day.session && x.groupId === day.groupId));
      if (!s) return [{ label: 'mobile.syncFigureLitres', value: '0' }];
      const litres = s.yields && s.yields.length > 0 ? s.yields.reduce((t, y) => t + num(y.litres), 0) : num(s.litres);
      return [{ label: 'mobile.syncFigureLitres', value: plain(litres) }];
    }
    case 'flockDay':
      return [
        { label: 'mobile.syncFigureDeaths', value: plain(num(b.deaths)) },
        { label: 'mobile.syncFigureCulls', value: plain(num(b.culls)) },
        { label: 'mobile.syncFigureEggs', value: plain(num(b.eggsGood)) },
        { label: 'mobile.syncFigureFeedKg', value: plain(num(b.feedKg)) },
      ];
    case 'pondDay': {
      const feeds = (b.feeds ?? []) as { kg: unknown }[];
      const deaths = (b.deaths ?? []) as { count: unknown }[];
      return [
        { label: 'mobile.syncFigureFeedKg', value: plain(feeds.reduce((t, f) => t + num(f.kg), 0)) },
        { label: 'mobile.syncFigureFishDeaths', value: plain(deaths.reduce((t, d) => t + num(d.count), 0)) },
        { label: 'mobile.syncFigureNotes', value: typeof b.notes === 'string' ? b.notes : '' },
      ];
    }
    default:
      return [];
  }
}

/** A value the user can correct before resending (N8): amounts, counts, dates and notes, never ids or choices. */
export type EditableField = { path: (string | number)[]; key: string; value: string };

const TEXT_FIELDS = new Set(['notes', 'description', 'deathCause', 'cause', 'fieldPart', 'actionTaken']);
const isDateKey = (k: string) => k === 'date' || /Date$/.test(k) || /On$/.test(k);
const isNumeric = (v: unknown) => typeof v === 'number' || (typeof v === 'string' && /^-?\d+(\.\d+)?$/.test(v.trim()));

export function editableFields(body: Record<string, unknown>): EditableField[] {
  const out: EditableField[] = [];
  const visit = (node: unknown, path: (string | number)[], depth: number) => {
    if (Array.isArray(node)) { if (depth < 3) node.forEach((x, i) => visit(x, [...path, i], depth + 1)); return; }
    if (!node || typeof node !== 'object') return;
    for (const [k, v] of Object.entries(node as Record<string, unknown>)) {
      if (k === 'baseVersion' || /Id$/.test(k) || k === 'id') continue;
      if (Array.isArray(v) || (v && typeof v === 'object')) { visit(v, [...path, k], depth + 1); continue; }
      if (TEXT_FIELDS.has(k) || isDateKey(k) || isNumeric(v)) out.push({ path: [...path, k], key: k, value: v == null ? '' : String(v) });
    }
  };
  visit(body, [], 0);
  return out;
}

/** The body with the user's corrections; a number stays a number, text stays text, an emptied field becomes null. */
export function applyEdits(body: Record<string, unknown>, edits: { path: (string | number)[]; value: string }[]): Record<string, unknown> {
  const next = JSON.parse(JSON.stringify(body)) as Record<string, unknown>;
  for (const e of edits) {
    let node = next as Record<string | number, unknown>;
    for (const p of e.path.slice(0, -1)) node = node[p] as Record<string | number, unknown>;
    const last = e.path[e.path.length - 1];
    const before = node[last];
    const v = e.value.trim();
    node[last] = v === '' ? null : typeof before === 'number' && isNumeric(v) ? Number(v) : v;
  }
  return next;
}

/** The catalog key of a field's label; fields without their own label are shown as "Value". */
export const fieldKey = (key: string) => `mobile.syncField${key[0].toUpperCase()}${key.slice(1)}`;

/** "06:10" for today, "2026-10-19 19:40" for another day, in the phone's time. */
export function whenText(iso: string, now: Date = new Date()): string {
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, '0');
  const time = `${pad(d.getHours())}:${pad(d.getMinutes())}`;
  const sameDay = d.getFullYear() === now.getFullYear() && d.getMonth() === now.getMonth() && d.getDate() === now.getDate();
  return sameDay ? time : `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${time}`;
}

/** Records for the Sync screen's sections. */
export function sections(list: OutboxRecord[]) {
  return {
    attention: list.filter(r => r.state === 'attention' || r.state === 'locked'),
    waiting: list.filter(r => r.state === 'waiting'),
    sent: list.filter(r => r.state === 'sent'),
    discarded: list.filter(r => r.state === 'discarded'),
  };
}
