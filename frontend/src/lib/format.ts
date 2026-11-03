import type { Lang } from '../i18n';

const locale = (lang: Lang) => (lang === 'ta' ? 'ta-IN' : 'en-IN');

/** Rupee amounts. Values arrive from the API as JSON numbers or decimal strings. */
export function formatMoney(value: number | string | null | undefined, lang: Lang): string {
  const n = Number(value ?? 0);
  return new Intl.NumberFormat(locale(lang), { style: 'currency', currency: 'INR', minimumFractionDigits: 2, maximumFractionDigits: 2 })
    .format(Number.isFinite(n) ? n : 0);
}

export function formatNumber(value: number | string | null | undefined, lang: Lang, maxFractionDigits = 3): string {
  const n = Number(value ?? 0);
  return new Intl.NumberFormat(locale(lang), { maximumFractionDigits: maxFractionDigits }).format(Number.isFinite(n) ? n : 0);
}

/** Farm-local dates (YYYY-MM-DD) are formatted without timezone conversion. */
export function formatDate(value: string | null | undefined, lang: Lang): string {
  if (!value) return '';
  const date = /^\d{4}-\d{2}-\d{2}$/.test(value) ? new Date(`${value}T00:00:00`) : new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat(locale(lang), { day: 'numeric', month: 'short', year: 'numeric' }).format(date);
}

export function formatDateTime(value: string, lang: Lang): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat(locale(lang), { dateStyle: 'medium', timeStyle: 'short' }).format(date);
}

export const today = () => {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
};

/** Short rupee amounts for chart axes and summaries (₹1.2L, ₹45K); full amounts stay in the tables. */
export function formatMoneyCompact(value: number | string | null | undefined, lang: Lang): string {
  const n = Number(value ?? 0);
  return new Intl.NumberFormat(locale(lang), { style: 'currency', currency: 'INR', notation: 'compact', maximumFractionDigits: 1 })
    .format(Number.isFinite(n) ? n : 0);
}

/** A month (YYYY-MM) as a short name, with the year when asked ("Jan 2026"). */
export function formatMonth(value: string, lang: Lang, withYear = false): string {
  const date = new Date(`${value}-01T00:00:00`);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat(locale(lang), withYear ? { month: 'short', year: 'numeric' } : { month: 'short' }).format(date);
}
