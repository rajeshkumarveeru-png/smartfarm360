import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { ArrowDownLeft, ArrowUpRight, ChevronRight, CircleCheck, Factory, Fish, HardHat, Landmark, Leaf, Milk, Package, Sprout, Tractor,
  TrendingDown, TrendingUp, TriangleAlert, Wallet, type LucideIcon } from 'lucide-react';
import { api } from '../api/client';
import { useTenant } from '../auth/useCurrentTenant';
import { ErrorBanner } from '../components/common';
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
  WATER_OUT_OF_LIMITS: 'aquaculture/water', POND_RECORD_MISSING: 'aquaculture', HARVEST_OVERDUE: 'aquaculture', ORGANIC_OVERDUE: 'organic', PROCESSING_OVERDUE: 'processing',
};

/** The icon for an attention item, by the section its link goes to (presentation only). */
const AREA_ICONS: Record<string, LucideIcon> = {
  finance: Wallet, stock: Package, workforce: HardHat, assets: Tractor, livestock: Milk, aquaculture: Fish, organic: Leaf, processing: Factory,
};
const areaIcon = (code: string) => AREA_ICONS[(ATTENTION_LINKS[code] ?? '').split('/')[0]] ?? TriangleAlert;

/** Placeholder blocks in the shape of the page while it loads, so nothing jumps when the figures arrive. */
function DashboardSkeleton({ label }: { label: string }) {
  return (
    <section className="dashboard db-page" aria-busy="true">
      <div role="status" className="sr-only">{label}</div>
      <div className="db-head"><div className="sf-skeleton" style={{ width: 220, height: 34 }} /><div className="sf-skeleton" style={{ width: 280, height: 16, marginTop: 10 }} /></div>
      <div className="db-kpis">{Array.from({ length: 5 }, (_, i) => <div key={i} className="db-kpi db-kpi-skeleton"><div className="sf-skeleton" style={{ width: '55%', height: 12 }} />
        <div className="sf-skeleton" style={{ width: '75%', height: 26 }} /><div className="sf-skeleton" style={{ width: '90%', height: 12 }} /></div>)}</div>
      <div className="db-grid"><div className="db-card db-span"><div className="sf-skeleton" style={{ height: 260 }} /></div>
        <div className="db-card"><div className="sf-skeleton" style={{ height: 260 }} /></div></div>
    </section>
  );
}

