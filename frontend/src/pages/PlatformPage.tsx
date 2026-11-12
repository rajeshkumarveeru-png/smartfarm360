import React, { useCallback, useEffect, useState } from 'react';
import { api } from '../api/client';
import type { Announcement, LegalDocument, Operations, OpsCheck, Page, PlatformAuditEntry, PlatformTenant, PlatformTenantDetail, PlatformUser } from '../api/types';
import { ErrorBanner, Loading } from '../components/common';
import { Toast } from '../components/Toast';
import { currentLanguage, useNs } from '../i18n';
import { describeError } from '../lib/errors';
import { formatDate, formatNumber } from '../lib/format';
import PlatformBillingTab from './PlatformBillingTab';

type Tab = 'tenants' | 'users' | 'billing' | 'ops' | 'announcements' | 'legal' | 'audit';
const TABS: Tab[] = ['tenants', 'users', 'billing', 'ops', 'announcements', 'legal', 'audit'];

/**
 * The platform console (SaaS operations design, section 6): farm businesses and users by counts and status, operations
 * checks, announcements, legal documents and the platform audit log. The API needs the second sign-in step.
 */
export default function PlatformPage() {
  const t = useNs('platform');
  const [tab, setTab] = useState<Tab>('tenants');
  return (
    <section className="crop-page">
      <div className="crop-page-head"><div><h2>{t('title')}</h2><p>{t('subtitle')}</p></div></div>
      <div className="tabs" role="tablist">
        {TABS.map(x => <button key={x} role="tab" aria-selected={tab === x} className={tab === x ? 'tab active' : 'tab'} onClick={() => setTab(x)}>{t(`tab.${x}`)}</button>)}
      </div>
      {tab === 'tenants' && <TenantsTab />}
      {tab === 'users' && <UsersTab />}
      {tab === 'billing' && <PlatformBillingTab />}
      {tab === 'ops' && <OpsTab />}
      {tab === 'announcements' && <AnnouncementsTab />}
      {tab === 'legal' && <LegalTab />}
      {tab === 'audit' && <AuditTab />}
    </section>
  );
}

function Pager({ page, totalPages, onPage }: { page: number; totalPages: number; onPage: (p: number) => void }) {
  const tc = useNs('common');
  if (totalPages <= 1) return null;
  return (
    <div className="pager">
      <button className="secondary" disabled={page === 0} onClick={() => onPage(page - 1)}>{tc('previous')}</button>
      <span>{page + 1} / {totalPages}</span>
      <button className="secondary" disabled={page + 1 >= totalPages} onClick={() => onPage(page + 1)}>{tc('next')}</button>
    </div>
  );
}

