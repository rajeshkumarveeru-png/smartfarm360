import { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { Plus, Trash2 } from 'lucide-react';
import { api } from '../api/client';
import type { Page } from '../api/types';
import { useTenant } from '../auth/useCurrentTenant';
import { ErrorBanner, Loading } from '../components/common';
import { isAmount } from '../finance/NewRecordPage';
import type { CostObject, DocumentSummary, FinancialAccount, Party } from '../finance/types';
import { newIdempotencyKey } from '../finance/useFinanceData';
import { currentLanguage, useNs } from '../i18n';
import { describeError } from '../lib/errors';
import { formatDate, formatMoney, formatNumber, today } from '../lib/format';
import { can } from '../lib/nav';
import type { StockRecordKind } from './StockHome';
import type { Item, Lot, StockRecordResult } from './types';
import { baseUnitLabel, isQuantity, unitChoices } from './types';
import { useStockData } from './useStockData';
import { useAssetChoices } from '../assets/useAssetChoices';

const ENDPOINT: Record<StockRecordKind, string> = {
  issue: 'issues', transfer: 'transfers', wastage: 'wastage', count: 'counts', produce: 'produce', 'purchase-return': 'purchase-returns', 'sale-return': 'sale-returns',
};

type Line = { itemId: string; quantity: string; unit: string; lotNo: string; lotExpiry: string; amount: string };
const emptyLine = (): Line => ({ itemId: '', quantity: '', unit: '', lotNo: '', lotExpiry: '', amount: '' });

/** Records a stock change that is not a purchase or a sale: use, transfer, wastage, count, farm produce, returns. */
export default function StockRecordPage() {
  const tenant = useTenant();
  const kind = (useParams().kind ?? 'issue') as StockRecordKind;
  const [search] = useSearchParams();
  const navigate = useNavigate();
  const t = useNs('stock');
  const tf = useNs('fin');
  const tc = useNs('common');
  const lang = currentLanguage();
  const data = useStockData();
  const assets = useAssetChoices();
  const fin = `/api/v1/tenants/${tenant.id}/finance`;
  const canSeeFinance = can(tenant, 'FINANCE_READ');
  const isReturn = kind === 'purchase-return' || kind === 'sale-return';
  const receives = kind === 'produce';

  const [key, setKey] = useState(newIdempotencyKey);
  const [form, setForm] = useState<Record<string, string>>({ docDate: today(), originalDocumentId: search.get('original') ?? '', partyId: search.get('party') ?? '' });
  const [lines, setLines] = useState<Line[]>([emptyLine()]);
  const [lots, setLots] = useState<Record<string, Lot[]>>({});
  const [costObjects, setCostObjects] = useState<CostObject[]>([]);
  const [accounts, setAccounts] = useState<FinancialAccount[]>([]);
  const [parties, setParties] = useState<Party[]>([]);
  const [originals, setOriginals] = useState<DocumentSummary[]>([]);
  const [result, setResult] = useState<StockRecordResult | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const set = (k: string, v: string) => setForm(f => ({ ...f, [k]: v }));

  useEffect(() => {
    if (!canSeeFinance) return;
    Promise.all([api<CostObject[]>(`${fin}/cost-objects`), api<FinancialAccount[]>(`${fin}/accounts`), api<Party[]>(`${fin}/parties`)])
      .then(([c, a, p]) => { setCostObjects(c.filter(x => x.active && x.kind !== 'OVERHEAD')); setAccounts(a.filter(x => x.active)); setParties(p.filter(x => x.active)); })
      .catch(e => setError(describeError(e)));
  }, [fin, canSeeFinance]);

  // Posted bills (of the chosen supplier) or sales that a return can refer to
  useEffect(() => {
    if (!isReturn || !canSeeFinance) return;
    const type = kind === 'purchase-return' ? 'BILL' : 'SALE';
    const party = kind === 'purchase-return' && form.partyId ? `&partyId=${form.partyId}` : '';
    api<Page<DocumentSummary>>(`${fin}/documents?type=${type}&status=POSTED&size=100${party}`).then(p => setOriginals(p.items)).catch(() => setOriginals([]));
  }, [fin, kind, isReturn, canSeeFinance, form.partyId]);

  const items = useMemo(() => data.items.filter(i => i.active && (kind !== 'produce' || i.kind === 'PRODUCE')), [data.items, kind]);
  const itemOf = (l: Line) => data.items.find(i => String(i.id) === l.itemId);
  const original = originals.find(o => String(o.id) === form.originalDocumentId);

  function loadLots(itemId: string) {
    const item = data.items.find(i => String(i.id) === itemId);
    if (!item?.trackLots || lots[itemId]) return;
    api<Lot[]>(`/api/v1/tenants/${tenant.id}/stock/items/${itemId}/lots`).then(l => setLots(x => ({ ...x, [itemId]: l }))).catch(() => undefined);
  }

  function updateLine(i: number, patch: Partial<Line>) {
    setLines(ls => ls.map((x, j) => (j === i ? { ...x, ...patch } : x)));
    if (patch.itemId) {
      loadLots(patch.itemId);
      // Suggest the first-expiring lot that has stock (E6)
      const item = data.items.find(it => String(it.id) === patch.itemId);
      if (item && !receives) {
        const first = (lots[patch.itemId] ?? []).find(l => l.quantity > 0);
        setLines(ls => ls.map((x, j) => (j === i ? { ...x, unit: item.baseUnit, lotNo: first?.lotNo ?? '' } : x)));
      } else if (item) {
        setLines(ls => ls.map((x, j) => (j === i ? { ...x, unit: item.baseUnit } : x)));
      }
    }
  }

  function validate(): string | null {
    if (!form.docDate) return tc('required');
    if (kind === 'transfer' && (!form.fromLocationId || !form.toLocationId || form.fromLocationId === form.toLocationId)) return t('errors.locations');
    if (kind === 'wastage' && !form.reason?.trim()) return t('errors.reason');
    if (kind === 'purchase-return' && !form.partyId && !form.financialAccountId) return tf('errors.partyOrAccount');
    if (kind === 'sale-return' && !form.originalDocumentId) return t('errors.originalSale');
    if (kind === 'sale-return' && original && !original.partyId && !form.financialAccountId) return tf('errors.account');
    for (const l of lines) {
      const item = itemOf(l);
      if (!item) return t('errors.item');
      if (!isQuantity(l.quantity, kind === 'count')) return t('errors.quantity');
      if (item.trackLots && !l.lotNo.trim()) return t('errors.lot');
      if (isReturn && !(l.amount === '0' || isAmount(l.amount))) return tf('errors.amount');
    }
    return null;
  }

  function payload() {
    const base = { docDate: form.docDate, notes: form.notes || null };
    const location = form.locationId ? Number(form.locationId) : null;
    const qtyLines = lines.map(l => ({ itemId: Number(l.itemId), quantity: l.quantity, unit: l.unit || null, lotNo: l.lotNo.trim() || null,
      lotExpiry: receives && l.lotExpiry ? l.lotExpiry : null }));
    const costObjectId = form.costObjectId ? Number(form.costObjectId) : null;
    switch (kind) {
      case 'issue': return { ...base, locationId: location, costObjectId, assetId: form.assetId ? Number(form.assetId) : null, lines: qtyLines };
      case 'produce': return { ...base, locationId: location, costObjectId, lines: qtyLines };
      case 'transfer': return { ...base, fromLocationId: Number(form.fromLocationId), toLocationId: Number(form.toLocationId), lines: qtyLines };
      case 'wastage': return { docDate: form.docDate, locationId: location, reason: form.reason, lines: qtyLines };
      case 'count': return { ...base, locationId: location, lines: lines.map(l => ({ itemId: Number(l.itemId), countedQuantity: l.quantity, unit: l.unit || null, lotNo: l.lotNo.trim() || null })) };
      case 'purchase-return': case 'sale-return': {
        const returnLines = lines.map(l => ({ itemId: Number(l.itemId), quantity: l.quantity, unit: l.unit || null, lotNo: l.lotNo.trim() || null, amount: l.amount }));
        const common = { ...base, locationId: location, referenceNo: form.referenceNo || null, lines: returnLines,
          originalDocumentId: form.originalDocumentId ? Number(form.originalDocumentId) : null };
        return kind === 'purchase-return'
          ? { ...common, partyId: form.partyId ? Number(form.partyId) : null, financialAccountId: !form.partyId && form.financialAccountId ? Number(form.financialAccountId) : null }
          : { ...common, financialAccountId: form.financialAccountId ? Number(form.financialAccountId) : null };
      }
    }
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    const problem = validate();
    if (problem) { setError(problem); return; }
    setBusy(true); setError('');
    try {
      const r = await api<StockRecordResult>(`/api/v1/tenants/${tenant.id}/stock/${ENDPOINT[kind]}`, { method: 'POST', body: payload(), headers: { 'Idempotency-Key': key } });
      setKey(newIdempotencyKey());
      if (r.warnings.length) setResult(r);
      else navigate(canSeeFinance ? `/t/${tenant.id}/finance/records/${r.document.summary.id}` : `/t/${tenant.id}/stock`);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusy(false);
    }
  }

  if (data.loading) return <Loading />;
  if (result) {
    return (
      <>
        <div className="crop-page-head"><div><h2>{t(`kind.${kind}`)}</h2><p>{t('recorded', { number: result.document.summary.number ?? '' })}</p></div></div>
        {result.warnings.map(w => { const [, item, lot] = w.split(':'); return <p key={w} className="low-stock-label" role="status">{t('warning.LOT_EXPIRED', { item, lot })}</p>; })}
        <div className="form-actions">
          {canSeeFinance && <Link className="primary" to={`/t/${tenant.id}/finance/records/${result.document.summary.id}`}>{t('viewRecord')}</Link>}
          <Link className="secondary" to={`/t/${tenant.id}/stock`}>{t('overview')}</Link>
        </div>
      </>
    );
  }
  const locations = data.locations.filter(l => l.active);
  const locationOptions = (allowDefault: boolean) => <>{allowDefault ? <option value="">{t('defaultLocationOption')}</option> : <option value="">—</option>}
    {locations.map(l => <option key={l.id} value={l.id}>{l.name}</option>)}</>;
  const accountOptions = <><option value="">—</option>{accounts.map(a => <option key={a.id} value={a.id}>{a.name}</option>)}</>;
  return (
    <>
      <div className="crop-page-head"><div><h2>{t(`kind.${kind}`)}</h2><p>{t(`help.${kind}`)}</p></div></div>
      <ErrorBanner message={error || data.error} />
      <form className="form-card form-grid" noValidate onSubmit={e => void submit(e)}>
        <label>{tf('date')}<input type="date" required value={form.docDate ?? ''} onChange={e => set('docDate', e.target.value)} /></label>
        {kind === 'transfer' ? <>
          <label>{t('fromLocation')}<select value={form.fromLocationId ?? ''} onChange={e => set('fromLocationId', e.target.value)}>{locationOptions(false)}</select></label>
          <label>{t('toLocation')}<select value={form.toLocationId ?? ''} onChange={e => set('toLocationId', e.target.value)}>{locationOptions(false)}</select></label>
        </> : <label>{t('location')}<select value={form.locationId ?? ''} onChange={e => set('locationId', e.target.value)}>{locationOptions(true)}</select></label>}
        {(kind === 'issue' || kind === 'produce') && costObjects.length > 0 && (
          <label>{tf('activity')}<select value={form.costObjectId ?? ''} onChange={e => set('costObjectId', e.target.value)}>
            <option value="">{tf('overhead')}</option>{costObjects.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}</select></label>
        )}
        {kind === 'issue' && assets.length > 0 && (
          <label>{tf('forAsset')} <small>{tc('optional')}</small><select value={form.assetId ?? ''} onChange={e => set('assetId', e.target.value)}>
            <option value="">—</option>{assets.map(a => <option key={a.id} value={a.id}>{a.name}</option>)}</select></label>
        )}
        {kind === 'wastage' && <label className="full">{t('reason')}<input required maxLength={500} value={form.reason ?? ''} onChange={e => set('reason', e.target.value)} /></label>}
        {kind === 'purchase-return' && <>
          <label>{tf('party')}<select value={form.partyId ?? ''} onChange={e => { set('partyId', e.target.value); set('originalDocumentId', ''); }}>
            <option value="">—</option>{parties.filter(p => p.supplier).map(p => <option key={p.id} value={p.id}>{p.name}</option>)}</select></label>
          {!form.partyId && <label>{tf('receivedInto')}<select value={form.financialAccountId ?? ''} onChange={e => set('financialAccountId', e.target.value)}>{accountOptions}</select></label>}
          {form.partyId && <label>{t('originalBill')} <small>{tc('optional')}</small><select value={form.originalDocumentId ?? ''} onChange={e => set('originalDocumentId', e.target.value)}>
            <option value="">—</option>{originals.map(o => <option key={o.id} value={o.id}>{o.number} · {formatDate(o.docDate, lang)} · {formatMoney(o.totalAmount, lang)}</option>)}</select></label>}
        </>}
        {kind === 'sale-return' && <>
          <label>{t('originalSale')}<select required value={form.originalDocumentId ?? ''} onChange={e => set('originalDocumentId', e.target.value)}>
            <option value="">—</option>{originals.map(o => <option key={o.id} value={o.id}>{o.number} · {o.partyName ?? tf('cashSale')} · {formatMoney(o.totalAmount, lang)}</option>)}</select></label>
          {original && !original.partyId && <label>{t('refundFrom')}<select value={form.financialAccountId ?? ''} onChange={e => set('financialAccountId', e.target.value)}>{accountOptions}</select></label>}
        </>}

        <fieldset className="full lines">
          <legend>{t('items')}</legend>
          {lines.map((l, i) => {
            const item = itemOf(l);
            const itemLots = item ? lots[l.itemId] ?? [] : [];
            return (
              <div className="line-row" key={i}>
                <label>{t('item')}<select value={l.itemId} onChange={e => updateLine(i, { itemId: e.target.value })}>
                  <option value="">—</option>{items.map(it => <option key={it.id} value={it.id}>{it.name}</option>)}</select></label>
                <label>{kind === 'count' ? t('countedQuantity') : t('quantity')}<input inputMode="decimal" value={l.quantity} onChange={e => updateLine(i, { quantity: e.target.value })} /></label>
                {item && <label>{t('unitLabel')}<select value={l.unit} onChange={e => updateLine(i, { unit: e.target.value })}>
                  {unitChoices(item, data.units).map(u => <option key={u.value} value={u.value}>{u.label ?? t(`unit.${u.value}`)}</option>)}</select></label>}
                {item?.trackLots && (receives ? <>
                  <label>{t('lot')}<input maxLength={60} value={l.lotNo} onChange={e => updateLine(i, { lotNo: e.target.value })} /></label>
                  <label>{t('expiry')} <small>{tc('optional')}</small><input type="date" value={l.lotExpiry} onChange={e => updateLine(i, { lotExpiry: e.target.value })} /></label>
                </> : (
                  <label>{t('lot')}<select value={l.lotNo} onChange={e => updateLine(i, { lotNo: e.target.value })}>
                    <option value="">—</option>{itemLots.filter(x => x.quantity > 0 || kind === 'count').map(x => (
                      <option key={x.id} value={x.lotNo}>{x.lotNo}{x.expiryDate ? ` · ${formatDate(x.expiryDate, lang)}` : ''}{x.expired ? ` · ${t('expired')}` : ''} · {formatNumber(x.quantity, lang, 4)}</option>))}</select></label>
                ))}
                {isReturn && <label>{kind === 'purchase-return' ? t('creditAmount') : t('refundAmount')}<input inputMode="decimal" value={l.amount} onChange={e => updateLine(i, { amount: e.target.value })} /></label>}
                {item && <small className="hint">{t('inStock', { quantity: formatNumber(item.quantity, lang, 4), unit: baseUnitLabel(item, t) })}</small>}
                {lines.length > 1 && <button type="button" className="text-action" aria-label={tc('remove')} onClick={() => setLines(ls => ls.filter((_, j) => j !== i))}><Trash2 size={16} /></button>}
              </div>
            );
          })}
          <button type="button" className="secondary" onClick={() => setLines(ls => [...ls, emptyLine()])}><Plus size={16} />{tf('addLine')}</button>
        </fieldset>
        {isReturn && <label>{tf('reference')} <small>{tc('optional')}</small><input maxLength={100} value={form.referenceNo ?? ''} onChange={e => set('referenceNo', e.target.value)} /></label>}
        {kind !== 'wastage' && <label className="full">{tf('notes')} <small>{tc('optional')}</small><input maxLength={500} value={form.notes ?? ''} onChange={e => set('notes', e.target.value)} /></label>}
        <div className="form-actions full">
          <button type="button" className="secondary" onClick={() => navigate(-1)}>{tc('cancel')}</button>
          <button className="primary" disabled={busy}>{busy ? tc('loading') : tf('post')}</button>
        </div>
      </form>
    </>
  );
}

export type { Item };
