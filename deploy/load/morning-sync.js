// Morning phone sync: 300 phones each sending 30 records within 10 minutes, with 10% retries of the same client id
// (section 12). Target: every record stored once; p95 < 1 s; no 5xx.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';
import { BASE, account, signIn, uuid7 } from './common.js';

const replays = new Counter('replayed_records');

export const options = {
  scenarios: { phones: { executor: 'per-vu-iterations', vus: Number(__ENV.PHONES || 300), iterations: 1, maxDuration: '10m' } },
  thresholds: { http_req_duration: ['p(95)<1000'], 'http_reqs{status:500}': ['count==0'], 'http_reqs{status:503}': ['count==0'] },
};

export default function () {
  const { email, password } = account(__VU);
  const { headers, tenant } = signIn(email, password);
  const t = `${BASE}/api/v1/tenants/${tenant}`;
  const cycles = http.get(`${t}/cultivation-cycles?size=1`, { headers }).json('items') || [];
  if (!cycles.length) return;
  const today = new Date().toISOString().slice(0, 10);
  for (let i = 0; i < 30; i++) {
    const key = uuid7();
    const body = JSON.stringify({ observedOn: today, kind: 'OTHER', severity: 'LOW', notes: `outbox ${i}` });
    const params = { headers: { ...headers, 'Idempotency-Key': key, 'X-Client-Version': '0.1.0' }, tags: { name: 'outbox' } };
    const r = http.post(`${t}/cultivation-cycles/${cycles[0].id}/observations`, body, params);
    check(r, { 'recorded': x => x.status === 201 });
    // A lost reply: the phone sends the same record again and must get the stored reply, not a second record
    if (Math.random() < 0.1) {
      const again = http.post(`${t}/cultivation-cycles/${cycles[0].id}/observations`, body, params);
      check(again, { 'replayed': x => x.status === 201 && x.headers['Idempotent-Replayed'] === 'true' && x.json('id') === r.json('id') });
      replays.add(1);
    }
    sleep(0.2); // the phone sends at most 5 a second
  }
}
