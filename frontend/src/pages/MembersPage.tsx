import React, { useCallback, useEffect, useState } from 'react';
import { Plus, UserRound } from 'lucide-react';
import { api } from '../api/client';
import { ROLE_CODES, type Invitation, type Member, type RoleCode } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { useTenant } from '../auth/useCurrentTenant';
import { EmptyState, ErrorBanner, Loading } from '../components/common';
import { currentLanguage, useNs } from '../i18n';
import { describeError } from '../lib/errors';
import { formatDate } from '../lib/format';
import { can } from '../lib/nav';

export default function MembersPage() {
  const tenant = useTenant();
  const { user, reloadTenants } = useAuth();
  const t = useNs('members');
  const tc = useNs('common');
  const tr = useNs('roles');
  const lang = currentLanguage();
  const canManage = can(tenant, 'MEMBERS_MANAGE');
  const isOwner = tenant.roles.includes('TENANT_OWNER');
  const assignable = ROLE_CODES.filter(r => r !== 'TENANT_OWNER' || isOwner);
  const base = `/api/v1/tenants/${tenant.id}`;

  const [members, setMembers] = useState<Member[]>([]);
  const [invitations, setInvitations] = useState<Invitation[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [showInvite, setShowInvite] = useState(false);
  const [email, setEmail] = useState('');
  const [role, setRole] = useState<RoleCode>('WORKER');
  const [created, setCreated] = useState<Invitation | null>(null);

  const load = useCallback(async () => {
    setLoading(true); setError('');
    try {
      const [m, i] = await Promise.all([api<Member[]>(`${base}/members`), api<Invitation[]>(`${base}/invitations`)]);
      setMembers(m);
      setInvitations(i);
    } catch (e) {
      setError(describeError(e));
    } finally {
      setLoading(false);
    }
  }, [base]);
  useEffect(() => { void load(); }, [load]);

  async function run(action: () => Promise<unknown>) {
    setError('');
    try { await action(); await load(); } catch (e) { setError(describeError(e)); }
  }

  async function invite(e: React.FormEvent) {
    e.preventDefault();
    if (!email.trim()) { setError(tc('required')); return; }
    await run(async () => {
      const inv = await api<Invitation>(`${base}/invitations`, { method: 'POST', body: { email: email.trim(), roles: [role] } });
      setCreated(inv);
      setEmail('');
      setShowInvite(false);
    });
  }

  async function changeRole(member: Member, next: RoleCode) {
    await run(async () => {
      await api(`${base}/members/${member.membershipId}/roles`, { method: 'PUT', body: { roles: [next] } });
      if (member.userId === user?.id) await reloadTenants();
    });
  }

  async function remove(member: Member) {
    if (!window.confirm(t('confirmRemove', { name: member.displayName }))) return;
    await run(async () => {
      await api(`${base}/members/${member.membershipId}`, { method: 'DELETE' });
      if (member.userId === user?.id) await reloadTenants();
    });
  }

  if (loading) return <Loading />;
  return (
    <section className="crop-page">
      <div className="crop-page-head">
        <div><h2>{t('title')}</h2><p>{t('intro')}</p></div>
        {canManage && <button className="primary" onClick={() => setShowInvite(x => !x)}><Plus size={16} aria-hidden />{t('invite')}</button>}
      </div>
      <ErrorBanner message={error} onRetry={() => void load()} />
      {created?.acceptUrl && (
        <div className="info-banner" role="status">
          <p>{t('inviteCreated', { email: created.email })}</p>
          <code className="link-box">{created.acceptUrl}</code>
          <button type="button" className="secondary" onClick={() => void navigator.clipboard?.writeText(created.acceptUrl ?? '')}>{tc('copyLink')}</button>
        </div>
      )}
      {showInvite && (
        <form className="form-card form-grid" onSubmit={invite} noValidate>
          <label>{t('inviteEmail')}<input type="email" maxLength={254} value={email} onChange={e => setEmail(e.target.value)} /></label>
          <label>{t('role')}
            <select value={role} onChange={e => setRole(e.target.value as RoleCode)}>{assignable.map(r => <option key={r} value={r}>{tr(r)}</option>)}</select>
          </label>
          <div className="form-actions full">
            <button type="button" className="secondary" onClick={() => setShowInvite(false)}>{tc('cancel')}</button>
            <button className="primary">{t('sendInvite')}</button>
          </div>
        </form>
      )}
      <div className="activity-list">
        {members.map(m => (
          <article className="activity-row" key={m.membershipId}>
            <span className="record-icon"><UserRound aria-hidden /></span>
            <div className="cycle-main">
              <h4>{m.displayName} {m.userId === user?.id && <small>{t('you')}</small>}</h4>
              <p>{m.email}</p>
            </div>
            {canManage ? (
              <div className="value-side">
                <label className="sr-only" htmlFor={`role-${m.membershipId}`}>{t('changeRole')}</label>
                <select id={`role-${m.membershipId}`} value={m.roles[0]} onChange={e => void changeRole(m, e.target.value as RoleCode)}
                        disabled={m.roles.includes('TENANT_OWNER') && !isOwner}>
                  {(m.roles.includes('TENANT_OWNER') ? ROLE_CODES : assignable).map(r => <option key={r} value={r}>{tr(r)}</option>)}
                </select>
                <button type="button" className="text-action" onClick={() => void remove(m)}>{tc('remove')}</button>
              </div>
            ) : <div className="value-side"><b>{m.roles.map(r => tr(r)).join(', ')}</b></div>}
          </article>
        ))}
      </div>
      <h3 className="section-space">{t('pending')}</h3>
      <div className="activity-list">
        {invitations.map(i => (
          <article className="activity-row" key={i.id}>
            <div className="cycle-main"><h4>{i.email}</h4><p>{i.roles.map(r => tr(r)).join(', ')} · {t('expires', { date: formatDate(i.expiresAt, lang) })}</p></div>
            {canManage && <button type="button" className="text-action" onClick={() => void run(() => api(`${base}/invitations/${i.id}`, { method: 'DELETE' }))}>{t('revoke')}</button>}
          </article>
        ))}
        {!invitations.length && <EmptyState>{tc('empty')}</EmptyState>}
      </div>
    </section>
  );
}
