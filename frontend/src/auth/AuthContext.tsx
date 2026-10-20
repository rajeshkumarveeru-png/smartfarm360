import React, { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { api, logoutRequest, onSessionEnded, refreshSession, setAccessToken, type TokenResponse, type User } from '../api/client';
import type { TenantSummary } from '../api/types';
import { currentLanguage, setLanguage, type Lang } from '../i18n';

type Status = 'loading' | 'signedOut' | 'signedIn';

type AuthState = {
  status: Status;
  user: User | null;
  tenants: TenantSummary[];
  sessionExpired: boolean;
  login: (email: string, password: string) => Promise<void>;
  register: (email: string, password: string, displayName: string) => Promise<void>;
  logout: () => Promise<void>;
  reloadTenants: () => Promise<TenantSummary[]>;
  changeLanguage: (lang: Lang) => Promise<void>;
};

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [status, setStatus] = useState<Status>('loading');
  const [user, setUser] = useState<User | null>(null);
  const [tenants, setTenants] = useState<TenantSummary[]>([]);
  const [sessionExpired, setSessionExpired] = useState(false);

  const reloadTenants = useCallback(async () => {
    const list = await api<TenantSummary[]>('/api/v1/tenants');
    setTenants(list);
    return list;
  }, []);

  const startSession = useCallback(async (session: TokenResponse) => {
    setAccessToken(session.accessToken);
    setUser(session.user);
    if (session.user.preferredLanguage !== currentLanguage()) setLanguage(session.user.preferredLanguage);
    await reloadTenants();
    setSessionExpired(false);
    setStatus('signedIn');
  }, [reloadTenants]);

  useEffect(() => {
    onSessionEnded(() => {
      setAccessToken(null);
      setUser(null);
      setTenants([]);
      setSessionExpired(true);
      setStatus('signedOut');
    });
    let alive = true;
    refreshSession().then(async session => {
      if (!alive) return;
      if (session) await startSession(session).catch(() => setStatus('signedOut'));
      else setStatus('signedOut');
    });
    return () => { alive = false; };
  }, [startSession]);

  const login = useCallback(async (email: string, password: string) => {
    const session = await api<TokenResponse>('/api/v1/auth/login', { method: 'POST', body: { email, password } });
    await startSession(session);
  }, [startSession]);

  const register = useCallback(async (email: string, password: string, displayName: string) => {
    await api('/api/v1/auth/register', { method: 'POST', body: { email, password, displayName, preferredLanguage: currentLanguage() } });
    await login(email, password);
  }, [login]);

  const logout = useCallback(async () => {
    await logoutRequest();
    setUser(null);
    setTenants([]);
    setStatus('signedOut');
  }, []);

  const changeLanguage = useCallback(async (lang: Lang) => {
    setLanguage(lang);
    if (user) {
      const updated = await api<User>('/api/v1/auth/me', { method: 'PATCH', body: { preferredLanguage: lang } }).catch(() => null);
      if (updated) setUser(updated);
    }
  }, [user]);

  const value = useMemo(() => ({ status, user, tenants, sessionExpired, login, register, logout, reloadTenants, changeLanguage }),
    [status, user, tenants, sessionExpired, login, register, logout, reloadTenants, changeLanguage]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const value = useContext(AuthContext);
  if (!value) throw new Error('useAuth must be used inside AuthProvider');
  return value;
}
