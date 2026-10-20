import { useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { api } from '../api/client';
import type { Page } from '../api/types';
import { useTenant } from '../auth/useCurrentTenant';
import { EmptyState, ErrorBanner, Loading } from '../components/common';
import { currentLanguage, useNs } from '../i18n';
import { describeError } from '../lib/errors';
import { formatDate, formatMoney } from '../lib/format';
import { DOC_TYPES, type DocumentSummary } from './types';

const PAGE_SIZE = 25;

/** Compact list of finance records; each row opens the record. */
export function RecordList({ items }: { items: DocumentSummary[] }) {
  const tenant = useTenant();
  const t = useNs('fin');
  const lang = currentLanguage();
  if (!items.length) return <EmptyState>{t('noRecords')}</EmptyState>;
  return (
    <div className="activity-list">
      {items.map(d => (
        <Link key={d.id} className="activity-row record-link" to={`/t/${tenant.id}/finance/records/${d.id}`}>
          <div className="cycle-main">
            <h4>{t(`type.${d.type}`)} {d.number ? `· ${d.number}` : ''}</h4>
            <p>{[d.partyName, d.financialAccountName].filter(Boolean).join(' · ') || '—'}</p>
            <small>{formatDate(d.docDate, lang)}{d.needsReview ? ` · ${t('needsReview')}` : ''}</small>
          </div>
          <div className="value-side">
            <b>{formatMoney(d.totalAmount, lang)}</b>
            <span className={`status ${d.status === 'POSTED' ? (d.paymentStatus === 'PAID' || !d.paymentStatus ? 'completed' : 'planned') : ''}`}>
              {d.status === 'POSTED' && d.paymentStatus ? t(`paymentStatus.${d.paymentStatus}`) : t(`status.${d.status}`)}
            </span>
            {d.paymentStatus && d.openAmount > 0 && d.status === 'POSTED' && <small>{t('open', { amount: formatMoney(d.openAmount, lang) })}</small>}
          </div>
        </Link>
      ))}
    </div>
  );
}

export default function RecordsPage() {
  const tenant = useTenant();
  const t = useNs('fin');
  const tc = useNs('common');
  const [search] = useSearchParams();
  const [filters, setFilters] = useState({ type: '', status: '', from: '', to: '', partyId: search.get('partyId') ?? '' });
  const [page, setPage] = useState(0);
  const [data, setData] = useState<Page<DocumentSummary> | null>(null);
  const [error, setError] = useState('');

  useEffect(() => {
    let alive = true;
    const q = new URLSearchParams({ page: String(page), size: String(PAGE_SIZE) });
    Object.entries(filters).forEach(([k, v]) => v && q.set(k, v));
    setError('');
    api<Page<DocumentSummary>>(`/api/v1/tenants/${tenant.id}/finance/documents?${q}`)
      .then(d => alive && setData(d)).catch(e => alive && setError(describeError(e)));
    return () => { alive = false; };
  }, [tenant.id, filters, page]);

  const set = (k: keyof typeof filters, v: string) => { setPage(0); setFilters(f => ({ ...f, [k]: v })); };
  return (
    <>
      <div className="crop-page-head"><div><h2>{t('records')}</h2></div></div>
      <div className="form-card form-grid filters">
        <label>{t('recordType')}
          <select value={filters.type} onChange={e => set('type', e.target.value)}>
            <option value="">{t('all')}</option>{DOC_TYPES.map(d => <option key={d} value={d}>{t(`type.${d}`)}</option>)}
          </select>
        </label>
        <label>{tc('status')}
          <select value={filters.status} onChange={e => set('status', e.target.value)}>
            <option value="">{t('all')}</option>{['DRAFT', 'POSTED', 'REVERSED', 'CANCELLED'].map(s => <option key={s} value={s}>{t(`status.${s}`)}</option>)}
          </select>
        </label>
        <label>{t('from')}<input type="date" value={filters.from} onChange={e => set('from', e.target.value)} /></label>
        <label>{t('to')}<input type="date" value={filters.to} onChange={e => set('to', e.target.value)} /></label>
      </div>
      <ErrorBanner message={error} />
      {!data ? <Loading /> : <RecordList items={data.items} />}
      {data && data.totalPages > 1 && (
        <div className="pager">
          <button className="secondary" disabled={page === 0} onClick={() => setPage(p => p - 1)}>{tc('previous')}</button>
          <span>{page + 1} / {data.totalPages}</span>
          <button className="secondary" disabled={page + 1 >= data.totalPages} onClick={() => setPage(p => p + 1)}>{tc('next')}</button>
        </div>
      )}
    </>
  );
}
