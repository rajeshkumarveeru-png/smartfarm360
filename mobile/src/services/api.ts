import * as SecureStore from 'expo-secure-store';
import { APP_VERSION } from '../offline/version';

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

/**
 * The signed-in user's cache of server reads (mobile offline design, N3): every successful GET of a farm business's data
 * is kept, and served with its "as of" time when the network is down.
 */
export type ReadCache = { get(path: string): Promise<{ value: unknown; asOf: string } | null>; put(path: string, value: unknown): Promise<void> };
let readCache: ReadCache | null = null;
let cacheServed: (path: string, asOf: string) => void = () => {};
let serverDate: (date: string | null) => void = () => {};

export function setReadCache(cache: ReadCache | null, onServed: (path: string, asOf: string) => void = () => {}) {
  readCache = cache;
  cacheServed = onServed;
}

/** Called with the Date header of each reply, for the clock check (N9). */
export function onServerDate(handler: (date: string | null) => void) { serverDate = handler; }

const cacheable = (path: string) => path.startsWith('/api/v1/tenants');

async function raw(path: string, init: { method?: string; body?: unknown; token?: string | null; headers?: Record<string, string> } = {}): Promise<Response> {
  if (!isApiConfigured) throw new ApiError(0, 'API_NOT_CONFIGURED', 'SmartFarm360 API is not configured.');
  try {
    return await fetch(`${API_BASE_URL}${path}`, {
      method: init.method ?? 'GET',
      headers: {
        Accept: 'application/json',
        'X-Client-Version': APP_VERSION,
        ...(init.body !== undefined ? { 'Content-Type': 'application/json' } : {}),
        ...(init.token ? { Authorization: `Bearer ${init.token}` } : {}),
        ...(init.headers ?? {}),
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

export async function apiRequest<T>(path: string, init: { method?: string; body?: unknown; headers?: Record<string, string> } = {}, retry = true): Promise<T> {
  const read = (init.method ?? 'GET') === 'GET' && cacheable(path);
  let response: Response;
  try {
    response = await raw(path, { ...init, token: accessToken });
  } catch (e) {
    const cached = read && readCache ? await readCache.get(path).catch(() => null) : null;
    if (cached) { cacheServed(path, cached.asOf); return cached.value as T; }
    throw e;
  }
  serverDate(response.headers?.get?.('date') ?? null);
  if (response.status === 401 && retry) {
    if (await refreshSession()) return apiRequest<T>(path, init, false);
    sessionEnded();
  }
  if (!response.ok) throw await toError(response);
  if (response.status === 204) return undefined as T;
  const text = await response.text();
  const value = (text ? JSON.parse(text) : undefined) as T;
  if (read && readCache && value !== undefined) await readCache.put(path, value).catch(() => undefined);
  return value;
}

/** A reply to an outbox record; status 0 means no reply arrived. */
export type RecordReply = { status: number; code?: string; message?: string; body?: unknown };

/**
 * Sends one outbox record with its client id as the Idempotency-Key (N4). Never throws: the sync engine decides what a
 * reply means. A 401 is retried once after refreshing the session.
 */
export async function sendRecord(path: string, method: string, body: unknown, key: string, retry = true): Promise<RecordReply> {
  let response: Response;
  try {
    response = await raw(path, { method, body, token: accessToken, headers: { 'Idempotency-Key': key } });
  } catch {
    return { status: 0, code: 'NETWORK' };
  }
  serverDate(response.headers?.get?.('date') ?? null);
  if (response.status === 401 && retry) {
    const refreshed = await refreshSession();
    if (refreshed) return sendRecord(path, method, body, key, false);
    if (!(await SecureStore.getItemAsync(REFRESH_TOKEN_KEY))) sessionEnded();
    return { status: 401, code: 'UNAUTHORIZED' };
  }
  if (!response.ok) {
    const error = await toError(response);
    return { status: response.status, code: error.code, message: error.message };
  }
  const text = response.status === 204 ? '' : await response.text().catch(() => '');
  let parsed: unknown;
  try { parsed = text ? JSON.parse(text) : undefined; } catch { parsed = undefined; }
  return { status: response.status, body: parsed };
}

/** True when a refresh token is kept on the phone (the user may still be signed in while offline, N10). */
export async function hasStoredSession(): Promise<boolean> {
  return !!(await SecureStore.getItemAsync(REFRESH_TOKEN_KEY).catch(() => null));
}

/** For tests only. */
export function __resetForTests() { accessToken = null; refreshing = null; sessionEnded = () => {}; readCache = null; cacheServed = () => {}; serverDate = () => {}; }
