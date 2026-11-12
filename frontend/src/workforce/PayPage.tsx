import { useEffect, useState } from 'react';
import { api } from '../api/client';
import { useTenant } from '../auth/useCurrentTenant';
import { ErrorBanner } from '../components/common';
import { Toast } from '../components/Toast';
import type { FinancialAccount } from '../finance/types';
import { newIdempotencyKey } from '../finance/useFinanceData';
import { currentLanguage, useNs } from '../i18n';
import { describeError } from '../lib/errors';
import { formatDate, formatMoney, formatNumber, today } from '../lib/format';
import type { PayRunPreview, RecordRef, Worker } from './types';

const monthRange = () => {
  const d = new Date();
  const first = new Date(d.getFullYear(), d.getMonth() - 1, 1), last = new Date(d.getFullYear(), d.getMonth(), 0);
  const iso = (x: Date) => `${x.getFullYear()}-${String(x.getMonth() + 1).padStart(2, '0')}-${String(x.getDate()).padStart(2, '0')}`;
  return { from: iso(first), to: iso(last) };
};
const money = (v: string) => /^\d{1,12}(\.\d{1,2})?$/.test(v.trim());

/** Pay runs (preview, then post), paying wages with advances recovered, and giving advances (W1–W3). */
export default function PayPage() {
  const tenant = useTenant();
  const t = useNs('workforce');
  const tc = useNs('common');
  const lang = currentLanguage();
  const base = `/api/v1/tenants/${tenant.id}/workforce`;
  const [range, setRange] = useState(monthRange);
  const [preview, setPreview] = useState<PayRunPreview | null>(null);
  const [workers, setWorkers] = useState<Worker[]>([]);
  const [accounts, setAccounts] = useState<FinancialAccount[]>([]);
  const [pay, setPay] = useState<Record<number, { cash: string; recovered: string }>>({});
  const [payment, setPayment] = useState({ date: today(), financialAccountId: '' });
  const [advance, setAdvance] = useState({ workerId: '', amount: '', date: today(), financialAccountId: '' });
  const [keys, setKeys] = useState({ run: newIdempotencyKey(), pay: newIdempotencyKey(), advance: newIdempotencyKey() });
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  const loadWorkers = () => api<Worker[]>(`${base}/workers`).then(w => {
    setWorkers(w);
    setPay(Object.fromEntries(w.filter(x => x.owed > 0).map(x => [x.id, { cash: Math.max(0, x.owed - Math.min(x.advance, x.owed)).toFixed(2), recovered: Math.min(x.advance, x.owed).toFixed(2) }])));
  }).catch(e => setError(describeError(e)));
  useEffect(() => {
    void loadWorkers();
    api<FinancialAccount[]>(`/api/v1/tenants/${tenant.id}/finance/accounts`).then(a => setAccounts(a.filter(x => x.active))).catch(() => setAccounts([]));
  }, [base]); // eslint-disable-line react-hooks/exhaustive-deps

  async function run<T>(action: () => Promise<T>, done: (r: T) => void) {
    setBusy(true); setError(''); setMessage('');
    try { done(await action()); } catch (e) { setError(describeError(e)); } finally { setBusy(false); }
  }

  const accountOptions = <><option value="">—</option>{accounts.map(a => <option key={a.id} value={a.id}>{a.name}</option>)}</>;
  const owing = workers.filter(w => w.owed > 0);
  return (
    <>
      <div className="crop-page-head"><div><h2>{t('pay')}</h2><p>{t('payHelp')}</p></div></div>
      <ErrorBanner message={error} />
      <Toast message={message} onClose={() => setMessage('')} />

      <h3>{t('payRun')}</h3>
      <form className="form-card form-grid filters" onSubmit={e => { e.preventDefault(); void run(() => api<PayRunPreview>(`${base}/pay-runs/preview`, { method: 'POST', body: range }), setPreview); }}>
        <label>{t('from')}<input type="date" value={range.from} onChange={e => { setRange({ ...range, from: e.target.value }); setPreview(null); }} /></label>
        <label>{t('to')}<input type="date" value={range.to} onChange={e => { setRange({ ...range, to: e.target.value }); setPreview(null); }} /></label>
        <div className="form-actions"><button className="secondary" disabled={busy}>{t('calculate')}</button></div>
      </form>
      {preview && (
        <>
          {preview.workers.length === 0 ? <p className="hint">{t('nothingToPay')}</p> : (
            <div className="table-scroll">
              <table className="data-table">
                <thead><tr><th>{t('worker')}</th><th className="num">{t('days')}</th><th className="num">{t('timePay')}</th><th className="num">{t('piecePay')}</th>
                  <th className="num">{t('gross')}</th><th>{t('costGoesTo')}</th></tr></thead>
                <tbody>
                  {preview.workers.map(w => (
                    <tr key={w.workerId}>
                      <td>{w.name}<small> · {t(`payType.${w.payType}`)}</small>{w.notes.map(n => <small key={n} className="low-stock-label"> · {t(`note.${n}`)}</small>)}</td>
                      <td className="num">{formatNumber(w.days, lang, 1)}</td><td className="num">{formatMoney(w.timePay, lang)}</td>
                      <td className="num">{formatMoney(w.piecePay, lang)}</td><td className="num"><b>{formatMoney(w.gross, lang)}</b></td>
                      <td>{w.costs.map(c => `${c.costObjectName ?? t('general')}: ${formatMoney(c.amount, lang)}`).join(' · ')}</td>
                    </tr>
                  ))}
                </tbody>
                <tfoot><tr><td colSpan={4}>{t('total')}</td><td className="num">{formatMoney(preview.total, lang)}</td><td /></tr></tfoot>
              </table>
            </div>
          )}
          {preview.skipped.length > 0 && <p className="hint">{preview.skipped.map(s => { const [why, name] = s.split(':'); return t(`skipped.${why}`, { name }); }).join(' ')}</p>}
          {preview.total > 0 && <div className="form-actions">
            <button type="button" className="primary" disabled={busy} onClick={() => void run(() => api(`${base}/pay-runs`, { method: 'POST', body: range, headers: { 'Idempotency-Key': keys.run } }),
              () => { setMessage(t('payRunPosted', { from: formatDate(range.from, lang), to: formatDate(range.to, lang) })); setPreview(null);
                setKeys(k => ({ ...k, run: newIdempotencyKey() })); void loadWorkers(); })}>{t('postPayRun')}</button></div>}
        </>
      )}

      <h3 className="section-space">{t('payWages')}</h3>
      {owing.length === 0 ? <p className="hint">{t('nobodyOwed')}</p> : (
        <form className="form-card" onSubmit={e => {
          e.preventDefault();
          const payments = owing.map(w => ({ workerId: w.id, cash: pay[w.id]?.cash || '0', advanceRecovered: pay[w.id]?.recovered || '0' }));
          if (payments.some(p => !money(p.cash) || !money(p.advanceRecovered))) { setError(t('errors.amount')); return; }
          void run(() => api<RecordRef[]>(`${base}/wage-payments`, { method: 'POST', headers: { 'Idempotency-Key': keys.pay },
            body: { date: payment.date, financialAccountId: payment.financialAccountId ? Number(payment.financialAccountId) : null, payments } }),
          r => { setMessage(t('paymentsRecorded', { count: String(r.length) })); setKeys(k => ({ ...k, pay: newIdempotencyKey() })); void loadWorkers(); });
        }}>
          <div className="form-grid">
            <label>{t('date')}<input type="date" value={payment.date} onChange={e => setPayment({ ...payment, date: e.target.value })} /></label>
            <label>{t('paidFrom')}<select value={payment.financialAccountId} onChange={e => setPayment({ ...payment, financialAccountId: e.target.value })}>{accountOptions}</select></label>
          </div>
          <div className="table-scroll"><table className="data-table">
            <thead><tr><th>{t('worker')}</th><th className="num">{t('owed')}</th><th className="num">{t('advance')}</th><th>{t('cashNow')}</th><th>{t('recoverAdvance')}</th></tr></thead>
            <tbody>
              {owing.map(w => (
                <tr key={w.id}>
                  <td>{w.name}</td><td className="num">{formatMoney(w.owed, lang)}</td><td className="num">{formatMoney(w.advance, lang)}</td>
                  <td><input aria-label={t('cashFor', { name: w.name })} inputMode="decimal" value={pay[w.id]?.cash ?? ''}
                             onChange={e => setPay(p => ({ ...p, [w.id]: { ...p[w.id], cash: e.target.value } }))} /></td>
                  <td><input aria-label={t('recoverFor', { name: w.name })} inputMode="decimal" value={pay[w.id]?.recovered ?? ''}
                             onChange={e => setPay(p => ({ ...p, [w.id]: { ...p[w.id], recovered: e.target.value } }))} /></td>
                </tr>
              ))}
            </tbody>
          </table></div>
          <div className="form-actions"><button className="primary" disabled={busy}>{t('recordPayments')}</button></div>
        </form>
      )}

      <h3 className="section-space">{t('giveAdvance')}</h3>
      <form className="form-card form-grid" onSubmit={e => {
        e.preventDefault();
        if (!advance.workerId || !advance.financialAccountId || !money(advance.amount) || Number(advance.amount) <= 0) { setError(tc('required')); return; }
        void run(() => api(`${base}/advances`, { method: 'POST', headers: { 'Idempotency-Key': keys.advance },
          body: { workerId: Number(advance.workerId), date: advance.date, amount: advance.amount, financialAccountId: Number(advance.financialAccountId) } }),
        () => { setMessage(t('advanceRecorded')); setAdvance({ ...advance, workerId: '', amount: '' }); setKeys(k => ({ ...k, advance: newIdempotencyKey() })); void loadWorkers(); });
      }}>
        <label>{t('worker')}<select value={advance.workerId} onChange={e => setAdvance({ ...advance, workerId: e.target.value })}>
          <option value="">—</option>{workers.map(w => <option key={w.id} value={w.id}>{w.name}</option>)}</select></label>
        <label>{t('amount')}<input inputMode="decimal" value={advance.amount} onChange={e => setAdvance({ ...advance, amount: e.target.value })} /></label>
        <label>{t('date')}<input type="date" value={advance.date} onChange={e => setAdvance({ ...advance, date: e.target.value })} /></label>
        <label>{t('paidFrom')}<select value={advance.financialAccountId} onChange={e => setAdvance({ ...advance, financialAccountId: e.target.value })}>{accountOptions}</select></label>
        <div className="form-actions full"><button className="secondary" disabled={busy}>{t('giveAdvance')}</button></div>
      </form>
    </>
  );
}
