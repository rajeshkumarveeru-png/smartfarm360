import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { Plus, Trash2 } from 'lucide-react';
import { api } from '../api/client';
import { useTenant } from '../auth/useCurrentTenant';
import { ErrorBanner, Loading } from '../components/common';
import type { Category } from '../finance/types';
import { useCategoryLabel } from '../finance/useFinanceData';
import { currentLanguage, useNs } from '../i18n';
import { describeError } from '../lib/errors';
import { formatMoney, formatNumber } from '../lib/format';
import { can } from '../lib/nav';
import type { Item, ItemKind } from './types';
import { ITEM_KINDS, baseUnitLabel, isQuantity } from './types';
import { useStockData } from './useStockData';

/** Categories for an item's default use (farm inputs, 5000) and sale (income); empty without finance access. */
export function useItemCategories() {
  const tenant = useTenant();
  const [categories, setCategories] = useState<Category[]>([]);
  useEffect(() => {
    if (!can(tenant, 'FINANCE_READ')) return;
    api<Category[]>(`/api/v1/tenants/${tenant.id}/finance/categories`).then(setCategories).catch(() => setCategories([]));
  }, [tenant]);
  return {
    inputCategories: categories.filter(c => c.active && c.accountCode === '5000'),
    saleCategories: categories.filter(c => c.active && c.kind === 'INCOME'),
  };
}

type Pack = { name: string; factor: string };
const emptyForm = { name: '', kind: 'INPUT' as ItemKind, baseUnit: 'kg', reorderLevel: '', trackLots: false, expenseCategoryId: '', saleCategoryId: '', notes: '' };

