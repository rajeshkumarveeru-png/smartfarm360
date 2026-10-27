import React, { useEffect, useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { api, ApiError } from '../api/client';
import type { LegalDocument } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { AuthCard, ErrorBanner } from '../components/common';
import { describeError } from '../lib/errors';

const PENDING_INVITE = 'sf360.pendingInvite';

/** Reads `#token=...` from the URL fragment (never sent to servers) and removes it from the address bar. */
function useFragmentToken(): string {
  const location = useLocation();
  const [token] = useState(() => new URLSearchParams(location.hash.replace(/^#/, '')).get('token') ?? '');
  useEffect(() => {
    if (token) window.history.replaceState(null, '', location.pathname);
  }, [token, location.pathname]);
  return token;
}

export function LoginPage() {
  const { t } = useTranslation();
  const { login, completeSecondStep, sessionExpired } = useAuth();
  const navigate = useNavigate();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [challenge, setChallenge] = useState<string | null>(null);
  const [code, setCode] = useState('');
  const [useRecovery, setUseRecovery] = useState(false);
  const done = () => navigate(sessionStorage.getItem(PENDING_INVITE) ? '/accept-invitation' : '/', { replace: true });

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!email.trim() || !password) { setError(t('common.required')); return; }
    setBusy(true); setError('');
    try {
      const next = await login(email.trim(), password);
      if (next) setChallenge(next); else done();
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusy(false);
    }
  }

  async function submitCode(e: React.FormEvent) {
    e.preventDefault();
    if (!challenge || !code.trim()) { setError(t('common.required')); return; }
    setBusy(true); setError('');
    try {
      await completeSecondStep(challenge, code, useRecovery);
      done();
    } catch (err) {
      if (err instanceof ApiError && err.code === 'SECOND_STEP_EXPIRED') { setChallenge(null); setCode(''); }
      setError(describeError(err));
    } finally {
      setBusy(false);
    }
  }

  if (challenge) {
    return (
      <AuthCard title={t('common.appName')} subtitle={t('auth.secondStepTitle')}>
        <form onSubmit={submitCode} noValidate>
          <p className="hint">{useRecovery ? t('auth.recoveryHelp') : t('auth.secondStepHelp')}</p>
          <label>{useRecovery ? t('auth.recoveryCode') : t('auth.appCode')}
            <input inputMode={useRecovery ? 'text' : 'numeric'} autoComplete="one-time-code" maxLength={useRecovery ? 20 : 6} value={code}
                   onChange={e => setCode(e.target.value)} autoFocus /></label>
          <ErrorBanner message={error} />
          <button className="primary" disabled={busy}>{busy ? t('common.loading') : t('auth.verifyCode')}</button>
        </form>
        <button type="button" className="text-link" onClick={() => { setUseRecovery(r => !r); setCode(''); setError(''); }}>
          {useRecovery ? t('auth.useAppCode') : t('auth.useRecoveryCode')}
        </button>
        <button type="button" className="secondary" onClick={() => { setChallenge(null); setCode(''); setError(''); }}>{t('common.back')}</button>
      </AuthCard>
    );
  }

  return (
    <AuthCard title={t('common.appName')} subtitle={t('auth.signInTitle')}>
      {sessionExpired && <div className="info-banner" role="status">{t('auth.sessionExpired')}</div>}
      <form onSubmit={submit} noValidate>
        <label>{t('auth.email')}<input type="email" autoComplete="email" value={email} onChange={e => setEmail(e.target.value)} /></label>
        <label>{t('auth.password')}<input type="password" autoComplete="current-password" value={password} onChange={e => setPassword(e.target.value)} /></label>
        <ErrorBanner message={error} />
        <button className="primary" disabled={busy}>{busy ? t('common.loading') : t('auth.signIn')}</button>
      </form>
      <Link className="text-link" to="/forgot-password">{t('auth.forgot')}</Link>
      <Link className="secondary" to="/register">{t('auth.noAccount')}</Link>
    </AuthCard>
  );
}

export function RegisterPage() {
  const { t } = useTranslation();
  const { register } = useAuth();
  const navigate = useNavigate();
  const [displayName, setDisplayName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!displayName.trim() || !email.trim() || !password) { setError(t('common.required')); return; }
    if ([...password].length < 10) { setError(t('errors.PASSWORD_TOO_SHORT')); return; }
    setBusy(true); setError('');
    try {
      await register(email.trim(), password, displayName.trim());
      navigate(sessionStorage.getItem(PENDING_INVITE) ? '/accept-invitation' : '/', { replace: true });
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <AuthCard title={t('auth.register')} subtitle={t('auth.registerTitle')}>
      <form onSubmit={submit} noValidate>
        <label>{t('auth.displayName')}<input maxLength={140} autoComplete="name" value={displayName} onChange={e => setDisplayName(e.target.value)} /></label>
        <label>{t('auth.email')}<input type="email" maxLength={254} autoComplete="email" value={email} onChange={e => setEmail(e.target.value)} /></label>
        <label>{t('auth.password')}<input type="password" autoComplete="new-password" value={password} onChange={e => setPassword(e.target.value)} aria-describedby="pw-hint" /></label>
        <small id="pw-hint" className="hint">{t('auth.passwordHint')}</small>
        <ErrorBanner message={error} />
        <button className="primary" disabled={busy}>{busy ? t('common.loading') : t('auth.register')}</button>
      </form>
      <Link className="secondary" to="/login">{t('auth.haveAccount')}</Link>
    </AuthCard>
  );
}

export function ForgotPasswordPage() {
  const { t } = useTranslation();
  const [email, setEmail] = useState('');
  const [sent, setSent] = useState(false);
  const [error, setError] = useState('');

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!email.trim()) { setError(t('common.required')); return; }
    setError('');
    try {
      await api('/api/v1/auth/password-reset/request', { method: 'POST', body: { email: email.trim() } });
      setSent(true);
    } catch (err) {
      setError(describeError(err));
    }
  }

  return (
    <AuthCard title={t('auth.resetTitle')} subtitle={t('auth.resetIntro')}>
      {sent ? <div className="info-banner" role="status">{t('auth.resetSent')}</div> : (
        <form onSubmit={submit} noValidate>
          <label>{t('auth.email')}<input type="email" autoComplete="email" value={email} onChange={e => setEmail(e.target.value)} /></label>
          <ErrorBanner message={error} />
          <button className="primary">{t('auth.sendLink')}</button>
        </form>
      )}
      <Link className="secondary" to="/login">{t('auth.signIn')}</Link>
    </AuthCard>
  );
}

