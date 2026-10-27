// Reports and exports: 20 users exporting large reports at once while others browse; and a sign-in burst of 100 a
// minute (section 12). Targets: each export < 10 s, other users' p95 < 1 s; sign-in p95 < 800 ms with limits holding.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE, account, signIn } from './common.js';

export const options = {
  scenarios: {
    exporters: { executor: 'constant-vus', vus: 20, duration: '10m', exec: 'exporter' },
    browsers: { executor: 'constant-vus', vus: 50, duration: '10m', exec: 'browser', startTime: '10s' },
    signins: { executor: 'constant-arrival-rate', rate: 100, timeUnit: '1m', duration: '10m', preAllocatedVUs: 20, exec: 'signin' },
  },
  thresholds: {
    'http_req_duration{name:export}': ['p(100)<10000'],
    'http_req_duration{name:browse}': ['p(95)<1000'],
    'http_req_duration{name:login}': ['p(95)<800'],
  },
};

const sessions = {};
const session = vu => (sessions[vu] = sessions[vu] || signIn(account(vu).email, account(vu).password));

export function exporter() {
  const { headers, tenant } = session(__VU);
  const r = http.get(`${BASE}/api/v1/tenants/${tenant}/finance/reports/trial-balance?asOf=${new Date().toISOString().slice(0, 10)}&format=xlsx`,
    { headers, tags: { name: 'export' }, responseType: 'binary' });
  check(r, { 'export 200': x => x.status === 200 });
  sleep(5);
}

export function browser() {
  const { headers, tenant } = session(__VU + 100);
  check(http.get(`${BASE}/api/v1/tenants/${tenant}/dashboard`, { headers, tags: { name: 'browse' } }), { 'dashboard 200': r => r.status === 200 });
  sleep(2);
}

export function signin() {
  const { email, password } = account(__ITER + 1);
  const r = http.post(`${BASE}/api/v1/auth/login`, JSON.stringify({ email, password, refreshTokenDelivery: 'BODY' }),
    { headers: { 'Content-Type': 'application/json' }, tags: { name: 'login' } });
  check(r, { 'signed in or rate limited': x => x.status === 200 || x.status === 429 });
}
