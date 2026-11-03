import React, { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api, download } from '../api/client';
import type { BillingInvoice, BillingOverview, BillingPeriod, BillingPlan, BillingProfile, CheckoutResult } from '../api/types';
import type { FinancialAccount } from '../finance/types';
import { useTenant } from '../auth/useCurrentTenant';
import { ErrorBanner } from '../components/common';
import { currentLanguage, useNs } from '../i18n';
import { describeError } from '../lib/errors';
import { formatDate, formatMoney, formatNumber } from '../lib/format';
import { can } from '../lib/nav';

/** GST state codes (the first two digits of a GSTIN); names come from the catalogs. */
export const GST_STATES = ['01', '02', '03', '04', '05', '06', '07', '08', '09', '10', '11', '12', '13', '14', '15', '16', '17', '18', '19', '20', '21',
  '22', '23', '24', '26', '27', '29', '30', '31', '32', '33', '34', '35', '36', '37', '38'];

const EMPTY_PROFILE: BillingProfile = { legalName: '', addressLine1: '', addressLine2: '', city: '', pincode: '', stateCode: '33', gstin: '', email: '' };
const RUNNING = ['ACTIVE', 'PAST_DUE'];

/**
 * The plan and billing on the settings page (billing design, sections 3–8, 10). Every member sees the plan; owners
 * and accountants see the billing details and invoices; only owners pay, change or cancel, on the gateway's page.
 */