export function ResetPasswordPage() {
  const { t } = useTranslation();
  const token = useFragmentToken();
  const [password, setPassword] = useState('');
  const [done, setDone] = useState(false);
  const [error, setError] = useState('');

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if ([...password].length < 10) { setError(t('errors.PASSWORD_TOO_SHORT')); return; }
    setError('');
    try {
      await api('/api/v1/auth/password-reset/confirm', { method: 'POST', body: { token, newPassword: password } });
      setDone(true);
    } catch (err) {
      setError(describeError(err));
    }
  }

  if (!token) return <AuthCard title={t('auth.resetTitle')}><ErrorBanner message={t('auth.missingToken')} /></AuthCard>;
  return (
    <AuthCard title={t('auth.resetTitle')}>
      {done ? <div className="info-banner" role="status">{t('auth.resetDone')}</div> : (
        <form onSubmit={submit} noValidate>
          <label>{t('auth.newPassword')}<input type="password" autoComplete="new-password" value={password} onChange={e => setPassword(e.target.value)} /></label>
          <small className="hint">{t('auth.passwordHint')}</small>
          <ErrorBanner message={error} />
          <button className="primary">{t('auth.setPassword')}</button>
        </form>
      )}
      <Link className="secondary" to="/login">{t('auth.signIn')}</Link>
    </AuthCard>
  );
}

