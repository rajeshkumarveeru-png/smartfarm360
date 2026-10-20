import React, { useState } from 'react';
import { Link } from 'react-router-dom';
import { Plus } from 'lucide-react';
import { api } from '../api/client';
import { useTenant } from '../auth/useCurrentTenant';
import { EmptyState, ErrorBanner, Loading } from '../components/common';
import { currentLanguage, useNs } from '../i18n';
import { describeError } from '../lib/errors';
import { formatMoney } from '../lib/format';
import { can } from '../lib/nav';
import { useFinanceData } from './useFinanceData';

/** Customers, suppliers and lenders (one list, D11) with what each owes or is owed. */
export default function PartiesPage() {
  const tenant = useTenant();
  const t = useNs('fin');
  const tc = useNs('common');
  const lang = currentLanguage();
  const data = useFinanceData();
  const [show, setShow] = useState(false);
  const [form, setForm] = useState({ name: '', phone: '', village: '', customer: true, supplier: false, lender: false });
  const [error, setError] = useState('');

  async function save(e: React.FormEvent) {
    e.preventDefault();
    if (!form.name.trim()) { setError(tc('required')); return; }
    setError('');
    try {
      await api(`/api/v1/tenants/${tenant.id}/finance/parties`, { method: 'POST', body: { ...form, name: form.name.trim() } });
      setForm({ name: '', phone: '', village: '', customer: true, supplier: false, lender: false });
      setShow(false);
      await data.reload();
    } catch (err) {
      setError(describeError(err));
    }
  }

  if (data.loading) return <Loading />;
  return (
    <>
      <div className="crop-page-head">
        <div><h2>{t('parties')}</h2><p>{t('partiesHelp')}</p></div>
        {can(tenant, 'FINANCE_WRITE') && <button className="primary" onClick={() => setShow(x => !x)}><Plus size={16} />{t('addParty')}</button>}
      </div>
      <ErrorBanner message={error || data.error} />
      {show && (
        <form className="form-card form-grid" onSubmit={save} noValidate>
          <label>{t('name')}<input maxLength={140} value={form.name} onChange={e => setForm({ ...form, name: e.target.value })} /></label>
          <label>{t('phone')}<input maxLength={30} inputMode="tel" value={form.phone} onChange={e => setForm({ ...form, phone: e.target.value })} /></label>
          <label>{t('village')}<input maxLength={120} value={form.village} onChange={e => setForm({ ...form, village: e.target.value })} /></label>
          <fieldset className="checks">
            {(['customer', 'supplier', 'lender'] as const).map(k => (
              <label key={k} className="check"><input type="checkbox" checked={form[k]} onChange={e => setForm({ ...form, [k]: e.target.checked })} />{t(k)}</label>
            ))}
          </fieldset>
          <div className="form-actions full"><button type="button" className="secondary" onClick={() => setShow(false)}>{tc('cancel')}</button><button className="primary">{tc('save')}</button></div>
        </form>
      )}
      <div className="activity-list">
        {data.parties.map(p => (
          <Link key={p.id} className="activity-row record-link" to={`/t/${tenant.id}/finance/records?partyId=${p.id}`}>
            <div className="cycle-main">
              <h4>{p.name}{!p.active ? ` · ${t('inactive')}` : ''}</h4>
              <p>{[p.customer && t('customer'), p.supplier && t('supplier'), p.lender && t('lender')].filter(Boolean).join(' · ')}</p>
              <small>{[p.phone, p.village].filter(Boolean).join(' · ')}</small>
            </div>
            <div className="value-side">
              {Number(p.receivable) !== 0 && <small>{Number(p.receivable) > 0 ? t('theyOwe', { amount: formatMoney(p.receivable, lang) }) : t('advanceReceived', { amount: formatMoney(-p.receivable, lang) })}</small>}
              {Number(p.payable) !== 0 && <small>{Number(p.payable) > 0 ? t('weOwe', { amount: formatMoney(p.payable, lang) }) : t('advancePaid', { amount: formatMoney(-p.payable, lang) })}</small>}
            </div>
          </Link>
        ))}
        {!data.parties.length && <EmptyState>{t('noParties')}</EmptyState>}
      </div>
    </>
  );
}
