import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { api } from '../api/client';
import type { Farm } from '../api/types';
import { useTenant } from '../auth/useCurrentTenant';
import { ErrorBanner, Loading } from '../components/common';
import { currentLanguage, useNs } from '../i18n';
import { describeError } from '../lib/errors';
import { formatDate, formatMoney, today } from '../lib/format';
import { ExportBar, QuickRanges, ReportHeader } from '../reports/ReportTools';
import type { IncomeComparison } from '../reports/types';
import type { Activity, AmountRow, CashFlow, Dues, IncomeStatement, PartyDues } from './types';
import { useCategoryLabel } from './useFinanceData';

type Tab = 'income' | 'cashflow' | 'dues' | 'activity';
const TABS: Tab[] = ['income', 'cashflow', 'dues', 'activity'];

function monthStart() { return `${today().slice(0, 7)}-01`; }

/**
 * Income statement (with an optional comparison, H10), cash flow, dues and activity profitability. Every report states its
 * range and that figures are actual, and exports as CSV, Excel or a printed PDF (H7, H8). {@code ?tab=} opens a tab.
 */
export default function ReportsPage() {
  const tenant = useTenant();
  const t = useNs('fin');
  const tr = useNs('reports');
  const lang = currentLanguage();
  const base = `/api/v1/tenants/${tenant.id}/finance/reports`;
  const [search] = useSearchParams();
  const initial = search.get('tab') as Tab | null;
  const [tab, setTab] = useState<Tab>(initial && TABS.includes(initial) ? initial : 'income');
  const [range, setRange] = useState({ from: monthStart(), to: today(), basis: 'ACCRUAL', farmId: '', compare: '' });
  const [farms, setFarms] = useState<Farm[]>([]);
  const [data, setData] = useState<unknown>(null);
  const [error, setError] = useState('');

  useEffect(() => { api<Farm[]>(`/api/v1/tenants/${tenant.id}/farms`).then(setFarms).catch(() => setFarms([])); }, [tenant.id]);

  const q = new URLSearchParams({ from: range.from, to: range.to });
  const url = tab === 'income' ? `${base}/income-statement?${q}&basis=${range.basis}${range.farmId ? `&farmId=${range.farmId}` : ''}${range.compare ? `&compare=${range.compare}` : ''}`
    : tab === 'cashflow' ? `${base}/cash-flow?${q}` : tab === 'dues' ? `${base}/dues?asOf=${range.to}` : `${base}/activity?${q}`;
  useEffect(() => {
    let alive = true;
    setData(null); setError('');
    api(url).then(d => alive && setData(d)).catch(e => alive && setError(describeError(e)));
    return () => { alive = false; };
  }, [url]);

  const period = tab === 'dues' ? t('asOfLabel', { date: formatDate(range.to, lang) }) : t('rangeLabel', { from: formatDate(range.from, lang), to: formatDate(range.to, lang) });
  const compared = tab === 'income' && range.compare !== '';
  return (
    <div className="report-page">
      <div className="crop-page-head"><div><h2>{t('reports')}</h2></div>
        <ExportBar path={url} name={`${tab}-${tab === 'dues' ? range.to : `${range.from}-${range.to}`}`} onError={setError} /></div>
      <div className="crop-tabs no-print" role="tablist">
        {TABS.map(x => (
          <button key={x} role="tab" aria-selected={tab === x} className={tab === x ? 'active' : ''} onClick={() => setTab(x)}>{t(`report.${x}`)}</button>
        ))}
      </div>
      {tab !== 'dues' && <QuickRanges onPick={r => setRange({ ...range, ...r })} />}
      <div className="form-card form-grid filters no-print">
        {tab !== 'dues' && <label>{t('from')}<input type="date" value={range.from} onChange={e => setRange({ ...range, from: e.target.value })} /></label>}
        <label>{tab === 'dues' ? t('asOf') : t('to')}<input type="date" value={range.to} onChange={e => setRange({ ...range, to: e.target.value })} /></label>
        {tab === 'income' && <>
          <label>{t('basis')}<select value={range.basis} onChange={e => setRange({ ...range, basis: e.target.value })}>
            <option value="ACCRUAL">{t('accrual')}</option><option value="CASH">{t('cashBasis')}</option></select></label>
          <label>{t('farm')}<select value={range.farmId} onChange={e => setRange({ ...range, farmId: e.target.value })}>
            <option value="">{t('allFarms')}</option>{farms.map(f => <option key={f.id} value={f.id}>{f.name}</option>)}</select></label>
          <label>{tr('compareWith')}<select value={range.compare} onChange={e => setRange({ ...range, compare: e.target.value })}>
            <option value="">{tr('noComparison')}</option><option value="previous">{tr('compare.previous')}</option><option value="lastYear">{tr('compare.lastYear')}</option></select></label>
        </>}
      </div>
      <ReportHeader title={t(`report.${tab}`)} period={period} basis={tab === 'income' ? (range.basis === 'CASH' ? t('cashBasis') : t('accrual')) : undefined} />
      <ErrorBanner message={error} />
      {!data && !error && <Loading />}
      {data != null && tab === 'income' && !compared && <IncomeView r={data as IncomeStatement} />}
      {data != null && compared && 'lines' in (data as object) && <ComparisonView c={data as IncomeComparison} />}
      {data != null && tab === 'cashflow' && <CashFlowView r={data as CashFlow} />}
      {data != null && tab === 'dues' && <DuesView r={data as Dues} />}
      {data != null && tab === 'activity' && <ActivityView r={data as Activity} />}
    </div>
  );
}

