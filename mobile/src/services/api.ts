import * as SecureStore from 'expo-secure-store';

/**
 * API client for the mobile app. The access token is kept in memory only; the refresh token is stored in the
 * platform keystore (expo-secure-store) and rotated on every refresh. Concurrent 401s share one refresh.
 */
export const API_BASE_URL = (process.env.EXPO_PUBLIC_API_URL ?? '').trim().replace(/\/$/, '').replace(/\/api$/, '');
export const isApiConfigured = API_BASE_URL.length > 0;
export const REFRESH_TOKEN_KEY = 'sf360.refreshToken';

export type User = { id: number; email: string; displayName: string; preferredLanguage: 'en' | 'ta'; platformAdmin: boolean };
export type TokenResponse = { accessToken: string; refreshToken: string; expiresIn: number; user: User };

export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string) {
    super(message);
    this.name = 'ApiError';
  }
}

let accessToken: string | null = null;
let refreshing: Promise<TokenResponse | null> | null = null;
let sessionEnded: () => void = () => {};

export function onSessionEnded(handler: () => void) { sessionEnded = handler; }

async function raw(path: string, init: { method?: string; body?: unknown; token?: string | null } = {}): Promise<Response> {
  if (!isApiConfigured) throw new ApiError(0, 'API_NOT_CONFIGURED', 'SmartFarm360 API is not configured.');
  try {
    return await fetch(`${API_BASE_URL}${path}`, {
      method: init.method ?? 'GET',
      headers: {
        Accept: 'application/json',
        ...(init.body !== undefined ? { 'Content-Type': 'application/json' } : {}),
        ...(init.token ? { Authorization: `Bearer ${init.token}` } : {}),
      },
      body: init.body === undefined ? undefined : JSON.stringify(init.body),
    });
  } catch {
    throw new ApiError(0, 'NETWORK', 'Network error');
  }
}

async function toError(response: Response): Promise<ApiError> {
  try {
    const body = await response.json();
    return new ApiError(response.status, body.code ?? 'REQUEST_FAILED', body.detail ?? '');
  } catch {
    return new ApiError(response.status, 'REQUEST_FAILED', '');
  }
}

async function storeSession(session: TokenResponse) {
  accessToken = session.accessToken;
  await SecureStore.setItemAsync(REFRESH_TOKEN_KEY, session.refreshToken);
}

export async function signIn(email: string, password: string): Promise<TokenResponse> {
  const response = await raw('/api/v1/auth/login', { method: 'POST', body: { email, password, refreshTokenDelivery: 'BODY' } });
  if (!response.ok) throw await toError(response);
  const session = (await response.json()) as TokenResponse;
  await storeSession(session);
  return session;
}

/** Restores a session from the stored refresh token (used at app start and after a 401). */
export function refreshSession(): Promise<TokenResponse | null> {
  if (!refreshing) {
    refreshing = (async () => {
      const stored = await SecureStore.getItemAsync(REFRESH_TOKEN_KEY);
      if (!stored) return null;
      const response = await raw('/api/v1/auth/refresh', { method: 'POST', body: { refreshToken: stored } }).catch(() => null);
      if (!response) return null; // offline: keep the stored token and try again later
      if (!response.ok) {
        accessToken = null;
        await SecureStore.deleteItemAsync(REFRESH_TOKEN_KEY);
        return null;
      }
      const session = (await response.json()) as TokenResponse;
      await storeSession(session);
      return session;
    })().finally(() => { refreshing = null; });
  }
  return refreshing;
}

export async function signOut() {
  const stored = await SecureStore.getItemAsync(REFRESH_TOKEN_KEY);
  accessToken = null;
  await SecureStore.deleteItemAsync(REFRESH_TOKEN_KEY);
  if (stored) await raw('/api/v1/auth/logout', { method: 'POST', body: { refreshToken: stored } }).catch(() => undefined);
}

export async function apiRequest<T>(path: string, init: { method?: string; body?: unknown } = {}, retry = true): Promise<T> {
  const response = await raw(path, { ...init, token: accessToken });
  if (response.status === 401 && retry) {
    if (await refreshSession()) return apiRequest<T>(path, init, false);
    sessionEnded();
  }
  if (!response.ok) throw await toError(response);
  if (response.status === 204) return undefined as T;
  const text = await response.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

/** For tests only. */
export function __resetForTests() { accessToken = null; refreshing = null; sessionEnded = () => {}; }
