import { useEffect, useState } from 'react';
import { api } from '../api/client';
import { useTenant } from '../auth/useCurrentTenant';
import { ErrorBanner, Loading } from '../components/common';
import { currentLanguage, useNs } from '../i18n';
import { describeError } from '../lib/errors';
import { formatDate, formatMoney, formatNumber, today } from '../lib/format';
import type { Reconciliation, Valuation } from './types';
import { baseUnitLabel } from './types';
import { ExportBar } from '../reports/ReportTools';

/** Stock value on any date (recomputed from movements) and the check that the ledger agrees with the store. */
export default function StockReportsPage() {
  const tenant = useTenant();
  const t = useNs('stock');
  const tf = useNs('fin');
  const lang = currentLanguage();
  const base = `/api/v1/tenants/${tenant.id}/stock/reports`;
  const [asOf, setAsOf] = useState(today());
  const [valuation, setValuation] = useState<Valuation | null>(null);
  const [rec, setRec] = useState<Reconciliation | null>(null);
  const [error, setError] = useState('');

  useEffect(() => { api<Reconciliation>(`${base}/reconciliation`).then(setRec).catch(e => setError(describeError(e))); }, [base]);
  useEffect(() => {
    let alive = true;
    setValuation(null);
    if (!asOf) return;
    api<Valuation>(`${base}/valuation?asOf=${asOf}`).then(v => alive && setValuation(v)).catch(e => alive && setError(describeError(e)));
    return () => { alive = false; };
  }, [base, asOf]);

  return (
    <>
      <div className="crop-page-head"><div><h2>{t('reports')}</h2></div></div>
      <ErrorBanner message={error} />
      {rec && (
        <div className="finance-stats">
          <div className="finance-stat"><span>{t('ledgerStock')}</span><b>{formatMoney(rec.ledgerBalance, lang)}</b></div>
          <div className="finance-stat"><span>{t('storeValue')}</span><b>{formatMoney(rec.stockValue, lang)}</b></div>
          <div className="finance-stat"><span>{t('difference')}</span><b>{formatMoney(rec.difference, lang)}</b>
            <small className={rec.difference === 0 ? '' : 'low-stock-label'}>{rec.difference === 0 ? t('reconciled') : t('notReconciled')}</small></div>
        </div>
      )}
      <div className="section-head section-space">
        <h3>{t('valuationTitle')}</h3>
        {asOf && <ExportBar path={`${base}/valuation?asOf=${asOf}`} name={`stock-value-${asOf}`} onError={setError} />}
      </div>
      <div className="form-card form-grid filters">
        <label>{tf('asOf')}<input type="date" value={asOf} onChange={e => setAsOf(e.target.value)} /></label>
      </div>
      <p className="hint">{tf('asOfLabel', { date: formatDate(asOf, lang) })} · {tf('actualFigures')}</p>
      {!valuation ? <Loading /> : valuation.items.length === 0 ? <p className="hint">{t('noStock')}</p> : (
        <table className="data-table report">
          <thead><tr><th>{t('item')}</th><th className="num">{t('quantity')}</th><th className="num">{t('value')}</th></tr></thead>
          <tbody>
            {valuation.items.map(r => (
              <tr key={r.itemId}><td>{r.name}</td><td className="num">{formatNumber(r.quantity, lang, 4)} {baseUnitLabel(r, t)}</td><td className="num">{formatMoney(r.value, lang)}</td></tr>
            ))}
            <tr className="total"><td colSpan={2}>{t('totalValue')}</td><td className="num">{formatMoney(valuation.totalValue, lang)}</td></tr>
          </tbody>
        </table>
      )}
    </>
  );
}
