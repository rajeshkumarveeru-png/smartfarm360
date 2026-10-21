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
import type { BatchReport } from './types';

/** Processing batches with yield, loss and cost per kg of the main product (M6, M7, M14). */
export default function ProcessingReportsPage() {
  const tenant = useTenant();
  const t = useNs('processing');
  const tf = useNs('fin');
  const lang = currentLanguage();
  const base = `/api/v1/tenants/${tenant.id}`;
  const showMoney = can(tenant, 'FINANCE_READ');
  const [range, setRange] = useState({ from: `${today().slice(0, 4)}-01-01`, to: today() });
  const [rows, setRows] = useState<BatchReport[] | null>(null);
  const [error, setError] = useState('');
  const q = `from=${range.from}&to=${range.to}`;

  useEffect(() => {
    setRows(null); setError('');
    api<BatchReport[]>(`${base}/processing/reports/batches?${q}`).then(setRows).catch(e => setError(describeError(e)));
  }, [base, q]);

  const n = (v: number | null) => (v == null ? '—' : formatNumber(v, lang, 1));
  const m = (v: number | null) => (v == null ? '—' : formatMoney(v, lang));
  return (
    <div className="report-page">
      <div className="crop-page-head"><div><h2>{t('reports')}</h2></div>
        {showMoney && <Link className="secondary no-print" to={`/t/${tenant.id}/stock/trading`}>{t('tradingLink')}</Link>}</div>
      <QuickRanges onPick={setRange} />
      <div className="form-card form-grid filters no-print">
        <label>{tf('from')}<input type="date" value={range.from} onChange={e => setRange({ ...range, from: e.target.value })} /></label>
        <label>{tf('to')}<input type="date" value={range.to} onChange={e => setRange({ ...range, to: e.target.value })} /></label>
      </div>
      <ReportHeader title={t('report.batches')} period={tf('rangeLabel', { from: formatDate(range.from, lang), to: formatDate(range.to, lang) })} />
      <ErrorBanner message={error} />
      <div className="section-head"><h3>{t('report.batches')}</h3>
        <ExportBar path={`${base}/processing/reports/batches?${q}`} name={`processing-batches-${range.from}-${range.to}`} onError={setError} /></div>
      {!rows ? <Loading /> : rows.length === 0 ? <p className="hint">{t('nothingInPeriod')}</p> : (
        <div className="table-scroll">
          <table className="data-table report">
            <thead><tr><th>{t('batchName')}</th><th className="num">{t('inputKg')}</th><th className="num">{t('mainKg')}</th><th className="num">{t('mainYield')}</th>
              <th className="num">{t('processLoss')}</th>
              {showMoney && <><th className="num">{tf('costs')}</th><th className="num">{tf('revenue')}</th><th className="num">{t('profit')}</th>
                <th className="num">{t('mainCostPerKg')}</th><th className="num">{t('rawCostPerKg')}</th></>}</tr></thead>
            <tbody>{rows.map(r => {
              const f = r.period;
              const main = f.outputs.filter(o => o.role === 'MAIN').reduce((a, o) => a + (o.yieldPercent ?? 0), 0);
              return (
                <tr key={r.batch.id}>
                  <td><Link to={`/t/${tenant.id}/processing/batches/${r.batch.id}`}>{r.batch.code}</Link><small> · {r.batch.name} · {t(`status.${r.batch.status}`)}</small></td>
                  <td className="num">{n(f.inputKg)}</td><td className="num">{n(f.mainKg)}</td><td className="num">{f.inputKg > 0 ? `${n(main)}%` : '—'}</td>
                  <td className="num">{n(f.lossKg)}</td>
                  {showMoney && <><td className="num">{m(f.costs)}</td><td className="num">{m(f.revenue)}</td><td className="num"><b>{m(f.profit)}</b></td>
                    <td className="num">{m(f.mainCostPerKg)}</td><td className="num">{m(f.rawCostPerKg)}</td></>}
                </tr>
              );
            })}</tbody>
          </table>
          <p className="hint">{t('batchesHelpReport')}</p>
        </div>
      )}
    </div>
  );
}
