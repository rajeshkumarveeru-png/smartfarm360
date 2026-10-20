import { useEffect, useState } from 'react';
import { api } from '../api/client';
import type { Farm } from '../api/types';
import { useTenant } from '../auth/useCurrentTenant';
import { ErrorBanner, Loading } from '../components/common';
import { currentLanguage, useNs } from '../i18n';
import { describeError } from '../lib/errors';
import { formatDate, formatMoney, today } from '../lib/format';
import type { Activity, AmountRow, CashFlow, Dues, IncomeStatement, PartyDues } from './types';
import { useCategoryLabel } from './useFinanceData';

type Tab = 'income' | 'cashflow' | 'dues' | 'activity';

function monthStart() { return `${today().slice(0, 7)}-01`; }

/** Income statement, cash flow, dues and activity profitability. Every report states its range and that figures are actual. */
export default function ReportsPage() {
  const tenant = useTenant();
  const t = useNs('fin');
  const lang = currentLanguage();
  const base = `/api/v1/tenants/${tenant.id}/finance/reports`;
  const [tab, setTab] = useState<Tab>('income');
  const [range, setRange] = useState({ from: monthStart(), to: today(), basis: 'ACCRUAL', farmId: '' });
  const [farms, setFarms] = useState<Farm[]>([]);
  const [data, setData] = useState<unknown>(null);
  const [error, setError] = useState('');

  useEffect(() => { api<Farm[]>(`/api/v1/tenants/${tenant.id}/farms`).then(setFarms).catch(() => setFarms([])); }, [tenant.id]);

  useEffect(() => {
    let alive = true;
    setData(null); setError('');
    const q = new URLSearchParams({ from: range.from, to: range.to });
    const url = tab === 'income' ? `${base}/income-statement?${q}&basis=${range.basis}${range.farmId ? `&farmId=${range.farmId}` : ''}`
      : tab === 'cashflow' ? `${base}/cash-flow?${q}` : tab === 'dues' ? `${base}/dues?asOf=${range.to}` : `${base}/activity?${q}`;
    api(url).then(d => alive && setData(d)).catch(e => alive && setError(describeError(e)));
    return () => { alive = false; };
  }, [base, tab, range]);

  return (
    <>
      <div className="crop-page-head"><div><h2>{t('reports')}</h2></div></div>
      <div className="crop-tabs" role="tablist">
        {(['income', 'cashflow', 'dues', 'activity'] as Tab[]).map(x => (
          <button key={x} role="tab" aria-selected={tab === x} className={tab === x ? 'active' : ''} onClick={() => setTab(x)}>{t(`report.${x}`)}</button>
        ))}
      </div>
      <div className="form-card form-grid filters">
        {tab !== 'dues' && <label>{t('from')}<input type="date" value={range.from} onChange={e => setRange({ ...range, from: e.target.value })} /></label>}
        <label>{tab === 'dues' ? t('asOf') : t('to')}<input type="date" value={range.to} onChange={e => setRange({ ...range, to: e.target.value })} /></label>
        {tab === 'income' && <>
          <label>{t('basis')}<select value={range.basis} onChange={e => setRange({ ...range, basis: e.target.value })}>
            <option value="ACCRUAL">{t('accrual')}</option><option value="CASH">{t('cashBasis')}</option></select></label>
          <label>{t('farm')}<select value={range.farmId} onChange={e => setRange({ ...range, farmId: e.target.value })}>
            <option value="">{t('allFarms')}</option>{farms.map(f => <option key={f.id} value={f.id}>{f.name}</option>)}</select></label>
        </>}
      </div>
      <p className="hint">
        {tab === 'dues' ? t('asOfLabel', { date: formatDate(range.to, lang) }) : t('rangeLabel', { from: formatDate(range.from, lang), to: formatDate(range.to, lang) })}
        {' · '}{t('actualFigures')}
      </p>
      <ErrorBanner message={error} />
      {!data && !error && <Loading />}
      {data != null && tab === 'income' && <IncomeView r={data as IncomeStatement} />}
      {data != null && tab === 'cashflow' && <CashFlowView r={data as CashFlow} />}
      {data != null && tab === 'dues' && <DuesView r={data as Dues} />}
      {data != null && tab === 'activity' && <ActivityView r={data as Activity} />}
    </>
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
        <tr><td>{t('interest')}</td><td className="num">−{formatMoney(r.interest, lang)}</td></tr>
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
