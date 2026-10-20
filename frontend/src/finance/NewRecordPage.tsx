import { useEffect, useMemo, useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { Plus, Trash2 } from 'lucide-react';
import { api } from '../api/client';
import type { Page } from '../api/types';
import { useTenant } from '../auth/useCurrentTenant';
import { ErrorBanner, Loading } from '../components/common';
import { currentLanguage, useNs } from '../i18n';
import { describeError } from '../lib/errors';
import { formatMoney, today } from '../lib/format';
import type { DocumentSummary, FinanceDocument, Party } from './types';
import { newIdempotencyKey, useCategoryLabel, useFinanceData } from './useFinanceData';

export type RecordKind = 'expense' | 'sale' | 'payment' | 'receipt' | 'transfer' | 'owner' | 'loan' | 'loan-repayment' | 'settlement'
  | 'write-off' | 'opening' | 'depreciation';

const ENDPOINT: Record<RecordKind, string> = {
  expense: 'bills', sale: 'sales', payment: 'payments', receipt: 'receipts', transfer: 'transfers', owner: 'owner-money', loan: 'loans',
  'loan-repayment': 'loan-repayments', settlement: 'settlements', 'write-off': 'write-offs', opening: 'opening-balances', depreciation: 'depreciation',
};

/** A positive rupee amount with at most two decimals. */
export function isAmount(value: string): boolean {
  return /^\d{1,12}(\.\d{1,2})?$/.test(value.trim()) && Number(value) > 0;
}

type Line = { categoryId: string; description: string; amount: string; quantity: string; rate: string; costObjectId: string };
type OpeningLine = { kind: string; financialAccountId: string; partyId: string; loanName: string; description: string; amount: string };
const emptyLine = (): Line => ({ categoryId: '', description: '', amount: '', quantity: '', rate: '', costObjectId: '' });

export default function NewRecordPage() {
  const tenant = useTenant();
  const kind = (useParams().kind ?? 'expense') as RecordKind;
  const [search] = useSearchParams();
  const draftId = search.get('draft');
  const navigate = useNavigate();
  const t = useNs('fin');
  const tc = useNs('common');
  const categoryLabel = useCategoryLabel();
  const lang = currentLanguage();
  const data = useFinanceData();
  const base = `/api/v1/tenants/${tenant.id}/finance`;
  const isInvoice = kind === 'expense' || kind === 'sale';

  const [key, setKey] = useState(newIdempotencyKey);
  const [form, setForm] = useState<Record<string, string>>({ docDate: today(), direction: 'IN' });
  const [lines, setLines] = useState<Line[]>([emptyLine()]);
  const [openingLines, setOpeningLines] = useState<OpeningLine[]>([{ kind: 'CASH', financialAccountId: '', partyId: '', loanName: '', description: '', amount: '' }]);
  const [openDocs, setOpenDocs] = useState<DocumentSummary[]>([]);
  const [allocations, setAllocations] = useState<Record<number, string>>({});
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const set = (k: string, v: string) => setForm(f => ({ ...f, [k]: v }));

  // Editing a draft bill or sale
  useEffect(() => {
    if (!draftId) return;
    api<FinanceDocument>(`${base}/documents/${draftId}`).then(d => {
      setForm({ docDate: d.summary.docDate, dueDate: d.summary.dueDate ?? '', partyId: d.summary.partyId ? String(d.summary.partyId) : '',
        financialAccountId: d.financialAccountId ? String(d.financialAccountId) : '', notes: d.notes ?? '', referenceNo: d.referenceNo ?? '' });
      setLines(d.lines.map(l => ({ categoryId: String(l.categoryId ?? ''), description: l.description ?? '', amount: String(l.amount),
        quantity: l.quantity != null ? String(l.quantity) : '', rate: l.rate != null ? String(l.rate) : '', costObjectId: l.costObjectId ? String(l.costObjectId) : '' })));
    }).catch(e => setError(describeError(e)));
  }, [base, draftId]);

  // Open bills/sales of the chosen party, for matching a payment or receipt
  useEffect(() => {
    const target = kind === 'payment' ? 'BILL' : kind === 'receipt' ? 'SALE' : null;
    if (!target || !form.partyId) { setOpenDocs([]); return; }
    api<Page<DocumentSummary>>(`${base}/documents?type=${target}&status=POSTED&partyId=${form.partyId}&size=100`)
      .then(p => setOpenDocs(p.items.filter(d => d.openAmount > 0).reverse())).catch(() => setOpenDocs([]));
  }, [base, kind, form.partyId]);

  const categories = useMemo(() => data.categories.filter(c => c.active && (kind === 'sale' ? c.kind === 'INCOME' : c.kind !== 'INCOME')), [data.categories, kind]);
  const accounts = data.accounts.filter(a => a.active);
  const party = data.parties.find(p => String(p.id) === form.partyId);
  const lineAmount = (l: Line) => (l.quantity && l.rate ? Math.round(Number(l.quantity) * Number(l.rate) * 100) / 100 : Number(l.amount || 0));
  const invoiceTotal = lines.reduce((s, l) => s + lineAmount(l), 0);

  function autoAllocate(amount: string) {
    let remaining = Number(amount) || 0;
    const next: Record<number, string> = {};
    for (const d of openDocs) {
      if (remaining <= 0) break;
      const part = Math.min(d.openAmount, remaining);
      next[d.id] = part.toFixed(2);
      remaining = Math.round((remaining - part) * 100) / 100;
    }
    setAllocations(next);
  }

  function validate(): string | null {
    if (!form.docDate) return tc('required');
    if (isInvoice) {
      if (!form.partyId && !form.financialAccountId) return t('errors.partyOrAccount');
      for (const l of lines) {
        if (!l.categoryId) return t('errors.category');
        const ok = l.quantity || l.rate ? isAmount(String(lineAmount(l))) : isAmount(l.amount);
        if (!ok) return t('errors.amount');
      }
      if (form.paidNow && !isAmount(form.paidNow)) return t('errors.amount');
      if (form.paidNow && !form.paidFrom) return t('errors.account');
      return null;
    }
    if (kind === 'opening') return openingLines.every(l => isAmount(l.amount)) ? null : t('errors.amount');
    if (kind === 'loan-repayment') {
      const p = form.principal || '0', i = form.interest || '0';
      if (!form.loanId || !form.financialAccountId) return tc('required');
      return (/^\d+(\.\d{1,2})?$/.test(p) && /^\d+(\.\d{1,2})?$/.test(i) && Number(p) + Number(i) > 0) ? null : t('errors.amount');
    }
    if (!isAmount(form.amount ?? '')) return t('errors.amount');
    return null;
  }

  function payload(post: boolean) {
    const date = form.docDate;
    switch (kind) {
      case 'expense': case 'sale':
        return {
          docDate: date, dueDate: form.dueDate || null, partyId: form.partyId ? Number(form.partyId) : null,
          financialAccountId: !form.partyId && form.financialAccountId ? Number(form.financialAccountId) : null,
          lines: lines.map(l => ({ categoryId: Number(l.categoryId), description: l.description || null,
            ...(l.quantity && l.rate ? { quantity: l.quantity, rate: l.rate } : { amount: l.amount }),
            costObjectId: l.costObjectId ? Number(l.costObjectId) : null })),
          notes: form.notes || null, referenceNo: form.referenceNo || null, post,
          paidNow: post && form.partyId && form.paidNow ? { financialAccountId: Number(form.paidFrom), amount: form.paidNow } : null,
        };
      case 'payment': case 'receipt':
        return { docDate: date, partyId: Number(form.partyId), financialAccountId: Number(form.financialAccountId), amount: form.amount,
          allocations: Object.entries(allocations).filter(([, a]) => Number(a) > 0).map(([id, a]) => ({ documentId: Number(id), amount: a })),
          notes: form.notes || null, referenceNo: form.referenceNo || null };
      case 'transfer': return { docDate: date, fromAccountId: Number(form.financialAccountId), toAccountId: Number(form.toAccountId), amount: form.amount, notes: form.notes || null };
      case 'owner': return { docDate: date, direction: form.direction, financialAccountId: Number(form.financialAccountId), amount: form.amount, notes: form.notes || null };
      case 'loan': return { docDate: date, lenderPartyId: Number(form.partyId), loanName: form.loanName, financialAccountId: Number(form.financialAccountId), amount: form.amount, notes: form.notes || null };
      case 'loan-repayment': return { docDate: date, loanId: Number(form.loanId), financialAccountId: Number(form.financialAccountId), principal: form.principal || '0', interest: form.interest || '0', notes: form.notes || null };
      case 'settlement': case 'write-off': return { docDate: date, partyId: Number(form.partyId), amount: form.amount, notes: form.notes || null };
      case 'depreciation': return { docDate: date, amount: form.amount, costObjectId: form.costObjectId ? Number(form.costObjectId) : null, notes: form.notes || null };
      case 'opening': return { docDate: date, notes: form.notes || null, lines: openingLines.map(l => ({ kind: l.kind, amount: l.amount,
        financialAccountId: l.financialAccountId ? Number(l.financialAccountId) : null, partyId: l.partyId ? Number(l.partyId) : null,
        loanName: l.loanName || null, description: l.description || null })) };
    }
  }

  async function submit(post: boolean) {
    const problem = validate();
    if (problem) { setError(problem); return; }
    setBusy(true); setError('');
    try {
      const result = draftId
        ? await api<FinanceDocument>(`${base}/documents/${draftId}`, { method: 'PUT', body: payload(false) })
        : await api<FinanceDocument>(`${base}/${ENDPOINT[kind]}`, { method: 'POST', body: payload(post), headers: { 'Idempotency-Key': key } });
      if (draftId && post) await api(`${base}/documents/${draftId}/post`, { method: 'POST' });
      setKey(newIdempotencyKey());
      navigate(`/t/${tenant.id}/finance/records/${result.summary.id}`);
    } catch (e) {
      setError(describeError(e));
    } finally {
      setBusy(false);
    }
  }

  if (data.loading) return <Loading />;
  const partyOptions = (filter?: (p: Party) => boolean) => (
    <><option value="">—</option>{data.parties.filter(p => p.active && (!filter || filter(p))).map(p => <option key={p.id} value={p.id}>{p.name}</option>)}</>
  );
  const accountOptions = <><option value="">—</option>{accounts.map(a => <option key={a.id} value={a.id}>{a.name}</option>)}</>;
  const activityOptions = <><option value="">{t('overhead')}</option>{data.costObjects.filter(c => c.kind !== 'OVERHEAD').map(c => <option key={c.id} value={c.id}>{c.name}</option>)}</>;

  return (
    <>
      <div className="crop-page-head"><div><h2>{t(`kind.${kind}`)}</h2><p>{t(`help.${kind}`)}</p></div></div>
      <ErrorBanner message={error || data.error} />
      <form className="form-card form-grid" noValidate onSubmit={e => { e.preventDefault(); void submit(true); }}>
        <label>{t('date')}<input type="date" required value={form.docDate ?? ''} onChange={e => set('docDate', e.target.value)} /></label>

        {isInvoice && <>
          <label>{t('party')} <small>{tc('optional')}</small><select value={form.partyId ?? ''} onChange={e => set('partyId', e.target.value)}>{partyOptions()}</select></label>
          {!form.partyId && <label>{kind === 'expense' ? t('paidFrom') : t('receivedInto')}<select value={form.financialAccountId ?? ''} onChange={e => set('financialAccountId', e.target.value)}>{accountOptions}</select></label>}
          {form.partyId && <label>{t('dueDate')} <small>{tc('optional')}</small><input type="date" min={form.docDate} value={form.dueDate ?? ''} onChange={e => set('dueDate', e.target.value)} /></label>}
          <fieldset className="full lines">
            <legend>{t('lines')}</legend>
            {lines.map((l, i) => (
              <div className="line-row" key={i}>
                <label>{t('category')}<select value={l.categoryId} onChange={e => setLines(ls => ls.map((x, j) => (j === i ? { ...x, categoryId: e.target.value } : x)))}>
                  <option value="">—</option>{categories.map(c => <option key={c.id} value={c.id}>{categoryLabel(c)}</option>)}</select></label>
                <label>{t('description')}<input maxLength={240} value={l.description} onChange={e => setLines(ls => ls.map((x, j) => (j === i ? { ...x, description: e.target.value } : x)))} /></label>
                <label>{t('quantity')}<input inputMode="decimal" value={l.quantity} onChange={e => setLines(ls => ls.map((x, j) => (j === i ? { ...x, quantity: e.target.value } : x)))} /></label>
                <label>{t('rate')}<input inputMode="decimal" value={l.rate} onChange={e => setLines(ls => ls.map((x, j) => (j === i ? { ...x, rate: e.target.value } : x)))} /></label>
                <label>{t('amount')}<input inputMode="decimal" disabled={!!(l.quantity && l.rate)} value={l.quantity && l.rate ? lineAmount(l).toFixed(2) : l.amount}
                                           onChange={e => setLines(ls => ls.map((x, j) => (j === i ? { ...x, amount: e.target.value } : x)))} /></label>
                <label>{t('activity')}<select value={l.costObjectId} onChange={e => setLines(ls => ls.map((x, j) => (j === i ? { ...x, costObjectId: e.target.value } : x)))}>{activityOptions}</select></label>
                {lines.length > 1 && <button type="button" className="text-action" aria-label={tc('remove')} onClick={() => setLines(ls => ls.filter((_, j) => j !== i))}><Trash2 size={16} /></button>}
              </div>
            ))}
            <button type="button" className="secondary" onClick={() => setLines(ls => [...ls, emptyLine()])}><Plus size={16} />{t('addLine')}</button>
            <p className="total-line">{t('total')}: <b>{formatMoney(invoiceTotal, lang)}</b></p>
          </fieldset>
          {form.partyId && !draftId && <>
            <label>{kind === 'expense' ? t('paidNow') : t('receivedNow')} <small>{tc('optional')}</small><input inputMode="decimal" value={form.paidNow ?? ''} onChange={e => set('paidNow', e.target.value)} /></label>
            {form.paidNow && <label>{kind === 'expense' ? t('paidFrom') : t('receivedInto')}<select value={form.paidFrom ?? ''} onChange={e => set('paidFrom', e.target.value)}>{accountOptions}</select></label>}
          </>}
          <label>{t('reference')} <small>{tc('optional')}</small><input maxLength={100} value={form.referenceNo ?? ''} onChange={e => set('referenceNo', e.target.value)} /></label>
        </>}

        {(kind === 'payment' || kind === 'receipt') && <>
          <label>{t('party')}<select required value={form.partyId ?? ''} onChange={e => { set('partyId', e.target.value); setAllocations({}); }}>{partyOptions()}</select></label>
          <label>{kind === 'payment' ? t('paidFrom') : t('receivedInto')}<select required value={form.financialAccountId ?? ''} onChange={e => set('financialAccountId', e.target.value)}>{accountOptions}</select></label>
          <label>{t('amount')}<input required inputMode="decimal" value={form.amount ?? ''} onChange={e => { set('amount', e.target.value); autoAllocate(e.target.value); }} /></label>
          {party && <p className="hint">{kind === 'payment' ? t('weOwe', { amount: formatMoney(party.payable, lang) }) : t('theyOwe', { amount: formatMoney(party.receivable, lang) })}</p>}
          {openDocs.length > 0 && (
            <fieldset className="full">
              <legend>{t('matchTo')}</legend>
              {openDocs.map(d => (
                <label key={d.id} className="inline-fields">
                  <span>{d.number} · {t('open', { amount: formatMoney(d.openAmount, lang) })}</span>
                  <input inputMode="decimal" value={allocations[d.id] ?? ''} onChange={e => setAllocations(a => ({ ...a, [d.id]: e.target.value }))} />
                </label>
              ))}
              <p className="hint">{t('unmatchedIsAdvance')}</p>
            </fieldset>
          )}
        </>}

        {kind === 'transfer' && <>
          <label>{t('fromAccount')}<select value={form.financialAccountId ?? ''} onChange={e => set('financialAccountId', e.target.value)}>{accountOptions}</select></label>
          <label>{t('toAccount')}<select value={form.toAccountId ?? ''} onChange={e => set('toAccountId', e.target.value)}>{accountOptions}</select></label>
          <label>{t('amount')}<input inputMode="decimal" value={form.amount ?? ''} onChange={e => set('amount', e.target.value)} /></label>
        </>}

        {kind === 'owner' && <>
          <label>{t('direction')}<select value={form.direction} onChange={e => set('direction', e.target.value)}>
            <option value="IN">{t('ownerIn')}</option><option value="OUT">{t('ownerOut')}</option></select></label>
          <label>{t('account')}<select value={form.financialAccountId ?? ''} onChange={e => set('financialAccountId', e.target.value)}>{accountOptions}</select></label>
          <label>{t('amount')}<input inputMode="decimal" value={form.amount ?? ''} onChange={e => set('amount', e.target.value)} /></label>
        </>}

        {kind === 'loan' && <>
          <label>{t('lender')}<select value={form.partyId ?? ''} onChange={e => set('partyId', e.target.value)}>{partyOptions()}</select></label>
          <label>{t('loanName')}<input maxLength={140} value={form.loanName ?? ''} onChange={e => set('loanName', e.target.value)} /></label>
          <label>{t('receivedInto')}<select value={form.financialAccountId ?? ''} onChange={e => set('financialAccountId', e.target.value)}>{accountOptions}</select></label>
          <label>{t('amount')}<input inputMode="decimal" value={form.amount ?? ''} onChange={e => set('amount', e.target.value)} /></label>
        </>}

        {kind === 'loan-repayment' && <>
          <label>{t('loan')}<select value={form.loanId ?? ''} onChange={e => set('loanId', e.target.value)}><option value="">—</option>
            {data.loans.map(l => <option key={l.id} value={l.id}>{l.name} · {l.lenderName} · {formatMoney(l.outstanding, lang)}</option>)}</select></label>
          <label>{t('paidFrom')}<select value={form.financialAccountId ?? ''} onChange={e => set('financialAccountId', e.target.value)}>{accountOptions}</select></label>
          <label>{t('principal')}<input inputMode="decimal" value={form.principal ?? ''} onChange={e => set('principal', e.target.value)} /></label>
          <label>{t('interest')}<input inputMode="decimal" value={form.interest ?? ''} onChange={e => set('interest', e.target.value)} /></label>
        </>}

        {(kind === 'settlement' || kind === 'write-off') && <>
          <label>{t('party')}<select value={form.partyId ?? ''} onChange={e => set('partyId', e.target.value)}>{partyOptions()}</select></label>
          <label>{t('amount')}<input inputMode="decimal" value={form.amount ?? ''} onChange={e => set('amount', e.target.value)} /></label>
          {party && <p className="hint">{t('theyOwe', { amount: formatMoney(party.receivable, lang) })} · {t('weOwe', { amount: formatMoney(party.payable, lang) })}</p>}
        </>}

        {kind === 'depreciation' && <>
          <label>{t('amount')}<input inputMode="decimal" value={form.amount ?? ''} onChange={e => set('amount', e.target.value)} /></label>
          <label>{t('activity')}<select value={form.costObjectId ?? ''} onChange={e => set('costObjectId', e.target.value)}>{activityOptions}</select></label>
        </>}

        {kind === 'opening' && (
          <fieldset className="full lines">
            <legend>{t('openingLines')}</legend>
            {openingLines.map((l, i) => {
              const update = (patch: Partial<OpeningLine>) => setOpeningLines(ls => ls.map((x, j) => (j === i ? { ...x, ...patch } : x)));
              return (
                <div className="line-row" key={i}>
                  <label>{t('openingKind')}<select value={l.kind} onChange={e => update({ kind: e.target.value })}>
                    {['CASH', 'RECEIVABLE', 'PAYABLE', 'LOAN', 'ASSET'].map(k => <option key={k} value={k}>{t(`opening.${k}`)}</option>)}</select></label>
                  {l.kind === 'CASH' && <label>{t('account')}<select value={l.financialAccountId} onChange={e => update({ financialAccountId: e.target.value })}>{accountOptions}</select></label>}
                  {['RECEIVABLE', 'PAYABLE', 'LOAN'].includes(l.kind) && <label>{t('party')}<select value={l.partyId} onChange={e => update({ partyId: e.target.value })}>{partyOptions()}</select></label>}
                  {l.kind === 'LOAN' && <label>{t('loanName')}<input value={l.loanName} onChange={e => update({ loanName: e.target.value })} /></label>}
                  {l.kind === 'ASSET' && <label>{t('description')}<input value={l.description} onChange={e => update({ description: e.target.value })} /></label>}
                  <label>{t('amount')}<input inputMode="decimal" value={l.amount} onChange={e => update({ amount: e.target.value })} /></label>
                  {openingLines.length > 1 && <button type="button" className="text-action" aria-label={tc('remove')} onClick={() => setOpeningLines(ls => ls.filter((_, j) => j !== i))}><Trash2 size={16} /></button>}
                </div>
              );
            })}
            <button type="button" className="secondary" onClick={() => setOpeningLines(ls => [...ls, { kind: 'CASH', financialAccountId: '', partyId: '', loanName: '', description: '', amount: '' }])}>
              <Plus size={16} />{t('addLine')}</button>
          </fieldset>
        )}

        <label className="full">{t('notes')} <small>{tc('optional')}</small><input maxLength={500} value={form.notes ?? ''} onChange={e => set('notes', e.target.value)} /></label>
        <div className="form-actions full">
          <button type="button" className="secondary" onClick={() => navigate(-1)}>{tc('cancel')}</button>
          {isInvoice && <button type="button" className="secondary" disabled={busy} onClick={() => void submit(false)}>{t('saveDraft')}</button>}
          <button className="primary" disabled={busy}>{busy ? tc('loading') : t('post')}</button>
        </div>
      </form>
    </>
  );
}

