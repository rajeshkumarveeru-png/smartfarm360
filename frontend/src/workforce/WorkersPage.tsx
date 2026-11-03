import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { Plus } from 'lucide-react';
import { api } from '../api/client';
import { useTenant } from '../auth/useCurrentTenant';
import { ErrorBanner, Loading } from '../components/common';
import { currentLanguage, useNs } from '../i18n';
import { describeError } from '../lib/errors';
import { formatMoney, today } from '../lib/format';
import { can } from '../lib/nav';
import type { PayType, Worker } from './types';
import { PAY_TYPES } from './types';

/** Workers with what each is owed or owes back, and a form to add one. */
export default function WorkersPage() {
  const tenant = useTenant();
  const t = useNs('workforce');
  const tc = useNs('common');
  const lang = currentLanguage();
  const base = `/api/v1/tenants/${tenant.id}/workforce`;
  const [showLeft, setShowLeft] = useState(false);
  const [workers, setWorkers] = useState<Worker[] | null>(null);
  const [open, setOpen] = useState(false);
  const [form, setForm] = useState({ name: '', phone: '', village: '', payType: 'DAILY' as PayType, rate: '', pieceUnit: '', joinedOn: today(), usualWork: '' });
  const [error, setError] = useState('');

  const load = useCallback(() => api<Worker[]>(`${base}/workers?includeLeft=${showLeft}`).then(setWorkers).catch(e => setError(describeError(e))), [base, showLeft]);
  useEffect(() => { void load(); }, [load]);

  async function create(e: React.FormEvent) {
    e.preventDefault();
    if (!form.name.trim() || !/^\d{1,10}(\.\d{1,2})?$/.test(form.rate)) { setError(t('errors.nameAndRate')); return; }
    setError('');
    try {
      await api(`${base}/workers`, { method: 'POST', body: { ...form, name: form.name.trim(), phone: form.phone || null, village: form.village || null,
        pieceUnit: form.payType === 'PIECE' ? form.pieceUnit || null : null, usualWork: form.usualWork || null } });
      setOpen(false);
      setForm({ name: '', phone: '', village: '', payType: 'DAILY', rate: '', pieceUnit: '', joinedOn: today(), usualWork: '' });
      await load();
    } catch (err) {
      setError(describeError(err));
    }
  }

  return (
    <>
      <div className="crop-page-head">
        <div><h2>{t('workers')}</h2><p>{t('workersHelp')}</p></div>
        {can(tenant, 'WORKFORCE_WRITE') && !open && <button type="button" className="primary" onClick={() => setOpen(true)}><Plus size={16} aria-hidden />{t('addWorker')}</button>}
      </div>
      <ErrorBanner message={error} />
      {open && (
        <form className="form-card form-grid" noValidate onSubmit={e => void create(e)}>
          <label>{t('name')}<input required maxLength={140} value={form.name} onChange={e => setForm({ ...form, name: e.target.value })} /></label>
          <label>{t('phone')} <small>{tc('optional')}</small><input maxLength={30} value={form.phone} onChange={e => setForm({ ...form, phone: e.target.value })} /></label>
          <label>{t('village')} <small>{tc('optional')}</small><input maxLength={120} value={form.village} onChange={e => setForm({ ...form, village: e.target.value })} /></label>
          <label>{t('payTypeLabel')}<select value={form.payType} onChange={e => setForm({ ...form, payType: e.target.value as PayType })}>
            {PAY_TYPES.map(p => <option key={p} value={p}>{t(`payType.${p}`)}</option>)}</select></label>
          <label>{t(`rateLabel.${form.payType}`)}<input inputMode="decimal" value={form.rate} onChange={e => setForm({ ...form, rate: e.target.value })} /></label>
          {form.payType === 'PIECE' && <label>{t('pieceUnit')}<input maxLength={30} placeholder="kg" value={form.pieceUnit} onChange={e => setForm({ ...form, pieceUnit: e.target.value })} /></label>}
          <label>{t('joinedOn')}<input type="date" value={form.joinedOn} onChange={e => setForm({ ...form, joinedOn: e.target.value })} /></label>
          <label>{t('usualWork')} <small>{tc('optional')}</small><input maxLength={120} value={form.usualWork} onChange={e => setForm({ ...form, usualWork: e.target.value })} /></label>
          <div className="form-actions full">
            <button type="button" className="secondary" onClick={() => setOpen(false)}>{tc('cancel')}</button>
            <button className="primary">{tc('save')}</button>
          </div>
        </form>
      )}
      <label className="check"><input type="checkbox" checked={showLeft} onChange={e => setShowLeft(e.target.checked)} />{t('showLeft')}</label>
      {!workers ? <Loading /> : workers.length === 0 ? <p className="hint">{t('noWorkers')}</p> : (
        <div className="table-scroll">
          <table className="data-table">
            <thead><tr><th>{t('name')}</th><th>{t('payTypeLabel')}</th><th className="num">{t('rate')}</th><th className="num">{t('owed')}</th><th className="num">{t('advance')}</th></tr></thead>
            <tbody>
              {workers.map(w => (
                <tr key={w.id}>
                  <td><Link to={`/t/${tenant.id}/workforce/workers/${w.id}`}>{w.name}</Link>{!w.active && <small> · {t('left')}</small>}</td>
                  <td>{t(`payType.${w.payType}`)}</td>
                  <td className="num">{w.rate != null ? formatMoney(w.rate, lang) : '—'}{w.pieceUnit ? ` / ${w.pieceUnit}` : ''}</td>
                  <td className="num">{formatMoney(w.owed, lang)}</td>
                  <td className="num">{formatMoney(w.advance, lang)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}
