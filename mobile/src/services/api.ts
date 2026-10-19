/** API boundary for the future SmartFarm360 backend. No request is made until configured. */
export const API_BASE_URL = (process.env.EXPO_PUBLIC_API_URL ?? '').trim().replace(/\/$/, '');
export const isApiConfigured = API_BASE_URL.length > 0;

export async function apiRequest<T>(path: string, token?: string, init: RequestInit = {}): Promise<T> {
  if (!isApiConfigured) throw new Error('SmartFarm360 API is not configured. Entries are currently stored locally on this device.');
  const response = await fetch(`${API_BASE_URL}${path.startsWith('/') ? path : `/${path}`}`, {
    ...init,
    headers: {
      Accept: 'application/json',
      ...(init.body ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...init.headers,
    },
  });
  if (!response.ok) throw new Error(`API request failed (${response.status})`);
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}
