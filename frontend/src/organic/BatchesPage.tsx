import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { Plus } from 'lucide-react';
import { api } from '../api/client';
import type { Farm } from '../api/types';
import { useTenant } from '../auth/useCurrentTenant';
import { ErrorBanner, Loading } from '../components/common';
import { Toast } from '../components/Toast';
import type { CostObject } from '../finance/types';
import { newIdempotencyKey } from '../finance/useFinanceData';
import { currentLanguage, useNs } from '../i18n';
import { describeError } from '../lib/errors';
import { formatDate, today } from '../lib/format';
import { can } from '../lib/nav';
import type { Item } from '../stock/types';
import { KINDS, type Batch } from './types';

const emptyBatch = () => ({ name: '', kind: 'VERMICOMPOST', farmId: '', startedOn: today(), readyBy: '' });

/**
 * Collecting dung or litter into stock as farm produce from the herd or batch it came from (L3), at nil value. A shortcut for
 * the stock record *Farm produce into stock*; needs permission to record stock and to see the activities.
 */
export function CollectManure({ onSaved }: { onSaved?: () => void }) {
  const tenant = useTenant();
  const t = useNs('organic');
  const lang = currentLanguage();
  const base = `/api/v1/tenants/${tenant.id}`;
  const [items, setItems] = useState<Item[]>([]);
  const [activities, setActivities] = useState<CostObject[]>([]);
  const [form, setForm] = useState({ date: today(), itemId: '', quantity: '', from: '' });
  const [key, setKey] = useState(newIdempotencyKey);
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');
  useEffect(() => {
    api<Item[]>(`${base}/stock/items`).then(i => setItems(i.filter(x => x.active && x.kind === 'PRODUCE'))).catch(() => undefined);
    api<CostObject[]>(`${base}/finance/cost-objects`).then(c => setActivities(c.filter(x => x.active && (x.kind === 'ANIMAL_GROUP' || x.kind === 'ORGANIC_BATCH'))))
      .catch(() => undefined);
  }, [base]);
  if (!can(tenant, 'INVENTORY_WRITE') || !can(tenant, 'FINANCE_READ')) return null;
  async function save(e: React.FormEvent) {
    e.preventDefault();
    if (!form.itemId || !form.from || !/^\d{1,10}(\.\d{1,4})?$/.test(form.quantity.trim()) || Number(form.quantity) <= 0) { setError(t('errors.manure')); return; }
    setError(''); setMessage('');
    try {
      await api(`${base}/stock/produce`, { method: 'POST', headers: { 'Idempotency-Key': key }, body: { docDate: form.date, costObjectId: Number(form.from),
        lines: [{ itemId: Number(form.itemId), quantity: form.quantity.trim() }], notes: t('manureNote') } });
      setKey(newIdempotencyKey());
      setForm(f => ({ ...f, quantity: '' }));
      setMessage(t('manureSaved', { date: formatDate(form.date, lang) }));
      onSaved?.();
    } catch (err) {
      setError(describeError(err));
    }
  }
  return (
    <form className="form-card form-grid" noValidate onSubmit={e => void save(e)}>
      <h4 className="full">{t('collectManure')}</h4>
      <ErrorBanner message={error} />
      <Toast message={message} onClose={() => setMessage('')} />
      <label>{t('date')}<input type="date" max={today()} value={form.date} onChange={e => setForm({ ...form, date: e.target.value })} /></label>
      <label>{t('collectedFrom')}<select value={form.from} onChange={e => setForm({ ...form, from: e.target.value })}>
        <option value="">—</option>{activities.map(a => <option key={a.id} value={a.id}>{a.name}</option>)}</select></label>
      <label>{t('item')}<select value={form.itemId} onChange={e => setForm({ ...form, itemId: e.target.value })}>
        <option value="">—</option>{items.map(i => <option key={i.id} value={i.id}>{i.name}</option>)}</select></label>
      <label>{t('quantity')}<input inputMode="decimal" value={form.quantity} onChange={e => setForm({ ...form, quantity: e.target.value })} /></label>
      <p className="hint full">{t('manureHelp')}</p>
      <div className="form-actions full"><button className="secondary">{t('recordManure')}</button></div>
    </form>
  );
}

