import { useEffect, useState } from 'react';
import { Download, Printer } from 'lucide-react';
import { api, download } from '../api/client';
import { useTenant } from '../auth/useCurrentTenant';
import type { FinanceSettings } from '../finance/types';
import { currentLanguage, useNs } from '../i18n';
import { describeError } from '../lib/errors';
import { can } from '../lib/nav';
import type { QuickRange } from './ranges';
import { QUICK_RANGES, rangeFor } from './ranges';

/** The tenant's fiscal year start month (April when finance settings cannot be read). */
export function useFiscalStartMonth(): number {
  const tenant = useTenant();
  const [month, setMonth] = useState(4);
  useEffect(() => {
    if (!can(tenant, 'FINANCE_READ')) return;
    api<FinanceSettings>(`/api/v1/tenants/${tenant.id}/finance/settings`).then(s => setMonth(s.fiscalYearStartMonth)).catch(() => undefined);
  }, [tenant]);
  return month;
}

/** Quick range buttons (H11); "custom" leaves the dates to the date fields. */
export function QuickRanges({ onPick }: { onPick: (r: { from: string; to: string }) => void }) {
  const t = useNs('reports');
  const startMonth = useFiscalStartMonth();
  const [picked, setPicked] = useState<QuickRange>('custom');
  return (
    <div className="quick-actions no-print" role="group" aria-label={t('quickRange')}>
      {QUICK_RANGES.filter(r => r !== 'custom').map(r => {
        const range = rangeFor(r as Exclude<QuickRange, 'custom'>, new Date(), startMonth);
        return (
          <button key={r} type="button" className={picked === r ? 'primary' : 'secondary'} onClick={() => { setPicked(r); onPick(range); }}>
            {t(`range.${r}`)}{range.label ? ` ${range.label}` : ''}
          </button>
        );
      })}
    </div>
  );
}

/**
 * Export CSV, Excel and Print / Save as PDF for a report (H7, H8). {@code path} is the report's JSON URL; the file
 * versions add format and the reader's language.
 */
export function ExportBar({ path, name, onError }: { path: string; name: string; onError: (message: string) => void }) {
  const t = useNs('reports');
  const lang = currentLanguage();
  const file = (format: 'csv' | 'xlsx') => {
    const sep = path.includes('?') ? '&' : '?';
    download(`${path}${sep}format=${format}&lang=${lang}`, `${name}.${format}`).catch(e => onError(describeError(e)));
  };
  return (
    <div className="quick-actions no-print">
      <button type="button" className="secondary" onClick={() => file('csv')}><Download size={16} aria-hidden />{t('exportCsv')}</button>
      <button type="button" className="secondary" onClick={() => file('xlsx')}><Download size={16} aria-hidden />{t('exportExcel')}</button>
      <button type="button" className="secondary" onClick={() => window.print()}><Printer size={16} aria-hidden />{t('print')}</button>
    </div>
  );
}

/**
 * The header every report shows (principle 2): on screen as a hint line, and in print as the block above the table
 * (farm business, title, range or date, basis, actual figures).
 */
export function ReportHeader({ title, period, basis, extra }: { title: string; period: string; basis?: string; extra?: string }) {
  const tenant = useTenant();
  const t = useNs('reports');
  const tf = useNs('fin');
  const figures = `${tf('actualFigures')} · ${t('postedOnly')}`;
  return (
    <>
      <div className="print-only print-header">
        <p className="print-tenant">{tenant.name}</p>
        <h1>{title}</h1>
        <p>{period}{basis ? ` · ${basis}` : ''}{extra ? ` · ${extra}` : ''}</p>
        <p>{figures}</p>
      </div>
      <p className="hint no-print">{period}{basis ? ` · ${basis}` : ''}{extra ? ` · ${extra}` : ''} · {figures}</p>
    </>
  );
}
