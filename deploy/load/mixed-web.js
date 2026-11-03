// Mixed web use: 200 concurrent users for 30 minutes (section 12). Target: p95 < 500 ms reads, < 800 ms saves, no errors.
//   k6 run -e BASE_URL=https://staging... -e LOAD_PASSWORD=... deploy/load/mixed-web.js
import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE, account, signIn } from './common.js';

export const options = {
  scenarios: { web: { executor: 'constant-vus', vus: Number(__ENV.VUS || 200), duration: __ENV.DURATION || '30m' } },
  thresholds: {
    'http_req_duration{kind:read}': ['p(95)<500'],
    'http_req_duration{kind:save}': ['p(95)<800'],
    http_req_failed: ['rate<0.001'],
  },
};

let session;

export default function () {
  if (!session) session = signIn(account(__VU).email, account(__VU).password);
  const { headers, tenant } = session;
  const t = `${BASE}/api/v1/tenants/${tenant}`;
  const today = new Date().toISOString().slice(0, 10);
  const read = (path, name) => check(http.get(`${t}${path}`, { headers, tags: { kind: 'read', name } }), { [`${name} 200`]: r => r.status === 200 });

  read('/dashboard', 'dashboard');
  sleep(2);
  read('/finance/documents?size=20', 'records');
  read('/cultivation-cycles?size=20', 'cycles');
  sleep(2);
  read(`/workforce/attendance/${today}`, 'attendance');
  read('/stock/items', 'stock');
  sleep(1);
  // One in five iterations saves a field note (an ordinary write with its own row)
  if (Math.random() < 0.2) {
    const cycles = http.get(`${t}/cultivation-cycles?size=1`, { headers, tags: { kind: 'read', name: 'cycles' } }).json('items');
    if (cycles && cycles.length) {
      const r = http.post(`${t}/cultivation-cycles/${cycles[0].id}/observations`, JSON.stringify({ observedOn: today, kind: 'OTHER', severity: 'LOW',
        notes: 'load test' }), { headers, tags: { kind: 'save', name: 'observation' } });
      check(r, { 'observation saved': x => x.status === 201 });
    }
  }
  sleep(3);
}