/** Compost pits, vermicompost beds and liquid manures (organic resources design, L1). */
export default function BatchesPage() {
  const tenant = useTenant();
  const t = useNs('organic');
  const tc = useNs('common');
  const lang = currentLanguage();
  const base = `/api/v1/tenants/${tenant.id}`;
  const canWrite = can(tenant, 'ORGANIC_WRITE');
  const [batches, setBatches] = useState<Batch[] | null>(null);
  const [farms, setFarms] = useState<Farm[]>([]);
  const [open, setOpen] = useState(false);
  const [form, setForm] = useState(emptyBatch);
  const [error, setError] = useState('');

  const load = useCallback(() => api<Batch[]>(`${base}/organic/batches`).then(setBatches).catch(e => setError(describeError(e))), [base]);
  useEffect(() => { void load(); }, [load]);
  useEffect(() => {
    if (canWrite) api<Farm[]>(`${base}/farms`).then(f => { setFarms(f.filter(x => x.active)); if (f.length === 1) setForm(x => ({ ...x, farmId: String(f[0].id) })); })
      .catch(() => undefined);
  }, [base, canWrite]);

  async function create(e: React.FormEvent) {
    e.preventDefault();
    if (!form.name.trim() || !form.farmId) { setError(t('errors.batch')); return; }
    setError('');
    try {
      await api(`${base}/organic/batches`, { method: 'POST', body: { name: form.name.trim(), kind: form.kind, farmId: Number(form.farmId), startedOn: form.startedOn,
        readyBy: form.readyBy || null } });
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
          <label>{t('batchName')}<input maxLength={140} placeholder={t('batchExample')} value={form.name} onChange={e => setForm({ ...form, name: e.target.value })} /></label>
          <label>{t('kindLabel')}<select value={form.kind} onChange={e => setForm({ ...form, kind: e.target.value })}>
            {KINDS.map(k => <option key={k} value={k}>{t(`kind.${k}`)}</option>)}</select></label>
          <label>{t('farm')}<select value={form.farmId} onChange={e => setForm({ ...form, farmId: e.target.value })}>
            <option value="">—</option>{farms.map(f => <option key={f.id} value={f.id}>{f.name}</option>)}</select></label>
          <label>{t('startedOn')}<input type="date" max={today()} value={form.startedOn} onChange={e => setForm({ ...form, startedOn: e.target.value })} /></label>
          <label>{t('readyBy')} <small>{tc('optional')}</small><input type="date" value={form.readyBy} onChange={e => setForm({ ...form, readyBy: e.target.value })} /></label>
          <div className="form-actions full">
            <button type="button" className="secondary" onClick={() => setOpen(false)}>{tc('cancel')}</button>
            <button className="primary">{tc('save')}</button>
          </div>
        </form>
      )}
      {!batches ? <Loading /> : batches.length === 0 ? <p className="hint">{t('noBatches')}</p> : (
        <table className="data-table">
          <thead><tr><th>{t('batch')}</th><th>{t('kindLabel')}</th><th>{t('startedOn')}</th><th>{t('statusLabel')}</th></tr></thead>
          <tbody>{batches.map(b => {
            const late = !b.finishedOn && b.readyBy && b.readyBy < today();
            return (
              <tr key={b.id}>
                <td><Link to={`/t/${tenant.id}/organic/batches/${b.id}`}>{b.name}</Link><small> · {b.farmName}</small></td>
                <td>{t(`kind.${b.kind}`)}</td><td>{formatDate(b.startedOn, lang)}</td>
                <td className={late ? 'low-stock-label' : undefined}>{t(`status.${b.status}`)}
                  {b.finishedOn ? ` · ${formatDate(b.finishedOn, lang)}` : b.readyBy ? ` · ${t('readyByDate', { date: formatDate(b.readyBy, lang) })}` : ''}</td>
              </tr>
            );
          })}</tbody>
        </table>
      )}
      <h3 className="section-space">{t('manure')}</h3>
      <CollectManure />
    </>
  );
}
