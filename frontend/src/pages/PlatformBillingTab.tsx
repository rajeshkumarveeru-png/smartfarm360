import React, { useCallback, useEffect, useState } from 'react';
import { api } from '../api/client';
import type { BillingSummary, ConsolePayment, ConsoleSubscription, PriceRow, ReconciliationRun, SubscriptionState } from '../api/types';
import { ErrorBanner, Loading } from '../components/common';
import { Toast } from '../components/Toast';
import { currentLanguage, useNs } from '../i18n';
import { describeError } from '../lib/errors';
import { formatDate, formatMoney } from '../lib/format';

const STATES: SubscriptionState[] = ['TRIAL', 'ACTIVE', 'PAST_DUE', 'LAPSED', 'CANCELLED', 'ENDED'];
type Action = { kind: 'trial' | 'grace' | 'comp' | 'endComp'; tenantId: number } | { kind: 'refund'; payment: ConsolePayment };

/**
 * The console's Billing tab (billing design, section 9, P12): subscriptions by state, payments with refunds, events
 * that did not apply, the nightly reconciliation and prices. Every change asks for a reason and is in the platform log.
 */
export default function PlatformBillingTab() {
  const t = useNs('platform');
  const tb = useNs('billing');
  const lang = currentLanguage();
  const [summary, setSummary] = useState<BillingSummary | null>(null);
  const [state, setState] = useState<string>('');
  const [subs, setSubs] = useState<ConsoleSubscription[]>([]);
  const [payments, setPayments] = useState<ConsolePayment[]>([]);
  const [prices, setPrices] = useState<PriceRow[]>([]);
  const [action, setAction] = useState<Action | null>(null);
  const [form, setForm] = useState({ days: '7', reason: '', planCode: 'GROWER', until: '', amount: '' });
  const [price, setPrice] = useState({ planCode: 'GROWER', period: 'YEARLY', amount: '', validFrom: '' });
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const load = useCallback(async () => {
    try {
      const [s, list, p, pr] = await Promise.all([api<BillingSummary>('/api/v1/platform/billing'),
        api<ConsoleSubscription[]>(`/api/v1/platform/billing/subscriptions${state ? `?state=${state}` : ''}`),
        api<ConsolePayment[]>('/api/v1/platform/billing/payments'), api<PriceRow[]>('/api/v1/platform/billing/prices')]);
      setSummary(s); setSubs(list); setPayments(p); setPrices(pr);
    } catch (e) { setError(describeError(e)); }
  }, [state]);
  useEffect(() => { void load(); }, [load]);

  async function run(fn: () => Promise<unknown>, done: string) {
    setError(''); setNotice('');
    try { await fn(); setAction(null); setForm({ days: '7', reason: '', planCode: 'GROWER', until: '', amount: '' }); await load(); setNotice(done); }
    catch (e) { setError(describeError(e)); }
  }

  function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!action) return;
    const reason = form.reason.trim();
    if (action.kind === 'refund') {
      void run(() => api(`/api/v1/platform/billing/payments/${action.payment.id}/refund`, { method: 'POST', body: { amount: form.amount, reason } }), t('billing.refunded'));
      return;
    }
    const base = `/api/v1/platform/billing/tenants/${action.tenantId}`;
    if (action.kind === 'comp') void run(() => api(`${base}/comp`, { method: 'PUT', body: { planCode: form.planCode, until: form.until, reason } }), t('billing.saved'));
    else if (action.kind === 'endComp') void run(() => api(`${base}/comp/end`, { method: 'POST', body: { reason } }), t('billing.saved'));
    else void run(() => api(`${base}/${action.kind}`, { method: 'POST', body: { days: Number(form.days), reason } }), t('billing.saved'));
  }

  if (!summary) return error ? <ErrorBanner message={error} /> : <Loading />;
  const last: ReconciliationRun | null = summary.lastReconciliation;
  return (
    <>
      <ErrorBanner message={error} />
      <Toast message={notice} onClose={() => setNotice('')} />
      <section className="panel">
        <ul className="plain-list">{STATES.map(s => <li key={s}>{tb(`state.${s}`)}: {summary.subscriptions[s] ?? 0}</li>)}</ul>
        <h4>{t('billing.reconciliation')}</h4>
        <p className={last && !last.ok ? 'warning' : undefined}>
          {last ? `${last.ok ? t('ok') : t('failed')} · ${formatDate(last.ranAt, lang)}` : t('never')}</p>
        {last?.differences.map((d, i) => <p key={i} className="warning">{t(`billing.difference.${d.kind}`)}: {d.gatewayId} · {d.detail}</p>)}
        <button className="secondary" onClick={() => void run(() => api('/api/v1/platform/billing/reconciliation', { method: 'POST' }), t('billing.reconciled'))}>
          {t('billing.reconcileNow')}</button>
        <h4>{t('billing.unapplied')}</h4>
        {summary.unappliedEvents.length === 0 && <p className="hint">{t('billing.noUnapplied')}</p>}
        {summary.unappliedEvents.map(ev => (
          <div className="activity-row" key={ev.id}>
            <div className="cycle-main"><h4>{ev.eventType} · {ev.outcome}</h4><p>{ev.eventId} · {formatDate(ev.receivedAt, lang)}{ev.error ? ` · ${ev.error}` : ''}</p></div>
            <button className="secondary" onClick={() => void run(() => api(`/api/v1/platform/billing/events/${ev.id}/replay`, { method: 'POST' }), t('billing.replayed'))}>
              {t('billing.replay')}</button>
          </div>
        ))}
      </section>

      {action && (
        <form className="form-card form-grid" onSubmit={submit}>
          <h4 className="full">{t(`billing.action.${action.kind}`)}</h4>
          {(action.kind === 'trial' || action.kind === 'grace') && (
            <label>{t('billing.days')}<input inputMode="numeric" value={form.days} onChange={e => setForm({ ...form, days: e.target.value })} /></label>
          )}
          {action.kind === 'comp' && (
            <>
              <label>{t('billing.plan')}<select value={form.planCode} onChange={e => setForm({ ...form, planCode: e.target.value })}>
                {['GROWER', 'ESTATE'].map(p => <option key={p} value={p}>{tb(`plan.${p}`)}</option>)}</select></label>
              <label>{t('billing.until')}<input type="date" value={form.until} onChange={e => setForm({ ...form, until: e.target.value })} /></label>
            </>
          )}
          {action.kind === 'refund' && (
            <label>{t('billing.amount')}<input inputMode="decimal" value={form.amount} onChange={e => setForm({ ...form, amount: e.target.value })} /></label>
          )}
          <label>{t('reason')}<input maxLength={300} value={form.reason} onChange={e => setForm({ ...form, reason: e.target.value })} /></label>
          <div className="form-actions full">
            <button className="primary" disabled={!form.reason.trim()}>{t('billing.apply')}</button>
            <button type="button" className="secondary" onClick={() => setAction(null)}>{t('close')}</button>
          </div>
        </form>
      )}

      <section className="panel">
        <h4>{t('billing.subscriptions')}</h4>
        <label>{t('status')}<select value={state} onChange={e => setState(e.target.value)}>
          <option value="">{t('billing.allStates')}</option>{STATES.map(s => <option key={s} value={s}>{tb(`state.${s}`)}</option>)}</select></label>
        <div className="activity-list">
          {subs.map(s => (
            <article className="activity-row" key={s.tenantId}>
              <div className="cycle-main">
                <h4>{s.tenantName} · {tb(`plan.${s.effectivePlanCode}`)}</h4>
                <p>{tb(`state.${s.state}`)}{s.period ? ` · ${tb(`period.${s.period}`)}` : ''}
                  {s.trialEndsAt && s.state === 'TRIAL' ? ` · ${tb('trialUntil', { date: formatDate(s.trialEndsAt, lang) })}` : ''}
                  {s.paidUntil ? ` · ${t('billing.paidUntil', { date: formatDate(s.paidUntil, lang) })}` : ''}
                  {s.graceEndsAt && s.state === 'PAST_DUE' ? ` · ${tb('graceUntil', { date: formatDate(s.graceEndsAt, lang) })}` : ''}
                  {s.compPlanCode && s.compUntil ? ` · ${tb('compUntil', { plan: tb(`plan.${s.compPlanCode}`), date: formatDate(s.compUntil, lang) })}` : ''}</p>
              </div>
              <div className="value-side">
                {s.state === 'TRIAL' && <button className="secondary" onClick={() => setAction({ kind: 'trial', tenantId: s.tenantId })}>{t('billing.action.trial')}</button>}
                {(s.state === 'PAST_DUE' || s.state === 'LAPSED') && <button className="secondary" onClick={() => setAction({ kind: 'grace', tenantId: s.tenantId })}>{t('billing.action.grace')}</button>}
                {s.compPlanCode
                  ? <button className="secondary" onClick={() => setAction({ kind: 'endComp', tenantId: s.tenantId })}>{t('billing.action.endComp')}</button>
                  : <button className="secondary" onClick={() => setAction({ kind: 'comp', tenantId: s.tenantId })}>{t('billing.action.comp')}</button>}
              </div>
            </article>
          ))}
        </div>
      </section>

      <section className="panel">
        <h4>{t('billing.payments')}</h4>
        <div className="activity-list">
          {payments.map(p => (
            <article className="activity-row" key={p.id}>
              <div className="cycle-main">
                <h4>{p.businessName} · {formatMoney(p.amount, lang)}</h4>
                <p>{p.invoiceNumber ?? '—'} · {formatDate(p.capturedAt, lang)}{Number(p.refundedAmount) > 0 ? ` · ${tb('refunded', { amount: formatMoney(p.refundedAmount, lang) })}` : ''}</p>
              </div>
              <div className="value-side">
                {p.invoiceId != null && <button className="secondary" onClick={() => void run(() => api(`/api/v1/platform/billing/invoices/${p.invoiceId}/resend`, { method: 'POST' }), t('billing.resent'))}>
                  {t('billing.resend')}</button>}
                {Number(p.refundedAmount) < Number(p.amount) && p.invoiceId != null && (
                  <button className="secondary" onClick={() => { setAction({ kind: 'refund', payment: p }); setForm(f => ({ ...f, amount: String(Number(p.amount) - Number(p.refundedAmount)) })); }}>
                    {t('billing.action.refund')}</button>)}
              </div>
            </article>
          ))}
        </div>
      </section>

      <section className="panel">
        <h4>{t('billing.prices')}</h4>
        <ul className="plain-list">{prices.map(p => <li key={`${p.planCode}-${p.period}-${p.validFrom}`}>
          {tb(`plan.${p.planCode}`)} · {tb(`period.${p.period}`)} · {formatMoney(p.amount, lang)} · {t('billing.from', { date: formatDate(p.validFrom, lang) })}</li>)}</ul>
        <form className="inline-form" onSubmit={e => { e.preventDefault(); void run(() => api('/api/v1/platform/billing/prices', { method: 'POST', body: price }), t('billing.priceSet')); }}>
          <label>{t('billing.plan')}<select value={price.planCode} onChange={e => setPrice({ ...price, planCode: e.target.value })}>
            {['GROWER', 'ESTATE'].map(p => <option key={p} value={p}>{tb(`plan.${p}`)}</option>)}</select></label>
          <label>{t('billing.period')}<select value={price.period} onChange={e => setPrice({ ...price, period: e.target.value })}>
            {['MONTHLY', 'YEARLY'].map(p => <option key={p} value={p}>{tb(`period.${p}`)}</option>)}</select></label>
          <label>{t('billing.amountBeforeGst')}<input inputMode="decimal" value={price.amount} onChange={e => setPrice({ ...price, amount: e.target.value })} /></label>
          <label>{t('billing.from', { date: '' })}<input type="date" value={price.validFrom} onChange={e => setPrice({ ...price, validFrom: e.target.value })} /></label>
          <button className="secondary" disabled={!price.amount || !price.validFrom}>{t('billing.setPrice')}</button>
        </form>
        <p className="hint">{t('billing.priceHelp')}</p>
      </section>
    </>
  );
}
