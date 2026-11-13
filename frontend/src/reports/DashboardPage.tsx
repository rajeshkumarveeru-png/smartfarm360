import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { ArrowDownLeft, ArrowUpRight, ChevronRight, CircleCheck, Egg, Factory, Fish, HardHat, Landmark, Leaf, Milk, Package, Sprout, Tractor,
  TrendingDown, TrendingUp, TriangleAlert, Wallet, type LucideIcon } from 'lucide-react';
import { api } from '../api/client';
import { useTenant } from '../auth/useCurrentTenant';
import { ErrorBanner } from '../components/common';
import { InsightModal } from '../components/InsightModal';
import { useCategoryLabel } from '../finance/useFinanceData';
import { currentLanguage, useNs } from '../i18n';
import { describeError } from '../lib/errors';
import { formatDate, formatMoney, today } from '../lib/format';
import TrendChart from './TrendChart';
import type { Attention, BusinessProfit, Dashboard } from './types';

/** Where each "needs attention" item is dealt with (dashboard and reports design, section 3C). */
export const ATTENTION_LINKS: Record<string, string> = {
  OVERDUE_SALES: 'finance/reports?tab=dues', BILLS_DUE_SOON: 'finance/reports?tab=dues', OLD_DRAFTS: 'finance/records',
  LOW_STOCK: 'stock', EXPIRING_LOTS: 'stock', STOCK_NOT_RECONCILED: 'stock/reports',
  WAGES_OWED: 'workforce/pay', ADVANCES_OUT: 'workforce/pay', WORKERS_NOT_RECONCILED: 'workforce/reports', ATTENDANCE_MISSING: 'workforce',
  MAINTENANCE_DUE: 'assets/maintenance', UNREGISTERED_ASSETS: 'assets', ASSETS_NOT_RECONCILED: 'assets/depreciation', DEPRECIATION_DUE: 'assets/depreciation',
  MILK_WITHHELD: 'livestock/milk', BREEDING_DUE: 'livestock/care', HEALTH_DUE: 'livestock/care', MILK_NOT_RECORDED: 'livestock/milk',
  CROP_ACTION_DUE: 'crops',
  BATCH_RECORD_MISSING: 'livestock/poultry', HIGH_MORTALITY: 'livestock/poultry',
  WATER_OUT_OF_LIMITS: 'aquaculture/water', POND_RECORD_MISSING: 'aquaculture', HARVEST_OVERDUE: 'aquaculture', ORGANIC_OVERDUE: 'organic', PROCESSING_OVERDUE: 'processing',
};

