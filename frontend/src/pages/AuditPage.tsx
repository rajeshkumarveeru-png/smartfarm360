import { useEffect, useState } from 'react';
import { api } from '../api/client';
import type { AuditEntry, Page } from '../api/types';
import { useTenant } from '../auth/useCurrentTenant';
import { EmptyState, ErrorBanner, Loading } from '../components/common';
import { currentLanguage, useNs } from '../i18n';
import { describeError } from '../lib/errors';
import { formatDateTime } from '../lib/format';

const PAGE_SIZE = 25;

export default function AuditPage() {
  const tenant = useTenant();
  const t = useNs('audit');
  const ta = useNs('auditAction');
  const tc = useNs('common');
  const lang = currentLanguage();
  const [page, setPage] = useState(0);
  const [data, setData] = useState<Page<AuditEntry> | null>(null);
  const [error, setError] = useState('');

  useEffect(() => {
    let alive = true;
    setError('');
    api<Page<AuditEntry>>(`/api/v1/tenants/${tenant.id}/audit?page=${page}&size=${PAGE_SIZE}`)
      .then(d => alive && setData(d))
      .catch(e => alive && setError(describeError(e)));
    return () => { alive = false; };
  }, [tenant.id, page]);

  if (error) return <ErrorBanner message={error} />;
  if (!data) return <Loading />;
  return (
    <section className="crop-page">
      <div className="crop-page-head"><div><h2>{t('title')}</h2><p>{t('intro')}</p></div></div>
      <div className="activity-list">
        {data.items.map(entry => (
          <article className="activity-row" key={entry.id}>
            <div className="cycle-main">
              <h4>{ta(entry.action)}</h4>
              <p>{t('by', { who: entry.actorEmail ?? t('system') })}</p>
            </div>
            <div className="value-side"><small>{formatDateTime(entry.occurredAt, lang)}</small></div>
          </article>
        ))}
        {!data.items.length && <EmptyState>{tc('empty')}</EmptyState>}
      </div>
      {data.totalPages > 1 && (
        <div className="pager">
          <button className="secondary" disabled={page === 0} onClick={() => setPage(p => p - 1)}>{tc('previous')}</button>
          <span>{page + 1} / {data.totalPages}</span>
          <button className="secondary" disabled={page + 1 >= data.totalPages} onClick={() => setPage(p => p + 1)}>{tc('next')}</button>
        </div>
      )}
    </section>
  );
}
