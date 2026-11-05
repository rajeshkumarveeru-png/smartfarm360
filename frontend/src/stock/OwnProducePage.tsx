import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { api } from '../api/client';
import { useTenant } from '../auth/useCurrentTenant';
import { ErrorBanner, Loading } from '../components/common';
import { Toast } from '../components/Toast';
import type { CostObject } from '../finance/types';
import { newIdempotencyKey } from '../finance/useFinanceData';
import { currentLanguage, useNs } from '../i18n';
import { describeError } from '../lib/errors';
import { formatDate, formatMoney, formatNumber, today } from '../lib/format';
import { can } from '../lib/nav';
import { ExportBar } from '../reports/ReportTools';
import type { Item, Lot } from './types';

export type Transfer = { documentId: number; number: string; date: string; itemId: number; itemName: string; baseQuantity: number; baseUnit: string;
  fromCostObjectId: number | null; fromName: string | null; fromKind: string | null; toCostObjectId: number; toName: string; toKind: string;
  method: 'NIL' | 'AVERAGE_COST'; unitCost: number | null; costMoved: number; reversed: boolean };
type Preview = { defaultMethod: 'NIL' | 'AVERAGE_COST'; method: 'NIL' | 'AVERAGE_COST'; baseQuantity: number; baseUnit: string; sourceCosts: number;
  sourceProduced: number; alreadyMovedQuantity: number; alreadyMovedCost: number; unitCost: number | null; costMoved: number };

const quantityOk = (v: string) => /^\d{1,10}(\.\d{1,4})?$/.test(v.trim()) && Number(v) > 0;
const daysAgo = (n: number) => { const d = new Date(); d.setDate(d.getDate() - n); return d.toISOString().slice(0, 10); };

/** Transfers in a period as a table (shared with the organic pages). */
export function TransfersTable({ rows }: { rows: Transfer[] }) {
  const tenant = useTenant();
  const t = useNs('stock');
  const lang = currentLanguage();
  if (rows.length === 0) return <p className="hint">{t('transfer.none')}</p>;
  return (
    <div className="table-scroll">
      <table className="data-table">
        <thead><tr><th>{t('transfer.date')}</th><th>{t('item')}</th><th className="num">{t('quantity')}</th><th>{t('transfer.from')}</th><th>{t('transfer.to')}</th>
          <th className="num">{t('transfer.costMoved')}</th></tr></thead>
        <tbody>{rows.map(r => (
          <tr key={r.documentId} className={r.reversed ? 'muted' : undefined}>
            <td>{formatDate(r.date, lang)}<small> · <Link to={`/t/${tenant.id}/finance/records/${r.documentId}`}>{r.number}</Link></small>
              {r.reversed && <small> · {t('transfer.reversed')}</small>}</td>
            <td>{r.itemName}</td><td className="num">{formatNumber(r.baseQuantity, lang, 3)} {r.baseUnit}</td>
            <td>{r.fromName ?? '—'}</td><td>{r.toName}</td>
            <td className="num">{formatMoney(r.costMoved, lang)}{r.unitCost != null && <small> · {formatMoney(r.unitCost, lang)}/{r.baseUnit}</small>}</td>
          </tr>
        ))}</tbody>
      </table>
    </div>
  );
}

/**
 * Using the farm's own produce on another activity (organic resources design, L4, L5, L9): compost on a field, fodder to
 * the herd, dung into a compost pit. Never income; cost moves only by rule, shown before saving.
 */
