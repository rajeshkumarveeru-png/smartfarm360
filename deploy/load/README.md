# Load tests (SaaS operations design, section 12)

Run against **staging only**, before the first release and before each major release. Never against production.

1. Seed staging with the data size of section 12:
   - 500 farm businesses, 20 of them large (50,000 journal lines, 200 workers, 5,000 animals);
   - load-test accounts `load-001@loadtest.invalid` … `load-200@...` sharing one password.

   Seed with the staging seed job; it is not part of this repository yet. Load-test accounts are verified and have no
   two-step sign-in.
2. Run each script with [k6](https://k6.io) from a machine outside the server:

   ```bash
   k6 run -e BASE_URL=https://staging.example.in -e LOAD_PASSWORD=... deploy/load/mixed-web.js
   k6 run -e BASE_URL=https://staging.example.in -e LOAD_PASSWORD=... deploy/load/morning-sync.js
   k6 run -e BASE_URL=https://staging.example.in -e LOAD_PASSWORD=... deploy/load/exports-and-signin.js
   ```

3. Each script fails when its target is missed:

   | Script | Load | Target |
   |---|---|---|
   | `mixed-web.js` | 200 users, 30 minutes | p95 under 500 ms for reads and 800 ms for saves; no errors |
   | `morning-sync.js` | 300 phones × 30 records within 10 minutes, 10% resent | Every record stored once (resends replayed); p95 under 1 s; no 5xx |
   | `exports-and-signin.js` | 20 exporters, 50 browsers, 100 sign-ins a minute | Exports under 10 s; others' p95 under 1 s; sign-in p95 under 800 ms, rate limits hold |

4. Record the result on the platform console:

   ```http
   POST /api/v1/platform/ops/checks
   {"kind": "LOAD_TEST", "ok": true, "details": "mixed-web p95 310 ms; ..."}
   ```

   Also record it in `docs/PROGRESS.md`.

The sign-in burst is expected to meet the rate limits (429) for repeated accounts. A response of 429 counts as the
limit holding, not as a failure.