export function AcceptInvitationPage() {
  const { t } = useTranslation();
  const { status, reloadTenants, user } = useAuth();
  const navigate = useNavigate();
  const fragmentToken = useFragmentToken();
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  // Keep the token across sign-in/registration, which happen on other pages.
  const token = fragmentToken || sessionStorage.getItem(PENDING_INVITE) || '';
  useEffect(() => { if (fragmentToken) sessionStorage.setItem(PENDING_INVITE, fragmentToken); }, [fragmentToken]);

  async function accept() {
    setBusy(true); setError('');
    try {
      const result = await api<{ tenantId: number }>('/api/v1/invitations/accept', { method: 'POST', body: { token } });
      sessionStorage.removeItem(PENDING_INVITE);
      await reloadTenants();
      navigate(`/t/${result.tenantId}/farms`, { replace: true });
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusy(false);
    }
  }

  if (!token) return <AuthCard title={t('auth.acceptTitle')}><ErrorBanner message={t('auth.missingToken')} /></AuthCard>;
  return (
    <AuthCard title={t('auth.acceptTitle')} subtitle={t('auth.acceptIntro')}>
      {status === 'signedIn' ? (
        <>
          <p className="hint">{t('auth.signedInAs', { email: user?.email ?? '' })}</p>
          <ErrorBanner message={error} />
          <button className="primary" disabled={busy} onClick={() => void accept()}>{t('auth.accept')}</button>
        </>
      ) : (
        <>
          <p className="hint">{t('auth.signInToAccept')}</p>
          <Link className="primary" to="/login">{t('auth.signIn')}</Link>
          <Link className="secondary" to="/register">{t('auth.register')}</Link>
        </>
      )}
    </AuthCard>
  );
}

/** Opens the link from the verification email (`#token=...`), then goes on to the app (O5). */
export function VerifyEmailPage() {
  const { t } = useTranslation();
  const { status, reloadUser } = useAuth();
  const token = useFragmentToken();
  const [state, setState] = useState<'working' | 'done' | 'failed'>('working');
  const [error, setError] = useState('');

  useEffect(() => {
    if (!token) { setState('failed'); setError(t('errors.INVALID_VERIFICATION_TOKEN')); return; }
    api('/api/v1/auth/verify-email', { method: 'POST', body: { token } })
      .then(async () => { setState('done'); if (status === 'signedIn') await reloadUser().catch(() => undefined); })
      .catch(err => { setState('failed'); setError(describeError(err)); });
  // eslint-disable-next-line react-hooks/exhaustive-deps -- run once for the token in the link
  }, [token]);

  return (
    <AuthCard title={t('auth.verifyTitle')} subtitle={t('auth.verifyIntro')}>
      {state === 'working' && <div className="info-banner" role="status">{t('common.loading')}</div>}
      {state === 'done' && <div className="info-banner" role="status">{t('auth.verifyDone')}</div>}
      <ErrorBanner message={error} />
      <Link className="primary" to="/">{t('auth.continue')}</Link>
    </AuthCard>
  );
}

/** Shown before anything else when new terms or a new privacy notice were published (O12). */
export function ConsentPage() {
  const { t } = useTranslation();
  const { user, reloadUser, logout } = useAuth();
  const [docs, setDocs] = useState<LegalDocument[]>([]);
  const [agreed, setAgreed] = useState(false);
  const [error, setError] = useState('');
  const pending = user?.pendingConsents ?? [];

  useEffect(() => {
    api<LegalDocument[]>(`/api/v1/legal/current?language=${user?.preferredLanguage ?? 'en'}`).then(setDocs).catch(err => setError(describeError(err)));
  }, [user?.preferredLanguage]);

  async function accept() {
    if (!agreed) { setError(t('auth.consentRequired')); return; }
    setError('');
    try {
      await api('/api/v1/me/consents', { method: 'POST', body: { documents: pending, client: 'WEB' } });
      await reloadUser();
    } catch (err) {
      setError(describeError(err));
    }
  }

  const shown = docs.filter(d => pending.includes(`${d.kind}:${d.version}`));
  return (
    <AuthCard title={t('auth.consentTitle')} subtitle={t('auth.consentIntro')}>
      {shown.map(d => (
        <article className="legal-document" key={d.kind}>
          <h3>{d.title}</h3>
          {d.changes && <p className="hint">{t('auth.whatChanged', { changes: d.changes })}</p>}
          <div className="legal-text">{d.body}</div>
        </article>
      ))}
      <label className="check"><input type="checkbox" checked={agreed} onChange={e => setAgreed(e.target.checked)} />{t('auth.consentAgree')}</label>
      <ErrorBanner message={error} />
      <button className="primary" onClick={() => void accept()}>{t('auth.consentAccept')}</button>
      <button type="button" className="secondary" onClick={() => void logout()}>{t('auth.signOut')}</button>
    </AuthCard>
  );
}
