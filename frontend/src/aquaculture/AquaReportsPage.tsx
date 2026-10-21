import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import { useTenant } from '../auth/useCurrentTenant';
import { ErrorBanner, Loading } from '../components/common';
import { currentLanguage, useNs } from '../i18n';
import { describeError } from '../lib/errors';
import { formatDate, formatMoney, formatNumber, today } from '../lib/format';
import { can } from '../lib/nav';
import { ExportBar, QuickRanges, ReportHeader } from '../reports/ReportTools';
import type { CycleReport } from './types';

/** Pond cycles with survival, FCR, yield and profit per acre (aquaculture design, K9 and K14). */
export default function AquaReportsPage() {
  const tenant = useTenant();
  const t = useNs('aquaculture');
  const tf = useNs('fin');
  const lang = currentLanguage();
  const base = `/api/v1/tenants/${tenant.id}/aquaculture/reports`;
  const showMoney = can(tenant, 'FINANCE_READ');
  const [range, setRange] = useState({ from: `${today().slice(0, 4)}-01-01`, to: today() });
  const [rows, setRows] = useState<CycleReport[] | null>(null);
  const [error, setError] = useState('');
  const q = `from=${range.from}&to=${range.to}`;

  useEffect(() => {
    setRows(null); setError('');
    api<CycleReport[]>(`${base}/cycles?${q}`).then(setRows).catch(e => setError(describeError(e)));
  }, [base, q]);

  const n = (v: number | null, d = 2) => (v == null ? '—' : formatNumber(v, lang, d));
  const m = (v: number | null) => (v == null ? '—' : formatMoney(v, lang));
  return (
    <div className="report-page">
      <div className="crop-page-head"><div><h2>{t('reports')}</h2></div></div>
      <QuickRanges onPick={setRange} />
      <div className="form-card form-grid filters no-print">
        <label>{tf('from')}<input type="date" value={range.from} onChange={e => setRange({ ...range, from: e.target.value })} /></label>
        <label>{tf('to')}<input type="date" value={range.to} onChange={e => setRange({ ...range, to: e.target.value })} /></label>
      </div>
      <ReportHeader title={t('report.cycles')} period={tf('rangeLabel', { from: formatDate(range.from, lang), to: formatDate(range.to, lang) })} />
      <ErrorBanner message={error} />
      <div className="section-head"><h3>{t('report.cycles')}</h3><ExportBar path={`${base}/cycles?${q}`} name={`pond-cycles-${range.from}-${range.to}`} onError={setError} /></div>
      {!rows ? <Loading /> : rows.length === 0 ? <p className="hint">{t('nothingInPeriod')}</p> : (
        <div className="table-scroll">
          <table className="data-table report">
            <thead><tr><th>{t('cycle')}</th><th className="num">{t('daysOfCulture')}</th><th className="num">{t('stocked')}</th><th className="num">{t('harvestedKg')}</th>
              <th className="num">{t('survival')}</th><th className="num">{t('fcr')}</th><th className="num">{t('yieldPerAcre')}</th>
              {showMoney && <><th className="num">{tf('revenue')}</th><th className="num">{tf('costs')}</th><th className="num">{t('profit')}</th>
                <th className="num">{t('costPerKg')}</th><th className="num">{t('profitPerAcre')}</th></>}</tr></thead>
            <tbody>{rows.map(r => { const f = r.period; return (
              <tr key={r.cycle.id}>
                <td><Link to={`/t/${tenant.id}/aquaculture/cycles/${r.cycle.id}`}>{r.cycle.name}</Link><small> · {r.cycle.pondName} · {t(`status.${r.cycle.status}`)}</small></td>
                <td className="num">{n(f.daysOfCulture, 0)}</td><td className="num">{n(f.stockedCount, 0)}</td><td className="num">{n(f.harvestedKg, 1)}</td>
                <td className="num">{f.survivalPercent == null ? '—' : `${n(f.survivalPercent, 1)}%`}</td><td className="num">{n(f.fcr)}</td><td className="num">{n(f.yieldPerAcre, 1)}</td>
                {showMoney && <><td className="num">{m(f.revenue)}</td><td className="num">{m(f.costs)}</td><td className="num"><b>{m(f.profit)}</b></td>
                  <td className="num">{m(f.costPerKg)}</td><td className="num">{m(f.profitPerAcre)}</td></>}
              </tr>); })}</tbody>
          </table>
          <p className="hint">{t('cyclesHelpReport')}</p>
        </div>
      )}
    </div>
  );
}
