import React, { useCallback, useEffect, useState } from 'react';
import { api, download } from '../api/client';
import type { DeletionState, SupportGrant, TenantExport, Usage } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { useTenant } from '../auth/useCurrentTenant';
import { ErrorBanner } from '../components/common';
import { Toast } from '../components/Toast';
import { currentLanguage, useNs } from '../i18n';
import { describeError } from '../lib/errors';
import { formatDate, formatNumber } from '../lib/format';

/**
 * A farm business's own operations on its settings page (SaaS operations design, sections 6–8): plan usage, support
 * access, the export of everything and deletion. Only owners can change support access, exports and deletion.
 */
export default function TenantOperations() {
  const tenant = useTenant();
  const t = useNs('tenantOps');
  const lang = currentLanguage();
  const { reloadTenants } = useAuth();
  const base = `/api/v1/tenants/${tenant.id}`;
  const owner = tenant.roles.includes('TENANT_OWNER');
  const [usage, setUsage] = useState<Usage[]>([]);
  const [grants, setGrants] = useState<SupportGrant[]>([]);
  const [exports, setExports] = useState<TenantExport[]>([]);
  const [deletion, setDeletion] = useState<DeletionState | null>(null);
  const [grant, setGrant] = useState({ hours: '24', reason: '' });
  const [confirmName, setConfirmName] = useState('');
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const load = useCallback(async () => {
    try {
      const [u, d] = await Promise.all([api<Usage[]>(`${base}/usage`), api<DeletionState>(`${base}/deletion`)]);
      setUsage(u); setDeletion(d);
      if (owner) {
        const [g, x] = await Promise.all([api<SupportGrant[]>(`${base}/support-grants`), api<TenantExport[]>(`${base}/exports`)]);
        setGrants(g); setExports(x);
      }
    } catch (e) {
      setError(describeError(e));
    }
  }, [base, owner]);
  useEffect(() => { void load(); }, [load]);

  // While an export is being prepared, look again every few seconds
  useEffect(() => {
    if (!exports.some(x => x.status === 'QUEUED' || x.status === 'RUNNING')) return;
    const id = window.setTimeout(() => void load(), 3000);
    return () => window.clearTimeout(id);
  }, [exports, load]);

  async function run(action: () => Promise<void>, done?: string) {
    setError(''); setNotice('');
    try { await action(); await load(); if (done) setNotice(done); } catch (e) { setError(describeError(e)); }
  }

  return (
    <>
      <ErrorBanner message={error} />
      <Toast message={notice} onClose={() => setNotice('')} />
      {deletion?.pending && deletion.scheduledFor && (
        <div className="error-banner" role="alert">{t('deletionBanner', { date: formatDate(deletion.scheduledFor, lang) })}</div>
      )}

      <section className="panel">
        <h3>{t('plan')}</h3>
        <ul className="plain-list">
          {usage.map(u => (
            <li key={u.limit} className={u.nearLimit ? 'warning' : undefined}>
              {t(`limit.${u.limit}`)}: {formatNumber(u.used, lang)} / {formatNumber(u.max, lang)}{u.nearLimit ? ` · ${t('nearLimit')}` : ''}
            </li>
          ))}
        </ul>
      </section>

      {owner && (
        <section className="panel">
          <h3>{t('support')}</h3>
          <p className="hint">{t('supportHelp')}</p>
          {grants.filter(g => g.active).map(g => (
            <div key={g.id} className="activity-row">
              <div className="cycle-main"><h4>{t('supportUntil', { date: formatDate(g.endsAt, lang) })}</h4><p>{g.reason}</p></div>
              <button className="secondary" onClick={() => void run(() => api(`${base}/support-grants/${g.id}/end`, { method: 'POST' }).then(() => undefined))}>{t('endSupport')}</button>
            </div>
          ))}
          {!grants.some(g => g.active) && (
            <form className="inline-form" onSubmit={(e: React.FormEvent) => { e.preventDefault(); void run(async () => {
              await api(`${base}/support-grants`, { method: 'POST', body: { hours: Number(grant.hours), reason: grant.reason.trim() } });
              setGrant({ hours: '24', reason: '' });
            }, t('supportGranted')); }}>
              <label>{t('supportHours')}<select value={grant.hours} onChange={e => setGrant({ ...grant, hours: e.target.value })}>
                {['4', '24', '72'].map(h => <option key={h} value={h}>{t('hours', { count: Number(h) })}</option>)}
              </select></label>
              <label>{t('supportReason')}<input maxLength={300} value={grant.reason} onChange={e => setGrant({ ...grant, reason: e.target.value })} /></label>
              <button className="primary" disabled={!grant.reason.trim()}>{t('grantSupport')}</button>
            </form>
          )}
        </section>
      )}

      {owner && (
        <section className="panel">
          <h3>{t('yourData')}</h3>
          <p className="hint">{t('exportHelp')}</p>
          <button className="primary" onClick={() => void run(() => api(`${base}/exports`, { method: 'POST' }).then(() => undefined), t('exportStarted'))}>{t('exportAll')}</button>
          <ul className="plain-list">
            {exports.map(x => (
              <li key={x.id}>
                {formatDate(x.requestedAt, lang)} · {t(`exportStatus.${x.status}`)}
                {x.status === 'READY' && (
                  <> · <button className="text-link" onClick={() => void run(() => download(`${base}/exports/${x.id}/download`, `smartfarm360-${tenant.slug}.zip`))}>
                    {t('download')}</button> · {t('availableUntil', { date: formatDate(x.expiresAt ?? x.requestedAt, lang) })}</>
                )}
              </li>
            ))}
          </ul>
        </section>
      )}

      {owner && deletion && (
        <section className="panel danger-zone">
          <h3>{t('deleteBusiness')}</h3>
          {deletion.pending ? (
            <>
              <p>{t('deletionPending', { date: formatDate(deletion.scheduledFor ?? '', lang), email: deletion.requestedByEmail ?? '' })}</p>
              <button className="primary" onClick={() => void run(async () => {
                setDeletion(await api<DeletionState>(`${base}/deletion`, { method: 'DELETE' })); await reloadTenants();
              }, t('deletionCancelled'))}>{t('cancelDeletion')}</button>
            </>
          ) : (
            <form className="inline-form" onSubmit={(e: React.FormEvent) => { e.preventDefault(); void run(async () => {
              if (!window.confirm(t('deleteConfirm', { name: tenant.name }))) return;
              setDeletion(await api<DeletionState>(`${base}/deletion`, { method: 'POST', body: { confirmName } })); setConfirmName(''); await reloadTenants();
            }); }}>
              <p className="hint">{t('deleteHelp')}</p>
              <label>{t('typeName', { name: tenant.name })}<input value={confirmName} onChange={e => setConfirmName(e.target.value)} /></label>
              <button className="danger" disabled={confirmName.trim().toLowerCase() !== tenant.name.trim().toLowerCase()}>{t('deleteButton')}</button>
            </form>
          )}
        </section>
      )}
    </>
  );
}