function TenantsTab() {
  const t = useNs('platform');
  const to = useNs('tenantOps');
  const lang = currentLanguage();
  const [query, setQuery] = useState('');
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(0);
  const [data, setData] = useState<Page<PlatformTenant> | null>(null);
  const [open, setOpen] = useState<PlatformTenantDetail | null>(null);
  const [reason, setReason] = useState('');
  const [limit, setLimit] = useState({ code: 'MEMBERS', value: '', reason: '' });
  const [error, setError] = useState('');

  const load = useCallback(async () => {
    setError('');
    try { setData(await api<Page<PlatformTenant>>(`/api/v1/platform/tenants?page=${page}&size=50${search ? `&q=${encodeURIComponent(search)}` : ''}`)); }
    catch (e) { setError(describeError(e)); }
  }, [page, search]);
  useEffect(() => { void load(); }, [load]);

  async function openTenant(id: number) {
    setError(''); setReason('');
    try { setOpen(await api<PlatformTenantDetail>(`/api/v1/platform/tenants/${id}`)); } catch (e) { setError(describeError(e)); }
  }

  async function setStatus(x: PlatformTenant, active: boolean) {
    setError('');
    try {
      await api(`/api/v1/platform/tenants/${x.id}`, { method: 'PATCH', body: active ? { active } : { active, reason: reason.trim() } });
      await openTenant(x.id); await load();
    } catch (e) { setError(describeError(e)); }
  }

  async function saveLimit(e: React.FormEvent) {
    e.preventDefault();
    if (!open) return;
    setError('');
    try {
      setOpen(await api<PlatformTenantDetail>(`/api/v1/platform/tenants/${open.tenant.id}/limits/${limit.code}`, { method: 'PUT',
        body: { value: limit.value === '' ? null : Number(limit.value), reason: limit.reason.trim() } }));
      setLimit({ ...limit, value: '', reason: '' });
    } catch (err) { setError(describeError(err)); }
  }

  if (!data) return error ? <ErrorBanner message={error} /> : <Loading />;
  return (
    <>
      <form className="inline-form" onSubmit={e => { e.preventDefault(); setPage(0); setSearch(query.trim()); }}>
        <label>{t('search')}<input value={query} onChange={e => setQuery(e.target.value)} placeholder={t('searchTenantsHint')} /></label>
        <button className="secondary">{t('find')}</button>
      </form>
      <ErrorBanner message={error} />
      {open && (
        <section className="panel">
          <div className="crop-page-head"><div><h3>{open.tenant.name}</h3><p>{open.tenant.slug} · {open.tenant.planCode}</p></div>
            <button className="secondary" onClick={() => setOpen(null)}>{t('close')}</button></div>
          <p>{t('owners')}: {open.owners.map(o => o.email).join(', ') || '—'}</p>
          <p>{t('modulesOn')}: {open.tenant.modules.join(', ') || '—'}</p>
          {open.tenant.deletionScheduledFor && <p className="warning">{t('deletionOn', { date: formatDate(open.tenant.deletionScheduledFor, lang) })}</p>}
          <h4>{t('usage')}</h4>
          <ul className="plain-list">{open.usage.map(u => <li key={u.limit}>{to(`limit.${u.limit}`)}: {u.used} / {u.max}{u.overridden ? ` · ${t('overridden')}` : ''}</li>)}</ul>
          <form className="inline-form" onSubmit={saveLimit}>
            <label>{t('limit')}<select value={limit.code} onChange={e => setLimit({ ...limit, code: e.target.value })}>
              {['MEMBERS', 'FARMS', 'EXPORTS_PER_DAY'].map(c => <option key={c} value={c}>{to(`limit.${c}`)}</option>)}</select></label>
            <label>{t('newLimit')}<input inputMode="numeric" value={limit.value} onChange={e => setLimit({ ...limit, value: e.target.value })} placeholder={t('emptyRemoves')} /></label>
            <label>{t('reason')}<input maxLength={300} value={limit.reason} onChange={e => setLimit({ ...limit, reason: e.target.value })} /></label>
            <button className="secondary">{t('saveLimit')}</button>
          </form>
          <h4>{t('status')}</h4>
          {open.tenant.active ? (
            <form className="inline-form" onSubmit={e => { e.preventDefault(); if (window.confirm(t('confirmSuspend', { name: open.tenant.name }))) void setStatus(open.tenant, false); }}>
              <label>{t('suspendReason')}<input maxLength={300} value={reason} onChange={e => setReason(e.target.value)} /></label>
              <button className="danger" disabled={!reason.trim()}>{t('suspend')}</button>
            </form>
          ) : (
            <>
              <p className="warning">{t('suspendedBecause', { reason: open.tenant.suspendedReason ?? '' })}</p>
              <button className="primary" onClick={() => void setStatus(open.tenant, true)}>{t('activate')}</button>
            </>
          )}
          <h4>{t('supportGrants')}</h4>
          {open.supportGrants.length === 0 ? <p className="hint">{t('noGrants')}</p> : (
            <ul className="plain-list">{open.supportGrants.map(g => <li key={g.id}>{g.grantedByEmail} · {g.reason} · {formatDate(g.startsAt, lang)} – {formatDate(g.endedAt ?? g.endsAt, lang)}</li>)}</ul>
          )}
          <h4>{t('history')}</h4>
          <ul className="plain-list">{open.history.map((h, i) => <li key={i}>{formatDate(h.at, lang)} · {t(`action.${h.action}`)} · {h.actorEmail ?? '—'}</li>)}</ul>
        </section>
      )}
      <div className="activity-list">
        {data.items.map(x => (
          <article className="activity-row" key={x.id}>
            <div className="cycle-main">
              <h4>{x.name}</h4>
              <p>{x.slug} · {t('members')}: {formatNumber(x.memberCount, lang)} · {t('farms')}: {formatNumber(x.farmCount, lang)} · {formatDate(x.createdAt, lang)}
                {x.lastActivity ? ` · ${t('lastActivity', { date: formatDate(x.lastActivity, lang) })}` : ''}</p>
            </div>
            <div className="value-side">
              <span className={`status ${x.active ? 'completed' : 'planned'}`}>{x.active ? t('active') : t('suspended')}</span>
              <button className="secondary" onClick={() => void openTenant(x.id)}>{t('open')}</button>
            </div>
          </article>
        ))}
      </div>
      <Pager page={page} totalPages={data.totalPages} onPage={setPage} />
    </>
  );
}