/** The icon for an attention item, by the section its link goes to (presentation only). */
const AREA_ICONS: Record<string, LucideIcon> = {
  finance: Wallet, stock: Package, workforce: HardHat, assets: Tractor, livestock: Milk, aquaculture: Fish, organic: Leaf, processing: Factory, crops: Sprout,
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

const BUSINESS_META: Record<BusinessProfit['module'], { label: string; href: string; Icon: LucideIcon }> = {
  FARM: { label: 'Farm / Crop', href: 'farms', Icon: Tractor },
  POULTRY: { label: 'Poultry', href: 'livestock/poultry', Icon: Egg },
  MILK: { label: 'Milk / Dairy', href: 'livestock/milk', Icon: Milk },
  POND: { label: 'Pond / Aquaculture', href: 'aquaculture', Icon: Fish },
};

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
  const [selectedBusiness, setSelectedBusiness] = useState<BusinessProfit | null>(null);

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
  // Crop work belongs with the crop cycle, not in the general alert feed.
  // The backend detail format is: cycleId|cycleName · cropName · action · description · yyyy-MM-dd.
  const cropActions = data.attention.filter(a => a.code === 'CROP_ACTION_DUE').map(a => {
    const [cycleId, summary = ''] = (a.detail ?? '').split('|', 2);
    const parts = summary.split(' · ');
    const dueToken = parts[parts.length - 1] ?? '';
    const dueDate = /^\d{4}-\d{2}-\d{2}$/.test(dueToken) ? dueToken : null;
    const dueStatus = dueDate && dueDate < data.asOf ? 'overdue' : dueDate === data.asOf ? 'today' : 'upcoming';
    return { ...a, cycleId, summary: dueDate ? parts.slice(0, -1).join(' · ') : summary, dueDate, dueStatus };
  });
  const otherAttention = data.attention.filter(a => a.code !== 'CROP_ACTION_DUE');
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

      {data.businessProfits?.length > 0 && (
        <section className="db-business-section">
          <div className="db-card-head">
            <h3>{lang === 'ta' ? 'தொழில் வாரியான லாபம் & நஷ்டம்' : 'Business-wise profit & loss'}</h3>
            <small>{lang === 'ta' ? 'இந்த மாதம்' : 'This month'} · {formatDate(data.asOf, lang)}</small>
          </div>
          <div className="finance-stats db-kpis">
            {data.businessProfits.map((item) => {
              const meta = BUSINESS_META[item.module];
              const Icon = meta.Icon;
              return (
                <button key={item.module} type="button" className={`finance-stat db-kpi kpi-button${tone(item.profit)}`}
                        onClick={() => setSelectedBusiness(item)} aria-haspopup="dialog">
                  <span className="db-kpi-label"><i className="db-kpi-icon tone-brand"><Icon size={16} aria-hidden /></i>{meta.label}</span>
                  <b>{money(item.profit)}</b>
                  <small>{t('income')} {money(item.revenue)} · {t('costs')} {money(item.costs)}</small>
                  <small>{lang === 'ta' ? 'செயலில் உள்ள பதிவுகள்' : 'Active records'}: {item.activeEntries}</small>
                  <small className="kpi-hint">{lang === 'ta' ? 'கணக்கீட்டை பார்க்க' : 'View calculation'} <ChevronRight size={13} aria-hidden /></small>
                </button>
              );
            })}
          </div>
        </section>
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
            {otherAttention.length > 0 && <span className="count db-count-warn" aria-label={t('attentionCount', { count: String(otherAttention.length) })}>{otherAttention.length}</span>}
          </div>
          {otherAttention.length === 0 ? (
            <div className="db-all-clear"><CircleCheck size={28} aria-hidden /><p className="hint">{t('allClear')}</p><small>{t('allClearHelp')}</small></div>
          ) : (
            <ul className="attention-list">
              {otherAttention.map((a, attentionIndex) => {
                const Icon = areaIcon(a.code);
                // HEALTH_DUE carries the next real due item from the livestock service.
                // Show its task and due date instead of hiding the useful detail in a generic alert.
                const healthParts = a.code === 'HEALTH_DUE' && a.detail ? a.detail.split(' · ') : [];
                const dueParts = healthParts;
                const dueToken = dueParts.length ? dueParts[dueParts.length - 1] : '';
                const dueDate = /^\d{4}-\d{2}-\d{2}$/.test(dueToken) ? dueToken : null;
                const healthSummary = dueDate ? dueParts.slice(0, -1).join(' · ') : a.detail;
                const dueStatus = dueDate && dueDate < data.asOf ? 'overdue' : dueDate === data.asOf ? 'today' : 'upcoming';
                const dueStatusLabel = lang === 'ta'
                  ? (dueStatus === 'overdue' ? 'காலம் கடந்தது' : dueStatus === 'today' ? 'இன்று செய்யவும்' : 'வரவுள்ளது')
                  : (dueStatus === 'overdue' ? 'Overdue' : dueStatus === 'today' ? 'Due today' : 'Upcoming');
                return (
                  <li key={`${a.code}-${a.detail ?? attentionIndex}`}><Link to={`/t/${tenant.id}/${ATTENTION_LINKS[a.code] ?? ''}`}>
                    <i className={`db-attention-icon${a.code === 'HEALTH_DUE' ? ' tone-health' : ''}`} aria-hidden><Icon size={16} /></i>
                    <span className="db-attention-copy">{a.code === 'HEALTH_DUE' ? <>
                      <strong>{(lang === 'ta' ? 'உடல்நல பராமரிப்பு நிலுவை' : 'Health activity upcoming')}</strong>
                      {healthSummary && <small>{healthSummary}</small>}
                      {dueDate && <small className="db-due-date">{lang === 'ta' ? 'தேதி: ' : 'Due: '}{formatDate(dueDate, lang)}</small>}

                    </> : attentionText(a)}</span>
                    {a.code === 'HEALTH_DUE' && dueDate && <span className={`db-upcoming-badge db-due-${dueStatus}`}>{dueStatusLabel}</span>}
                    <ChevronRight className="db-chevron" size={16} aria-hidden />
                  </Link></li>
                );
              })}
            </ul>
          )}
        </div>

        {(data.crops || cropActions.length > 0) && (
          <div className="db-card db-crops-section">
            <div className="db-card-head">
              <h3>{t('cropsInField')}</h3>
              {(data.crops?.length || cropActions.length) > 0 && <span className="count">{(data.crops?.length ?? 0) + cropActions.length}</span>}
            </div>
            {(!data.crops || data.crops.length === 0) && cropActions.length === 0 ? (
              <div className="db-all-clear muted"><Sprout size={28} aria-hidden /><p className="hint">{t('noCrops')}</p></div>
            ) : (
              <ul className="db-crop-list">
                {(data.crops ?? []).map(c => {
                  const cycleActions = cropActions.filter(a => a.cycleId === String(c.cycleId));
                  return (
                    <li key={c.cycleId} className="db-crop-item">
                      <i className="db-attention-icon tone-brand" aria-hidden><Sprout size={16} /></i>
                      <div className="db-crop-main">
                        <Link to={`/t/${tenant.id}/crops/cycles/${c.cycleId}`}>{c.name}</Link>
                        <small>{c.cropName} · <span className={`stage stage-${c.status.toLowerCase()}`}>{tcrop(`stage.${c.status}`)}</span>
                          {c.daysSinceSowing != null && ` · ${tcrop('daysSinceSowing', { days: String(c.daysSinceSowing) })}`}</small>
                        {(c.profitToDate != null || c.unsoldEstimate != null) && <small>
                          {c.profitToDate != null && <>{t('profitToDate')}: <b className={tone(c.profitToDate).trim()}>{money(c.profitToDate)}</b></>}
                          {c.unsoldEstimate != null && <> · {t('unsoldEstimate')}: {money(c.unsoldEstimate)}</>}
                        </small>}
                        {cycleActions.length > 0 && <ul className="attention-list db-crop-actions" aria-label={lang === 'ta' ? 'வயல் பணிகள்' : 'Crop actions'}>
                          {cycleActions.map((a, i) => (
                            <li key={`${a.cycleId}-${a.detail}-${i}`}>
                              <Link to={`/t/${tenant.id}/crops/cycles/${a.cycleId}`}>
                                <i className="db-attention-icon tone-brand" aria-hidden><Sprout size={15} /></i>
                                <span className="db-attention-copy">
                                  <strong>{lang === 'ta' ? 'வயல் பணி செய்ய வேண்டும்' : 'Field activity due'}</strong>
                                  <small>{a.summary}</small>
                                  {a.dueDate && <small className="db-due-date">{lang === 'ta' ? 'தேதி: ' : 'Due: '}{formatDate(a.dueDate, lang)}</small>}
                                </span>
                                {a.dueDate && <span className={`db-upcoming-badge db-due-${a.dueStatus}`}>{lang === 'ta' ? (a.dueStatus === 'overdue' ? 'காலம் கடந்தது' : a.dueStatus === 'today' ? 'இன்று செய்யவும்' : 'வரவுள்ளது') : (a.dueStatus === 'overdue' ? 'Overdue' : a.dueStatus === 'today' ? 'Due today' : 'Upcoming')}</span>}
                                <ChevronRight className="db-chevron" size={16} aria-hidden />
                              </Link>
                            </li>
                          ))}
                        </ul>}
                      </div>
                    </li>
                  );
                })}
                {cropActions.filter(a => !(data.crops ?? []).some(c => String(c.cycleId) === a.cycleId)).map((a, i) => (
                  <li key={`orphan-crop-action-${a.cycleId}-${i}`} className="db-crop-item">
                    <i className="db-attention-icon tone-brand" aria-hidden><Sprout size={16} /></i>
                    <div className="db-crop-main">
                      <Link to={`/t/${tenant.id}/crops/cycles/${a.cycleId}`}>{a.summary.split(' · ').slice(0, 2).join(' · ') || (lang === 'ta' ? 'பயிர் சுழற்சி' : 'Cultivation cycle')}</Link>
                      <ul className="attention-list db-crop-actions"><li><Link to={`/t/${tenant.id}/crops/cycles/${a.cycleId}`}><span className="db-attention-copy"><strong>{lang === 'ta' ? 'வயல் பணி செய்ய வேண்டும்' : 'Field activity due'}</strong><small>{a.summary}</small>{a.dueDate && <small className="db-due-date">{lang === 'ta' ? 'தேதி: ' : 'Due: '}{formatDate(a.dueDate, lang)}</small>}</span><ChevronRight className="db-chevron" size={16} aria-hidden /></Link></li></ul>
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </div>
        )}
      </div>
      {selectedBusiness && (() => {
        const meta = BUSINESS_META[selectedBusiness.module];
        const Icon = meta.Icon;
        const periodStart = `${data.asOf.slice(0, 7)}-01`;
        return <InsightModal tone={selectedBusiness.profit < 0 ? 'rose' : 'emerald'} icon={<Icon size={20} />}
          eyebrow={lang === 'ta' ? 'தொழில் வாரியான லாபம் & நஷ்டம்' : 'Business-wise profit & loss'}
          title={meta.label} subtitle={`${formatDate(periodStart, lang)} – ${formatDate(data.asOf, lang)}`}
          summary={[
            { label: t('income'), value: money(selectedBusiness.revenue), tone: 'emerald' },
            { label: t('costs'), value: money(selectedBusiness.costs), tone: 'amber' },
            { label: lang === 'ta' ? 'லாபம் / நஷ்டம்' : 'Profit / loss', value: money(selectedBusiness.profit), tone: selectedBusiness.profit < 0 ? 'rose' : 'violet' },
          ]}
          footer={<Link className="primary" to={`/t/${tenant.id}/${meta.href}`} onClick={() => setSelectedBusiness(null)}>
            {lang === 'ta' ? 'தொடர்புடைய பதிவுகளைத் திற' : 'Open related records'} <ChevronRight size={16} aria-hidden />
          </Link>}
          onClose={() => setSelectedBusiness(null)}>
          <div className="im-calculation">
            <h3>{lang === 'ta' ? 'கணக்கீட்டு விவரம்' : 'How this figure is calculated'}</h3>
            <div className="im-calculation-row"><span>{lang === 'ta' ? 'மொத்த வருமானம்' : 'Total income'}</span><strong>{money(selectedBusiness.revenue)}</strong></div>
            <div className="im-calculation-row"><span>{lang === 'ta' ? 'மொத்த செலவுகள்' : 'Total costs'}</span><strong>− {money(selectedBusiness.costs)}</strong></div>
            <div className="im-calculation-row total"><span>{lang === 'ta' ? 'நிகர லாபம் / நஷ்டம்' : 'Net profit / loss'}</span><strong>{money(selectedBusiness.revenue - selectedBusiness.costs)}</strong></div>
            <p className="hint">{lang === 'ta' ? 'நிகர லாபம் = வருமானம் − செலவுகள். இந்த மாதத்தில் செயலில் உள்ள பதிவுகள் மட்டும் கணக்கில் எடுக்கப்பட்டுள்ளன.' : 'Net profit = income − costs. The total is calculated from this module’s active records for the selected month; it uses the module report’s cost allocation.'}</p>
            <div className="im-calculation-row"><span>{lang === 'ta' ? 'செயலில் உள்ள பதிவுகள்' : 'Active records included'}</span><strong>{selectedBusiness.activeEntries}</strong></div>
            <p className="hint">{lang === 'ta' ? 'ஒவ்வொரு வரவு / செலவு பதிவையும் பார்க்க கீழே திறக்கவும்.' : 'Open the module to inspect the underlying entries and their source documents.'}</p>
          </div>
        </InsightModal>;
      })()}
    </section>
  );
}
