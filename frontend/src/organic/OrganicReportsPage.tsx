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
import { TransfersTable, type Transfer } from '../stock/OwnProducePage';
import type { BatchReport } from './types';

/** Organic batches with yield, cost per kg and cost moved to the farm's other activities (L8, L14), and the transfers. */
export default function OrganicReportsPage() {
  const tenant = useTenant();
  const t = useNs('organic');
  const tf = useNs('fin');
  const ts = useNs('stock');
  const lang = currentLanguage();
  const base = `/api/v1/tenants/${tenant.id}`;
  const showMoney = can(tenant, 'FINANCE_READ');
  const [range, setRange] = useState({ from: `${today().slice(0, 4)}-01-01`, to: today() });
  const [rows, setRows] = useState<BatchReport[] | null>(null);
  const [transfers, setTransfers] = useState<Transfer[] | null>(null);
  const [error, setError] = useState('');
  const q = `from=${range.from}&to=${range.to}`;

  useEffect(() => {
    setRows(null); setError('');
    api<BatchReport[]>(`${base}/organic/reports/batches?${q}`).then(setRows).catch(e => setError(describeError(e)));
    if (can(tenant, 'INVENTORY_READ')) api<Transfer[]>(`${base}/stock/internal-transfers?${q}`).then(setTransfers).catch(e => setError(describeError(e)));
  }, [base, q, tenant]);

  const m = (v: number | null) => (v == null ? '—' : formatMoney(v, lang));
  return (
    <div className="report-page">
      <div className="crop-page-head"><div><h2>{t('reports')}</h2></div></div>
      <QuickRanges onPick={setRange} />
      <div className="form-card form-grid filters no-print">
        <label>{tf('from')}<input type="date" value={range.from} onChange={e => setRange({ ...range, from: e.target.value })} /></label>
        <label>{tf('to')}<input type="date" value={range.to} onChange={e => setRange({ ...range, to: e.target.value })} /></label>
      </div>
      <ReportHeader title={t('report.batches')} period={tf('rangeLabel', { from: formatDate(range.from, lang), to: formatDate(range.to, lang) })} />
      <ErrorBanner message={error} />
      <div className="section-head"><h3>{t('report.batches')}</h3><ExportBar path={`${base}/organic/reports/batches?${q}`} name={`organic-batches-${range.from}-${range.to}`} onError={setError} /></div>
      {!rows ? <Loading /> : rows.length === 0 ? <p className="hint">{t('nothingInPeriod')}</p> : (
        <div className="table-scroll">
          <table className="data-table report">
            <thead><tr><th>{t('batch')}</th><th className="num">{t('inputKg')}</th><th className="num">{t('output')}</th><th className="num">{t('yield')}</th>
              {showMoney && <><th className="num">{tf('costs')}</th><th className="num">{t('movedOut')}</th><th className="num">{tf('revenue')}</th><th className="num">{t('profit')}</th>
                <th className="num">{t('costPerUnit')}</th></>}<th className="num">{t('inStock')}</th></tr></thead>
            <tbody>{rows.map(r => { const f = r.period; return (
              <tr key={r.batch.id}>
                <td><Link to={`/t/${tenant.id}/organic/batches/${r.batch.id}`}>{r.batch.name}</Link><small> · {t(`status.${r.batch.status}`)}</small></td>
                <td className="num">{formatNumber(f.inputKg, lang, 1)}</td><td className="num">{formatNumber(f.output, lang, 1)} {t(f.outputUnit === 'litre' ? 'litres' : 'kg')}</td>
                <td className="num">{f.yieldPercent == null ? '—' : `${formatNumber(f.yieldPercent, lang, 1)}%`}</td>
                {showMoney && <><td className="num">{m(f.costs)}</td><td className="num">{m(f.movedOut)}</td><td className="num">{m(f.revenue)}</td>
                  <td className="num"><b>{m(f.profit)}</b></td><td className="num">{m(f.costPerUnit)}</td></>}
                <td className="num">{formatNumber(f.inStock, lang, 1)}</td>
              </tr>); })}</tbody>
          </table>
          <p className="hint">{t('batchesHelpReport')}</p>
        </div>
      )}
      {transfers && <>
        <div className="section-head section-space"><h3>{ts('transfer.report')}</h3>
          <ExportBar path={`${base}/stock/internal-transfers?${q}`} name={`internal-transfers-${range.from}-${range.to}`} onError={setError} /></div>
        <TransfersTable rows={transfers} />
      </>}
    </div>
  );
}
