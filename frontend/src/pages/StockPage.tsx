import React, { useCallback, useEffect, useState } from 'react';
import { Boxes, Plus, RefreshCw, Truck } from 'lucide-react';
import { api } from '../api/client';
import { useTenant } from '../auth/useCurrentTenant';
import { EmptyState, ErrorBanner } from '../components/common';
import type { FinancialAccount } from '../finance/types';
import { currentLanguage, useNs } from '../i18n';
import { describeError } from '../lib/errors';
import { formatDate, formatMoney, formatNumber, today } from '../lib/format';
import { can } from '../lib/nav';

type Item = { id: number; name: string; category: string; unit: string; quantityOnHand: number; reorderLevel: number; averageUnitCost: number; stockValue: number };
type Supplier = { id: number; name: string; phone?: string; village?: string };
type Purchase = { id: number; supplierName: string; itemName: string; purchaseDate: string; quantity: number; unitCost: number; totalAmount: number; paidAmount: number; outstandingAmount: number };
type Tab = 'inventory' | 'suppliers' | 'purchases';

/**
 * Phase 1 stock screen (items, suppliers, purchases), kept until Phase 4 brings stock movements. Purchases recorded here
 * are posted to finance as bills to Stock, with the paid part as a payment from the chosen account.
 */
export default function StockPage() {
  const tenant = useTenant();
  const t = useNs('finance');
  const tf = useNs('fin');
  const tc = useNs('common');
  const lang = currentLanguage();
  const canWrite = can(tenant, 'INVENTORY_WRITE');
  const base = `/api/v1/tenants/${tenant.id}`;
  const [tab, setTab] = useState<Tab>('inventory');
  const [items, setItems] = useState<Item[]>([]);
  const [suppliers, setSuppliers] = useState<Supplier[]>([]);
  const [purchases, setPurchases] = useState<Purchase[]>([]);
  const [accounts, setAccounts] = useState<FinancialAccount[]>([]);
  const [show, setShow] = useState<Tab | ''>('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [item, setItem] = useState({ name: '', category: '', unit: 'kg', quantityOnHand: '0', reorderLevel: '0', averageUnitCost: '0' });
  const [supplier, setSupplier] = useState({ name: '', phone: '', village: '' });
  const [purchase, setPurchase] = useState({ supplierId: '', itemId: '', purchaseDate: today(), quantity: '1', unitCost: '', paidAmount: '0', financialAccountId: '', notes: '' });

  const load = useCallback(async () => {
    setBusy(true); setError('');
    try {
      const [i, s, p] = await Promise.all([api<Item[]>(`${base}/inventory/items`), api<Supplier[]>(`${base}/suppliers`), api<Purchase[]>(`${base}/purchases`)]);
      setItems(i); setSuppliers(s); setPurchases(p);
      if (can(tenant, 'FINANCE_READ')) setAccounts((await api<FinancialAccount[]>(`${base}/finance/accounts`)).filter(a => a.active));
    } catch (e) {
      setError(describeError(e));
    } finally {
      setBusy(false);
    }
  }, [base, tenant]);
  useEffect(() => { void load(); }, [load]);

  async function save(e: React.FormEvent, path: string, body: unknown, reset: () => void) {
    e.preventDefault();
    setBusy(true); setError('');
    try { await api(`${base}${path}`, { method: 'POST', body }); reset(); setShow(''); await load(); }
    catch (err) { setError(describeError(err)); }
    finally { setBusy(false); }
  }

  const lowStock = items.filter(i => Number(i.quantityOnHand) <= Number(i.reorderLevel)).length;
  const addLabel = { inventory: t('addItem'), suppliers: t('addSupplier'), purchases: t('addPurchase') }[tab];
  return (
    <section className="crop-page finance-page">
      <div className="crop-page-head"><div><h2><Boxes /> {tf('stockTitle')}</h2><p>{tf('stockHelp')}</p></div></div>
      <ErrorBanner message={error} onRetry={() => void load()} />
      {lowStock > 0 && <div className="finance-low-stock"><b>{lowStock}</b> {t('low')}</div>}
      <div className="crop-tabs">
        {(['inventory', 'suppliers', 'purchases'] as Tab[]).map(k => <button key={k} className={tab === k ? 'active' : ''} onClick={() => { setTab(k); setShow(''); }}>{t(k)}</button>)}
      </div>
      <div className="crop-toolbar">
        <h3>{t(tab)}</h3>
        {canWrite && <button className="primary" onClick={() => setShow(show === tab ? '' : tab)}><Plus size={16} />{addLabel}</button>}
      </div>

      {show === 'inventory' && (
        <form className="form-card form-grid" onSubmit={e => void save(e, '/inventory/items', item, () => setItem({ ...item, name: '', quantityOnHand: '0' }))}>
          <label>{t('name')}<input required value={item.name} onChange={e => setItem({ ...item, name: e.target.value })} /></label>
          <label>{t('category')}<input required value={item.category} onChange={e => setItem({ ...item, category: e.target.value })} /></label>
          <label>{t('unit')}<input required value={item.unit} onChange={e => setItem({ ...item, unit: e.target.value })} /></label>
          <label>{t('quantity')}<input inputMode="decimal" required value={item.quantityOnHand} onChange={e => setItem({ ...item, quantityOnHand: e.target.value })} /></label>
          <label>{t('reorder')}<input inputMode="decimal" required value={item.reorderLevel} onChange={e => setItem({ ...item, reorderLevel: e.target.value })} /></label>
          <label>{t('unitCost')}<input inputMode="decimal" required value={item.averageUnitCost} onChange={e => setItem({ ...item, averageUnitCost: e.target.value })} /></label>
          <div className="form-actions full"><button type="button" className="secondary" onClick={() => setShow('')}>{tc('cancel')}</button><button className="primary" disabled={busy}>{tc('save')}</button></div>
        </form>
      )}
      {show === 'suppliers' && (
        <form className="form-card form-grid" onSubmit={e => void save(e, '/suppliers', { name: supplier.name, phone: supplier.phone || null, village: supplier.village || null }, () => setSupplier({ name: '', phone: '', village: '' }))}>
          <label>{t('name')}<input required value={supplier.name} onChange={e => setSupplier({ ...supplier, name: e.target.value })} /></label>
          <label>{t('phone')}<input value={supplier.phone} onChange={e => setSupplier({ ...supplier, phone: e.target.value })} /></label>
          <label>{t('village')}<input value={supplier.village} onChange={e => setSupplier({ ...supplier, village: e.target.value })} /></label>
          <div className="form-actions full"><button type="button" className="secondary" onClick={() => setShow('')}>{tc('cancel')}</button><button className="primary" disabled={busy}>{tc('save')}</button></div>
        </form>
      )}
      {show === 'purchases' && (
        <form className="form-card form-grid" onSubmit={e => void save(e, '/purchases', {
          ...purchase, supplierId: Number(purchase.supplierId), itemId: Number(purchase.itemId),
          financialAccountId: purchase.financialAccountId ? Number(purchase.financialAccountId) : null, notes: purchase.notes || null,
        }, () => setPurchase({ ...purchase, quantity: '1', unitCost: '', paidAmount: '0', notes: '' }))}>
          <label>{t('supplier')}<select required value={purchase.supplierId} onChange={e => setPurchase({ ...purchase, supplierId: e.target.value })}>
            <option value="">—</option>{suppliers.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}</select></label>
          <label>{t('item')}<select required value={purchase.itemId} onChange={e => setPurchase({ ...purchase, itemId: e.target.value })}>
            <option value="">—</option>{items.map(i => <option key={i.id} value={i.id}>{i.name} ({i.unit})</option>)}</select></label>
          <label>{t('date')}<input type="date" required value={purchase.purchaseDate} onChange={e => setPurchase({ ...purchase, purchaseDate: e.target.value })} /></label>
          <label>{t('quantity')}<input inputMode="decimal" required value={purchase.quantity} onChange={e => setPurchase({ ...purchase, quantity: e.target.value })} /></label>
          <label>{t('unitCost')}<input inputMode="decimal" required value={purchase.unitCost} onChange={e => setPurchase({ ...purchase, unitCost: e.target.value })} /></label>
          <label>{t('paidAmount')}<input inputMode="decimal" required value={purchase.paidAmount} onChange={e => setPurchase({ ...purchase, paidAmount: e.target.value })} /></label>
          {accounts.length > 0 && <label>{tf('paidFrom')}<select value={purchase.financialAccountId} onChange={e => setPurchase({ ...purchase, financialAccountId: e.target.value })}>
            <option value="">{tf('defaultCash')}</option>{accounts.map(a => <option key={a.id} value={a.id}>{a.name}</option>)}</select></label>}
          <label>{t('notes')}<input value={purchase.notes} onChange={e => setPurchase({ ...purchase, notes: e.target.value })} /></label>
          <div className="form-actions full"><button type="button" className="secondary" onClick={() => setShow('')}>{tc('cancel')}</button><button className="primary" disabled={busy}>{tc('save')}</button></div>
        </form>
      )}

      {tab === 'inventory' && <div className="crop-record-grid">
        {items.map(i => (
          <article className="crop-record" key={i.id}><span className="record-icon"><Boxes /></span>
            <div className="cycle-main"><h4>{i.name}</h4><p>{i.category} · {i.unit}</p>
              <small>{t('onHand')}: {formatNumber(i.quantityOnHand, lang)} · {t('reorder')}: {formatNumber(i.reorderLevel, lang)}</small>
              <b>{formatMoney(i.stockValue, lang)}</b>
              {Number(i.quantityOnHand) <= Number(i.reorderLevel) && <small className="low-stock-label">{t('low')}</small>}</div>
          </article>
        ))}
        {!items.length && <EmptyState>{t('empty')}</EmptyState>}
      </div>}
      {tab === 'suppliers' && <div className="crop-record-grid">
        {suppliers.map(s => <article className="crop-record" key={s.id}><span className="record-icon"><Truck /></span><div><h4>{s.name}</h4><p>{s.phone || '—'}</p><small>{s.village || ''}</small></div></article>)}
        {!suppliers.length && <EmptyState>{t('empty')}</EmptyState>}
      </div>}
      {tab === 'purchases' && <div className="activity-list">
        {purchases.map(p => (
          <article className="activity-row" key={p.id}><span className="record-icon"><Truck /></span>
            <div className="cycle-main"><h4>{p.itemName} · {formatNumber(p.quantity, lang)}</h4><p>{p.supplierName} · {formatDate(p.purchaseDate, lang)}</p>
              <small>{t('paid')}: {formatMoney(p.paidAmount, lang)} · {t('payables')}: {formatMoney(p.outstandingAmount, lang)}</small></div>
            <div className="value-side"><b>{formatMoney(p.totalAmount, lang)}</b></div>
          </article>
        ))}
        {!purchases.length && <EmptyState>{t('empty')}</EmptyState>}
      </div>}
      {busy && <div className="crop-loading"><RefreshCw size={16} />{tc('loading')}</div>}
    </section>
  );
}
