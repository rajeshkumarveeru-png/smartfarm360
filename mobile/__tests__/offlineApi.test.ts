const store: Record<string, string> = {};
jest.mock('expo-secure-store', () => ({
  getItemAsync: jest.fn(async (k: string) => store[k] ?? null),
  setItemAsync: jest.fn(async (k: string, v: string) => { store[k] = v; }),
  deleteItemAsync: jest.fn(async (k: string) => { delete store[k]; }),
}));

process.env.EXPO_PUBLIC_API_URL = 'http://farm.test';

// eslint-disable-next-line @typescript-eslint/no-require-imports
const api = require('../src/services/api') as typeof import('../src/services/api');
import app from '../app.json';
import { APP_VERSION } from '../src/offline/version';

const reply = (status: number, body: unknown, date?: string) => ({ ok: status >= 200 && status < 300, status, json: async () => body,
  text: async () => JSON.stringify(body), headers: { get: (h: string) => (h.toLowerCase() === 'date' ? date ?? null : null) } });
const fetchMock = jest.fn();
(global as unknown as { fetch: jest.Mock }).fetch = fetchMock;

beforeEach(() => { fetchMock.mockReset(); api.__resetForTests(); for (const k of Object.keys(store)) delete store[k]; });

test('every request carries the app version (N14)', async () => {
  expect(APP_VERSION).toBe(app.expo.version);
  fetchMock.mockResolvedValueOnce(reply(200, []));
  await api.apiRequest('/api/v1/tenants');
  expect(fetchMock.mock.calls[0][1].headers['X-Client-Version']).toBe(APP_VERSION);
});

test('farm data read online is served from the phone with its time when the network is down (N3)', async () => {
  const cache = new Map<string, { value: unknown; asOf: string }>();
  const served: string[] = [];
  api.setReadCache({ get: async p => cache.get(p) ?? null, put: async (p, v) => { cache.set(p, { value: v, asOf: '2026-10-19T14:10:00Z' }); } },
    (_p, asOf) => served.push(asOf));
  fetchMock.mockResolvedValueOnce(reply(200, [{ id: 3, name: 'Milking herd' }]));
  await api.apiRequest('/api/v1/tenants/7/livestock/groups');
  fetchMock.mockRejectedValueOnce(new Error('offline'));
  await expect(api.apiRequest('/api/v1/tenants/7/livestock/groups')).resolves.toEqual([{ id: 3, name: 'Milking herd' }]);
  expect(served).toEqual(['2026-10-19T14:10:00Z']);
  // Nothing cached: the network error reaches the screen; writes are never answered from the cache
  fetchMock.mockRejectedValueOnce(new Error('offline'));
  await expect(api.apiRequest('/api/v1/tenants/7/aquaculture/ponds')).rejects.toMatchObject({ code: 'NETWORK' });
  fetchMock.mockRejectedValueOnce(new Error('offline'));
  await expect(api.apiRequest('/api/v1/tenants/7/livestock/groups', { method: 'POST', body: {} })).rejects.toMatchObject({ code: 'NETWORK' });
});

test('an outbox record is sent with its client id and the reply is returned, never thrown (N4)', async () => {
  const dates: (string | null)[] = [];
  api.onServerDate(d => dates.push(d));
  fetchMock.mockResolvedValueOnce(reply(409, { code: 'STALE_RECORD', detail: 'Someone else changed this' }, 'Tue, 20 Oct 2026 05:30:00 GMT'));
  const r = await api.sendRecord('/api/v1/tenants/7/workforce/attendance/2026-10-20', 'PUT', { entries: [] }, '0192a6f0-0001');
  expect(r).toEqual({ status: 409, code: 'STALE_RECORD', message: 'Someone else changed this' });
  expect(fetchMock.mock.calls[0][1].headers['Idempotency-Key']).toBe('0192a6f0-0001');
  expect(dates).toEqual(['Tue, 20 Oct 2026 05:30:00 GMT']);
  fetchMock.mockRejectedValueOnce(new Error('offline'));
  await expect(api.sendRecord('/x', 'POST', {}, 'k-12345678')).resolves.toEqual({ status: 0, code: 'NETWORK' });
});

test('a 401 while sending refreshes the session once, then retries the same record', async () => {
  store[api.REFRESH_TOKEN_KEY] = 'r1';
  fetchMock
    .mockResolvedValueOnce(reply(401, { code: 'UNAUTHENTICATED' }))
    .mockResolvedValueOnce(reply(200, { accessToken: 'a2', refreshToken: 'r2', expiresIn: 900, user: { id: 1 } }))
    .mockResolvedValueOnce(reply(201, { id: 9 }));
  await expect(api.sendRecord('/api/v1/tenants/7/x', 'POST', {}, 'k-12345678')).resolves.toEqual({ status: 201, body: { id: 9 } });
  expect(fetchMock.mock.calls[2][1].headers).toMatchObject({ Authorization: 'Bearer a2', 'Idempotency-Key': 'k-12345678' });
  expect(await api.hasStoredSession()).toBe(true);
});
