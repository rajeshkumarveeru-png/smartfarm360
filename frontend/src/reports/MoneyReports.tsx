import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api } from '../api/client';
import type { Farm } from '../api/types';
import { useTenant } from '../auth/useCurrentTenant';
import { ErrorBanner, Loading } from '../components/common';
import { useCategoryLabel } from '../finance/useFinanceData';
import { currentLanguage, useNs } from '../i18n';
import { describeError } from '../lib/errors';
import { formatDate, formatMoney, today } from '../lib/format';
import { can } from '../lib/nav';
import { ExportBar, QuickRanges, ReportHeader } from './ReportTools';
import type { BalanceLine, BalanceSheet, CategoryBreakdown, MonthlyProfitAndLoss, MonthlyRow, PartyStatement, TrialBalance } from './types';

const yearStart = () => { const d = new Date(); const y = d.getMonth() >= 3 ? d.getFullYear() : d.getFullYear() - 1; return `${y}-04-01`; };
const pct = (v: number | null) => (v == null ? '—' : `${v}%`);

/** Loads a report whenever its URL changes. */
function useReport<T>(url: string | null): { data: T | null; error: string; setError: (e: string) => void } {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState('');
  useEffect(() => {
    if (!url) return;
    let alive = true;
    setData(null); setError('');
    api<T>(url).then(d => alive && setData(d)).catch(e => alive && setError(describeError(e)));
    return () => { alive = false; };
  }, [url]);
  return { data, error, setError };
}

function useLineLabel() {
  const t = useNs('fin');
  const categoryLabel = useCategoryLabel();
  return (r: { accountCode: string | null; categoryId: number | null; categoryCode: string | null; categoryName: string | null }) =>
    r.categoryId ? categoryLabel({ code: r.categoryCode, name: r.categoryName }) : t(`ledger.${r.accountCode}`);
}

function RangeFields({ range, setRange, single }: { range: { from: string; to: string }; setRange: (r: { from: string; to: string }) => void; single?: boolean }) {
  const t = useNs('fin');
  return (
    <>
      {!single && <QuickRanges onPick={setRange} />}
      <div className="form-card form-grid filters no-print">
        {!single && <label>{t('from')}<input type="date" value={range.from} onChange={e => setRange({ ...range, from: e.target.value })} /></label>}
        <label>{single ? t('asOf') : t('to')}<input type="date" value={range.to} onChange={e => setRange({ ...range, to: e.target.value })} /></label>
      </div>
    </>
  );
}

// ---- Monthly profit and loss -------------------------------------------------------------------------------------