export default function BillingPanel() {
  const tenant = useTenant();
  const t = useNs('billing');
  const lang = currentLanguage();
  const base = `/api/v1/tenants/${tenant.id}/billing`;
  const canView = can(tenant, 'BILLING_VIEW');
  const canManage = can(tenant, 'BILLING_MANAGE');
  const canBook = can(tenant, 'FINANCE_WRITE') && canView;
  const [overview, setOverview] = useState<BillingOverview | null>(null);
  const [profile, setProfile] = useState<BillingProfile>(EMPTY_PROFILE);
  const [invoices, setInvoices] = useState<BillingInvoice[]>([]);
  const [accounts, setAccounts] = useState<FinancialAccount[]>([]);
  const [bookFrom, setBookFrom] = useState('');
  const [period, setPeriod] = useState<BillingPeriod>('YEARLY');
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const load = useCallback(async () => {
    try {
      setOverview(await api<BillingOverview>(base));
      if (canView) {
        const [p, i] = await Promise.all([api<BillingProfile | undefined>(`${base}/profile`), api<BillingInvoice[]>(`${base}/invoices`)]);
        if (p) setProfile({ ...EMPTY_PROFILE, ...p, addressLine2: p.addressLine2 ?? '', gstin: p.gstin ?? '' });
        setInvoices(i);
      }
      if (canBook) setAccounts((await api<FinancialAccount[]>(`/api/v1/tenants/${tenant.id}/finance/accounts`)).filter(a => a.active));
    } catch (e) {
      setError(describeError(e));
    }
  }, [base, canView, canBook, tenant.id]);
  useEffect(() => { void load(); }, [load]);

  async function run(action: () => Promise<void>, done?: string) {
    setError(''); setNotice('');
    try { await action(); if (done) setNotice(done); } catch (e) { setError(describeError(e)); }
  }

  function choose(plan: BillingPlan) {
    if (!overview) return;
    const subscribed = RUNNING.includes(overview.state);
    void run(async () => {
      const r = await api<CheckoutResult>(`${base}/${subscribed ? 'change' : 'checkout'}`, { method: 'POST', body: { planCode: plan.code, period } });
      if (r.checkoutUrl) { window.location.assign(r.checkoutUrl); return; }
      setOverview(r.overview);
    }, RUNNING.includes(overview.state) ? t('changeScheduled') : undefined);
  }

  function saveProfile(e: React.FormEvent) {
    e.preventDefault();
    void run(async () => {
      const saved = await api<BillingProfile>(`${base}/profile`, { method: 'PUT', body: {
        ...profile, addressLine2: profile.addressLine2?.trim() || null, gstin: profile.gstin?.trim().toUpperCase() || null } });
      setProfile({ ...EMPTY_PROFILE, ...saved, addressLine2: saved.addressLine2 ?? '', gstin: saved.gstin ?? '' });
      setOverview(await api<BillingOverview>(base));
    }, t('profileSaved'));
  }

  if (!overview) return error ? <ErrorBanner message={error} /> : null;
  const subscribed = RUNNING.includes(overview.state);
  const planName = (code: string) => t(`plan.${code}`);

  return (
    <section className="panel" id="billing">
      <h3>{t('title')}</h3>
      <ErrorBanner message={error} />
      {notice && <div className="info-banner" role="status">{notice}</div>}
      <p><strong>{planName(overview.effectivePlanCode)}</strong> · {t(`state.${overview.state}`)}</p>
      <ul className="plain-list">
        {overview.state === 'TRIAL' && overview.trialEndsAt && <li>{t('trialUntil', { date: formatDate(overview.trialEndsAt, lang) })}</li>}
        {overview.paidUntil && ['ACTIVE', 'CANCELLED'].includes(overview.state) && (
          <li>{t(overview.state === 'CANCELLED' ? 'endsOn' : 'renewsOn', { date: formatDate(overview.paidUntil, lang), period: overview.period ? t(`period.${overview.period}`) : '' })}</li>
        )}
        {overview.state === 'PAST_DUE' && overview.graceEndsAt && <li className="warning">{t('graceUntil', { date: formatDate(overview.graceEndsAt, lang) })}</li>}
        {overview.pendingPlanCode && overview.paidUntil && (
          <li>{t('pendingChange', { plan: planName(overview.pendingPlanCode), period: t(`period.${overview.pendingPeriod ?? 'MONTHLY'}`), date: formatDate(overview.paidUntil, lang) })}</li>
        )}
        {overview.compPlanCode && overview.compUntil && <li>{t('compUntil', { plan: planName(overview.compPlanCode), date: formatDate(overview.compUntil, lang) })}</li>}
        {overview.checkoutStarted && <li>{t('checkoutWaiting')}</li>}
        {overview.upgradeStarted && <li>{t('upgradeWaiting')}</li>}
      </ul>
      <p className="hint">{t('neverLocked')}</p>

      <div className="tabs" role="tablist" aria-label={t('periodLabel')}>
        {(['MONTHLY', 'YEARLY'] as BillingPeriod[]).map(p => (
          <button key={p} role="tab" aria-selected={period === p} className={period === p ? 'tab active' : 'tab'} onClick={() => setPeriod(p)}>{t(`period.${p}`)}</button>
        ))}
      </div>
      <div className="activity-list">
        {overview.plans.map(plan => {
          const price = plan.prices.find(p => p.period === period);
          const current = subscribed && overview.planCode === plan.code && overview.period === period;
          return (
            <article className="activity-row" key={plan.code}>
              <div className="cycle-main">
                <h4>{planName(plan.code)}</h4>
                <p>{plan.limits.filter(l => l.code !== 'STORAGE_MB').map(l => `${t(`limit.${l.code}`)}: ${l.value == null ? t('unlimited') : formatNumber(l.value, lang)}`).join(' · ')}</p>
                {plan.paid && price?.current && (
                  <p>{t(period === 'YEARLY' ? 'perYear' : 'perMonth', { amount: formatMoney(price.current.amount, lang) })} {t('plusGst')}
                    {price.next && <> · {t('priceFrom', { amount: formatMoney(price.next.amount, lang), date: formatDate(price.next.validFrom, lang) })}</>}</p>
                )}
                {!plan.paid && <p>{t('free')}</p>}
              </div>
              {canManage && plan.paid && (current
                ? <span className="badge">{t('current')}</span>
                : <button className="primary" disabled={!overview.profileComplete || overview.state === 'PAST_DUE'} onClick={() => choose(plan)}>
                    {subscribed ? t('switchTo') : t('choose')}</button>)}
            </article>
          );
        })}
      </div>
      {canManage && !overview.profileComplete && <p className="hint">{t('profileFirst')}</p>}
      <p className="hint">{t('payOnGateway')}</p>
      {canManage && subscribed && (
        <button className="secondary" onClick={() => {
          if (!window.confirm(t('cancelConfirm'))) return;
          void run(async () => setOverview(await api<BillingOverview>(`${base}/cancel`, { method: 'POST' })), t('cancelled'));
        }}>{t('cancel')}</button>
      )}

      {canView && (
        <>
          <h4>{t('details')}</h4>
          <form className="form-card form-grid" onSubmit={saveProfile}>
            <label>{t('legalName')}<input required maxLength={140} disabled={!canManage} value={profile.legalName} onChange={e => setProfile({ ...profile, legalName: e.target.value })} /></label>
            <label>{t('gstin')}<input maxLength={15} disabled={!canManage} value={profile.gstin ?? ''} placeholder={t('gstinOptional')}
                                      onChange={e => setProfile({ ...profile, gstin: e.target.value.toUpperCase() })} /></label>
            <label>{t('addressLine1')}<input required maxLength={200} disabled={!canManage} value={profile.addressLine1} onChange={e => setProfile({ ...profile, addressLine1: e.target.value })} /></label>
            <label>{t('addressLine2')}<input maxLength={200} disabled={!canManage} value={profile.addressLine2 ?? ''} onChange={e => setProfile({ ...profile, addressLine2: e.target.value })} /></label>
            <label>{t('city')}<input required maxLength={80} disabled={!canManage} value={profile.city} onChange={e => setProfile({ ...profile, city: e.target.value })} /></label>
            <label>{t('pincode')}<input required inputMode="numeric" pattern="[1-9][0-9]{5}" maxLength={6} disabled={!canManage} value={profile.pincode}
                                        onChange={e => setProfile({ ...profile, pincode: e.target.value })} /></label>
            <label>{t('stateField')}<select disabled={!canManage} value={profile.stateCode} onChange={e => setProfile({ ...profile, stateCode: e.target.value })}>
              {GST_STATES.map(s => <option key={s} value={s}>{t(`gstState.${s}`)}</option>)}</select></label>
            <label>{t('invoiceEmail')}<input required type="email" maxLength={254} disabled={!canManage} value={profile.email} onChange={e => setProfile({ ...profile, email: e.target.value })} /></label>
            {canManage && <div className="form-actions full"><button className="primary">{t('saveDetails')}</button></div>}
          </form>

          <h4>{t('invoices')}</h4>
          {invoices.length === 0 && <p className="hint">{t('noInvoices')}</p>}
          {canBook && invoices.some(i => i.bookedDocumentId == null) && (
            <label>{t('paidFrom')}<select value={bookFrom} onChange={e => setBookFrom(e.target.value)}>
              <option value="">{t('notPaidYet')}</option>
              {accounts.map(a => <option key={a.id} value={a.id}>{a.name}</option>)}</select></label>
          )}
          <div className="activity-list">
            {invoices.map(inv => (
              <article className="activity-row" key={inv.id}>
                <div className="cycle-main">
                  <h4>{inv.number} · {formatMoney(inv.total, lang)}</h4>
                  <p>{formatDate(inv.invoiceDate, lang)} · {t('gstIncluded', { amount: formatMoney(Number(inv.cgst) + Number(inv.sgst) + Number(inv.igst), lang) })}
                    {Number(inv.refundedAmount) > 0 && <> · {t('refunded', { amount: formatMoney(inv.refundedAmount, lang) })}</>}</p>
                  {inv.creditNotes.map(c => (
                    <p key={c.id}>{t('creditNote', { number: c.number, amount: formatMoney(c.total, lang) })}{' '}
                      <button className="text-link" onClick={() => void run(() => download(`${base}/invoices/${inv.id}/credit-notes/${c.id}/pdf`, `${c.number.replace(/\//g, '-')}.pdf`))}>{t('pdf')}</button></p>
                  ))}
                </div>
                <button className="secondary" onClick={() => void run(() => download(`${base}/invoices/${inv.id}/pdf`, `${inv.number.replace(/\//g, '-')}.pdf`))}>{t('pdf')}</button>
                {canBook && (inv.bookedDocumentId == null
                  ? <button className="secondary" onClick={() => void run(async () => {
                      await api(`/api/v1/tenants/${tenant.id}/finance/subscription-invoices/${inv.id}/book`, { method: 'POST', body: { financialAccountId: bookFrom ? Number(bookFrom) : null } });
                      setInvoices(await api<BillingInvoice[]>(`${base}/invoices`));
                    }, t('booked'))}>{t('book')}</button>
                  : <span className="badge">{t('inBooks')}</span>)}
              </article>
            ))}
          </div>
        </>
      )}
    </section>
  );
}

