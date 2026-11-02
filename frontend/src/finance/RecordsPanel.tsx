import { useCallback, useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { ChevronRight, Pencil, Plus, Receipt, RotateCcw, ShoppingBag, Trash2, TrendingDown, TrendingUp } from 'lucide-react';
import { api } from '../api/client';
import type { Page } from '../api/types';
import { useTenant } from '../auth/useCurrentTenant';
import { ErrorBanner } from '../components/common';
import { currentLanguage, useNs } from '../i18n';
import { describeError } from '../lib/errors';
import { formatDate, formatMoney, today } from '../lib/format';
import { can } from '../lib/nav';
import type { DocumentSummary } from './types';

const PAGE = 10;
const KIND: Partial<Record<DocumentSummary['type'], 'expense' | 'sale'>> = { BILL: 'expense', SALE: 'sale' };
const isIn = (d: DocumentSummary) => d.type === 'SALE' || d.type === 'RECEIPT';

/**
 * The money records of one farm or activity, with the ways to fix a mistake: a draft can be edited or discarded; a posted
 * record is never deleted (ADR 0004) but reversed, and "Correct" reverses it and opens a copy to save again.
 * {@code filter} is the records query (for example "farmId=2" or "costObjectId=7"); {@code prefill} is added to the new
 * expense and sale links so they are booked to the same farm or activity; {@code newButtons} false leaves those links to the page.
 */
export default function RecordsPanel({ filter, prefill, title, newButtons = true }: { filter: string; prefill: string; title: string; newButtons?: boolean }) {
  const tenant = useTenant();
  const navigate = useNavigate();
  const t = useNs('fin');
  const lang = currentLanguage();
  const base = `/api/v1/tenants/${tenant.id}/finance`;
  const canWrite = can(tenant, 'FINANCE_WRITE');
  const canReverse = can(tenant, 'FINANCE_REVERSE');
  const [records, setRecords] = useState<DocumentSummary[] | null>(null);
  const [paging, setPaging] = useState({ page: 0, total: 0 });
  const [busyId, setBusyId] = useState<number | null>(null);
  const [error, setError] = useState('');

  const load = useCallback((page: number) => {
    api<Page<DocumentSummary>>(`${base}/documents?${filter}&page=${page}&size=${PAGE}`)
      .then(p => { setRecords(old => (page === 0 || !old ? p.items : [...old, ...p.items])); setPaging({ page, total: p.totalItems }); })
      .catch(e => setError(describeError(e)));
  }, [base, filter]);
  useEffect(() => load(0), [load]);

  async function act(d: DocumentSummary, action: 'discard' | 'reverse' | 'correct') {
    const question = action === 'discard' ? t('confirmDiscard') : action === 'reverse' ? t('confirmReverse') : t('confirmCorrect');
    if (!window.confirm(question)) return;
    setBusyId(d.id); setError('');
    try {
      if (action === 'discard') await api(`${base}/documents/${d.id}/cancel`, { method: 'POST' });
      else await api(`${base}/documents/${d.id}/reverse`, { method: 'POST', body: { reversalDate: today() } });
      if (action === 'correct') navigate(`/t/${tenant.id}/finance/new/${KIND[d.type]}?copy=${d.id}&${prefill}`);
      else load(0);
    } catch (e) {
      setError(describeError(e));
    } finally {
      setBusyId(null);
    }
  }

  return (
    <div className="db-card records-panel">
      <div className="db-card-head">
        <h3>{title} {paging.total > 0 && <span className="count">{paging.total}</span>}</h3>
        {canWrite && newButtons && <div className="rp-new">
          <Link className="secondary" to={`/t/${tenant.id}/finance/new/sale?${prefill}`}><ShoppingBag size={15} aria-hidden />{t('newSale')}</Link>
          <Link className="primary" to={`/t/${tenant.id}/finance/new/expense?${prefill}`}><Plus size={15} aria-hidden />{t('newExpense')}</Link>
        </div>}
      </div>
      <ErrorBanner message={error} />
      {records && records.length === 0 && <div className="db-all-clear muted"><Receipt size={26} aria-hidden /><p className="hint">{t('noRecordsHere')}</p></div>}
      {records && records.length > 0 && <>
        <ul className="fw-records">
          {records.map(d => {
            const kind = KIND[d.type];
            const posted = d.status === 'POSTED';
            return (
              <li key={d.id} className={d.status === 'REVERSED' || d.status === 'CANCELLED' ? 'rp-void' : ''}>
                <Link to={`/t/${tenant.id}/finance/records/${d.id}`} className="rp-row">
                  <span className={`fw-type ${isIn(d) ? 'in' : 'out'}`} aria-hidden>{isIn(d) ? <TrendingUp size={15} /> : <TrendingDown size={15} />}</span>
                  <span className="fw-record-main"><strong>{t(`type.${d.type}`)}{d.number ? ` · ${d.number}` : ''}</strong>
                    <small>{formatDate(d.docDate, lang)}{d.partyName ? ` · ${d.partyName}` : d.financialAccountName ? ` · ${d.financialAccountName}` : ''}</small></span>
                  <span className="fw-record-side"><b>{formatMoney(d.totalAmount, lang)}</b>
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
      </>}
    </div>
  );
}
