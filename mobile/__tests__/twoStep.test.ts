const store: Record<string, string> = {};
jest.mock('expo-secure-store', () => ({
  getItemAsync: jest.fn(async (k: string) => store[k] ?? null),
  setItemAsync: jest.fn(async (k: string, v: string) => { store[k] = v; }),
  deleteItemAsync: jest.fn(async (k: string) => { delete store[k]; }),
}));

process.env.EXPO_PUBLIC_API_URL = 'http://farm.test';

// eslint-disable-next-line @typescript-eslint/no-require-imports
const api = require('../src/services/api') as typeof import('../src/services/api');

const reply = (status: number, body: unknown) => ({ ok: status >= 200 && status < 300, status, json: async () => body, text: async () => JSON.stringify(body), headers: { get: () => null } });
const fetchMock = jest.fn();
(global as unknown as { fetch: jest.Mock }).fetch = fetchMock;

beforeEach(() => { fetchMock.mockReset(); api.__resetForTests(); for (const k of Object.keys(store)) delete store[k]; });

test('with two-step sign-in on, the password alone keeps no session; the code from the app opens it (O6)', async () => {
  fetchMock.mockResolvedValueOnce(reply(200, { accessToken: null, refreshToken: null, user: null, secondStepChallenge: 'challenge-1' }));
  const first = await api.signIn('arun@example.com', 'correct-horse-battery');
  expect(first.secondStepChallenge).toBe('challenge-1');
  expect(store[api.REFRESH_TOKEN_KEY]).toBeUndefined();

  fetchMock.mockResolvedValueOnce(reply(200, { accessToken: 'a1', refreshToken: 'r1', expiresIn: 900, user: { id: 3 } }));
  const session = await api.completeSecondStep('challenge-1', ' 123456 ');
  expect(session.user.id).toBe(3);
  const [url, init] = fetchMock.mock.calls[1];
  expect(url).toBe('http://farm.test/api/v1/auth/login/second-step');
  expect(JSON.parse(init.body)).toEqual({ challenge: 'challenge-1', refreshTokenDelivery: 'BODY', code: '123456' });
  expect(store[api.REFRESH_TOKEN_KEY]).toBe('r1');

  fetchMock.mockResolvedValueOnce(reply(401, { code: 'INVALID_TWO_STEP_CODE' }));
  await expect(api.completeSecondStep('challenge-1', '12345-67890', true)).rejects.toMatchObject({ code: 'INVALID_TWO_STEP_CODE' });
  expect(JSON.parse(fetchMock.mock.calls[2][1].body)).toMatchObject({ recoveryCode: '12345-67890' });
});
