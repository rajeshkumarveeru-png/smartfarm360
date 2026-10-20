import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { Download } from 'lucide-react';
import { api, download } from '../api/client';
import type { Permission } from '../api/types';
import { useTenant } from '../auth/useCurrentTenant';
import { ErrorBanner, Loading } from '../components/common';
import { currentLanguage, useNs } from '../i18n';
import { describeError } from '../lib/errors';
import { formatDate, formatMoney, formatNumber } from '../lib/format';
import { can } from '../lib/nav';
import type { Alerts, Location, OnHand, Reconciliation } from './types';
import { baseUnitLabel } from './types';

export type StockRecordKind = 'issue' | 'transfer' | 'wastage' | 'count' | 'produce' | 'purchase-return' | 'sale-return';

export const STOCK_ACTIONS: { kind: StockRecordKind; permission: Permission }[] = [
  { kind: 'issue', permission: 'INVENTORY_WRITE' },
  { kind: 'produce', permission: 'INVENTORY_WRITE' },
  { kind: 'transfer', permission: 'INVENTORY_WRITE' },
  { kind: 'wastage', permission: 'INVENTORY_WRITE' },
  { kind: 'count', permission: 'INVENTORY_WRITE' },
  { kind: 'purchase-return', permission: 'FINANCE_WRITE' },
  { kind: 'sale-return', permission: 'FINANCE_WRITE' },
];

/** Stock on hand, alerts and the ledger check, with shortcuts to record stock changes. */
export default function StockHome() {
  const tenant = useTenant();
  const t = useNs('stock');
  const tf = useNs('fin');
  const lang = currentLanguage();
  const base = `/api/v1/tenants/${tenant.id}/stock`;
  const [locationId, setLocationId] = useState('');
  const [locations, setLocations] = useState<Location[]>([]);
  const [onHand, setOnHand] = useState<OnHand | null>(null);
  const [alerts, setAlerts] = useState<Alerts | null>(null);
  const [rec, setRec] = useState<Reconciliation | null>(null);
  const [error, setError] = useState('');

  useEffect(() => {
    let alive = true;
    Promise.all([api<Location[]>(`${base}/locations`), api<Alerts>(`${base}/reports/alerts`), api<Reconciliation>(`${base}/reports/reconciliation`)])
      .then(([l, a, r]) => { if (alive) { setLocations(l); setAlerts(a); setRec(r); } })
      .catch(e => alive && setError(describeError(e)));
    return () => { alive = false; };
  }, [base]);

  useEffect(() => {
    let alive = true;
    setOnHand(null);
    api<OnHand>(`${base}/reports/on-hand${locationId ? `?locationId=${locationId}` : ''}`)
      .then(o => alive && setOnHand(o)).catch(e => alive && setError(describeError(e)));
    return () => { alive = false; };
  }, [base, locationId]);

  const actions = STOCK_ACTIONS.filter(a => can(tenant, a.permission));
  return (
    <>
      <div className="crop-page-head">
        <div><h2>{t('title')}</h2><p>{t('subtitle')}</p></div>
        <button type="button" className="secondary" onClick={() => download(`${base}/reports/on-hand.csv`, 'stock-on-hand.csv').catch(e => setError(describeError(e)))}>
          <Download size={16} aria-hidden />{t('exportCsv')}</button>
      </div>
      <ErrorBanner message={error} />
      {actions.length > 0 && (
        <div className="quick-actions">
          {actions.map(a => <Link key={a.kind} className="secondary" to={`/t/${tenant.id}/stock/new/${a.kind}`}>{t(`kind.${a.kind}`)}</Link>)}
          {can(tenant, 'FINANCE_WRITE') && <Link className="secondary" to={`/t/${tenant.id}/finance/new/expense`}>{t('buyStock')}</Link>}
          {can(tenant, 'FINANCE_WRITE') && <Link className="secondary" to={`/t/${tenant.id}/finance/new/sale`}>{t('sellStock')}</Link>}
        </div>
      )}
      {rec && rec.difference !== 0 && <div className="error-banner" role="alert">{t('reconciliationProblem', { amount: formatMoney(rec.difference, lang) })}</div>}
      {alerts && (alerts.lowStock.length > 0 || alerts.expiringLots.length > 0) && (
        <div className="finance-stats">
          {alerts.lowStock.map(i => (
            <div className="finance-stat" key={`low-${i.id}`}><span>{t('lowStock')}</span>
              <b><Link to={`/t/${tenant.id}/stock/items/${i.id}`}>{i.name}</Link></b>
              <small className="low-stock-label">{formatNumber(i.quantity, lang, 4)} {baseUnitLabel(i, t)} · {t('reorderAt', { level: formatNumber(i.reorderLevel, lang, 4) })}</small></div>
          ))}
          {alerts.expiringLots.map(l => (
            <div className="finance-stat" key={`lot-${l.lotId}`}><span>{l.expired ? t('expired') : t('expiringSoon')}</span>
              <b><Link to={`/t/${tenant.id}/stock/items/${l.itemId}`}>{l.itemName}</Link></b>
              <small className="low-stock-label">{t('lot')} {l.lotNo} · {formatDate(l.expiryDate, lang)} · {formatNumber(l.quantity, lang, 4)}</small></div>
          ))}
        </div>
      )}
      <div className="section-head section-space">
        <h3>{t('onHand')}</h3>
        <label className="inline-fields">{t('location')}
          <select value={locationId} onChange={e => setLocationId(e.target.value)}>
            <option value="">{t('allLocations')}</option>{locations.filter(l => l.active).map(l => <option key={l.id} value={l.id}>{l.name}</option>)}</select>
        </label>
      </div>
      {!onHand ? <Loading /> : onHand.items.length === 0 ? <p className="hint">{t('noStock')}</p> : (
        <div className="table-scroll">
          <table className="data-table">
            <thead><tr><th>{t('item')}</th><th className="num">{t('quantity')}</th><th className="num">{t('unitCost')}</th><th className="num">{t('value')}</th><th>{t('where')}</th></tr></thead>
            <tbody>
              {onHand.items.map(({ item, locations: where }) => {
                // One average cost per item (E2): a location's share is its quantity at that cost
                const quantity = locationId ? where.reduce((s, w) => s + Number(w.quantity), 0) : item.quantity;
                const value = locationId ? Math.round(quantity * item.unitCost * 100) / 100 : item.value;
                return (
                <tr key={item.id}>
                  <td><Link to={`/t/${tenant.id}/stock/items/${item.id}`}>{item.name}</Link>{item.lowStock && <small className="low-stock-label"> · {t('lowStock')}</small>}
                    {item.needsReview && <small className="low-stock-label"> · {tf('needsReview')}</small>}</td>
                  <td className="num">{formatNumber(quantity, lang, 4)} {baseUnitLabel(item, t)}</td>
                  <td className="num">{Number(item.value) === 0 && Number(item.quantity) > 0 ? t('nilValue') : formatMoney(item.unitCost, lang)}</td>
                  <td className="num">{formatMoney(value, lang)}</td>
                  <td>{where.map(w => `${w.locationName}${w.lotNo ? ` (${w.lotNo})` : ''}: ${formatNumber(w.quantity, lang, 4)}`).join(' · ')}</td>
                </tr>
                );
              })}
            </tbody>
            {!locationId && <tfoot><tr className="total"><td colSpan={3}>{t('totalValue')}</td><td className="num">{formatMoney(onHand.totalValue, lang)}</td><td /></tr></tfoot>}
          </table>
        </div>
      )}
      <p className="hint">{t('valuationHelp')}</p>
    </>
  );
}