function UsersTab() {
  const t = useNs('platform');
  const lang = currentLanguage();
  const [query, setQuery] = useState('');
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(0);
  const [data, setData] = useState<Page<PlatformUser> | null>(null);
  const [error, setError] = useState('');

  const load = useCallback(async () => {
    setError('');
    try { setData(await api<Page<PlatformUser>>(`/api/v1/platform/users?page=${page}&size=50${search ? `&q=${encodeURIComponent(search)}` : ''}`)); }
    catch (e) { setError(describeError(e)); }
  }, [page, search]);
  useEffect(() => { void load(); }, [load]);

  async function setActive(u: PlatformUser, active: boolean) {
    let reason: string | null = null;
    if (!active) {
      reason = window.prompt(t('lockReason', { email: u.email }));
      if (!reason?.trim()) return;
    }
    setError('');
    try { await api(`/api/v1/platform/users/${u.id}`, { method: 'PATCH', body: { active, reason } }); await load(); } catch (e) { setError(describeError(e)); }
  }

  if (!data) return error ? <ErrorBanner message={error} /> : <Loading />;
  return (
    <>
      <form className="inline-form" onSubmit={e => { e.preventDefault(); setPage(0); setSearch(query.trim()); }}>
        <label>{t('search')}<input value={query} onChange={e => setQuery(e.target.value)} placeholder={t('searchUsersHint')} /></label>
        <button className="secondary">{t('find')}</button>
      </form>
      <ErrorBanner message={error} />
      <div className="activity-list">
        {data.items.map(u => (
          <article className="activity-row" key={u.id}>
            <div className="cycle-main">
              <h4>{u.displayName} · {u.email}</h4>
              <p>{u.memberships.map(m => `${m.tenantName} (${m.roles.join(', ')})`).join(' · ') || t('noBusinesses')} · {formatDate(u.createdAt, lang)}
                {u.twoStep ? ` · ${t('twoStepOn')}` : ''}{u.emailVerified ? '' : ` · ${t('unverified')}`}{u.platformAdmin ? ` · ${t('administrator')}` : ''}</p>
            </div>
            <div className="value-side">
              <span className={`status ${u.active ? 'completed' : 'planned'}`}>{u.active ? t('active') : t('locked')}</span>
              <button className="secondary" onClick={() => void setActive(u, !u.active)}>{u.active ? t('lock') : t('unlock')}</button>
            </div>
          </article>
        ))}
      </div>
      <Pager page={page} totalPages={data.totalPages} onPage={setPage} />
    </>
  );
}

function CheckLine({ label, check }: { label: string; check: OpsCheck | null }) {
  const t = useNs('platform');
  const lang = currentLanguage();
  return (
    <li className={check && !check.ok ? 'warning' : undefined}>
      {label}: {check ? `${check.ok ? t('ok') : t('failed')} · ${formatDate(check.recordedAt, lang)}${check.details ? ` · ${check.details}` : ''}` : t('never')}
    </li>
  );
}

