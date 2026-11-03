import { useEffect, useState, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { ArrowDownLeft, ArrowUpRight, Landmark, Wallet } from 'lucide-react';
import { api } from '../api/client';
import type { Page } from '../api/types';
import { useTenant } from '../auth/useCurrentTenant';
import { ErrorBanner, Loading } from '../components/common';
import { currentLanguage, useNs } from '../i18n';
import { describeError } from '../lib/errors';
import { formatDate, formatMoney, today } from '../lib/format';
import { can } from '../lib/nav';
import { RecordList } from './RecordsPage';
import type { DocumentSummary, Summary } from './types';

/** The kinds of record a user can start from the overview, with the permission each needs. */
export const QUICK_ACTIONS = [
  { kind: 'expense', permission: 'FINANCE_WRITE' }, { kind: 'sale', permission: 'FINANCE_WRITE' },
  { kind: 'payment', permission: 'FINANCE_WRITE' }, { kind: 'receipt', permission: 'FINANCE_WRITE' },
  { kind: 'transfer', permission: 'FINANCE_WRITE' }, { kind: 'owner', permission: 'FINANCE_WRITE' },
  { kind: 'loan', permission: 'FINANCE_WRITE' }, { kind: 'loan-repayment', permission: 'FINANCE_WRITE' },
  { kind: 'settlement', permission: 'FINANCE_WRITE' }, { kind: 'write-off', permission: 'FINANCE_REVERSE' },
  { kind: 'opening', permission: 'FINANCE_MANAGE' }, { kind: 'depreciation', permission: 'FINANCE_MANAGE' },
] as const;

export default function FinanceHome() {
  const tenant = useTenant();
  const t = useNs('fin');
  const lang = currentLanguage();
  const base = `/api/v1/tenants/${tenant.id}/finance`;
  const [summary, setSummary] = useState<Summary | null>(null);
  const [recent, setRecent] = useState<DocumentSummary[]>([]);
  const [error, setError] = useState('');

  useEffect(() => {
    let alive = true;
    Promise.all([api<Summary>(`${base}/reports/summary?asOf=${today()}`), api<Page<DocumentSummary>>(`${base}/documents?size=8`)])
      .then(([s, r]) => { if (alive) { setSummary(s); setRecent(r.items); } })
      .catch(e => alive && setError(describeError(e)));
    return () => { alive = false; };
  }, [base]);

  if (error) return <ErrorBanner message={error} />;
  if (!summary) return <Loading />;
  const actions = QUICK_ACTIONS.filter(a => can(tenant, a.permission));
  return (
    <>
      <div className="crop-page-head">
        <div>
          <h2>{t('title')}</h2>
          <p>{t('yearSoFar', { from: formatDate(summary.fiscalYearFrom, lang), to: formatDate(summary.asOf, lang) })} · {t('actualFigures')}</p>
        </div>
      </div>
      <div className="finance-stats">
        <Stat label={t('yearRevenue')} value={formatMoney(summary.yearRevenue, lang)} icon={<ArrowDownLeft size={18} />} />
        <Stat label={t('yearCosts')} value={formatMoney(summary.yearCosts, lang)} icon={<ArrowUpRight size={18} />} />
        <Stat label={t('operatingProfit')} value={formatMoney(summary.yearOperatingProfit, lang)} />
        <Stat label={t('netProfit')} value={formatMoney(summary.yearNetProfit, lang)} />
        <Stat label={t('cashAndBank')} value={formatMoney(summary.cashTotal, lang)} icon={<Wallet size={18} />} />
        <Stat label={t('receivable')} value={formatMoney(summary.receivableTotal, lang)} hint={summary.overdueReceivables > 0 ? t('overdue', { amount: formatMoney(summary.overdueReceivables, lang) }) : undefined} />
        <Stat label={t('payable')} value={formatMoney(summary.payableTotal, lang)} icon={<Landmark size={18} />} />
      </div>
      {actions.length > 0 && (
        <>
          <h3>{t('newRecord')}</h3>
          <div className="quick-actions">
            {actions.map(a => <Link key={a.kind} className="secondary" to={`/t/${tenant.id}/finance/new/${a.kind}`}>{t(`kind.${a.kind}`)}</Link>)}
          </div>
        </>
      )}
      <div className="section-head section-space">
        <h3>{t('recentRecords')}</h3>
        <Link className="text-link" to={`/t/${tenant.id}/finance/records`}>{t('allRecords')}</Link>
      </div>
      <RecordList items={recent} />
    </>
  );
}

function Stat({ label, value, icon, hint }: { label: string; value: string; icon?: ReactNode; hint?: string }) {
  return (
    <div className="finance-stat">
      <span>{label}</span><b>{value}</b>{icon}
      {hint && <small className="low-stock-label">{hint}</small>}
    </div>
  );
}
