import { useCallback, useEffect, useState } from 'react';
import { api } from '../api/client';
import type { Page, PlatformTenant } from '../api/types';
import { ErrorBanner, Loading } from '../components/common';
import { currentLanguage, useNs } from '../i18n';
import { describeError } from '../lib/errors';
import { formatDate } from '../lib/format';

/** Platform administration: tenant status only. Shown only to platform administrators; the API enforces it. */
export default function PlatformPage() {
  const t = useNs('platform');
  const tc = useNs('common');
  const lang = currentLanguage();
  const [page, setPage] = useState(0);
  const [data, setData] = useState<Page<PlatformTenant> | null>(null);
  const [error, setError] = useState('');

  const load = useCallback(async () => {
    setError('');
    try { setData(await api<Page<PlatformTenant>>(`/api/v1/platform/tenants?page=${page}&size=50`)); } catch (e) { setError(describeError(e)); }
  }, [page]);
  useEffect(() => { void load(); }, [load]);

  async function toggle(tenant: PlatformTenant) {
    if (tenant.active && !window.confirm(t('confirmSuspend', { name: tenant.name }))) return;
    try {
      await api(`/api/v1/platform/tenants/${tenant.id}`, { method: 'PATCH', body: { active: !tenant.active } });
      await load();
    } catch (e) {
      setError(describeError(e));
    }
  }

  if (!data) return error ? <ErrorBanner message={error} /> : <Loading />;
  return (
    <section className="crop-page">
      <div className="crop-page-head"><div><h2>{t('title')}</h2></div></div>
      <ErrorBanner message={error} />
      <div className="activity-list">
        {data.items.map(x => (
          <article className="activity-row" key={x.id}>
            <div className="cycle-main">
              <h4>{x.name}</h4>
              <p>{x.slug} · {t('members')}: {x.memberCount} · {formatDate(x.createdAt, lang)}</p>
            </div>
            <div className="value-side">
              <span className={`status ${x.active ? 'completed' : 'planned'}`}>{x.active ? t('active') : t('suspended')}</span>
              <button className="secondary" onClick={() => void toggle(x)}>{x.active ? t('suspend') : t('activate')}</button>
            </div>
          </article>
        ))}
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