function OpsTab() {
  const t = useNs('platform');
  const [ops, setOps] = useState<Operations | null>(null);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const load = useCallback(async () => {
    try { setOps(await api<Operations>('/api/v1/platform/ops')); } catch (e) { setError(describeError(e)); }
  }, []);
  useEffect(() => { void load(); }, [load]);
  if (!ops) return error ? <ErrorBanner message={error} /> : <Loading />;
  return (
    <section className="panel">
      <ErrorBanner message={error} />
      <Toast message={notice} onClose={() => setNotice('')} />
      <p>{t('version', { version: ops.version })}</p>
      <ul className="plain-list">
        <CheckLine label={t('lastBackup')} check={ops.lastBackup} />
        <CheckLine label={t('lastRestoreTest')} check={ops.lastRestoreTest} />
        <CheckLine label={t('lastAlertTest')} check={ops.lastAlertTest} />
        <CheckLine label={t('lastLoadTest')} check={ops.lastLoadTest} />
      </ul>
      {!ops.alertEmailSet && <p className="warning">{t('alertEmailMissing')}</p>}
      <button className="secondary" disabled={!ops.alertEmailSet} onClick={() => void (async () => {
        setError(''); setNotice('');
        try { await api('/api/v1/platform/ops/test-alert', { method: 'POST' }); setNotice(t('testAlertSent')); await load(); } catch (e) { setError(describeError(e)); }
      })()}>{t('sendTestAlert')}</button>
    </section>
  );
}

function AnnouncementsTab() {
  const t = useNs('platform');
  const lang = currentLanguage();
  const [list, setList] = useState<Announcement[]>([]);
  const [form, setForm] = useState({ messageEn: '', messageTa: '', hours: '2', maintenance: false });
  const [error, setError] = useState('');
  const load = useCallback(async () => {
    try { setList(await api<Announcement[]>('/api/v1/platform/announcements')); } catch (e) { setError(describeError(e)); }
  }, []);
  useEffect(() => { void load(); }, [load]);

  async function create(e: React.FormEvent) {
    e.preventDefault();
    if (form.maintenance && !window.confirm(t('confirmMaintenance'))) return;
    setError('');
    try {
      await api('/api/v1/platform/announcements', { method: 'POST', body: { messageEn: form.messageEn.trim(), messageTa: form.messageTa.trim(),
        endsAt: new Date(Date.now() + Number(form.hours) * 3_600_000).toISOString(), maintenance: form.maintenance } });
      setForm({ messageEn: '', messageTa: '', hours: '2', maintenance: false });
      await load();
    } catch (err) { setError(describeError(err)); }
  }

  return (
    <>
      <ErrorBanner message={error} />
      <form className="form-card form-grid" onSubmit={create}>
        <label className="full">{t('messageEn')}<input maxLength={500} value={form.messageEn} onChange={e => setForm({ ...form, messageEn: e.target.value })} /></label>
        <label className="full">{t('messageTa')}<input maxLength={500} value={form.messageTa} onChange={e => setForm({ ...form, messageTa: e.target.value })} /></label>
        <label>{t('forHours')}<input inputMode="numeric" value={form.hours} onChange={e => setForm({ ...form, hours: e.target.value })} /></label>
        <label className="check"><input type="checkbox" checked={form.maintenance} onChange={e => setForm({ ...form, maintenance: e.target.checked })} />{t('readOnlyMaintenance')}</label>
        <div className="form-actions full"><button className="primary" disabled={!form.messageEn.trim() || !form.messageTa.trim() || !(Number(form.hours) > 0)}>{t('announce')}</button></div>
      </form>
      <div className="activity-list">
        {list.map(a => (
          <article className="activity-row" key={a.id}>
            <div className="cycle-main"><h4>{lang === 'ta' ? a.messageTa : a.messageEn}</h4>
              <p>{formatDate(a.startsAt, lang)} – {formatDate(a.endsAt, lang)}{a.maintenance ? ` · ${t('maintenance')}` : ''}{a.endedAt ? ` · ${t('ended')}` : ''}</p></div>
            {!a.endedAt && <button className="secondary" onClick={() => void (async () => {
              try { await api(`/api/v1/platform/announcements/${a.id}/end`, { method: 'POST' }); await load(); } catch (e) { setError(describeError(e)); }
            })()}>{t('end')}</button>}
          </article>
        ))}
      </div>
    </>
  );
}

