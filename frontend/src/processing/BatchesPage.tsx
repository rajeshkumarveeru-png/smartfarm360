import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { Plus } from 'lucide-react';
import { api } from '../api/client';
import type { Farm } from '../api/types';
import { useTenant } from '../auth/useCurrentTenant';
import { ErrorBanner, Loading } from '../components/common';
import { currentLanguage, useNs } from '../i18n';
import { describeError } from '../lib/errors';
import { formatDate, today } from '../lib/format';
import { can } from '../lib/nav';
import type { Item } from '../stock/types';
import { KINDS, type Batch } from './types';

const emptyBatch = () => ({ code: '', name: '', kind: 'MILLING', farmId: '', mainItemId: '', startedOn: today(), expectedEnd: '' });

/** Processing batches: one run of one process on one lot of produce (processing and trading design, M1). */
export default function BatchesPage() {
  const tenant = useTenant();
  const t = useNs('processing');
  const tc = useNs('common');
  const lang = currentLanguage();
  const base = `/api/v1/tenants/${tenant.id}`;
  const canWrite = can(tenant, 'PROCESSING_WRITE');
  const [batches, setBatches] = useState<Batch[] | null>(null);
  const [farms, setFarms] = useState<Farm[]>([]);
  const [items, setItems] = useState<Item[]>([]);
  const [open, setOpen] = useState(false);
  const [form, setForm] = useState(emptyBatch);
  const [error, setError] = useState('');

  const load = useCallback(() => api<Batch[]>(`${base}/processing/batches`).then(setBatches).catch(e => setError(describeError(e))), [base]);
  useEffect(() => { void load(); }, [load]);
  useEffect(() => {
    if (!canWrite) return;
    api<Farm[]>(`${base}/farms`).then(f => { setFarms(f.filter(x => x.active)); if (f.length === 1) setForm(x => ({ ...x, farmId: String(f[0].id) })); }).catch(() => undefined);
    api<Item[]>(`${base}/stock/items`).then(i => setItems(i.filter(x => x.active && x.kind === 'PRODUCE'))).catch(() => undefined);
  }, [base, canWrite]);

  async function create(e: React.FormEvent) {
    e.preventDefault();
    if (!/^[A-Za-z0-9][A-Za-z0-9-]{0,29}$/.test(form.code.trim()) || !form.name.trim() || !form.farmId || !form.mainItemId) { setError(t('errors.batch')); return; }
    setError('');
    try {
      await api(`${base}/processing/batches`, { method: 'POST', body: { code: form.code.trim(), name: form.name.trim(), kind: form.kind, farmId: Number(form.farmId),
        mainItemId: Number(form.mainItemId), startedOn: form.startedOn, expectedEnd: form.expectedEnd || null } });
      setOpen(false); setForm(emptyBatch());
      await load();
    } catch (err) {
      setError(describeError(err));
    }
  }

  return (
    <>
      <div className="crop-page-head">
        <div><h2>{t('batches')}</h2><p>{t('batchesHelp')}</p></div>
        {canWrite && !open && <button type="button" className="primary" onClick={() => setOpen(true)}><Plus size={16} aria-hidden />{t('startBatch')}</button>}
      </div>
      <ErrorBanner message={error} />
      {open && (
        <form className="form-card form-grid" noValidate onSubmit={e => void create(e)}>
          <label>{t('code')}<input maxLength={30} placeholder="RM-2610-01" value={form.code} onChange={e => setForm({ ...form, code: e.target.value.toUpperCase() })} /></label>
          <label>{t('batchName')}<input maxLength={140} placeholder={t('batchExample')} value={form.name} onChange={e => setForm({ ...form, name: e.target.value })} /></label>
          <label>{t('kindLabel')}<select value={form.kind} onChange={e => setForm({ ...form, kind: e.target.value })}>
            {KINDS.map(k => <option key={k} value={k}>{t(`kind.${k}`)}</option>)}</select></label>
          <label>{t('mainProduct')}<select value={form.mainItemId} onChange={e => setForm({ ...form, mainItemId: e.target.value })}>
            <option value="">—</option>{items.map(i => <option key={i.id} value={i.id}>{i.name}</option>)}</select></label>
          <label>{t('farm')}<select value={form.farmId} onChange={e => setForm({ ...form, farmId: e.target.value })}>
            <option value="">—</option>{farms.map(f => <option key={f.id} value={f.id}>{f.name}</option>)}</select></label>
          <label>{t('startedOn')}<input type="date" max={today()} value={form.startedOn} onChange={e => setForm({ ...form, startedOn: e.target.value })} /></label>
          <label>{t('expectedEnd')} <small>{tc('optional')}</small><input type="date" value={form.expectedEnd} onChange={e => setForm({ ...form, expectedEnd: e.target.value })} /></label>
          <p className="hint full">{t('batchHelp')}</p>
          <div className="form-actions full">
            <button type="button" className="secondary" onClick={() => setOpen(false)}>{tc('cancel')}</button>
            <button className="primary">{tc('save')}</button>
          </div>
        </form>
      )}
      {!batches ? <Loading /> : batches.length === 0 ? <p className="hint">{t('noBatches')}</p> : (
        <div className="table-scroll"><table className="data-table">
          <thead><tr><th>{t('code')}</th><th>{t('batchName')}</th><th>{t('mainProduct')}</th><th>{t('startedOn')}</th><th>{t('statusLabel')}</th></tr></thead>
          <tbody>{batches.map(b => {
            const late = !b.finishedOn && b.expectedEnd && b.expectedEnd < today();
            return (
              <tr key={b.id}>
                <td><Link to={`/t/${tenant.id}/processing/batches/${b.id}`}>{b.code}</Link></td>
                <td>{b.name}<small> · {t(`kind.${b.kind}`)}</small></td><td>{b.mainItemName}</td><td>{formatDate(b.startedOn, lang)}</td>
                <td className={late ? 'low-stock-label' : undefined}>{t(`status.${b.status}`)}{b.finishedOn ? ` · ${formatDate(b.finishedOn, lang)}` : ''}</td>
              </tr>
            );
          })}</tbody>
        </table></div>
      )}
    </>
  );
}
