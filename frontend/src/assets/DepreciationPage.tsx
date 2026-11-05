import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import { useTenant } from '../auth/useCurrentTenant';
import { ErrorBanner, Loading } from '../components/common';
import { Toast } from '../components/Toast';
import { newIdempotencyKey } from '../finance/useFinanceData';
import { currentLanguage, useNs } from '../i18n';
import { describeError } from '../lib/errors';
import { formatMoney, today } from '../lib/format';
import { can } from '../lib/nav';
import type { AssetReconciliation, RunPreview, RunSummary } from './types';

const lastMonth = () => {
  const d = new Date();
  const x = new Date(d.getFullYear(), d.getMonth() - 1, 1);
  return `${x.getFullYear()}-${String(x.getMonth() + 1).padStart(2, '0')}`;
};

/** Monthly depreciation runs (preview, then post one month at a time, G9) and the register against the ledger. */
export default function DepreciationPage() {
  const tenant = useTenant();
  const t = useNs('assets');
  const tf = useNs('fin');
  const lang = currentLanguage();
  const base = `/api/v1/tenants/${tenant.id}/assets`;
  const canManage = can(tenant, 'FINANCE_MANAGE');
  const [month, setMonth] = useState(lastMonth);
  const [preview, setPreview] = useState<RunPreview | null>(null);
  const [runs, setRuns] = useState<RunSummary[] | null>(null);
  const [rec, setRec] = useState<AssetReconciliation | null>(null);
  const [key, setKey] = useState(newIdempotencyKey);
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  const load = useCallback(() => Promise.all([api<RunSummary[]>(`${base}/depreciation-runs`), api<AssetReconciliation>(`${base}/reports/reconciliation`)])
    .then(([r, c]) => { setRuns(r); setRec(c); }).catch(e => setError(describeError(e))), [base]);
  useEffect(() => { void load(); }, [load]);

  async function run(action: () => Promise<void>) {
    setBusy(true); setError(''); setMessage('');
    try { await action(); } catch (e) { setError(describeError(e)); } finally { setBusy(false); }
  }

  return (
    <>
      <div className="crop-page-head"><div><h2>{t('depreciation')}</h2><p>{t('depreciationHelp')}</p></div></div>
      <ErrorBanner message={error} />
      <Toast message={message} onClose={() => setMessage('')} />
      {canManage && (
        <>
          <form className="form-card form-grid filters" onSubmit={e => { e.preventDefault(); void run(async () => setPreview(await api<RunPreview>(`${base}/depreciation-runs/preview`, { method: 'POST', body: { month } }))); }}>
            <label>{t('month')}<input type="month" max={today().slice(0, 7)} value={month} onChange={e => { setMonth(e.target.value); setPreview(null); }} /></label>
            <div className="form-actions"><button className="secondary" disabled={busy}>{t('calculate')}</button></div>
          </form>
          {preview && (preview.assets.length === 0 ? <p className="hint">{t('nothingToDepreciate', { month: preview.month })}</p> : (
            <>
              <table className="data-table report">
                <thead><tr><th>{t('name')}</th><th className="num">{t('depreciationAmount')}</th><th className="num">{t('bookValueAfter')}</th></tr></thead>
                <tbody>
                  {preview.assets.map(a => <tr key={a.assetId}><td>{a.name}</td><td className="num">{formatMoney(a.amount, lang)}</td><td className="num">{formatMoney(a.bookValueAfter, lang)}</td></tr>)}
                  <tr className="total"><td>{t('total')}</td><td className="num">{formatMoney(preview.total, lang)}</td><td /></tr>
                </tbody>
              </table>
              <div className="form-actions">
                <button type="button" className="primary" disabled={busy} onClick={() => void run(async () => {
                  await api(`${base}/depreciation-runs`, { method: 'POST', body: { month: preview.month }, headers: { 'Idempotency-Key': key } });
                  setMessage(t('runPosted', { month: preview.month })); setPreview(null); setKey(newIdempotencyKey()); await load();
                })}>{t('postRun', { month: preview.month })}</button>
              </div>
            </>
          ))}
        </>
      )}

      <h3 className="section-space">{t('runs')}</h3>
      {!runs ? <Loading /> : runs.length === 0 ? <p className="hint">{t('noRuns')}</p> : (
        <table className="data-table report">
          <thead><tr><th>{t('month')}</th><th>{t('record')}</th><th className="num">{t('total')}</th></tr></thead>
          <tbody>{runs.map(r => (
            <tr key={r.documentId}><td>{r.month}</td>
              <td>{can(tenant, 'FINANCE_READ') ? <Link to={`/t/${tenant.id}/finance/records/${r.documentId}`}>{r.number}</Link> : r.number}
                {r.status !== 'POSTED' && <small> · {tf(`status.${r.status}`)}</small>}</td>
              <td className="num">{formatMoney(r.total, lang)}</td></tr>
          ))}</tbody>
        </table>
      )}
      <p className="hint">{t('reverseHelp')}</p>

      {rec && (
        <>
          <h3 className="section-space">{t('reconciliation')}</h3>
          <table className="data-table report">
            <thead><tr><th /><th className="num">{t('register')}</th><th className="num">{t('ledger')}</th></tr></thead>
            <tbody>
              <tr><td>{t('cost')}</td><td className="num">{formatMoney(rec.registerCost, lang)}</td><td className="num">{formatMoney(rec.ledgerCost, lang)}</td></tr>
              <tr><td>{t('accumulated')}</td><td className="num">{formatMoney(rec.registerAccumulated, lang)}</td><td className="num">{formatMoney(rec.ledgerAccumulated, lang)}</td></tr>
              <tr><td>{t('unregisteredCost')}</td><td /><td className="num">{formatMoney(rec.unregisteredCost, lang)}</td></tr>
              <tr><td>{t('manualDepreciation')}</td><td /><td className="num">{formatMoney(rec.manualDepreciation, lang)}</td></tr>
              <tr className="total"><td>{t('unexplained')}</td><td /><td className="num">{formatMoney(rec.unexplained, lang)}</td></tr>
            </tbody>
          </table>
          <p className="hint">{rec.unexplained === 0 ? t('reconciled') : t('notReconciled')}</p>
        </>
      )}
    </>
  );
}