function LegalTab() {
  const t = useNs('platform');
  const lang = currentLanguage();
  const [docs, setDocs] = useState<LegalDocument[]>([]);
  const [form, setForm] = useState({ kind: 'TERMS', titleEn: '', bodyEn: '', titleTa: '', bodyTa: '', changes: '' });
  const [error, setError] = useState('');
  const load = useCallback(async () => {
    try { setDocs(await api<LegalDocument[]>('/api/v1/platform/legal-documents')); } catch (e) { setError(describeError(e)); }
  }, []);
  useEffect(() => { void load(); }, [load]);

  async function publish(e: React.FormEvent) {
    e.preventDefault();
    if (!window.confirm(t('confirmPublish'))) return;
    setError('');
    try {
      await api('/api/v1/platform/legal-documents', { method: 'POST', body: { kind: form.kind, changes: form.changes.trim() || null, texts: [
        { language: 'en', title: form.titleEn.trim(), body: form.bodyEn }, { language: 'ta', title: form.titleTa.trim(), body: form.bodyTa }] } });
      setForm({ kind: form.kind, titleEn: '', bodyEn: '', titleTa: '', bodyTa: '', changes: '' });
      await load();
    } catch (err) { setError(describeError(err)); }
  }

  return (
    <>
      <ErrorBanner message={error} />
      <p className="hint">{t('legalHelp')}</p>
      <form className="form-card form-grid" onSubmit={publish}>
        <label>{t('document')}<select value={form.kind} onChange={e => setForm({ ...form, kind: e.target.value })}>
          <option value="TERMS">{t('terms')}</option><option value="PRIVACY">{t('privacyNotice')}</option></select></label>
        <label className="full">{t('changes')}<input maxLength={1000} value={form.changes} onChange={e => setForm({ ...form, changes: e.target.value })} /></label>
        <label>{t('titleEn')}<input maxLength={140} value={form.titleEn} onChange={e => setForm({ ...form, titleEn: e.target.value })} /></label>
        <label>{t('titleTa')}<input maxLength={140} value={form.titleTa} onChange={e => setForm({ ...form, titleTa: e.target.value })} /></label>
        <label className="full">{t('bodyEn')}<textarea rows={6} value={form.bodyEn} onChange={e => setForm({ ...form, bodyEn: e.target.value })} /></label>
        <label className="full">{t('bodyTa')}<textarea rows={6} value={form.bodyTa} onChange={e => setForm({ ...form, bodyTa: e.target.value })} /></label>
        <div className="form-actions full"><button className="primary" disabled={!form.titleEn.trim() || !form.bodyEn.trim() || !form.titleTa.trim() || !form.bodyTa.trim()}>{t('publish')}</button></div>
      </form>
      <ul className="plain-list">{docs.map(d => <li key={`${d.kind}-${d.version}-${d.language}`}>{d.kind === 'TERMS' ? t('terms') : t('privacyNotice')} v{d.version} ({d.language}) · {d.title} · {formatDate(d.publishedAt, lang)}</li>)}</ul>
    </>
  );
}

function AuditTab() {
  const t = useNs('platform');
  const lang = currentLanguage();
  const [page, setPage] = useState(0);
  const [data, setData] = useState<Page<PlatformAuditEntry> | null>(null);
  const [error, setError] = useState('');
  useEffect(() => {
    api<Page<PlatformAuditEntry>>(`/api/v1/platform/audit?page=${page}&size=50`).then(setData).catch(e => setError(describeError(e)));
  }, [page]);
  if (!data) return error ? <ErrorBanner message={error} /> : <Loading />;
  return (
    <>
      <ul className="plain-list">
        {data.items.map(a => <li key={a.id}>{formatDate(a.at, lang)} · {t(`action.${a.action}`)} · {a.actorEmail ?? '—'} · {a.targetType}{a.targetId ? ` #${a.targetId}` : ''}</li>)}
      </ul>
      <Pager page={page} totalPages={data.totalPages} onPage={setPage} />
    </>
  );
}
