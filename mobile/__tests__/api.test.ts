const store: Record<string, string> = {};
jest.mock('expo-secure-store', () => ({
  getItemAsync: jest.fn(async (k: string) => store[k] ?? null),
  setItemAsync: jest.fn(async (k: string, v: string) => { store[k] = v; }),
  deleteItemAsync: jest.fn(async (k: string) => { delete store[k]; }),
}));

process.env.EXPO_PUBLIC_API_URL = 'http://farm.test/api';

// Loaded after the env var is set: the module reads it at import time.
// eslint-disable-next-line @typescript-eslint/no-require-imports
const { __resetForTests, apiRequest, onSessionEnded, REFRESH_TOKEN_KEY, refreshSession, signIn, signOut } = require('../src/services/api') as typeof import('../src/services/api');

const json = (status: number, body: unknown) => ({ ok: status >= 200 && status < 300, status, json: async () => body, text: async () => JSON.stringify(body) });
const session = (access: string, refresh: string) => ({ accessToken: access, refreshToken: refresh, expiresIn: 900, user: { id: 1 } });
const fetchMock = jest.fn();
(global as unknown as { fetch: jest.Mock }).fetch = fetchMock;

beforeEach(() => {
  fetchMock.mockReset();
  for (const k of Object.keys(store)) delete store[k];
  __resetForTests();
});

test('sign-in asks for the refresh token in the body and keeps it only in secure storage', async () => {
  fetchMock.mockResolvedValueOnce(json(200, session('a1', 'r1')));
  await signIn('farmer@example.com', 'secret-password');
  const [url, init] = fetchMock.mock.calls[0];
  expect(url).toBe('http://farm.test/api/v1/auth/login'); // a trailing /api in the env var is tolerated
  expect(JSON.parse(init.body)).toMatchObject({ refreshTokenDelivery: 'BODY' });
  expect(store[REFRESH_TOKEN_KEY]).toBe('r1');
});

test('a 401 triggers one refresh, stores the rotated token and retries', async () => {
  store[REFRESH_TOKEN_KEY] = 'r1';
  fetchMock
    .mockResolvedValueOnce(json(401, { code: 'UNAUTHENTICATED' }))
    .mockResolvedValueOnce(json(200, session('a2', 'r2')))
    .mockResolvedValueOnce(json(200, [{ id: 7 }]));
  await expect(apiRequest('/api/v1/tenants')).resolves.toEqual([{ id: 7 }]);
  expect(JSON.parse(fetchMock.mock.calls[1][1].body)).toEqual({ refreshToken: 'r1' });
  expect(fetchMock.mock.calls[2][1].headers.Authorization).toBe('Bearer a2');
  expect(store[REFRESH_TOKEN_KEY]).toBe('r2');
});

test('concurrent requests share a single refresh (so rotation is not mistaken for token reuse)', async () => {
  store[REFRESH_TOKEN_KEY] = 'r1';
  let refreshes = 0;
  fetchMock.mockImplementation(async (url: string, init: { headers: Record<string, string> }) => {
    if (url.endsWith('/auth/refresh')) { refreshes++; return json(200, session('fresh', 'r2')); }
    return init.headers.Authorization === 'Bearer fresh' ? json(200, {}) : json(401, {});
  });
  await Promise.all([apiRequest('/a'), apiRequest('/b'), apiRequest('/c')]);
  expect(refreshes).toBe(1);
});

test('a rejected refresh token is deleted and the session ends', async () => {
  store[REFRESH_TOKEN_KEY] = 'stolen-or-expired';
  const ended = jest.fn();
  onSessionEnded(ended);
  fetchMock.mockResolvedValueOnce(json(401, {})).mockResolvedValueOnce(json(401, { code: 'INVALID_REFRESH_TOKEN' }));
  await expect(apiRequest('/api/v1/tenants')).rejects.toMatchObject({ status: 401 });
  expect(store[REFRESH_TOKEN_KEY]).toBeUndefined();
  expect(ended).toHaveBeenCalledTimes(1);
});

test('being offline keeps the stored refresh token for later', async () => {
  store[REFRESH_TOKEN_KEY] = 'r1';
  fetchMock.mockRejectedValueOnce(new TypeError('Network request failed'));
  await expect(refreshSession()).resolves.toBeNull();
  expect(store[REFRESH_TOKEN_KEY]).toBe('r1');
});

test('sign-out deletes the token and revokes it on the server', async () => {
  store[REFRESH_TOKEN_KEY] = 'r1';
  fetchMock.mockResolvedValueOnce(json(204, {}));
  await signOut();
  expect(store[REFRESH_TOKEN_KEY]).toBeUndefined();
  expect(fetchMock.mock.calls[0][0]).toBe('http://farm.test/api/v1/auth/logout');
  expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({ refreshToken: 'r1' });
});