export function MonthlyPage() {
  const tenant = useTenant();
  const t = useNs('reports');
  const tf = useNs('fin');
  const lang = currentLanguage();
  const label = useLineLabel();
  const [range, setRange] = useState({ from: yearStart(), to: today() });
  const [basis, setBasis] = useState('ACCRUAL');
  const [farmId, setFarmId] = useState('');
  const [farms, setFarms] = useState<Farm[]>([]);
  useEffect(() => { api<Farm[]>(`/api/v1/tenants/${tenant.id}/farms`).then(setFarms).catch(() => setFarms([])); }, [tenant.id]);
  const path = `/api/v1/tenants/${tenant.id}/finance/reports/monthly?from=${range.from}&to=${range.to}&basis=${basis}${farmId ? `&farmId=${farmId}` : ''}`;
  const { data, error, setError } = useReport<MonthlyProfitAndLoss>(path);
  const row = (r: MonthlyRow, name: string, total = false) => (
    <tr key={`${r.section}-${r.accountCode}-${r.categoryId}`} className={total ? 'total' : ''}>
      <td>{name}</td>{r.amounts.map((v, i) => <td key={i} className="num">{formatMoney(v, lang)}</td>)}<td className="num"><b>{formatMoney(r.total, lang)}</b></td>
    </tr>
  );
  return (
    <section className="crop-page report-page">
      <div className="crop-page-head"><div><h2>{t('title.monthly')}</h2><p>{t('help.monthly')}</p></div>
        <ExportBar path={path} name={`monthly-profit-and-loss-${range.from}-${range.to}`} onError={setError} /></div>
      <RangeFields range={range} setRange={setRange} />
      <div className="form-card form-grid filters no-print">
        <label>{tf('basis')}<select value={basis} onChange={e => setBasis(e.target.value)}><option value="ACCRUAL">{tf('accrual')}</option><option value="CASH">{tf('cashBasis')}</option></select></label>
        <label>{tf('farm')}<select value={farmId} onChange={e => setFarmId(e.target.value)}><option value="">{tf('allFarms')}</option>{farms.map(f => <option key={f.id} value={f.id}>{f.name}</option>)}</select></label>
      </div>
      <ReportHeader title={t('title.monthly')} period={tf('rangeLabel', { from: formatDate(range.from, lang), to: formatDate(range.to, lang) })}
        basis={basis === 'CASH' ? tf('cashBasis') : tf('accrual')} />
      <ErrorBanner message={error} />
      {!data ? !error && <Loading /> : (
        <div className="table-scroll">
          <table className="data-table report">
            <thead><tr><th>{tf('description')}</th>{data.months.map(m => <th key={m} className="num">{m}</th>)}<th className="num">{tf('total')}</th></tr></thead>
            <tbody>
              {(['REVENUE', 'COSTS', 'OTHER_INCOME'] as const).map(section => {
                const rows = data.rows.filter(r => r.section === section);
                return rows.length === 0 ? null : [
                  <tr key={section} className="section"><th colSpan={data.months.length + 2}>{t(`section.${section}`)}</th></tr>,
                  ...rows.map(r => row(r, label(r))),
                ];
              })}
              {data.totals.map(r => row(r, t(`section.${r.section}`), true))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

// ---- Category breakdown ------------------------------------------------------------------------------------------

export function CategoriesPage() {
  const tenant = useTenant();
  const t = useNs('reports');
  const tf = useNs('fin');
  const lang = currentLanguage();
  const label = useLineLabel();
  const [range, setRange] = useState({ from: `${today().slice(0, 7)}-01`, to: today() });
  const [kind, setKind] = useState<'COSTS' | 'INCOME'>('COSTS');
  const [compare, setCompare] = useState('');
  const path = `/api/v1/tenants/${tenant.id}/finance/reports/categories?from=${range.from}&to=${range.to}&kind=${kind}${compare ? `&compare=${compare}` : ''}`;
  const { data, error, setError } = useReport<CategoryBreakdown>(path);
  const title = t(kind === 'COSTS' ? 'title.costCategories' : 'title.incomeCategories');
  return (
    <section className="crop-page report-page">
      <div className="crop-page-head"><div><h2>{title}</h2><p>{t('help.categories')}</p></div>
        <ExportBar path={path} name={`categories-${range.from}-${range.to}`} onError={setError} /></div>
      <RangeFields range={range} setRange={setRange} />
      <div className="form-card form-grid filters no-print">
        <label>{t('show')}<select value={kind} onChange={e => setKind(e.target.value as 'COSTS' | 'INCOME')}>
          <option value="COSTS">{t('title.costCategories')}</option><option value="INCOME">{t('title.incomeCategories')}</option></select></label>
        <label>{t('compareWith')}<select value={compare} onChange={e => setCompare(e.target.value)}>
          <option value="">{t('noComparison')}</option><option value="previous">{t('compare.previous')}</option><option value="lastYear">{t('compare.lastYear')}</option></select></label>
      </div>
      <ReportHeader title={title} period={tf('rangeLabel', { from: formatDate(range.from, lang), to: formatDate(range.to, lang) })}
        extra={data?.compare ? `${t(`compare.${data.compare}`)}: ${formatDate(data.previousFrom!, lang)} – ${formatDate(data.previousTo!, lang)}` : undefined} />
      <ErrorBanner message={error} />
      {!data ? !error && <Loading /> : data.rows.length === 0 ? <p className="hint">{t('nothingInPeriod')}</p> : (
        <table className="data-table report">
          <thead><tr><th>{tf('category')}</th><th className="num">{tf('amount')}</th><th className="num">{t('sharePercent')}</th>
            {data.compare && <><th className="num">{t('earlierPeriod')}</th><th className="num">{t('change')}</th><th className="num">{t('changePercent')}</th></>}</tr></thead>
          <tbody>
            {data.rows.map(r => (
              <tr key={`${r.accountCode}-${r.categoryId}`}>
                <td>{label(r)}<span className="share-bar" style={{ width: `${Math.min(100, Math.max(0, r.share ?? 0))}%` }} aria-hidden /></td>
                <td className="num">{formatMoney(r.amount, lang)}</td><td className="num">{pct(r.share)}</td>
                {data.compare && <><td className="num">{formatMoney(r.previous, lang)}</td><td className="num">{formatMoney(r.change, lang)}</td><td className="num">{pct(r.changePercent)}</td></>}
              </tr>
            ))}
            <tr className="total"><td>{tf('total')}</td><td className="num">{formatMoney(data.total, lang)}</td><td />
              {data.compare && <><td className="num">{formatMoney(data.previousTotal, lang)}</td><td colSpan={2} /></>}</tr>
          </tbody>
        </table>
      )}
    </section>
  );
}

// ---- Balance sheet -----------------------------------------------------------------------------------------------

export function BalanceSheetPage() {
  const tenant = useTenant();
  const t = useNs('reports');
  const tf = useNs('fin');
  const lang = currentLanguage();
  const [range, setRange] = useState({ from: today(), to: today() });
  const path = `/api/v1/tenants/${tenant.id}/finance/reports/balance-sheet?asOf=${range.to}`;
  const { data, error, setError } = useReport<BalanceSheet>(path);
  const lines = (rows: BalanceLine[]) => rows.map(l => <tr key={l.accountCode}><td>{tf(`ledger.${l.accountCode}`)}</td><td className="num">{formatMoney(l.amount, lang)}</td></tr>);
  return (
    <section className="crop-page report-page">
      <div className="crop-page-head"><div><h2>{t('title.balanceSheet')}</h2><p>{t('help.balanceSheet')}</p></div>
        <ExportBar path={path} name={`balance-sheet-${range.to}`} onError={setError} /></div>
      <RangeFields range={range} setRange={setRange} single />
      <ReportHeader title={t('title.balanceSheet')} period={tf('asOfLabel', { date: formatDate(range.to, lang) })} />
      <ErrorBanner message={error} />
      {!data ? !error && <Loading /> : (
        <>
          {data.difference !== 0 && <p className="error-banner" role="alert">{t('notBalanced', { amount: formatMoney(data.difference, lang) })}</p>}
          <div className="balance-sheet">
            <table className="data-table report">
              <thead><tr><th colSpan={2}>{t('section.ASSETS')}</th></tr></thead>
              <tbody>{lines(data.assets)}<tr className="total"><td>{t('assetTotal')}</td><td className="num">{formatMoney(data.assetTotal, lang)}</td></tr></tbody>
            </table>
            <table className="data-table report">
              <thead><tr><th colSpan={2}>{t('section.LIABILITIES')}</th></tr></thead>
              <tbody>
                {lines(data.liabilities)}
                <tr className="subtotal"><td>{t('liabilityTotal')}</td><td className="num">{formatMoney(data.liabilityTotal, lang)}</td></tr>
                <tr className="section"><th colSpan={2}>{t('section.EQUITY')}</th></tr>
                {lines(data.equity)}
                <tr><td>{t('earlierProfit')}</td><td className="num">{formatMoney(data.earlierProfit, lang)}</td></tr>
                <tr><td>{t('yearProfit')}</td><td className="num">{formatMoney(data.yearProfit, lang)}</td></tr>
                <tr className="subtotal"><td>{t('equityTotal')}</td><td className="num">{formatMoney(data.equityTotal, lang)}</td></tr>
                <tr className="total"><td>{t('liabilitiesAndEquity')}</td><td className="num">{formatMoney(data.liabilitiesAndEquity, lang)}</td></tr>
              </tbody>
            </table>
          </div>
          <p className="hint">{t('fiscalYearNote', { date: formatDate(data.fiscalYearFrom, lang) })}</p>
        </>
      )}
    </section>
  );
}

// ---- Trial balance -----------------------------------------------------------------------------------------------

export function TrialBalancePage() {
  const tenant = useTenant();
  const t = useNs('reports');
  const tf = useNs('fin');
  const lang = currentLanguage();
  const [range, setRange] = useState({ from: yearStart(), to: today() });
  const path = can(tenant, 'FINANCE_MANAGE') ? `/api/v1/tenants/${tenant.id}/finance/reports/trial-balance?from=${range.from}&to=${range.to}` : null;
  const { data, error, setError } = useReport<TrialBalance>(path);
  if (!path) return <p className="hint">{t('accountantsOnly')}</p>;
  return (
    <section className="crop-page report-page">
      <div className="crop-page-head"><div><h2>{t('title.trialBalance')}</h2><p>{t('help.trialBalance')}</p></div>
        <ExportBar path={path} name={`trial-balance-${range.from}-${range.to}`} onError={setError} /></div>
      <RangeFields range={range} setRange={setRange} />
      <ReportHeader title={t('title.trialBalance')} period={tf('rangeLabel', { from: formatDate(range.from, lang), to: formatDate(range.to, lang) })} />
      <ErrorBanner message={error} />
      {!data ? !error && <Loading /> : (
        <div className="table-scroll">
          <table className="data-table report">
            <thead><tr><th>{t('accountCode')}</th><th>{tf('account')}</th><th className="num">{t('openingBalance')}</th><th className="num">{t('debit')}</th>
              <th className="num">{t('credit')}</th><th className="num">{t('closingBalance')}</th></tr></thead>
            <tbody>
              {data.rows.map(r => <tr key={r.accountCode}><td>{r.accountCode}</td><td>{tf(`ledger.${r.accountCode}`)}</td><td className="num">{formatMoney(r.opening, lang)}</td>
                <td className="num">{formatMoney(r.debit, lang)}</td><td className="num">{formatMoney(r.credit, lang)}</td><td className="num">{formatMoney(r.closing, lang)}</td></tr>)}
              <tr className="total"><td colSpan={3}>{tf('total')}</td><td className="num">{formatMoney(data.debitTotal, lang)}</td><td className="num">{formatMoney(data.creditTotal, lang)}</td>
                <td className="num">{formatMoney(data.closingTotal, lang)}</td></tr>
            </tbody>
          </table>
          <p className="hint">{t('trialBalanceHelp')}</p>
        </div>
      )}
    </section>
  );
}

// ---- Party statement ---------------------------------------------------------------------------------------------

export function PartyStatementPage() {
  const tenant = useTenant();
  const { partyId } = useParams();
  const t = useNs('reports');
  const tf = useNs('fin');
  const lang = currentLanguage();
  const [range, setRange] = useState({ from: yearStart(), to: today() });
  const path = `/api/v1/tenants/${tenant.id}/finance/parties/${partyId}/statement?from=${range.from}&to=${range.to}`;
  const { data, error, setError } = useReport<PartyStatement>(path);
  const side = (v: number) => (v > 0 ? t('theyOweFarm') : v < 0 ? t('farmOwesThem') : '');
  return (
    <section className="crop-page report-page">
      <div className="crop-page-head"><div><h2>{t('title.partyStatement')}{data ? `: ${data.name}` : ''}</h2>
        <p>{data?.phone}{data?.village ? ` · ${data.village}` : ''}</p></div>
        <ExportBar path={path} name={`statement-${partyId}-${range.from}-${range.to}`} onError={setError} /></div>
      <RangeFields range={range} setRange={setRange} />
      <ReportHeader title={`${t('title.partyStatement')}${data ? `: ${data.name}` : ''}`} period={tf('rangeLabel', { from: formatDate(range.from, lang), to: formatDate(range.to, lang) })} />
      <ErrorBanner message={error} />
      {!data ? !error && <Loading /> : (
        <>
          <div className="table-scroll">
            <table className="data-table report">
              <thead><tr><th>{tf('date')}</th><th>{t('record')}</th><th className="num">{t('debit')}</th><th className="num">{t('credit')}</th><th className="num">{tf('balance')}</th></tr></thead>
              <tbody>
                <tr><td colSpan={4}>{t('openingBalance')}</td><td className="num">{formatMoney(data.opening, lang)}</td></tr>
                {data.lines.map((l, i) => (
                  <tr key={`${l.documentId}-${i}`}>
                    <td>{formatDate(l.date, lang)}</td>
                    <td>{can(tenant, 'FINANCE_READ') ? <Link to={`/t/${tenant.id}/finance/records/${l.documentId}`}>{l.number}</Link> : l.number}
                      <small> · {tf(`type.${l.documentType}`)}{l.reversal ? ` · ${tf('status.REVERSED')}` : ''}</small></td>
                    <td className="num">{l.debit ? formatMoney(l.debit, lang) : ''}</td><td className="num">{l.credit ? formatMoney(l.credit, lang) : ''}</td>
                    <td className="num">{formatMoney(l.balance, lang)}</td>
                  </tr>
                ))}
                <tr className="total"><td colSpan={4}>{t('closingBalance')} <small>{side(data.closing)}</small></td><td className="num">{formatMoney(data.closing, lang)}</td></tr>
              </tbody>
            </table>
          </div>
          <p className="hint">{t('balanceSignHelp')}</p>
          {data.openItems.length > 0 && <>
            <h3>{t('openItems')}</h3>
            <table className="data-table report">
              <thead><tr><th>{t('record')}</th><th>{tf('date')}</th><th>{tf('dueDate')}</th><th className="num">{tf('total')}</th><th className="num">{tf('openAmount')}</th></tr></thead>
              <tbody>{data.openItems.map(o => <tr key={o.documentId}><td>{o.number} <small>· {tf(`type.${o.documentType}`)}</small></td><td>{formatDate(o.date, lang)}</td>
                <td>{o.dueDate ? formatDate(o.dueDate, lang) : '—'}</td><td className="num">{formatMoney(o.total, lang)}</td><td className="num">{formatMoney(o.open, lang)}</td></tr>)}</tbody>
            </table>
          </>}
        </>
      )}
    </section>
  );
}
