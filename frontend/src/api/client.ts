/**
 * API client. The access token lives only in memory; the refresh token is an HttpOnly cookie the browser sends
 * to /api/v1/auth. On a 401 the client refreshes once (shared by concurrent requests) and retries.
 */
const BASE = (import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/$/, '');
const REFRESH_HEADER = 'X-SF360-Refresh';

export type FieldError = { field: string; code: string; message: string };

export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string, public fieldErrors: FieldError[] = []) {
    super(message);
    this.name = 'ApiError';
  }
}

export type User = { id: number; email: string; displayName: string; preferredLanguage: 'en' | 'ta'; platformAdmin: boolean };
export type TokenResponse = { accessToken: string; tokenType: string; expiresIn: number; user: User };

let accessToken: string | null = null;
let refreshing: Promise<TokenResponse | null> | null = null;
let sessionEnded: () => void = () => {};

export function setAccessToken(token: string | null) { accessToken = token; }
export function hasAccessToken() { return accessToken !== null; }
export function onSessionEnded(handler: () => void) { sessionEnded = handler; }

async function doRefresh(): Promise<TokenResponse | null> {
  try {
    const r = await fetch(`${BASE}/api/v1/auth/refresh`, { method: 'POST', credentials: 'include', headers: { [REFRESH_HEADER]: '1' } });
    if (!r.ok) { accessToken = null; return null; }
    const body = (await r.json()) as TokenResponse;
    accessToken = body.accessToken;
    return body;
  } catch {
    return null;
  }
}

/**
 * Exchanges the refresh cookie for a new access token. Concurrent callers in this tab share one request, and the
 * Web Locks API serializes refreshes across tabs: the server treats a second use of a rotated token as theft, so two
 * tabs must never send the same cookie at once. A tab that waited for the lock sends the cookie the first tab received.
 */
export function refreshSession(): Promise<TokenResponse | null> {
  if (!refreshing) {
    const locks = typeof navigator !== 'undefined' ? navigator.locks : undefined;
    const run = locks ? (async () => await locks.request('sf360-refresh', doRefresh))() : doRefresh();
    refreshing = run.finally(() => { refreshing = null; });
  }
  return refreshing;
}

type Init = { method?: string; body?: unknown; headers?: Record<string, string> };

export async function api<T>(path: string, init: Init = {}, retry = true): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json', ...(init.headers ?? {}) };
  if (init.body !== undefined) headers['Content-Type'] = 'application/json';
  if (accessToken) headers.Authorization = `Bearer ${accessToken}`;
  let response: Response;
  try {
    response = await fetch(`${BASE}${path}`, {
      method: init.method ?? 'GET',
      headers,
      body: init.body === undefined ? undefined : JSON.stringify(init.body),
      credentials: 'include',
    });
  } catch {
    throw new ApiError(0, 'NETWORK', 'Network error');
  }
  if (response.status === 401 && retry && !path.startsWith('/api/v1/auth/')) {
    if (await refreshSession()) return api<T>(path, init, false);
    sessionEnded();
  }
  if (!response.ok) throw await toApiError(response);
  if (response.status === 204) return undefined as T;
  const text = await response.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

/** Sign-out also needs the refresh header so the server can revoke the cookie's token family. */
export async function logoutRequest() {
  try {
    await fetch(`${BASE}/api/v1/auth/logout`, { method: 'POST', credentials: 'include', headers: { [REFRESH_HEADER]: '1' } });
  } finally {
    accessToken = null;
  }
}

async function toApiError(response: Response): Promise<ApiError> {
  try {
    const body = await response.json();
    return new ApiError(response.status, body.code ?? 'REQUEST_FAILED', body.detail ?? '', Array.isArray(body.errors) ? body.errors : []);
  } catch {
    return new ApiError(response.status, response.status >= 500 ? 'INTERNAL_ERROR' : 'REQUEST_FAILED', '');
  }
}