export default function OwnProducePage() {
  const tenant = useTenant();
  const t = useNs('stock');
  const tc = useNs('common');
  const lang = currentLanguage();
  const [params] = useSearchParams();
  const base = `/api/v1/tenants/${tenant.id}`;
  const canWrite = can(tenant, 'INVENTORY_WRITE');
  const canManage = can(tenant, 'FINANCE_MANAGE');
  const [items, setItems] = useState<Item[]>([]);
  const [activities, setActivities] = useState<CostObject[]>([]);
  const [form, setForm] = useState({ date: today(), itemId: params.get('itemId') ?? '', quantity: '', from: params.get('from') ?? '', to: params.get('to') ?? '',
    method: '', notes: '', lotNo: '' });
  const [lots, setLots] = useState<Lot[]>([]);
  const [preview, setPreview] = useState<Preview | null>(null);
  const [rows, setRows] = useState<Transfer[] | null>(null);
  const [range] = useState({ from: daysAgo(90), to: today() });
  const [key, setKey] = useState(newIdempotencyKey);
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');

  const load = useCallback(() => api<Transfer[]>(`${base}/stock/internal-transfers?from=${range.from}&to=${range.to}`).then(setRows)
    .catch(e => setError(describeError(e))), [base, range.from, range.to]);
  useEffect(() => { void load(); }, [load]);
  useEffect(() => {
    api<Item[]>(`${base}/stock/items`).then(i => setItems(i.filter(x => x.active && x.kind === 'PRODUCE'))).catch(e => setError(describeError(e)));
    if (can(tenant, 'FINANCE_READ')) api<CostObject[]>(`${base}/finance/cost-objects`).then(c => setActivities(c.filter(x => x.active && x.kind !== 'OVERHEAD')))
      .catch(() => undefined);
  }, [base, tenant]);

  const body = useMemo(() => ({ date: form.date, itemId: Number(form.itemId), quantity: form.quantity.trim(), toCostObjectId: Number(form.to),
    fromCostObjectId: form.from ? Number(form.from) : null, costMethod: form.method || null, notes: form.notes.trim() || null, lotNo: form.lotNo || null }), [form]);
  const ready = form.itemId !== '' && form.to !== '' && quantityOk(form.quantity) && form.from !== form.to;
  useEffect(() => {
    if (!ready || !canWrite) { setPreview(null); return; }
    let alive = true;
    const timer = setTimeout(() => {
      api<Preview>(`${base}/stock/internal-transfers/preview`, { method: 'POST', body }).then(p => alive && setPreview(p))
        .catch(e => { if (alive) { setPreview(null); setError(describeError(e)); } });
    }, 300);
    return () => { alive = false; clearTimeout(timer); };
  }, [base, body, ready, canWrite]);

  // Harvests that track lots are used lot by lot
  useEffect(() => {
    if (!form.itemId || !items.find(i => String(i.id) === form.itemId)?.trackLots) { setLots([]); return; }
    api<Lot[]>(`${base}/stock/items/${form.itemId}/lots`).then(l => {
      const open = l.filter(x => x.quantity > 0);
      setLots(open);
      if (open.length === 1) setForm(f => ({ ...f, lotNo: open[0].lotNo }));
    }).catch(() => setLots([]));
  }, [base, form.itemId, items]);

  async function save(e: React.FormEvent) {
    e.preventDefault();
    if (!ready) { setError(t('transfer.errors.form')); return; }
    setError(''); setMessage('');
    try {
      const r = await api<Transfer>(`${base}/stock/internal-transfers`, { method: 'POST', body, headers: { 'Idempotency-Key': key } });
      setKey(newIdempotencyKey());
      setForm(f => ({ ...f, quantity: '', notes: '' }));
      await load();
      setMessage(t('transfer.saved', { number: r.number, amount: formatMoney(r.costMoved, lang) }));
    } catch (err) {
      setError(describeError(err));
    }
  }

  const item = items.find(i => String(i.id) === form.itemId);
  const tracksLots = item?.trackLots ?? false;
  return (
    <>
      <div className="crop-page-head"><div><h2>{t('transfer.title')}</h2><p>{t('transfer.help')}</p></div></div>
      <ErrorBanner message={error} />
      <Toast message={message} onClose={() => setMessage('')} />
      {canWrite && (
        <form className="form-card form-grid" noValidate onSubmit={e => void save(e)}>
          <label>{t('transfer.date')}<input type="date" max={today()} value={form.date} onChange={e => setForm({ ...form, date: e.target.value })} /></label>
          <label>{t('item')}<select value={form.itemId} onChange={e => setForm({ ...form, itemId: e.target.value })}>
            <option value="">—</option>{items.map(i => <option key={i.id} value={i.id}>{i.name}</option>)}</select></label>
          <label>{t('quantity')} {item && <small>{item.baseUnit}</small>}<input inputMode="decimal" value={form.quantity}
                                                                                onChange={e => setForm({ ...form, quantity: e.target.value })} /></label>
          {tracksLots && <label>{t('transfer.lot')}<select value={form.lotNo} onChange={e => setForm({ ...form, lotNo: e.target.value })}>
            <option value="">—</option>{lots.map(l => <option key={l.id} value={l.lotNo}>{l.lotNo} · {formatNumber(l.quantity, lang, 3)}</option>)}</select></label>}
          <label>{t('transfer.from')} <small>{tc('optional')}</small><select value={form.from} onChange={e => setForm({ ...form, from: e.target.value, method: '' })}>
            <option value="">{t('transfer.fromStock')}</option>{activities.map(a => <option key={a.id} value={a.id}>{a.name}</option>)}</select></label>
          <label>{t('transfer.to')}<select value={form.to} onChange={e => setForm({ ...form, to: e.target.value })}>
            <option value="">—</option>{activities.filter(a => String(a.id) !== form.from).map(a => <option key={a.id} value={a.id}>{a.name}</option>)}</select></label>
          {canManage && form.from && (
            <label>{t('transfer.method')}<select value={form.method} onChange={e => setForm({ ...form, method: e.target.value })}>
              <option value="">{t('transfer.byDefault')}</option>
              <option value="NIL">{t('transfer.methodName.NIL')}</option><option value="AVERAGE_COST">{t('transfer.methodName.AVERAGE_COST')}</option></select></label>
          )}
          <label className="full">{t('transfer.notes')} <small>{tc('optional')}</small><input maxLength={500} value={form.notes}
                                                                                          onChange={e => setForm({ ...form, notes: e.target.value })} /></label>
          {preview && (
            <p className="hint full" role="note">
              {preview.method === 'AVERAGE_COST' && preview.unitCost != null
                ? t('transfer.previewCost', { unitCost: formatMoney(preview.unitCost, lang), unit: preview.baseUnit, amount: formatMoney(preview.costMoved, lang),
                  costs: formatMoney(preview.sourceCosts, lang), produced: formatNumber(preview.sourceProduced, lang, 3) })
                : t('transfer.previewNil')}
            </p>
          )}
          <p className="hint full">{t('transfer.rules')}</p>
          <div className="form-actions full"><button className="primary">{t('transfer.save')}</button></div>
        </form>
      )}
      <div className="section-head section-space">
        <h3>{t('transfer.report')}</h3>
        <ExportBar path={`${base}/stock/internal-transfers?from=${range.from}&to=${range.to}`} name={`internal-transfers-${range.from}-${range.to}`} onError={setError} />
      </div>
      {!rows ? <Loading /> : <TransfersTable rows={rows} />}
    </>
  );
}
