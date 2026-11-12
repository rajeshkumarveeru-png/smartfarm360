import React, { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import { useTenant } from '../auth/useCurrentTenant';
import { ErrorBanner } from '../components/common';
import { useNs } from '../i18n';
import { describeError } from '../lib/errors';
import { today } from '../lib/format';
import { isAmount } from './NewRecordPage';
import { newIdempotencyKey, useCategoryLabel, useFinanceData } from './useFinanceData';

/**
 * A short expense form shown inside a records panel, so an expense is added without leaving the farm, crop or batch.
 * {@code prefill} is the panel's query ("farm=2&activity=7"): the bill is booked to that farm and activity. Bills with
 * stock items or several lines use the full form ("More options").
 */
export default function QuickExpenseForm({ prefill, onSaved, onCancel }: { prefill: string; onSaved: () => void; onCancel: () => void }) {
  const tenant = useTenant();
  const t = useNs('fin');
  const tc = useNs('common');
  const categoryLabel = useCategoryLabel();
  const data = useFinanceData();
  const params = useMemo(() => new URLSearchParams(prefill), [prefill]);
  const [key, setKey] = useState(newIdempotencyKey);
  const [form, setForm] = useState({ docDate: today(), categoryId: '', amount: '', financialAccountId: '', partyId: '', description: '' });
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const set = (k: keyof typeof form, v: string) => setForm(f => ({ ...f, [k]: v }));
  const categories = data.categories.filter(c => c.active && c.kind === 'EXPENSE');

  async function save(e: React.FormEvent) {
    e.preventDefault();
    if (!form.categoryId) { setError(t('errors.category')); return; }
    if (!isAmount(form.amount)) { setError(t('errors.amount')); return; }
    if (!form.partyId && !form.financialAccountId) { setError(t('errors.partyOrAccount')); return; }
    setBusy(true); setError('');
    try {
      const farm = params.get('farm'), activity = params.get('activity');
      await api(`/api/v1/tenants/${tenant.id}/finance/bills`, { method: 'POST', headers: { 'Idempotency-Key': key }, body: {
        docDate: form.docDate, partyId: form.partyId ? Number(form.partyId) : null, farmId: farm ? Number(farm) : null,
        financialAccountId: !form.partyId && form.financialAccountId ? Number(form.financialAccountId) : null, post: true,
        lines: [{ categoryId: Number(form.categoryId), amount: form.amount, description: form.description.trim() || null, costObjectId: activity ? Number(activity) : null }],
      } });
      setKey(newIdempotencyKey());
      onSaved();
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="quick-expense" noValidate onSubmit={e => void save(e)} aria-label={t('newExpense')}>
      <ErrorBanner message={error || data.error} />
      <div className="quick-expense-fields">
        <label>{t('date')}<input type="date" max={today()} value={form.docDate} onChange={e => set('docDate', e.target.value)} /></label>
        <label>{t('category')}<select value={form.categoryId} onChange={e => set('categoryId', e.target.value)}>
          <option value="">—</option>{categories.map(c => <option key={c.id} value={c.id}>{categoryLabel(c)}</option>)}</select></label>
        <label>{t('amount')}<input inputMode="decimal" value={form.amount} onChange={e => set('amount', e.target.value)} /></label>
        {!form.partyId && <label>{t('paidFrom')}<select value={form.financialAccountId} onChange={e => set('financialAccountId', e.target.value)}>
          <option value="">—</option>{data.accounts.filter(a => a.active).map(a => <option key={a.id} value={a.id}>{a.name}</option>)}</select></label>}
        <label>{t('party')} <small>{tc('optional')}</small><select value={form.partyId} onChange={e => set('partyId', e.target.value)}>
          <option value="">—</option>{data.parties.filter(p => p.active).map(p => <option key={p.id} value={p.id}>{p.name}</option>)}</select></label>
        <label className="wide">{t('description')} <small>{tc('optional')}</small><input maxLength={240} value={form.description} onChange={e => set('description', e.target.value)} /></label>
      </div>
      <div className="form-actions">
        <Link className="text-link quick-more" to={`/t/${tenant.id}/finance/new/expense?${prefill}`}>{t('moreOptions')}</Link>
        <button type="button" className="secondary" onClick={onCancel}>{tc('cancel')}</button>
        <button className="primary" disabled={busy}>{busy ? tc('loading') : t('saveExpense')}</button>
      </div>
    </form>
  );
}
