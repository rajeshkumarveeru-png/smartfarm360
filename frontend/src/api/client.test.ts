import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api, ApiError, onSessionEnded, setAccessToken } from './client';

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

describe('api client', () => {
  const fetchMock = vi.fn();
  beforeEach(() => {
    fetchMock.mockReset();
    vi.stubGlobal('fetch', fetchMock);
    setAccessToken('old-token');
  });
  afterEach(() => vi.unstubAllGlobals());

  it('refreshes once on 401 and retries with the new token', async () => {
    fetchMock
      .mockResolvedValueOnce(json(401, { code: 'UNAUTHENTICATED' }))
      .mockResolvedValueOnce(json(200, { accessToken: 'new-token', user: {} }))
      .mockResolvedValueOnce(json(200, [{ id: 1 }]));
    await expect(api('/api/v1/tenants')).resolves.toEqual([{ id: 1 }]);
    const [refreshUrl, refreshInit] = fetchMock.mock.calls[1];
    expect(refreshUrl).toBe('/api/v1/auth/refresh');
    expect(refreshInit.headers['X-SF360-Refresh']).toBe('1');
    expect(fetchMock.mock.calls[2][1].headers.Authorization).toBe('Bearer new-token');
  });

  it('shares one refresh between concurrent requests', async () => {
    let refreshCalls = 0;
    fetchMock.mockImplementation(async (url: string, init: RequestInit) => {
      if (url === '/api/v1/auth/refresh') { refreshCalls++; return json(200, { accessToken: 'fresh', user: {} }); }
      const auth = (init.headers as Record<string, string>).Authorization;
      return auth === 'Bearer fresh' ? json(200, { ok: true }) : json(401, { code: 'UNAUTHENTICATED' });
    });
    await Promise.all([api('/a'), api('/b'), api('/c')]);
    expect(refreshCalls).toBe(1);
  });

  it('holds a cross-tab lock while refreshing when the browser supports Web Locks', async () => {
    const request = vi.fn((_name: string, callback: () => Promise<unknown>) => callback());
    vi.stubGlobal('navigator', { ...navigator, locks: { request } });
    fetchMock
      .mockResolvedValueOnce(json(401, { code: 'UNAUTHENTICATED' }))
      .mockResolvedValueOnce(json(200, { accessToken: 'locked', user: {} }))
      .mockResolvedValueOnce(json(200, {}));
    await api('/api/v1/tenants');
    expect(request).toHaveBeenCalledWith('sf360-refresh', expect.any(Function));
  });

  it('ends the session when the refresh fails', async () => {
    const ended = vi.fn();
    onSessionEnded(ended);
    fetchMock
      .mockResolvedValueOnce(json(401, { code: 'UNAUTHENTICATED' }))
      .mockResolvedValueOnce(json(401, { code: 'INVALID_REFRESH_TOKEN' }))
      .mockResolvedValueOnce(json(401, { code: 'UNAUTHENTICATED' }));
    await expect(api('/api/v1/tenants')).rejects.toBeInstanceOf(ApiError);
    expect(ended).toHaveBeenCalledOnce();
  });

  it('turns problem responses into ApiError with code and field errors', async () => {
    fetchMock.mockResolvedValueOnce(json(400, { code: 'VALIDATION_FAILED', detail: 'bad', errors: [{ field: 'name', code: 'REQUIRED', message: 'x' }] }));
    const error = (await api('/api/v1/tenants/1/farms', { method: 'POST', body: {} }).catch((e: unknown) => e)) as ApiError;
    expect(error).toMatchObject({ status: 400, code: 'VALIDATION_FAILED' });
    expect(error.fieldErrors[0].field).toBe('name');
  });

  it('reports network failures with a NETWORK code', async () => {
    fetchMock.mockRejectedValueOnce(new TypeError('Failed to fetch'));
    await expect(api('/x')).rejects.toMatchObject({ code: 'NETWORK' });
  });
});
