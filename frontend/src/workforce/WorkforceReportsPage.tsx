import { useEffect, useState } from 'react';
import { api } from '../api/client';
import { useTenant } from '../auth/useCurrentTenant';
import { ErrorBanner, Loading } from '../components/common';
import { currentLanguage, useNs } from '../i18n';
import { describeError } from '../lib/errors';
import { formatMoney, formatNumber, today } from '../lib/format';
import type { LabourByActivity, MonthSheet, Reconciliation } from './types';

/** Labour days and cost by crop, the month's muster summary, and wages and advances against the ledger. */
export default function WorkforceReportsPage() {
  const tenant = useTenant();
  const t = useNs('workforce');
  const tf = useNs('fin');
  const lang = currentLanguage();
  const base = `/api/v1/tenants/${tenant.id}/workforce`;
  const [month, setMonth] = useState(today().slice(0, 7));
  const [labour, setLabour] = useState<LabourByActivity | null>(null);
  const [sheet, setSheet] = useState<MonthSheet | null>(null);
  const [rec, setRec] = useState<Reconciliation | null>(null);
  const [error, setError] = useState('');

  useEffect(() => {
    const [y, m] = month.split('-').map(Number);
    const to = `${month}-${String(new Date(y, m, 0).getDate()).padStart(2, '0')}`;
    setLabour(null); setSheet(null);
    Promise.all([api<LabourByActivity>(`${base}/reports/labour?from=${month}-01&to=${to}`), api<MonthSheet>(`${base}/attendance-sheet?month=${month}`),
      api<Reconciliation>(`${base}/reports/reconciliation`)])
      .then(([l, s, r]) => { setLabour(l); setSheet(s); setRec(r); }).catch(e => setError(describeError(e)));
  }, [base, month]);

  const reconciled = rec && rec.workersOwed === rec.ledgerWagesPayable && rec.workersAdvance === rec.ledgerAdvances;
  return (
    <>
      <div className="crop-page-head"><div><h2>{t('reports')}</h2></div></div>
      <ErrorBanner message={error} />
      <div className="form-card form-grid filters"><label>{t('month')}<input type="month" value={month} onChange={e => setMonth(e.target.value)} /></label></div>
      <p className="hint">{tf('actualFigures')}</p>
      <h3>{t('labourByActivity')}</h3>
      {!labour ? <Loading /> : labour.rows.length === 0 ? <p className="hint">{t('noLabour')}</p> : (
        <table className="data-table report">
          <thead><tr><th>{tf('activity')}</th><th className="num">{t('days')}</th><th className="num">{t('cost')}</th></tr></thead>
          <tbody>
            {labour.rows.map(r => <tr key={r.costObjectId}><td>{r.kind === 'OVERHEAD' ? tf('overhead') : r.name}</td>
              <td className="num">{formatNumber(r.days, lang, 1)}</td><td className="num">{formatMoney(r.cost, lang)}</td></tr>)}
            <tr className="total"><td colSpan={2}>{t('total')}</td><td className="num">{formatMoney(labour.totalCost, lang)}</td></tr>
          </tbody>
        </table>
      )}
      <h3 className="section-space">{t('musterSummary')}</h3>
      {!sheet ? <Loading /> : sheet.workers.length === 0 ? <p className="hint">{t('noWorkers')}</p> : (
        <div className="table-scroll">
          <table className="data-table">
            <thead><tr><th>{t('worker')}</th><th className="num">{t('status.PRESENT')}</th><th className="num">{t('status.HALF_DAY')}</th><th className="num">{t('status.ABSENT')}</th>
              <th className="num">{t('status.PAID_LEAVE')}</th><th className="num">{t('status.UNPAID_LEAVE')}</th><th className="num">{t('days')}</th></tr></thead>
            <tbody>{sheet.workers.map(w => <tr key={w.workerId}><td>{w.workerName}</td>
              {[w.present, w.halfDays, w.absent, w.paidLeave, w.unpaidLeave, w.days].map((v, i) => <td key={i} className="num">{formatNumber(v, lang, 1)}</td>)}</tr>)}</tbody>
          </table>
        </div>
      )}
      {rec && (
        <div className="finance-stats section-space">
          <div className="finance-stat"><span>{t('owedToWorkers')}</span><b>{formatMoney(rec.workersOwed, lang)}</b><small>{t('ledger')}: {formatMoney(rec.ledgerWagesPayable, lang)}</small></div>
          <div className="finance-stat"><span>{t('advancesOut')}</span><b>{formatMoney(rec.workersAdvance, lang)}</b><small>{t('ledger')}: {formatMoney(rec.ledgerAdvances, lang)}</small></div>
          <div className="finance-stat"><span>{t('check')}</span><b>{reconciled ? t('agrees') : t('disagrees')}</b></div>
        </div>
      )}
    </>
  );
}