/** Stock items and a form to add one. */
export default function ItemsPage() {
  const tenant = useTenant();
  const t = useNs('stock');
  const tc = useNs('common');
  const tf = useNs('fin');
  const lang = currentLanguage();
  const label = useCategoryLabel();
  const [showInactive, setShowInactive] = useState(false);
  const data = useStockData(showInactive);
  const { inputCategories, saleCategories } = useItemCategories();
  const [form, setForm] = useState(emptyForm);
  const [packs, setPacks] = useState<Pack[]>([]);
  const [open, setOpen] = useState(false);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const canWrite = can(tenant, 'INVENTORY_WRITE');

  async function create(e: React.FormEvent) {
    e.preventDefault();
    if (!form.name.trim()) { setError(tc('required')); return; }
    if (form.reorderLevel && !isQuantity(form.reorderLevel, true)) { setError(t('errors.quantity')); return; }
    if (packs.some(p => !p.name.trim() || !isQuantity(p.factor))) { setError(t('errors.packUnit')); return; }
    setBusy(true); setError('');
    try {
      await api<Item>(`/api/v1/tenants/${tenant.id}/stock/items`, { method: 'POST', body: {
        name: form.name.trim(), kind: form.kind, baseUnit: form.baseUnit, reorderLevel: form.reorderLevel || null, trackLots: form.trackLots,
        expenseCategoryId: form.expenseCategoryId ? Number(form.expenseCategoryId) : null, saleCategoryId: form.saleCategoryId ? Number(form.saleCategoryId) : null,
        notes: form.notes || null, packUnits: packs.map(p => ({ name: p.name.trim(), factor: p.factor })),
      } });
      setForm(emptyForm); setPacks([]); setOpen(false);
      await data.reload();
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusy(false);
    }
  }

  if (data.loading && !data.items.length) return <Loading />;
  const stockUnits = data.units.filter(u => u.dimension !== 'AREA');
  return (
    <>
      <div className="crop-page-head">
        <div><h2>{t('items')}</h2><p>{t('itemsHelp')}</p></div>
        {canWrite && !open && <button type="button" className="primary" onClick={() => setOpen(true)}><Plus size={16} aria-hidden />{t('addItem')}</button>}
      </div>
      <ErrorBanner message={error || data.error} />
      {open && (
        <form className="form-card form-grid" noValidate onSubmit={e => void create(e)}>
          <label>{t('name')}<input required maxLength={140} value={form.name} onChange={e => setForm({ ...form, name: e.target.value })} /></label>
          <label>{t('kindLabel')}<select value={form.kind} onChange={e => setForm({ ...form, kind: e.target.value as ItemKind })}>
            {ITEM_KINDS.map(k => <option key={k} value={k}>{t(`itemKind.${k}`)}</option>)}</select></label>
          <label>{t('baseUnit')}<select value={form.baseUnit} onChange={e => setForm({ ...form, baseUnit: e.target.value })}>
            {stockUnits.map(u => <option key={u.code} value={u.code}>{t(`unit.${u.code}`)}</option>)}</select></label>
          <label>{t('reorderLevel')} <small>{tc('optional')}</small><input inputMode="decimal" value={form.reorderLevel} onChange={e => setForm({ ...form, reorderLevel: e.target.value })} /></label>
          {inputCategories.length > 0 && form.kind !== 'PRODUCE' && (
            <label>{t('useCategory')} <small>{tc('optional')}</small><select value={form.expenseCategoryId} onChange={e => setForm({ ...form, expenseCategoryId: e.target.value })}>
              <option value="">—</option>{inputCategories.map(c => <option key={c.id} value={c.id}>{label(c)}</option>)}</select></label>
          )}
          {saleCategories.length > 0 && (
            <label>{t('saleCategory')} <small>{tc('optional')}</small><select value={form.saleCategoryId} onChange={e => setForm({ ...form, saleCategoryId: e.target.value })}>
              <option value="">—</option>{saleCategories.map(c => <option key={c.id} value={c.id}>{label(c)}</option>)}</select></label>
          )}
          <label className="check"><input type="checkbox" checked={form.trackLots} onChange={e => setForm({ ...form, trackLots: e.target.checked })} />{t('trackLots')}</label>
          <fieldset className="full lines">
            <legend>{t('packUnits')}</legend>
            <p className="hint">{t('packUnitsHelp', { unit: t(`unit.${form.baseUnit}`) })}</p>
            {packs.map((p, i) => (
              <div className="line-row" key={i}>
                <label>{t('packName')}<input maxLength={30} value={p.name} onChange={e => setPacks(ps => ps.map((x, j) => (j === i ? { ...x, name: e.target.value } : x)))} /></label>
                <label>{t('packFactor', { unit: t(`unit.${form.baseUnit}`) })}<input inputMode="decimal" value={p.factor} onChange={e => setPacks(ps => ps.map((x, j) => (j === i ? { ...x, factor: e.target.value } : x)))} /></label>
                <button type="button" className="text-action" aria-label={tc('remove')} onClick={() => setPacks(ps => ps.filter((_, j) => j !== i))}><Trash2 size={16} /></button>
              </div>
            ))}
            <button type="button" className="secondary" onClick={() => setPacks(ps => [...ps, { name: '', factor: '' }])}><Plus size={16} />{t('addPackUnit')}</button>
          </fieldset>
          <label className="full">{t('notes')} <small>{tc('optional')}</small><input maxLength={300} value={form.notes} onChange={e => setForm({ ...form, notes: e.target.value })} /></label>
          <div className="form-actions full">
            <button type="button" className="secondary" onClick={() => setOpen(false)}>{tc('cancel')}</button>
            <button className="primary" disabled={busy}>{busy ? tc('loading') : tc('save')}</button>
          </div>
        </form>
      )}
      <label className="check"><input type="checkbox" checked={showInactive} onChange={e => setShowInactive(e.target.checked)} />{t('showInactive')}</label>
      {data.items.length === 0 ? <p className="hint">{t('noItems')}</p> : (
        <div className="table-scroll">
          <table className="data-table">
            <thead><tr><th>{t('item')}</th><th>{t('kindLabel')}</th><th className="num">{t('quantity')}</th><th className="num">{t('value')}</th></tr></thead>
            <tbody>
              {data.items.map(i => (
                <tr key={i.id}>
                  <td><Link to={`/t/${tenant.id}/stock/items/${i.id}`}>{i.name}</Link>
                    {!i.active && <small> · {tf('inactive')}</small>}{i.needsReview && <small className="low-stock-label"> · {tf('needsReview')}</small>}
                    {i.lowStock && <small className="low-stock-label"> · {t('lowStock')}</small>}</td>
                  <td>{t(`itemKind.${i.kind}`)}</td>
                  <td className="num">{formatNumber(i.quantity, lang, 4)} {baseUnitLabel(i, t)}</td>
                  <td className="num">{formatMoney(i.value, lang)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}