/** The banner every member of the farm business sees: the trial's last week, a failed renewal, or a lapsed plan. */
export function BillingBanner({ tenantId, canManage }: { tenantId: number; canManage: boolean }) {
  const t = useNs('billing');
  const lang = currentLanguage();
  const [o, setO] = useState<BillingOverview | null>(null);
  useEffect(() => { api<BillingOverview>(`/api/v1/tenants/${tenantId}/billing`).then(setO).catch(() => setO(null)); }, [tenantId]);
  if (!o?.banner) return null;
  const date = o.banner === 'TRIAL_ENDING' ? o.trialEndsAt : o.graceEndsAt;
  return (
    <div className={o.banner === 'TRIAL_ENDING' ? 'info-banner' : 'error-banner'} role="status">
      {t(`banner.${o.banner}`, { date: formatDate(date ?? '', lang) })}{canManage && <> <Link to={`/t/${tenantId}/settings#billing`}>{t('bannerLink')}</Link></>}
    </div>
  );
}

/** Where the gateway's page sends the owner back: the plan changes once the gateway confirms the payment. */
export function BillingReturnPage() {
  const t = useNs('billing');
  return (
    <section className="crop-page">
      <div className="info-banner" role="status">{t('returned')}</div>
      <p><Link className="text-link" to="/">{t('backToApp')}</Link></p>
    </section>
  );
}