/** The income statement beside an earlier period (H10); "—" where the earlier amount is zero. */
function ComparisonView({ c }: { c: IncomeComparison }) {
  const t = useNs('fin');
  const tr = useNs('reports');
  const label = useCategoryLabel();
  const lang = currentLanguage();
  const pct = (v: number | null) => (v == null ? '—' : `${v}%`);
  let section = '';
  return (
    <div className="table-scroll">
      <p className="hint">{tr(`compare.${c.compare}`)}: {formatDate(c.previous.from, lang)} – {formatDate(c.previous.to, lang)}</p>
      <table className="data-table report">
        <thead><tr><th>{t('description')}</th><th className="num">{tr('thisPeriod')}</th><th className="num">{tr('earlierPeriod')}</th>
          <th className="num">{tr('change')}</th><th className="num">{tr('changePercent')}</th></tr></thead>
        <tbody>
          {c.lines.flatMap(l => {
            const head = l.section !== section ? [<tr key={`s-${l.section}`} className="section"><th colSpan={5}>{tr(`section.${l.section}`)}</th></tr>] : [];
            section = l.section;
            return [...head, <tr key={`${l.section}-${l.accountCode}-${l.categoryId}`}>
              <td>{l.categoryId ? label({ code: l.categoryCode, name: l.categoryName }) : t(`ledger.${l.accountCode}`)}</td>
              <td className="num">{formatMoney(l.current, lang)}</td><td className="num">{formatMoney(l.previous, lang)}</td>
              <td className="num">{formatMoney(l.change, lang)}</td><td className="num">{pct(l.changePercent)}</td></tr>];
          })}
          {c.totals.map(l => <tr key={l.section} className={l.section === 'NET_PROFIT' || l.section === 'OPERATING_PROFIT' ? 'total' : 'subtotal'}>
            <td>{tr(`section.${l.section}`)}</td><td className="num">{formatMoney(l.current, lang)}</td><td className="num">{formatMoney(l.previous, lang)}</td>
            <td className="num">{formatMoney(l.change, lang)}</td><td className="num">{pct(l.changePercent)}</td></tr>)}
        </tbody>
      </table>
    </div>
  );
}

