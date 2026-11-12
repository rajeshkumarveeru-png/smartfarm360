import { type CSSProperties, type ReactNode, useCallback, useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { ChevronRight, Hammer, Pencil, Plus, Receipt, RotateCcw, ShoppingBag, Tag, Trash2, TrendingDown, TrendingUp, X } from 'lucide-react';
import { api } from '../api/client';
import type { Page } from '../api/types';
import { useTenant } from '../auth/useCurrentTenant';
import { ErrorBanner } from '../components/common';
import { currentLanguage, useNs } from '../i18n';
import { describeError } from '../lib/errors';
import { formatDate, formatMoney, today } from '../lib/format';
import { can } from '../lib/nav';
import type { CategoryBreakdown, CategoryRow } from '../reports/types';
import type { DocumentSummary } from './types';
import QuickExpenseForm from './QuickExpenseForm';
import { useCategoryLabel } from './useFinanceData';

const PAGE = 10;

/**
 * Something else that happened on the same date line as the records, such as work done on a crop. {@code documentIds}
 * are the records it made (its paid-work bill, its stock issue): they are shown on this row, not as rows of their own.
 */
export type TimelineEntry = { key: string; date: string; title: string; sub?: string; note?: string; documentIds: number[]; muted?: boolean; actions?: ReactNode };
const KIND: Partial<Record<DocumentSummary['type'], 'expense' | 'sale'>> = { BILL: 'expense', SALE: 'sale' };
const isIn = (d: DocumentSummary) => d.type === 'SALE' || d.type === 'RECEIPT';
/** The start of the category totals: ten years back, within the longest range the reports accept. */
const tenYearsAgo = () => { const d = new Date(); d.setFullYear(d.getFullYear() - 10); d.setDate(d.getDate() + 2); return d.toISOString().slice(0, 10); };
/** Category colours, in turn, so a category keeps the same colour in the bar and on its records. */
const HUES = ['16 185 129', '14 165 233', '245 158 11', '139 92 246', '244 63 94', '20 184 166', '234 88 12', '99 102 241'];

/**
 * The money records of one farm or activity, with the ways to fix a mistake: a draft can be edited or discarded; a posted
 * record is never deleted (ADR 0004) but reversed, and "Correct" reverses it and opens a copy to save again.
 * {@code filter} is the records query (for example "farmId=2" or "costObjectId=7"), which the cost-by-category report
 * takes too: the category bar shows what was spent on each category here, and picking one lists only its records.
 * {@code prefill} is added to the new expense and sale links so they are booked to the same farm or activity;
 * {@code newButtons} false leaves those links to the page, and {@code actions} replaces them. {@code timeline} merges other
 * entries (work done) into the same list by date; then the records are read in one page.
 */
export default function RecordsPanel({ filter, prefill, title, newButtons = true, actions, timeline }: {
  filter: string; prefill: string; title: string; newButtons?: boolean; actions?: ReactNode; timeline?: TimelineEntry[];
}) {
  const tenant = useTenant();
  const navigate = useNavigate();
  const t = useNs('fin');
  const lang = currentLanguage();
  const categoryLabel = useCategoryLabel();
  const base = `/api/v1/tenants/${tenant.id}/finance`;
  const canWrite = can(tenant, 'FINANCE_WRITE');
  const canReverse = can(tenant, 'FINANCE_REVERSE');
  const [records, setRecords] = useState<DocumentSummary[] | null>(null);
  const [paging, setPaging] = useState({ page: 0, total: 0 });
  const [costs, setCosts] = useState<CategoryBreakdown | null>(null);
  const [category, setCategory] = useState<number | null>(null);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [error, setError] = useState('');
  // The expense form opens in place, so an expense is added without leaving the page
  const [adding, setAdding] = useState(false);

  const merged = !!timeline;
  const query = `${filter}${category != null ? `&categoryId=${category}` : ''}`;
  const load = useCallback((page: number) => {
    api<Page<DocumentSummary>>(`${base}/documents?${query}&page=${page}&size=${merged ? 100 : PAGE}`)
      .then(p => { setRecords(old => (page === 0 || !old ? p.items : [...old, ...p.items])); setPaging({ page, total: p.totalItems }); })
      .catch(e => setError(describeError(e)));
  }, [base, query, merged]);
  useEffect(() => load(0), [load]);

  const loadCosts = useCallback(() => {
    api<CategoryBreakdown>(`${base}/reports/categories?${filter}&kind=COSTS&from=${tenYearsAgo()}&to=${today()}`).then(setCosts).catch(() => setCosts(null));
  }, [base, filter]);
  useEffect(loadCosts, [loadCosts]);

  async function act(d: DocumentSummary, action: 'discard' | 'reverse' | 'correct') {
    const question = action === 'discard' ? t('confirmDiscard') : action === 'reverse' ? t('confirmReverse') : t('confirmCorrect');
    if (!window.confirm(question)) return;
    setBusyId(d.id); setError('');
    try {
      if (action === 'discard') await api(`${base}/documents/${d.id}/cancel`, { method: 'POST' });
      else await api(`${base}/documents/${d.id}/reverse`, { method: 'POST', body: { reversalDate: today() } });
      if (action === 'correct') navigate(`/t/${tenant.id}/finance/new/${KIND[d.type]}?copy=${d.id}&${prefill}`);
      else { load(0); loadCosts(); }
    } catch (e) {
      setError(describeError(e));
    } finally {
      setBusyId(null);
    }
  }

  const money = (v: number) => formatMoney(v, lang);
  const rows = (costs?.rows ?? []).filter((r): r is CategoryRow & { categoryId: number } => r.categoryId != null);
  const hueOf = (id: number) => HUES[Math.max(0, rows.findIndex(r => r.categoryId === id)) % HUES.length];
  const picked = rows.find(r => r.categoryId === category);

  return (
    <div className="db-card records-panel">
      <div className="db-card-head">
        <h3>{title} {paging.total > 0 && <span className="count">{paging.total}</span>}</h3>
        {actions ? <div className="rp-new">{actions}</div> : canWrite && newButtons && <div className="rp-new">
          <Link className="secondary" to={`/t/${tenant.id}/finance/new/sale?${prefill}`}><ShoppingBag size={15} aria-hidden />{t('newSale')}</Link>
          {!adding && <button type="button" className="primary" onClick={() => setAdding(true)}><Plus size={15} aria-hidden />{t('newExpense')}</button>}
        </div>}
      </div>
      <ErrorBanner message={error} />
      {adding && <QuickExpenseForm prefill={prefill} onCancel={() => setAdding(false)} onSaved={() => { setAdding(false); load(0); loadCosts(); }} />}

      {rows.length > 0 && costs && (
        <div className="rp-cats" role="group" aria-label={t('byCategory')}>
          <button type="button" className="rp-cat rp-cat-all" aria-pressed={category == null} onClick={() => setCategory(null)}>
            <span>{t('allCategories')}</span><b>{money(costs.total)}</b>
          </button>
          {rows.map(r => (
            <button key={r.categoryId} type="button" className="rp-cat" aria-pressed={category === r.categoryId} style={{ '--hue': hueOf(r.categoryId) } as CSSProperties}
              onClick={() => setCategory(c => (c === r.categoryId ? null : r.categoryId))}>
              <i className="rp-dot" aria-hidden /><span>{categoryLabel({ code: r.categoryCode, name: r.categoryName })}</span><b>{money(r.amount)}</b>
              {r.share != null && <small>{r.share}%</small>}
              <span className="rp-cat-bar" aria-hidden><span style={{ width: `${Math.min(100, Math.max(0, r.share ?? 0))}%` }} /></span>
            </button>
          ))}
        </div>
      )}
      {picked && (
        <div className="rp-focus" role="status" style={{ '--hue': hueOf(picked.categoryId) } as CSSProperties}>
          <span className="rp-focus-icon" aria-hidden><Tag size={18} /></span>
          <span className="rp-focus-text">
            <strong>{t('categorySpent', { category: categoryLabel({ code: picked.categoryCode, name: picked.categoryName }) })}</strong>
            <small>{t('categoryShare', { percent: String(picked.share ?? 0), count: String(paging.total) })}</small>
          </span>
          <b className="rp-focus-amount">{money(picked.amount)}</b>
          <button type="button" className="rp-btn" aria-label={t('allCategories')} title={t('allCategories')} onClick={() => setCategory(null)}><X size={15} aria-hidden /></button>
        </div>
      )}

      {records && (() => {
        const owned = new Set((timeline ?? []).flatMap(e => e.documentIds));
        const byId = new Map(records.map(d => [d.id, d]));
        const entries = (timeline ?? []).filter(e => category == null || e.documentIds.some(id => byId.has(id)));
        const rowsInOrder: ({ date: string; doc: DocumentSummary } | { date: string; entry: TimelineEntry })[] = [
          ...records.filter(d => !owned.has(d.id)).map(d => ({ date: d.docDate, doc: d })),
          ...entries.map(e => ({ date: e.date, entry: e })),
        ].sort((a, b) => b.date.localeCompare(a.date));
        if (rowsInOrder.length === 0) return <div className="db-all-clear muted"><Receipt size={26} aria-hidden /><p className="hint">{t('noRecordsHere')}</p></div>;
        return <>
        <ul className="fw-records">
          {rowsInOrder.map(row => {
            if ('entry' in row) {
              const e = row.entry;
              const docs = e.documentIds.map(id => byId.get(id)).filter((d): d is DocumentSummary => !!d);
              const money = docs.filter(d => d.type === 'BILL' || d.type === 'SALE');
              const amount = money.reduce((sum, d) => sum + d.totalAmount, 0);
              const cats = money.flatMap(d => d.categories ?? []);
              return (
                <li key={e.key} className={`rp-work${e.muted ? ' rp-void' : ''}`}>
                  <span className="rp-row">
                    <span className="fw-type work" aria-hidden><Hammer size={15} /></span>
                    <span className="fw-record-main"><strong>{e.title}</strong>
                      <small>{formatDate(e.date, lang)}{e.sub ? ` · ${e.sub}` : ''}</small>
                      {e.note && <small className="rp-note">{e.note}</small>}
                      {cats.length > 0 && <span className="rp-tags">{cats.map(c => (
                        <span key={c.categoryId} className="rp-tag" style={{ '--hue': hueOf(c.categoryId) } as CSSProperties}>{categoryLabel(c)}</span>))}</span>}
                    </span>
                    {money.length > 0 && <span className="fw-record-side"><b>{formatMoney(amount, lang)}</b>
                      {money.map(d => <Link key={d.id} className="rp-doc-link" to={`/t/${tenant.id}/finance/records/${d.id}`}>{d.number ?? t(`type.${d.type}`)}</Link>)}</span>}
                  </span>
                  {e.actions && <span className="rp-actions">{e.actions}</span>}
                </li>
              );
            }
            const d = row.doc;
            const kind = KIND[d.type];
            const posted = d.status === 'POSTED';
            return (
              <li key={d.id} className={d.status === 'REVERSED' || d.status === 'CANCELLED' ? 'rp-void' : ''}>
                <Link to={`/t/${tenant.id}/finance/records/${d.id}`} className="rp-row">
                  <span className={`fw-type ${isIn(d) ? 'in' : 'out'}`} aria-hidden>{isIn(d) ? <TrendingUp size={15} /> : <TrendingDown size={15} />}</span>
                  <span className="fw-record-main"><strong>{t(`type.${d.type}`)}{d.number ? ` · ${d.number}` : ''}</strong>
                    <small>{formatDate(d.docDate, lang)}{d.partyName ? ` · ${d.partyName}` : d.financialAccountName ? ` · ${d.financialAccountName}` : ''}</small>
                    {(d.categories?.length ?? 0) > 0 && <span className="rp-tags">{d.categories!.map(c => (
                      <span key={c.categoryId} className="rp-tag" style={{ '--hue': hueOf(c.categoryId) } as CSSProperties}>
                        {categoryLabel(c)}{d.categories!.length > 1 && <em> {money(c.amount)}</em>}</span>))}</span>}
                  </span>
                  <span className="fw-record-side"><b>{money(d.totalAmount)}</b>
                    <small className={`status ${posted ? 'completed' : 'planned'}`}>
                      {posted && d.paymentStatus ? t(`paymentStatus.${d.paymentStatus}`) : t(`status.${d.status}`)}</small></span>
                  <ChevronRight className="db-chevron" size={16} aria-hidden />
                </Link>
                {canWrite && (d.status === 'DRAFT' || (posted && canReverse)) && (
                  <span className="rp-actions">
                    {d.status === 'DRAFT' && kind && <Link className="rp-btn" to={`/t/${tenant.id}/finance/new/${kind}?draft=${d.id}`} title={t('edit')} aria-label={`${t('edit')} ${d.number ?? ''}`}><Pencil size={15} aria-hidden /></Link>}
                    {d.status === 'DRAFT' && <button type="button" className="rp-btn danger-btn" disabled={busyId === d.id} title={t('cancelDraft')} aria-label={`${t('cancelDraft')} ${d.number ?? ''}`}
                      onClick={() => void act(d, 'discard')}><Trash2 size={15} aria-hidden /></button>}
                    {posted && kind && <button type="button" className="rp-btn" disabled={busyId === d.id} title={t('correct')} aria-label={`${t('correct')} ${d.number ?? ''}`}
                      onClick={() => void act(d, 'correct')}><Pencil size={15} aria-hidden /></button>}
                    {posted && <button type="button" className="rp-btn danger-btn" disabled={busyId === d.id} title={t('reverse')} aria-label={`${t('reverse')} ${d.number ?? ''}`}
                      onClick={() => void act(d, 'reverse')}><RotateCcw size={15} aria-hidden /></button>}
                  </span>
                )}
              </li>
            );
          })}
        </ul>
        {records.length < paging.total && <button type="button" className="secondary fw-more" onClick={() => load(paging.page + 1)}>{t('showMore')}</button>}
      </>;
      })()}
    </div>
  );
}
