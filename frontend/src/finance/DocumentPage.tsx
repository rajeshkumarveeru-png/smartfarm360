import { useCallback, useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api } from '../api/client';
import type { Page } from '../api/types';
import { useTenant } from '../auth/useCurrentTenant';
import { ErrorBanner, Loading } from '../components/common';
import { currentLanguage, useNs } from '../i18n';
import { describeError } from '../lib/errors';
import { formatDate, formatMoney, formatNumber } from '../lib/format';
import { can } from '../lib/nav';
import type { DocumentSummary, FinanceDocument } from './types';
import { useCategoryLabel } from './useFinanceData';

/** One record with its lines and matches, and the actions allowed on it. */
export default function DocumentPage() {
  const tenant = useTenant();
  const { documentId } = useParams();
  const t = useNs('fin');
  const tc = useNs('common');
  const ts = useNs('stock');
  const categoryLabel = useCategoryLabel();
  const lang = currentLanguage();
  const base = `/api/v1/tenants/${tenant.id}/finance`;
  const [doc, setDoc] = useState<FinanceDocument | null>(null);
  const [open, setOpen] = useState<DocumentSummary[]>([]);
  const [error, setError] = useState('');
  const [reversalDate, setReversalDate] = useState('');
  const [match, setMatch] = useState({ documentId: '', amount: '' });

  const load = useCallback(async () => {
    setError('');
    try {
      const d = await api<FinanceDocument>(`${base}/documents/${documentId}`);
      setDoc(d);
      const s = d.summary;
      const targetType = s.type === 'PAYMENT' ? 'BILL' : s.type === 'RECEIPT' ? 'SALE' : null;
      if (targetType && s.status === 'POSTED' && s.openAmount > 0 && s.partyId) {
        const page = await api<Page<DocumentSummary>>(`${base}/documents?type=${targetType}&status=POSTED&partyId=${s.partyId}&size=100`);
        setOpen(page.items.filter(x => x.openAmount > 0));
      } else {
        setOpen([]);
      }
    } catch (e) {
      setError(describeError(e));
    }
  }, [base, documentId]);
  useEffect(() => { void load(); }, [load]);

  async function act(path: string, body?: unknown) {
    setError('');
    try { await api(`${base}${path}`, { method: 'POST', body }); await load(); } catch (e) { setError(describeError(e)); }
  }

  async function unmatch(allocationId: number) {
    setError('');
    try { await api(`${base}/allocations/${allocationId}`, { method: 'DELETE' }); await load(); } catch (e) { setError(describeError(e)); }
  }

  if (!doc) return error ? <ErrorBanner message={error} /> : <Loading />;
  const s = doc.summary;
  const canWrite = can(tenant, 'FINANCE_WRITE');
  const canReverse = can(tenant, 'FINANCE_REVERSE');
  return (
    <>
      <div className="crop-page-head">
        <div>
          <h2>{t(`type.${s.type}`)} {s.number ?? ''}</h2>
          <p>{formatDate(s.docDate, lang)} · {t(`status.${s.status}`)}{s.paymentStatus ? ` · ${t(`paymentStatus.${s.paymentStatus}`)}` : ''}</p>
          {s.needsReview && <p className="low-stock-label">{t('needsReviewHelp')}</p>}
        </div>
        <Link className="secondary" to={`/t/${tenant.id}/finance/records`}>{tc('back')}</Link>
      </div>
      <ErrorBanner message={error} />
      <div className="finance-stats">
        <div className="finance-stat"><span>{t('total')}</span><b>{formatMoney(s.totalAmount, lang)}</b></div>
        {s.paymentStatus && <div className="finance-stat"><span>{t('settled')}</span><b>{formatMoney(s.settledAmount, lang)}</b></div>}
        {s.paymentStatus && <div className="finance-stat"><span>{t('openAmount')}</span><b>{formatMoney(s.openAmount, lang)}</b></div>}
        {doc.principalAmount != null && <div className="finance-stat"><span>{t('principal')}</span><b>{formatMoney(doc.principalAmount, lang)}</b></div>}
        {doc.interestAmount != null && <div className="finance-stat"><span>{t('interest')}</span><b>{formatMoney(doc.interestAmount, lang)}</b></div>}
      </div>
      <dl className="detail-list">
        {s.partyName && <><dt>{t('party')}</dt><dd>{s.partyName}</dd></>}
        {s.financialAccountName && <><dt>{t('account')}</dt><dd>{s.financialAccountName}</dd></>}
        {doc.toFinancialAccountName && <><dt>{t('toAccount')}</dt><dd>{doc.toFinancialAccountName}</dd></>}
        {doc.loanName && <><dt>{t('loan')}</dt><dd>{doc.loanName}</dd></>}
        {s.dueDate && <><dt>{t('dueDate')}</dt><dd>{formatDate(s.dueDate, lang)}</dd></>}
        {doc.referenceNo && <><dt>{t('reference')}</dt><dd>{doc.referenceNo}</dd></>}
        {doc.notes && <><dt>{t('notes')}</dt><dd>{doc.notes}</dd></>}
        {doc.reversalDate && <><dt>{t('reversedOn')}</dt><dd>{formatDate(doc.reversalDate, lang)}</dd></>}
      </dl>

      {doc.lines.length > 0 && (
        <div className="table-scroll"><table className="data-table">
          <thead><tr><th>{t('category')}</th><th>{t('description')}</th><th>{t('quantity')}</th><th>{t('activity')}</th><th className="num">{t('amount')}</th></tr></thead>
          <tbody>
            {doc.lines.map(l => (
              <tr key={l.id}>
                <td>{l.categoryId ? categoryLabel({ code: l.categoryCode, name: l.categoryName }) : t(`ledger.${l.accountCode}`)}</td>
                <td>{l.description ?? ''}</td>
                <td>{l.quantity != null ? `${formatNumber(l.quantity, lang)} ${l.unit ?? ''} × ${formatMoney(l.rate, lang)}` : ''}</td>
                <td>{l.costObjectName ?? (/^[45]/.test(l.accountCode) ? t('overhead') : '')}</td>
                <td className="num">{formatMoney(l.amount, lang)}</td>
              </tr>
            ))}
          </tbody>
        </table></div>
      )}

      {doc.movements.length > 0 && (
        <>
          <h3>{ts('stockMovements')}</h3>
          <div className="table-scroll"><table className="data-table">
            <thead><tr><th>{ts('item')}</th><th>{ts('where')}</th><th>{ts('movementLabel')}</th><th className="num">{ts('quantity')}</th><th className="num">{ts('value')}</th></tr></thead>
            <tbody>
              {doc.movements.map(m => (
                <tr key={m.id}>
                  <td><Link to={`/t/${tenant.id}/stock/items/${m.itemId}`}>{m.itemName}</Link></td>
                  <td>{m.locationName}{m.lotNo ? ` (${m.lotNo})` : ''}</td>
                  <td>{ts(`movement.${m.movementType}`)}</td>
                  <td className="num">{m.quantity > 0 ? '+' : ''}{formatNumber(m.quantity, lang, 4)} {ts(`unit.${m.baseUnit}`, { defaultValue: m.baseUnit })}</td>
                  <td className="num">{formatMoney(m.value, lang)}</td>
                </tr>
              ))}
            </tbody>
          </table></div>
        </>
      )}

      {doc.allocations.length > 0 && (
        <>
          <h3>{t('matches')}</h3>
          <div className="activity-list">
            {doc.allocations.map(a => {
              const otherId = a.sourceDocumentId === s.id ? a.targetDocumentId : a.sourceDocumentId;
              const otherNumber = a.sourceDocumentId === s.id ? a.targetNumber : a.sourceNumber;
              return (
                <article className="activity-row" key={a.id}>
                  <div className="cycle-main"><Link to={`/t/${tenant.id}/finance/records/${otherId}`}>{otherNumber}</Link><small> · {formatDate(a.allocationDate, lang)}</small></div>
                  <div className="value-side"><b>{formatMoney(a.amount, lang)}</b>
                    {canReverse && <button className="text-action" onClick={() => void unmatch(a.id)}>{t('unmatch')}</button>}</div>
                </article>
              );
            })}
          </div>
        </>
      )}

      {canWrite && open.length > 0 && (
        <form className="form-card form-grid" onSubmit={e => { e.preventDefault(); void act(`/documents/${s.id}/allocations`, { documentId: Number(match.documentId), amount: match.amount }); }}>
          <label>{t('matchTo')}
            <select required value={match.documentId} onChange={e => setMatch({ ...match, documentId: e.target.value })}>
              <option value="">—</option>
              {open.map(o => <option key={o.id} value={o.id}>{o.number} · {t('open', { amount: formatMoney(o.openAmount, lang) })}</option>)}
            </select>
          </label>
          <label>{t('amount')}<input required inputMode="decimal" value={match.amount} onChange={e => setMatch({ ...match, amount: e.target.value })} /></label>
          <div className="form-actions full"><button className="primary">{t('match')}</button></div>
        </form>
      )}

      <div className="form-actions">
        {s.status === 'DRAFT' && canWrite && <>
          <Link className="secondary" to={`/t/${tenant.id}/finance/new/${s.type === 'BILL' ? 'expense' : 'sale'}?draft=${s.id}`}>{tc('edit')}</Link>
          <button className="secondary" onClick={() => void act(`/documents/${s.id}/cancel`)}>{t('cancelDraft')}</button>
          <button className="primary" onClick={() => void act(`/documents/${s.id}/post`)}>{t('post')}</button>
        </>}
        {s.status === 'POSTED' && canWrite && s.type === 'SALE' && doc.movements.length > 0 && (
          <Link className="secondary" to={`/t/${tenant.id}/stock/new/sale-return?original=${s.id}`}>{ts('kind.sale-return')}</Link>
        )}
        {s.status === 'POSTED' && canWrite && s.type === 'BILL' && s.partyId && doc.movements.length > 0 && (
          <Link className="secondary" to={`/t/${tenant.id}/stock/new/purchase-return?original=${s.id}&party=${s.partyId}`}>{ts('kind.purchase-return')}</Link>
        )}
        {s.status === 'POSTED' && canReverse && (
          <form className="inline-form" onSubmit={e => {
            e.preventDefault();
            if (window.confirm(t('confirmReverse'))) void act(`/documents/${s.id}/reverse`, reversalDate ? { reversalDate } : {});
          }}>
            <label>{t('reversalDate')}<input type="date" min={s.docDate} value={reversalDate} onChange={e => setReversalDate(e.target.value)} /></label>
            <button className="secondary">{t('reverse')}</button>
          </form>
        )}
      </div>
    </>
  );
}