function IncomeView({ r }: { r: IncomeStatement }) {
  const t = useNs('fin');
  const label = useCategoryLabel();
  const lang = currentLanguage();
  const rows = (items: AmountRow[]) => items.map(i => (
    <tr key={`${i.accountCode}-${i.categoryId}`}>
      <td>{i.categoryId ? label({ code: i.categoryCode, name: i.categoryName }) : t(`ledger.${i.accountCode}`)}</td>
      <td className="num">{formatMoney(i.amount, lang)}</td>
    </tr>
  ));
  return (
    <table className="data-table report">
      <tbody>
        <tr className="section"><th colSpan={2}>{t('operatingRevenue')}</th></tr>{rows(r.operatingRevenue)}
        <tr className="subtotal"><td>{t('totalRevenue')}</td><td className="num">{formatMoney(r.operatingRevenueTotal, lang)}</td></tr>
        <tr className="section"><th colSpan={2}>{t('operatingCosts')}</th></tr>{rows(r.operatingCosts)}
        <tr className="subtotal"><td>{t('totalCosts')}</td><td className="num">{formatMoney(r.operatingCostTotal, lang)}</td></tr>
        <tr className="total"><td>{t('operatingProfit')}</td><td className="num">{formatMoney(r.operatingProfit, lang)}</td></tr>
        {r.otherIncome.length > 0 && <><tr className="section"><th colSpan={2}>{t('otherIncome')}</th></tr>{rows(r.otherIncome)}</>}
        <tr><td>{t('ledger.5900')}</td><td className="num">−{formatMoney(r.interest, lang)}</td></tr>
        <tr><td>{t('losses')}</td><td className="num">−{formatMoney(r.losses, lang)}</td></tr>
        <tr className="total"><td>{t('netProfit')}</td><td className="num">{formatMoney(r.netProfit, lang)}</td></tr>
      </tbody>
    </table>
  );
}

function CashFlowView({ r }: { r: CashFlow }) {
  const t = useNs('fin');
  const lang = currentLanguage();
  const line = (label: string, value: number, cls = '') => <tr className={cls}><td>{label}</td><td className="num">{formatMoney(value, lang)}</td></tr>;
  return (
    <table className="data-table report">
      <tbody>
        {line(t('openingCash'), r.opening, 'subtotal')}
        {line(t('flow.OPERATING'), r.operating)}
        {line(t('flow.INVESTING'), r.investing)}
        {line(t('flow.FINANCING'), r.financing)}
        {r.openingBalances !== 0 && line(t('flow.OPENING'), r.openingBalances)}
        {line(t('netChange'), r.netChange, 'subtotal')}
        {line(t('closingCash'), r.closing, 'total')}
      </tbody>
    </table>
  );
}

function DuesView({ r }: { r: Dues }) {
  const t = useNs('fin');
  const lang = currentLanguage();
  const table = (title: string, rows: PartyDues[], total: number) => (
    <>
      <h3>{title}: {formatMoney(total, lang)}</h3>
      <div className="table-scroll">
        <table className="data-table">
          <thead><tr><th>{t('party')}</th><th className="num">{t('notDue')}</th><th className="num">1–30</th><th className="num">31–60</th>
            <th className="num">61–90</th><th className="num">90+</th><th className="num">{t('unlinked')}</th><th className="num">{t('advance')}</th><th className="num">{t('balance')}</th></tr></thead>
          <tbody>
            {rows.map(d => (
              <tr key={d.partyId}>
                <td>{d.partyName}</td>
                {[d.notDue, d.days1to30, d.days31to60, d.days61to90, d.over90, d.unmatched, d.advance, d.balance].map((v, i) => <td key={i} className="num">{formatMoney(v, lang)}</td>)}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  );
  return <>{table(t('receivable'), r.receivables, r.receivableTotal)}{table(t('payable'), r.payables, r.payableTotal)}</>;
}

function ActivityView({ r }: { r: Activity }) {
  const t = useNs('fin');
  const lang = currentLanguage();
  return (
    <table className="data-table">
      <thead><tr><th>{t('activity')}</th><th className="num">{t('revenue')}</th><th className="num">{t('costs')}</th><th className="num">{t('contribution')}</th></tr></thead>
      <tbody>
        {r.rows.map(row => (
          <tr key={row.costObjectId}>
            <td>{row.kind === 'OVERHEAD' ? t('unallocatedOverhead') : row.name}</td>
            <td className="num">{formatMoney(row.revenue, lang)}</td><td className="num">{formatMoney(row.costs, lang)}</td><td className="num">{formatMoney(row.contribution, lang)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
