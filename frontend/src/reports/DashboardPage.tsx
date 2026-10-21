import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import { useTenant } from '../auth/useCurrentTenant';
import { ErrorBanner, Loading } from '../components/common';
import { useCategoryLabel } from '../finance/useFinanceData';
import { currentLanguage, useNs } from '../i18n';
import { describeError } from '../lib/errors';
import { formatDate, formatMoney, today } from '../lib/format';
import TrendChart from './TrendChart';
import type { Attention, Dashboard } from './types';

/** Where each "needs attention" item is dealt with (dashboard and reports design, section 3C). */
export const ATTENTION_LINKS: Record<string, string> = {
  OVERDUE_SALES: 'finance/reports?tab=dues', BILLS_DUE_SOON: 'finance/reports?tab=dues', OLD_DRAFTS: 'finance/records',
  LOW_STOCK: 'stock', EXPIRING_LOTS: 'stock', STOCK_NOT_RECONCILED: 'stock/reports',
  WAGES_OWED: 'workforce/pay', ADVANCES_OUT: 'workforce/pay', WORKERS_NOT_RECONCILED: 'workforce/reports', ATTENDANCE_MISSING: 'workforce',
  MAINTENANCE_DUE: 'assets/maintenance', UNREGISTERED_ASSETS: 'assets', ASSETS_NOT_RECONCILED: 'assets/depreciation', DEPRECIATION_DUE: 'assets/depreciation',
  MILK_WITHHELD: 'livestock/milk', BREEDING_DUE: 'livestock/care', HEALTH_DUE: 'livestock/care', MILK_NOT_RECORDED: 'livestock/milk',
  BATCH_RECORD_MISSING: 'livestock/poultry', HIGH_MORTALITY: 'livestock/poultry',
  WATER_OUT_OF_LIMITS: 'aquaculture/water', POND_RECORD_MISSING: 'aquaculture', HARVEST_OVERDUE: 'aquaculture', ORGANIC_OVERDUE: 'organic',
};

/** The farm business at a glance: money, the last 12 months, crops in the field and what needs attention (H1–H4). */
export default function DashboardPage() {
  const tenant = useTenant();
  const t = useNs('dashboard');
  const tf = useNs('fin');
  const tcrop = useNs('crop');
  const lang = currentLanguage();
  const categoryLabel = useCategoryLabel();
  const [data, setData] = useState<Dashboard | null>(null);
  const [error, setError] = useState('');

  useEffect(() => {
    setData(null);
    api<Dashboard>(`/api/v1/tenants/${tenant.id}/dashboard?asOf=${today()}`).then(setData).catch(e => setError(describeError(e)));
  }, [tenant.id]);

  if (!data) return error ? <ErrorBanner message={error} /> : <Loading />;
  const m = data.money;
  const attentionText = (a: Attention) => t(`attention.${a.code}`, { count: String(a.count), amount: a.amount != null ? formatMoney(a.amount, lang) : '',
    detail: a.detail ? (/^\d{4}-\d{2}-\d{2}$/.test(a.detail) ? formatDate(a.detail, lang) : a.detail) : '' });
  return (
    <section className="dashboard">
      <div className="crop-page-head"><div><h2>{t('title')}</h2><p>{t('asOf', { date: formatDate(data.asOf, lang) })} · {tf('actualFigures')}</p></div></div>
      {data.errors.length > 0 && <p className="hint" role="status">{t('someTilesFailed', { tiles: data.errors.map(e => t(`tile.${e.tile}`)).join(', ') })}</p>}

      {m && (
        <div className="finance-stats">
          <Link className="finance-stat" to={`/t/${tenant.id}/finance/accounts`}><span>{tf('cashAndBank')}</span><b>{formatMoney(m.cashTotal, lang)}</b>
            <small>{m.accounts.slice(0, 3).map(a => `${a.name}: ${formatMoney(a.balance, lang)}`).join(' · ')}</small></Link>
          <Link className="finance-stat" to={`/t/${tenant.id}/finance/reports?tab=dues`}><span>{tf('receivable')}</span><b>{formatMoney(m.receivableTotal, lang)}</b>
            <small>{tf('overdue', { amount: formatMoney(m.overdueReceivables, lang) })}</small></Link>
          <Link className="finance-stat" to={`/t/${tenant.id}/finance/reports?tab=dues`}><span>{tf('payable')}</span><b>{formatMoney(m.payableTotal, lang)}</b>
            {m.loanTotal !== 0 && <small>{tf('loans')}: {formatMoney(m.loanTotal, lang)}</small>}</Link>
          <div className="finance-stat"><span>{t('thisMonth')}</span><b>{formatMoney(m.monthOperatingProfit, lang)}</b>
            <small>{t('income')} {formatMoney(m.monthRevenue, lang)} · {t('costs')} {formatMoney(m.monthCosts, lang)}</small></div>
          <div className="finance-stat"><span>{t('thisYear', { from: formatDate(m.fiscalYearFrom, lang) })}</span><b>{formatMoney(m.yearNetProfit, lang)}</b>
            <small>{t('income')} {formatMoney(m.yearRevenue, lang)} · {t('costs')} {formatMoney(m.yearCosts, lang)}</small></div>
        </div>
      )}

      <div className="dashboard-grid">
        {data.trend && (
          <div className="form-card">
            <h3>{t('trend')}</h3>
            <TrendChart months={data.trend.months} />
            {data.trend.topCosts.length > 0 && <>
              <h4>{t('topCosts')}</h4>
              <ul className="plain-list">{data.trend.topCosts.map(c => (
                <li key={`${c.accountCode}-${c.categoryId}`}>{c.categoryId ? categoryLabel({ code: c.categoryCode, name: c.categoryName }) : tf(`ledger.${c.accountCode}`)}
                  {' · '}<b>{formatMoney(c.amount, lang)}</b>{c.share != null && <small> · {c.share}%</small>}</li>))}</ul>
            </>}
          </div>
        )}

        <div className="form-card">
          <h3>{t('needsAttention')}</h3>
          {data.attention.length === 0 ? <p className="hint">{t('allClear')}</p> : (
            <ul className="attention-list">
              {data.attention.map(a => (
                <li key={a.code}><Link to={`/t/${tenant.id}/${ATTENTION_LINKS[a.code] ?? ''}`}>{attentionText(a)}</Link></li>
              ))}
            </ul>
          )}
        </div>

        {data.crops && (
          <div className="form-card">
            <h3>{t('cropsInField')}</h3>
            {data.crops.length === 0 ? <p className="hint">{t('noCrops')}</p> : (
              <ul className="plain-list">
                {data.crops.map(c => (
                  <li key={c.cycleId}>
                    <Link to={`/t/${tenant.id}/crops/cycles/${c.cycleId}`}>{c.name}</Link> · {c.cropName} · {tcrop(`stage.${c.status}`)}
                    {c.daysSinceSowing != null && ` · ${tcrop('daysSinceSowing', { days: String(c.daysSinceSowing) })}`}
                    {c.profitToDate != null && <> · {t('profitToDate')}: <b>{formatMoney(c.profitToDate, lang)}</b></>}
                    {c.unsoldEstimate != null && <small> · {t('unsoldEstimate')}: {formatMoney(c.unsoldEstimate, lang)}</small>}
                  </li>
                ))}
              </ul>
            )}
          </div>
        )}
      </div>
    </section>
  );
}
