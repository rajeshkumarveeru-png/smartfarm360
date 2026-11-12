import React, { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api, download } from '../api/client';
import type { Consent } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { ErrorBanner } from '../components/common';
import { Toast } from '../components/Toast';
import { currentLanguage, useNs } from '../i18n';
import { describeError } from '../lib/errors';
import { formatDate } from '../lib/format';

type Setup = { secret: string; otpauthUrl: string };

/** The person's own account (SaaS operations design, O5, O6, O11, O12): email, two-step sign-in, privacy, deletion. */
export default function AccountPage() {
  const t = useNs('account');
  const lang = currentLanguage();
  const navigate = useNavigate();
  const { user, reloadUser, logout } = useAuth();
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [password, setPassword] = useState('');
  const [setup, setSetup] = useState<Setup | null>(null);
  const [code, setCode] = useState('');
  const [recovery, setRecovery] = useState<string[] | null>(null);
  const [consents, setConsents] = useState<Consent[]>([]);
  const [deletePassword, setDeletePassword] = useState('');

  const loadConsents = useCallback(async () => {
    try { setConsents(await api<Consent[]>('/api/v1/me/consents')); } catch (e) { setError(describeError(e)); }
  }, []);
  useEffect(() => { void loadConsents(); }, [loadConsents]);

  async function run(action: () => Promise<void>) {
    setError(''); setNotice('');
    try { await action(); } catch (e) { setError(describeError(e)); }
  }

  const marketing = consents.find(c => c.kind === 'MARKETING');

  return (
    <section className="crop-page">
      <div className="crop-page-head"><div><h2>{t('title')}</h2><p>{user?.email}</p></div></div>
      <ErrorBanner message={error} />
      <Toast message={notice} onClose={() => setNotice('')} />

      <section className="panel">
        <h3>{t('email')}</h3>
        {user?.emailVerified ? <p>{t('emailVerified')}</p> : (
          <>
            <p>{t('emailNotVerified')}</p>
            <button className="secondary" onClick={() => void run(async () => {
              await api('/api/v1/auth/me/verification', { method: 'POST' });
              setNotice(t('verificationSent'));
            })}>{t('resendVerification')}</button>
          </>
        )}
      </section>

      <section className="panel">
        <h3>{t('twoStep')}</h3>
        <p className="hint">{user?.platformAdmin ? t('twoStepRequired') : t('twoStepHelp')}</p>
        {recovery ? (
          <div>
            <p><strong>{t('recoveryCodesTitle')}</strong></p>
            <p className="hint">{t('recoveryCodesHelp')}</p>
            <ul className="recovery-codes">{recovery.map(c => <li key={c}><code>{c}</code></li>)}</ul>
            <button className="primary" onClick={() => { setRecovery(null); setNotice(t('twoStepOnSignInAgain')); }}>{t('savedCodes')}</button>
          </div>
        ) : user?.twoStepEnabled ? (
          <form className="inline-form" onSubmit={e => { e.preventDefault(); void run(async () => {
            const r = await api<{ codes: string[] }>('/api/v1/auth/me/two-step/recovery-codes', { method: 'POST', body: { password } });
            setPassword(''); setRecovery(r.codes);
          }); }}>
            <p>{t('twoStepOn')}</p>
            <label>{t('password')}<input type="password" autoComplete="current-password" value={password} onChange={e => setPassword(e.target.value)} /></label>
            <button className="secondary">{t('newRecoveryCodes')}</button>
          </form>
        ) : setup ? (
          <form className="inline-form" onSubmit={e => { e.preventDefault(); void run(async () => {
            const r = await api<{ codes: string[] }>('/api/v1/auth/me/two-step/enable', { method: 'POST', body: { code: code.trim() } });
            setSetup(null); setCode(''); setRecovery(r.codes); await reloadUser();
          }); }}>
            <p>{t('scanOrType')}</p>
            <p><a href={setup.otpauthUrl}>{t('openInApp')}</a></p>
            <p className="hint">{t('secretKey')}: <code>{setup.secret.replace(/(.{4})/g, '$1 ').trim()}</code></p>
            <label>{t('codeFromApp')}<input inputMode="numeric" autoComplete="one-time-code" maxLength={6} value={code} onChange={e => setCode(e.target.value)} /></label>
            <button className="primary">{t('turnOn')}</button>
          </form>
        ) : (
          <form className="inline-form" onSubmit={e => { e.preventDefault(); void run(async () => {
            setSetup(await api<Setup>('/api/v1/auth/me/two-step/setup', { method: 'POST', body: { password } })); setPassword('');
          }); }}>
            <label>{t('password')}<input type="password" autoComplete="current-password" value={password} onChange={e => setPassword(e.target.value)} /></label>
            <button className="primary">{t('setUp')}</button>
          </form>
        )}
      </section>

      <section className="panel">
        <h3>{t('privacy')}</h3>
        <label className="check">
          <input type="checkbox" checked={marketing?.accepted ?? false} onChange={e => void run(async () => {
            setConsents(await api<Consent[]>('/api/v1/me/marketing', { method: 'PUT', body: { optIn: e.target.checked, client: 'WEB' } }));
          })} />
          {t('marketing')}
        </label>
        <h4>{t('consentHistory')}</h4>
        {consents.length === 0 ? <p className="hint">{t('noConsents')}</p> : (
          <ul className="plain-list">
            {consents.map((c, i) => <li key={i}>{t(`consentKind.${c.kind}`)} {c.kind === 'MARKETING' ? '' : `v${c.version}`} · {c.accepted ? t('accepted') : t('declined')} · {formatDate(c.recordedAt, lang)}</li>)}
          </ul>
        )}
        <button className="secondary" onClick={() => void run(() => download('/api/v1/me/data', 'smartfarm360-my-data.json'))}>{t('downloadMyData')}</button>
      </section>

      <section className="panel danger-zone">
        <h3>{t('deleteAccount')}</h3>
        <p className="hint">{t('deleteAccountHelp')}</p>
        <form className="inline-form" onSubmit={(e: React.FormEvent) => { e.preventDefault(); void run(async () => {
          if (!window.confirm(t('deleteAccountConfirm'))) return;
          await api('/api/v1/me/deletion', { method: 'POST', body: { password: deletePassword } });
          await logout();
          navigate('/login', { replace: true });
        }); }}>
          <label>{t('password')}<input type="password" autoComplete="current-password" value={deletePassword} onChange={e => setDeletePassword(e.target.value)} /></label>
          <button className="danger">{t('deleteAccountButton')}</button>
        </form>
      </section>
    </section>
  );
}
