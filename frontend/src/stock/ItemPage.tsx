import { useCallback, useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { Download, Trash2 } from 'lucide-react';
import { api, download } from '../api/client';
import type { Page } from '../api/types';
import { useTenant } from '../auth/useCurrentTenant';
import { ErrorBanner, Loading } from '../components/common';
import { useCategoryLabel } from '../finance/useFinanceData';
import { currentLanguage, useNs } from '../i18n';
import { describeError } from '../lib/errors';
import { formatDate, formatMoney, formatNumber } from '../lib/format';
import { can } from '../lib/nav';
import { useItemCategories } from './ItemsPage';
import type { Item, Lot, MovementRow } from './types';
import { baseUnitLabel, isQuantity } from './types';

/** One stock item: its figures, settings, pack units, lots and movement history. */
export default function ItemPage() {
  const tenant = useTenant();
  const { itemId } = useParams();
  const t = useNs('stock');
  const tf = useNs('fin');
  const tc = useNs('common');
  const lang = currentLanguage();
  const label = useCategoryLabel();
  const { inputCategories, saleCategories } = useItemCategories();
  const base = `/api/v1/tenants/${tenant.id}/stock/items/${itemId}`;
  const canWrite = can(tenant, 'INVENTORY_WRITE');
  const [item, setItem] = useState<Item | null>(null);
  const [lots, setLots] = useState<Lot[]>([]);
  const [movements, setMovements] = useState<Page<MovementRow> | null>(null);
  const [page, setPage] = useState(0);
  const [edit, setEdit] = useState<Record<string, string> | null>(null);
  const [pack, setPack] = useState({ name: '', factor: '' });
  const [error, setError] = useState('');

  const load = useCallback(async () => {
    setError('');
    try {
      const [i, l] = await Promise.all([api<Item>(base), api<Lot[]>(`${base}/lots`)]);
      setItem(i); setLots(l);
    } catch (e) {
      setError(describeError(e));
    }
  }, [base]);
  useEffect(() => { void load(); }, [load]);
  useEffect(() => {
    api<Page<MovementRow>>(`${base}/movements?page=${page}&size=25`).then(setMovements).catch(e => setError(describeError(e)));
  }, [base, page, item]);

  async function patch(body: Record<string, unknown>) {
    setError('');
    try { setItem(await api<Item>(base, { method: 'PATCH', body })); setEdit(null); } catch (e) { setError(describeError(e)); }
  }

  async function addPack(e: React.FormEvent) {
    e.preventDefault();
    if (!pack.name.trim() || !isQuantity(pack.factor)) { setError(t('errors.packUnit')); return; }
    try { setItem(await api<Item>(`${base}/units`, { method: 'POST', body: { name: pack.name.trim(), factor: pack.factor } })); setPack({ name: '', factor: '' }); }
    catch (err) { setError(describeError(err)); }
  }

  async function removePack(id: number) {
    try { setItem(await api<Item>(`${base}/units/${id}`, { method: 'DELETE' })); } catch (e) { setError(describeError(e)); }
  }

  if (!item) return error ? <ErrorBanner message={error} /> : <Loading />;
  const unit = baseUnitLabel(item, t);
  return (
    <>
      <div className="crop-page-head">
        <div>
          <h2>{item.name}</h2>
          <p>{t(`itemKind.${item.kind}`)} · {t('countedIn', { unit })}{item.trackLots ? ` · ${t('tracksLots')}` : ''}{!item.active ? ` · ${tf('inactive')}` : ''}</p>
          {item.needsReview && <p className="low-stock-label">{t('needsReviewHelp')}</p>}
        </div>
        <Link className="secondary" to={`/t/${tenant.id}/stock/items`}>{tc('back')}</Link>
      </div>
      <ErrorBanner message={error} />
      <div className="finance-stats">
        <div className="finance-stat"><span>{t('quantity')}</span><b>{formatNumber(item.quantity, lang, 4)} {unit}</b>
          {item.packUnits.map(p => <small key={p.id}>{formatNumber(item.quantity / p.factor, lang, 2)} {p.name}</small>)}</div>
        <div className="finance-stat"><span>{t('value')}</span><b>{formatMoney(item.value, lang)}</b></div>
        <div className="finance-stat"><span>{t('unitCost')}</span><b>{formatMoney(item.unitCost, lang)}</b><small>{t('perUnit', { unit })} · {formatNumber(item.unitCost, lang, 4)}</small></div>
        <div className="finance-stat"><span>{t('reorderLevel')}</span><b>{formatNumber(item.reorderLevel, lang, 4)} {unit}</b>{item.lowStock && <small className="low-stock-label">{t('lowStock')}</small>}</div>
      </div>

      {canWrite && !edit && (
        <div className="form-actions">
          <button type="button" className="secondary" onClick={() => setEdit({ name: item.name, reorderLevel: String(item.reorderLevel), expenseCategoryId: String(item.expenseCategoryId ?? ''),
            saleCategoryId: String(item.saleCategoryId ?? ''), notes: item.notes ?? '' })}>{tc('edit')}</button>
          {item.needsReview && <button type="button" className="secondary" onClick={() => void patch({ reviewed: true })}>{t('markReviewed')}</button>}
          <button type="button" className="secondary" onClick={() => void patch({ trackLots: !item.trackLots })}>{item.trackLots ? t('stopLots') : t('startLots')}</button>
          <button type="button" className="secondary" onClick={() => void patch({ active: !item.active })}>{item.active ? tf('deactivate') : tf('activate')}</button>
        </div>
      )}
      {edit && (
        <form className="form-card form-grid" onSubmit={e => { e.preventDefault(); void patch({ name: edit.name, reorderLevel: edit.reorderLevel || '0', notes: edit.notes,
          expenseCategoryId: edit.expenseCategoryId ? Number(edit.expenseCategoryId) : null, saleCategoryId: edit.saleCategoryId ? Number(edit.saleCategoryId) : null }); }}>
          <label>{t('name')}<input required maxLength={140} value={edit.name} onChange={e => setEdit({ ...edit, name: e.target.value })} /></label>
          <label>{t('reorderLevel')}<input inputMode="decimal" value={edit.reorderLevel} onChange={e => setEdit({ ...edit, reorderLevel: e.target.value })} /></label>
          {inputCategories.length > 0 && <label>{t('useCategory')}<select value={edit.expenseCategoryId} onChange={e => setEdit({ ...edit, expenseCategoryId: e.target.value })}>
            <option value="">—</option>{inputCategories.map(c => <option key={c.id} value={c.id}>{label(c)}</option>)}</select></label>}
          {saleCategories.length > 0 && <label>{t('saleCategory')}<select value={edit.saleCategoryId} onChange={e => setEdit({ ...edit, saleCategoryId: e.target.value })}>
            <option value="">—</option>{saleCategories.map(c => <option key={c.id} value={c.id}>{label(c)}</option>)}</select></label>}
          <label className="full">{t('notes')}<input maxLength={300} value={edit.notes} onChange={e => setEdit({ ...edit, notes: e.target.value })} /></label>
          <div className="form-actions full">
            <button type="button" className="secondary" onClick={() => setEdit(null)}>{tc('cancel')}</button>
            <button className="primary">{tc('save')}</button>
          </div>
        </form>
      )}

      <h3>{t('packUnits')}</h3>
      {item.packUnits.length === 0 ? <p className="hint">{t('noPackUnits')}</p> : (
        <ul className="plain-list">
          {item.packUnits.map(p => (
            <li key={p.id}>{t('packEquals', { pack: p.name, factor: formatNumber(p.factor, lang, 6), unit })}
              {canWrite && <button type="button" className="text-action" aria-label={tc('remove')} onClick={() => void removePack(p.id)}><Trash2 size={15} /></button>}</li>
          ))}
        </ul>
      )}
      {canWrite && !item.customUnitName && (
        <form className="inline-form" onSubmit={e => void addPack(e)}>
          <label>{t('packName')}<input maxLength={30} value={pack.name} onChange={e => setPack({ ...pack, name: e.target.value })} /></label>
          <label>{t('packFactor', { unit })}<input inputMode="decimal" value={pack.factor} onChange={e => setPack({ ...pack, factor: e.target.value })} /></label>
          <button className="secondary">{t('addPackUnit')}</button>
        </form>
      )}

      {item.trackLots && (
        <>
          <h3>{t('lots')}</h3>
          {lots.length === 0 ? <p className="hint">{t('noLots')}</p> : (
            <table className="data-table">
              <thead><tr><th>{t('lot')}</th><th>{t('expiry')}</th><th>{t('received')}</th><th className="num">{t('quantity')}</th></tr></thead>
              <tbody>{lots.map(l => (
                <tr key={l.id}><td>{l.lotNo}</td><td>{l.expiryDate ? formatDate(l.expiryDate, lang) : '—'}{l.expired && <small className="low-stock-label"> · {t('expired')}</small>}</td>
                  <td>{formatDate(l.receivedOn, lang)}</td><td className="num">{formatNumber(l.quantity, lang, 4)}</td></tr>
              ))}</tbody>
            </table>
          )}
        </>
      )}

      <div className="section-head section-space">
        <h3>{t('movements')}</h3>
        <button type="button" className="secondary" onClick={() => download(`${base}/movements.csv`, `stock-movements-${item.id}.csv`).catch(e => setError(describeError(e)))}>
          <Download size={16} aria-hidden />{t('exportCsv')}</button>
      </div>
      {!movements ? <Loading /> : movements.items.length === 0 ? <p className="hint">{t('noMovements')}</p> : (
        <>
          <div className="table-scroll">
            <table className="data-table">
              <thead><tr><th>{tf('date')}</th><th>{t('record')}</th><th>{t('where')}</th><th className="num">{t('inOut')}</th><th className="num">{t('value')}</th>
                <th className="num">{t('balance')}</th><th className="num">{t('balanceValue')}</th></tr></thead>
              <tbody>
                {movements.items.map(m => (
                  <tr key={m.id}>
                    <td>{formatDate(m.date, lang)}</td>
                    <td><Link to={`/t/${tenant.id}/finance/records/${m.documentId}`}>{m.documentNumber}</Link> · {t(`movement.${m.movementType}`)}
                      {m.reversal && <small> · {t('reversal')}</small>}{m.costObjectName && <small> · {m.costObjectName}</small>}</td>
                    <td>{m.locationName}{m.lotNo ? ` (${m.lotNo})` : ''}</td>
                    <td className="num">{m.quantity > 0 ? '+' : ''}{formatNumber(m.quantity, lang, 4)}</td>
                    <td className="num">{formatMoney(m.value, lang)}</td>
                    <td className="num">{formatNumber(m.balanceQuantity, lang, 4)}</td>
                    <td className="num">{formatMoney(m.balanceValue, lang)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {movements.totalPages > 1 && (
            <div className="form-actions">
              <button type="button" className="secondary" disabled={page === 0} onClick={() => setPage(p => p - 1)}>{tc('previous')}</button>
              <button type="button" className="secondary" disabled={page + 1 >= movements.totalPages} onClick={() => setPage(p => p + 1)}>{tc('next')}</button>
            </div>
          )}
        </>
      )}
    </>
  );
}
