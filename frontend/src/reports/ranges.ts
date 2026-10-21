/** Quick ranges for reports (dashboard and reports design, H11), following the fiscal year start in finance settings. */
export type QuickRange = 'thisMonth' | 'lastMonth' | 'thisQuarter' | 'thisYear' | 'lastYear' | 'custom';
export const QUICK_RANGES: QuickRange[] = ['thisMonth', 'lastMonth', 'thisQuarter', 'thisYear', 'lastYear', 'custom'];

const iso = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
const endOfMonth = (y: number, m: number) => new Date(y, m + 1, 0);

/** The first day of the fiscal year containing {@code date}; {@code startMonth} is 1–12 (April = 4 by default). */
export function fiscalYearStart(date: Date, startMonth: number): Date {
  const m = startMonth - 1;
  const year = date.getMonth() >= m ? date.getFullYear() : date.getFullYear() - 1;
  return new Date(year, m, 1);
}

/** "2026-27" for a fiscal year starting in 2026 (January starts are just "2026"). */
export function fiscalYearLabel(start: Date, startMonth: number): string {
  if (startMonth === 1) return String(start.getFullYear());
  return `${start.getFullYear()}-${String((start.getFullYear() + 1) % 100).padStart(2, '0')}`;
}

/**
 * Dates for a quick range on {@code today}. "This quarter" uses quarters of the fiscal year. Ranges that reach past today
 * end today, so reports never show a future date.
 */
export function rangeFor(kind: Exclude<QuickRange, 'custom'>, today: Date, startMonth = 4): { from: string; to: string; label?: string } {
  const y = today.getFullYear(), m = today.getMonth();
  const clip = (d: Date) => (d > today ? today : d);
  switch (kind) {
    case 'thisMonth': return { from: iso(new Date(y, m, 1)), to: iso(today) };
    case 'lastMonth': return { from: iso(new Date(y, m - 1, 1)), to: iso(endOfMonth(y, m - 1)) };
    case 'thisQuarter': {
      const fy = fiscalYearStart(today, startMonth);
      const monthsIn = (y - fy.getFullYear()) * 12 + m - fy.getMonth();
      const qStart = new Date(fy.getFullYear(), fy.getMonth() + Math.floor(monthsIn / 3) * 3, 1);
      return { from: iso(qStart), to: iso(clip(endOfMonth(qStart.getFullYear(), qStart.getMonth() + 2))) };
    }
    case 'thisYear': {
      const fy = fiscalYearStart(today, startMonth);
      return { from: iso(fy), to: iso(today), label: fiscalYearLabel(fy, startMonth) };
    }
    case 'lastYear': {
      const fy = fiscalYearStart(today, startMonth);
      const prev = new Date(fy.getFullYear() - 1, fy.getMonth(), 1);
      return { from: iso(prev), to: iso(new Date(fy.getFullYear(), fy.getMonth(), 0)), label: fiscalYearLabel(prev, startMonth) };
    }
  }
}
