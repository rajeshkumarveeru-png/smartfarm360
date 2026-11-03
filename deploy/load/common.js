// Shared helpers for the k6 load tests (SaaS operations design, section 12). Run against staging only.
import http from 'k6/http';
import { check } from 'k6';

export const BASE = __ENV.BASE_URL || 'https://staging.example.in';

/** Signs in a load-test account (seeded on staging by seed-staging.md) and returns its headers and first farm business. */
export function signIn(email, password) {
  const r = http.post(`${BASE}/api/v1/auth/login`, JSON.stringify({ email, password, refreshTokenDelivery: 'BODY' }),
    { headers: { 'Content-Type': 'application/json' }, tags: { name: 'login' } });
  check(r, { 'signed in': x => x.status === 200 && !!x.json('accessToken') });
  const headers = { Authorization: `Bearer ${r.json('accessToken')}`, 'Content-Type': 'application/json' };
  const tenants = http.get(`${BASE}/api/v1/tenants`, { headers, tags: { name: 'tenants' } }).json();
  return { headers, tenant: tenants[0].id, refreshToken: r.json('refreshToken') };
}

/** Load-test accounts: LOAD_USERS="a@x:pw,b@x:pw" or LOAD_USER_PREFIX + LOAD_PASSWORD (load-001@..., load-002@...). */
export function account(vu) {
  if (__ENV.LOAD_USERS) {
    const list = __ENV.LOAD_USERS.split(',');
    const [email, password] = list[(vu - 1) % list.length].split(':');
    return { email, password };
  }
  const n = String(((vu - 1) % Number(__ENV.LOAD_ACCOUNTS || 200)) + 1).padStart(3, '0');
  return { email: `${__ENV.LOAD_USER_PREFIX || 'load'}-${n}@${__ENV.LOAD_DOMAIN || 'loadtest.invalid'}`, password: __ENV.LOAD_PASSWORD };
}

export function uuid7() {
  const ms = Date.now().toString(16).padStart(12, '0');
  const rnd = () => Math.floor(Math.random() * 0x10000).toString(16).padStart(4, '0');
  return `${ms.slice(0, 8)}-${ms.slice(8, 12)}-7${rnd().slice(1)}-${(8 + Math.floor(Math.random() * 4)).toString(16)}${rnd().slice(1)}-${rnd()}${rnd()}${rnd()}`;
}
