import React, { useState } from 'react';
import { Plus } from 'lucide-react';
import { api } from '../api/client';
import { useTenant } from '../auth/useCurrentTenant';
import { EmptyState, ErrorBanner, Loading } from '../components/common';
import { currentLanguage, useNs } from '../i18n';
import { describeError } from '../lib/errors';
import { formatDate, formatMoney } from '../lib/format';
import { can } from '../lib/nav';
import { useFinanceData } from './useFinanceData';

/** Cash, bank and UPI accounts with balances, and loans with what is still owed. */
export default function AccountsPage() {
  const tenant = useTenant();
  const t = useNs('fin');
  const tc = useNs('common');
  const lang = currentLanguage();
  const data = useFinanceData();
  const canManage = can(tenant, 'FINANCE_MANAGE');
  const [form, setForm] = useState({ name: '', kind: 'BANK' });
  const [show, setShow] = useState(false);
  const [error, setError] = useState('');

  async function save(e: React.FormEvent) {
    e.preventDefault();
    if (!form.name.trim()) { setError(tc('required')); return; }
    setError('');
    try {
      await api(`/api/v1/tenants/${tenant.id}/finance/accounts`, { method: 'POST', body: { name: form.name.trim(), kind: form.kind } });
      setForm({ name: '', kind: 'BANK' }); setShow(false); await data.reload();
    } catch (err) {
      setError(describeError(err));
    }
  }

  async function toggle(id: number, active: boolean) {
    setError('');
    try { await api(`/api/v1/tenants/${tenant.id}/finance/accounts/${id}`, { method: 'PATCH', body: { active } }); await data.reload(); }
    catch (err) { setError(describeError(err)); }
  }

  if (data.loading) return <Loading />;
  const total = data.accounts.reduce((s, a) => s + Number(a.balance), 0);
  return (
    <>
      <div className="crop-page-head">
        <div><h2>{t('accounts')}</h2><p>{t('cashAndBank')}: <b>{formatMoney(total, lang)}</b></p></div>
        {canManage && <button className="primary" onClick={() => setShow(x => !x)}><Plus size={16} />{t('addAccount')}</button>}
      </div>
      <ErrorBanner message={error || data.error} />
      {show && (
        <form className="form-card form-grid" onSubmit={save} noValidate>
          <label>{t('name')}<input maxLength={80} value={form.name} onChange={e => setForm({ ...form, name: e.target.value })} /></label>
          <label>{t('accountKind')}<select value={form.kind} onChange={e => setForm({ ...form, kind: e.target.value })}>
            {['CASH', 'BANK', 'UPI'].map(k => <option key={k} value={k}>{t(`accountKinds.${k}`)}</option>)}</select></label>
          <div className="form-actions full"><button type="button" className="secondary" onClick={() => setShow(false)}>{tc('cancel')}</button><button className="primary">{tc('save')}</button></div>
        </form>
      )}
      <div className="activity-list">
        {data.accounts.map(a => (
          <article className="activity-row" key={a.id}>
            <div className="cycle-main"><h4>{a.name}</h4><p>{t(`accountKinds.${a.kind}`)}{!a.active ? ` · ${t('inactive')}` : ''}</p></div>
            <div className="value-side"><b>{formatMoney(a.balance, lang)}</b>
              {canManage && <button className="text-action" onClick={() => void toggle(a.id, !a.active)}>{a.active ? t('deactivate') : t('activate')}</button>}</div>
          </article>
        ))}
      </div>
      <h3 className="section-space">{t('loans')}</h3>
      <div className="activity-list">
        {data.loans.map(l => (
          <article className="activity-row" key={l.id}>
            <div className="cycle-main"><h4>{l.name}</h4><p>{l.lenderName} · {formatDate(l.startDate, lang)}</p></div>
            <div className="value-side"><b>{formatMoney(l.outstanding, lang)}</b><small>{t('outstanding')}</small></div>
          </article>
        ))}
        {!data.loans.length && <EmptyState>{t('noLoans')}</EmptyState>}
      </div>
    </>
  );
}