/** The farm business at a glance: money, the last 12 months, crops in the field and what needs attention (H1–H4). */
export default function DashboardPage() {
  const tenant = useTenant();
  const t = useNs('dashboard');
  const tf = useNs('fin');
  const tcrop = useNs('crop');
  const tc = useNs('common');
  const lang = currentLanguage();
  const categoryLabel = useCategoryLabel();
  const [data, setData] = useState<Dashboard | null>(null);
  const [error, setError] = useState('');

  const load = useCallback(() => {
    setData(null);
    setError('');
    api<Dashboard>(`/api/v1/tenants/${tenant.id}/dashboard?asOf=${today()}`).then(setData).catch(e => setError(describeError(e)));
  }, [tenant.id]);
  useEffect(load, [load]);

  if (!data) return error ? <ErrorBanner message={error} onRetry={load} /> : <DashboardSkeleton label={tc('loading')} />;
  const m = data.money;
  const money = (v: number) => formatMoney(v, lang);
  const tone = (v: number) => (v > 0 ? ' positive' : v < 0 ? ' negative' : '');
  const attentionText = (a: Attention) => t(`attention.${a.code}`, { count: String(a.count), amount: a.amount != null ? money(a.amount) : '',
    detail: a.detail ? (/^\d{4}-\d{2}-\d{2}$/.test(a.detail) ? formatDate(a.detail, lang) : a.detail) : '' });
  return (
    <section className="dashboard db-page">
      <div className="db-head">
        <h2>{t('title')}</h2>
        <p>{t('asOf', { date: formatDate(data.asOf, lang) })}<span className="db-dot" aria-hidden>·</span>{tf('actualFigures')}</p>
      </div>
      {data.errors.length > 0 && <p className="hint db-partial" role="status"><TriangleAlert size={15} aria-hidden />
        {t('someTilesFailed', { tiles: data.errors.map(e => t(`tile.${e.tile}`)).join(', ') })}</p>}

      {m && (
        <div className="finance-stats db-kpis">
          <Link className="finance-stat db-kpi" to={`/t/${tenant.id}/finance/accounts`}>
            <span className="db-kpi-label"><i className="db-kpi-icon tone-brand"><Wallet size={16} aria-hidden /></i>{tf('cashAndBank')}</span>
            <b>{money(m.cashTotal)}</b>
            <small>{m.accounts.slice(0, 3).map(a => `${a.name}: ${money(a.balance)}`).join(' · ')}</small>
          </Link>
          <Link className="finance-stat db-kpi" to={`/t/${tenant.id}/finance/reports?tab=dues`}>
            <span className="db-kpi-label"><i className="db-kpi-icon tone-income"><ArrowDownLeft size={16} aria-hidden /></i>{tf('receivable')}</span>
            <b>{money(m.receivableTotal)}</b>
            <small className={m.overdueReceivables > 0 ? 'db-warn' : ''}>{tf('overdue', { amount: money(m.overdueReceivables) })}</small>
          </Link>
          <Link className="finance-stat db-kpi" to={`/t/${tenant.id}/finance/reports?tab=dues`}>
            <span className="db-kpi-label"><i className="db-kpi-icon tone-costs"><ArrowUpRight size={16} aria-hidden /></i>{tf('payable')}</span>
            <b>{money(m.payableTotal)}</b>
            {m.loanTotal !== 0 && <small>{tf('loans')}: {money(m.loanTotal)}</small>}
          </Link>
          <div className={`finance-stat db-kpi${tone(m.monthOperatingProfit)}`}>
            <span className="db-kpi-label"><i className="db-kpi-icon tone-profit">{m.monthOperatingProfit < 0 ? <TrendingDown size={16} aria-hidden /> : <TrendingUp size={16} aria-hidden />}</i>{t('thisMonth')}</span>
            <b>{money(m.monthOperatingProfit)}</b>
            <small>{t('income')} {money(m.monthRevenue)} · {t('costs')} {money(m.monthCosts)}</small>
          </div>
          <div className={`finance-stat db-kpi${tone(m.yearNetProfit)}`}>
            <span className="db-kpi-label"><i className="db-kpi-icon tone-profit"><Landmark size={16} aria-hidden /></i>{t('thisYear', { from: formatDate(m.fiscalYearFrom, lang) })}</span>
            <b>{money(m.yearNetProfit)}</b>
            <small>{t('income')} {money(m.yearRevenue)} · {t('costs')} {money(m.yearCosts)}</small>
          </div>
        </div>
      )}

      <div className="dashboard-grid db-grid">
        {data.trend && (
          <div className="db-card db-span">
            <div className="db-card-head"><h3>{t('trend')}</h3></div>
            <TrendChart months={data.trend.months} />
            {data.trend.topCosts.length > 0 && <div className="db-top-costs">
              <h4>{t('topCosts')}</h4>
              <ul className="plain-list">{data.trend.topCosts.map(c => (
                <li key={`${c.accountCode}-${c.categoryId}`}>
                  <span className="db-cost-name">{c.categoryId ? categoryLabel({ code: c.categoryCode, name: c.categoryName }) : tf(`ledger.${c.accountCode}`)}</span>
                  <span className="db-cost-figures">{' · '}<b>{money(c.amount)}</b>{c.share != null && <small> · {c.share}%</small>}</span>
                  {c.share != null && <span className="db-cost-bar" aria-hidden><span style={{ width: `${Math.min(100, Math.max(0, c.share))}%` }} /></span>}
                </li>))}</ul>
            </div>}
          </div>
        )}

        <div className="db-card">
          <div className="db-card-head">
            <h3>{t('needsAttention')}</h3>
            {data.attention.length > 0 && <span className="count db-count-warn" aria-label={t('attentionCount', { count: String(data.attention.length) })}>{data.attention.length}</span>}
          </div>
          {data.attention.length === 0 ? (
            <div className="db-all-clear"><CircleCheck size={28} aria-hidden /><p className="hint">{t('allClear')}</p><small>{t('allClearHelp')}</small></div>
          ) : (
            <ul className="attention-list">
              {data.attention.map(a => {
                const Icon = areaIcon(a.code);
                return (
                  <li key={a.code}><Link to={`/t/${tenant.id}/${ATTENTION_LINKS[a.code] ?? ''}`}>
                    <i className="db-attention-icon" aria-hidden><Icon size={16} /></i><span>{attentionText(a)}</span><ChevronRight className="db-chevron" size={16} aria-hidden />
                  </Link></li>
                );
              })}
            </ul>
          )}
        </div>

        {data.crops && (
          <div className="db-card">
            <div className="db-card-head"><h3>{t('cropsInField')}</h3>{data.crops.length > 0 && <span className="count">{data.crops.length}</span>}</div>
            {data.crops.length === 0 ? <div className="db-all-clear muted"><Sprout size={28} aria-hidden /><p className="hint">{t('noCrops')}</p></div> : (
              <ul className="db-crop-list">
                {data.crops.map(c => (
                  <li key={c.cycleId}>
                    <i className="db-attention-icon tone-brand" aria-hidden><Sprout size={16} /></i>
                    <div>
                      <Link to={`/t/${tenant.id}/crops/cycles/${c.cycleId}`}>{c.name}</Link>
                      <small>{c.cropName} · <span className={`stage stage-${c.status.toLowerCase()}`}>{tcrop(`stage.${c.status}`)}</span>
                        {c.daysSinceSowing != null && ` · ${tcrop('daysSinceSowing', { days: String(c.daysSinceSowing) })}`}</small>
                      {(c.profitToDate != null || c.unsoldEstimate != null) && <small>
                        {c.profitToDate != null && <>{t('profitToDate')}: <b className={tone(c.profitToDate).trim()}>{money(c.profitToDate)}</b></>}
                        {c.unsoldEstimate != null && <> · {t('unsoldEstimate')}: {money(c.unsoldEstimate)}</>}
                      </small>}
                    </div>
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
