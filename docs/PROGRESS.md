# SmartFarm360 progress log

## Current state

- **Phase:** 15 (billing and payments) built, plus staging readiness (rehearsed locally in production mode), on
  branch `feature/staging-readiness` (based on `feature/phase-15-billing`). Nothing is pushed yet. Next: staging, as in
  [docs/ops/staging.md](ops/staging.md).
- **Production readiness:** not ready yet. The application side is in place and tested: production settings checks,
  RLS, two-step sign-in, support access, limits, export and deletion, consent, SMTP email, backup and restore scripts,
  and runbooks. What is left needs the real host: choose the provider, deploy staging and production from `deploy/`,
  configure email and alerts, run the restore check and load tests, and publish the legal texts. That is the
  production-readiness review of the Phase 14 design, section 12. Before charging anyone, billing also needs the real
  prices, the legal entity's GST details, the Razorpay account and one end-to-end run in its test mode on staging.

## Decisions

| Decision | Outcome | Where |
|---|---|---|
| Baseline commit of pre-existing work | Done: `bf8cdbc` on `main` | — |
| Architecture | Modular monolith | ADR 0001 |
| Tenancy | Tenant id in path, central enforcement, composite FKs, RLS later | ADR 0002 |
| Login identity | Email + password (default); phone/OTP later | ADR 0003 |
| Accounting model | Documents + append-only journal | ADR 0004 |
| Inventory valuation | Moving weighted average, 4 dp unit cost | ADR 0005 |
| Test database | Embedded PostgreSQL (no Docker available) | ADR 0007 |
| Attachment storage | Storage interface; local disk default, S3 later | ADR 0009 |
| Offline records | Client ids with a server idempotency store; day-record versions as value hashes | ADR 0010 |
| **Hosting / deployment target** | Approved shape (O1): Indian region, one server, managed PostgreSQL 18. **The provider is still open** (product owner) | [SaaS operations](platform/saas-operations.md) |
| Billing and payments design, P1–P15 | Approved as recommended (2026-10-27); **prices, GST details and the Razorpay account are open** (product owner) | [billing](platform/billing.md), ADRs 0014–0016 |
| Desktop (Electron) scope | Frozen: not built or released (O15) | [SaaS operations](platform/saas-operations.md) |
| **Tamil terminology reviewer** | **Open** — required before the first release | ADR 0008 |
| Finance posting rules, D1–D12 | Approved as recommended (2026-10-20) | [posting rules](finance/posting-rules.md) |
| Stock movement design, E1–E12 | Approved as recommended (2026-10-20) | [stock movements](inventory/stock-movements.md) |
| Crop management design, F1–F12 | Approved as recommended (2026-10-20) | [crop management](crops/crop-management.md) |
| **Workforce and assets design, G1–G15** | **Awaiting review** (draft 2026-10-20) | [workforce and assets](workforce/workforce-and-assets.md) |
| **Stock inputs by field supervisors** | **Open** — today only roles with `INVENTORY_WRITE` (owner, farm admin, farm manager, accountant) can record stock used on a crop; field supervisors and workers record notes-only work and harvests. Should field supervisors get stock-use permission? | crop design §15 |
| **Email provider** | **Open** — Phase 2 ships the `EmailSender` interface with a logging stub, as instructed. The SMTP sender is built (O5); **the provider is chosen with the host** | ADR 0012 |

## Phase 1 — Baseline and hygiene (2026-10-19)

### Changes

- Baseline commit `bf8cdbc` on `main` with all previously untracked work (excluding `frontend/tsconfig.tsbuildinfo`).
- `backend/build.gradle`
  - Enabled JUnit Platform. **Before this, `gradle test` discovered no tests and failed** — the existing tests had never run.
  - Added `junit-platform-launcher` (required by Gradle 9) and embedded PostgreSQL for tests.
  - `bootJar` now produces `smartfarm360-0.1.0.jar`, the name Electron packaging expects (it was
    `smartfarm360-backend-0.1.0.jar`, which would have broken `npm run dist`). Plain JAR disabled.
- New `LiquibaseMigrationIntegrationTest` + `EmbeddedPostgresSupport`: full changelog on an empty PostgreSQL,
  Hibernate schema validation, composite cross-tenant FK enforcement, idempotent re-run on populated data.
- Root `package.json`: `backend:build` uses the Gradle wrapper instead of a system `gradle`.
- `mobile`: `react-native` 0.81.4 → 0.81.5 via `npx expo install --fix`; added `package-lock.json`.
- `.gitignore` (tsbuildinfo, certs, DB dumps, IDE per-user files), new `.gitattributes`, `backend/gradlew` marked executable.
- CI: `.github/workflows/ci.yml` (backend, web, mobile jobs).
- Docs: new `README.md`, `CLAUDE.md`, ADRs 0001–0009, `BACKLOG.md`, this file. The old README is preserved verbatim
  with a warning header at `docs/legacy/original-readme-notes.md`.

No application behaviour, API, schema or database data changed. No changesets were added or edited.

### Commands run and results

All backend commands with `JAVA_HOME=%USERPROFILE%\.jdks\ms-21.0.12` (OpenJDK 21.0.12).

| Command | Result |
|---|---|
| `backend: gradlew clean test bootJar` (before fixes) | **Failed**: no tests discovered. `bootJar` produced `smartfarm360-backend-0.1.0.jar` |
| `backend: gradlew clean test bootJar` (after JUnit fix) | Passed: 4/4 existing unit tests; JAR `smartfarm360-0.1.0.jar` |
| `backend: gradlew test` (with migration tests) | Passed: 8/8 (4 unit + 4 migration integration) |
| `frontend: tsc --noEmit` | Passed |
| `frontend: npm run build` | Passed (210.6 kB JS, 13.1 kB CSS) |
| `frontend: npm audit` | 0 vulnerabilities |
| `mobile: npm install`, `tsc --noEmit` | Passed |
| `mobile: npx expo install --check` | Flagged react-native 0.81.4 (expected 0.81.5) → fixed with `--fix` |
| `mobile: npx expo-doctor@1.21.5` | 18/18 checks passed |
| `mobile: npm audit --omit=dev` | 35 findings (12 moderate, 23 high), all transitive via `expo`/`react-native` tooling (braces, image-size, node-forge, postcss, sprintf-js, uuid). Fix requires breaking `--force` upgrades; not applied |

Not verified: the GitHub Actions workflow has not run (nothing pushed). `npm run dist` (Electron package) was not run.

## Phase 2 — Platform foundation (2026-10-20)

Commits on `feature/phase-2-platform`: `ab1876d` (backend), `52b3808` (web), `4546825` (mobile), plus this docs commit.

### Features completed

- **Authentication (ADR 0003):** register (no account enumeration), sign in, JWT access tokens (15 min), rotating
  refresh tokens stored as SHA-256 hashes with reuse detection, sign out, change password, password reset with
  single-use 30-minute tokens, lockout after 5 failures (15 min), per-IP/per-email rate limits, BCrypt 72-byte limit
  enforced. HTTP Basic and the built-in `admin`/`admin123` bootstrap are gone.
- **Email:** `EmailSender` interface with `LoggingEmailSender` (recipient masked; links only with
  `APP_MAIL_DEV_LOG_LINKS=true`). Emails are sent after commit.
- **Tenancy and permissions (ADR 0002):** 14 permissions, 7 system roles, `TenantContextInterceptor` (404 for
  non-members, 403 for suspended tenants, fail-closed `@RequiresPermission`, `@RequiresModule`).
- **Tenant management:** create (creator becomes owner), list my tenants with roles/permissions/modules, settings
  (timezone, currency, units), modules (only implemented ones can be enabled), members (change roles, remove),
  invitations (bound to the invited email; accept link also returned to the inviter for sharing), owner safeguards.
- **Platform administration:** separate `PLATFORM_ADMIN` user role; list and suspend/reactivate tenants (audited).
  Platform admins get no access to tenant farm data.
- **Audit history:** append-only `sf_audit_history`, written in the same transaction; tenant audit view (paged).
- **API conventions:** everything under `/api/v1`; RFC 7807 errors with `code`, `errors[]`, `correlationId`;
  `X-Correlation-Id` in logs; Actuator health/liveness/readiness without details; OpenAPI at `/v3/api-docs`.
- **Farm, crop, finance** moved to `/api/v1/tenants/{tenantId}/...`; farms and parcels gained get/update/deactivate;
  crop requests are typed and validated. Legacy `/api/smartfarm/*` removed (no external consumers).
- **Web:** router, sign-in/registration/reset/invitation pages, onboarding, tenant switcher, permission- and
  module-aware navigation and actions, members, settings, activity history, platform page, shared Tamil/English
  catalog with runtime switching and saved preference, Intl money/number/date formatting, cross-tab-safe refresh.
- **Mobile:** sign-in, tenant picker, refresh token in the keystore, shared catalog, entries stored per user+tenant.

### Database migrations

`004-platform-foundation.yaml`: `028-platform-schema`, `029-platform-seed-rbac`, `030-platform-backfill`.
Legacy membership roles map as OWNER→TENANT_OWNER, ADMIN→FARM_ADMIN, FARM_MANAGER→FARM_MANAGER, other→VIEWER (write
access is unchanged for existing users). `sf_tenant_memberships.role` is now nullable and unused; drop it in a later
contract step. No data deleted.

Applied to the local `smartfarm360` database on 2026-10-20 (backup taken first with `pg_dump`). The existing local
`admin` user was mapped to FARM_ADMIN in its tenant and **still has the old `admin123` password** — change it or
deactivate that account.

### Security and tenant-isolation tests

- `CrossTenantIsolationTest` builds a test for **every** handler under `/api/v1/tenants/{tenantId}` from the live
  mapping (44 endpoints × non-member and non-existent tenant = 88 cases) and checks 404 with no audit writes; plus IDOR
  tests for farms, parcels, crops, cycles, finance entries, purchases, members and invitations.
- Mutation check (temporary, reverted): disabling the permission check failed 4 permission tests; ignoring
  membership failed all 44 cross-tenant cases.
- `TenantEndpointInventoryTest` fails the build if a tenant endpoint lacks `@RequiresPermission` or business data is
  served outside the tenant path. `ModuleBoundaryTest` (ArchUnit) enforces ADR 0001 boundaries.

### Commands run and results

Backend with `JAVA_HOME=%USERPROFILE%\.jdks\ms-21.0.12`.

| Command | Result |
|---|---|
| `backend: gradlew clean test bootJar` | Passed: **142 tests, 0 failures** (auth 15+2, cross-tenant 92, membership 6, permissions 6, platform 4, inventory 2, migrations 7, ArchUnit 5, other unit 3); JAR `smartfarm360-0.1.0.jar` |
| `frontend: npm run lint` | Passed |
| `frontend: npx vitest run` | Passed: **23 tests** (catalog parity, formatting, nav rules, API client refresh/locks, sign-in/registration, layout by role) |
| `frontend: tsc --noEmit`, `npm run build` | Passed (333.6 kB JS / 98.5 kB gzip) |
| `mobile: tsc --noEmit` | Passed |
| `mobile: npx jest --ci` | Passed: **11 tests** |
| `mobile: npx expo install --check` | Passed |
| `mobile: npx expo export --platform android` | Passed; Hermes bundle contains shared catalog strings |
| `mobile: npx expo-doctor@1.21.5` | 17 passed; 1 failed because the React Native Directory server returned an unexpected response (network check, not a project issue) |
| Manual browser check (backend on :8081 against local PostgreSQL 18, Vite on :5174) | Register → onboarding → farm + parcel → Tamil switch → reload keeps session → crops page → invitation link; backend log had 0 errors and no passwords/tokens |

Not verified: GitHub Actions (nothing pushed); Electron packaging (`npm run dist`); a real phone or emulator.

### Compatibility notes

- Port 8080 is used locally by the BIZ360 backend; run SmartFarm360 with `PORT=8081` and
  `SMARTFARM_API_TARGET=http://localhost:8081` for Vite while both are running.
- Without `APP_JWT_SECRET` a random key is used and everyone is signed out on restart.
- Static hosting of the web app needs an SPA fallback to `index.html` (the Electron server already does this).

## Phase 3 — Finance core (2026-10-20)

Commits on `feature/phase-3-finance`: `8b64fac` (posting rules), `49e8332` (backend), `f54d0f7` (web), `21c7b7b` (mobile),
plus the verification and docs commit.

### Features completed

- **Approved posting rules** implemented: system ledger (29 accounts), 30 Tamil/English categories plus tenant categories,
  cash/bank/UPI accounts, one parties list (D11), loans, cost objects (crop cycles + farm overhead).
- **Records:** bills and sales (lines, quantity × rate rounded per line, drafts, paid now), payments and receipts with
  matching to open bills/sales, transfers, owner money in/out (drawings, D2), loans and repayments (principal vs
  interest), settlements with a trader, write-offs, opening balances, manual depreciation.
- **Controls:** append-only journal and balance check in the database, reversal instead of editing, lock date (D9),
  April–March numbering (D1), idempotency keys, row locks so concurrent payments cannot over-pay a bill, audit of every
  post, reversal, match and settings change.
- **Reports:** profit and loss (accrual and cash basis, D10; subsidies below operating profit, D4), cash flow
  (operating/investing/financing), dues with ageing and advances, balances, activity profitability, dashboard summary.
- **Web:** finance overview, records, record detail, a form for every record type, parties, cash & bank and loans,
  reports, finance settings; the old screen became *Stock & purchases* and purchases now post bills to Stock.
- **Mobile:** home shows real month figures and the cash/bank total; quick entries post to the accounts and wait on
  the phone when offline, then retry with the same idempotency key.

### Database migrations

`005-finance-core.yaml`: `031-finance-schema`, `032-finance-journal-guards`, `033-finance-seed`,
`034-finance-legacy-migration` (moves Phase 1 entries and purchases into documents and journal, verifies counts and
totals inside the changeset, makes `sf_finance_entries` read-only). Applied to the local database on 2026-10-20 after a
`pg_dump` backup; the local database had no legacy finance rows.

A defect found by the new migration test was fixed before the changeset was ever applied outside tests: the category
uniqueness constraint allowed only one custom category per tenant.

### APIs added

All under `/api/v1/tenants/{tenantId}/finance`: settings, categories, accounts, parties, loans, cost-objects, documents
(list, detail, edit draft, post, cancel, reverse, allocations), bills, sales, payments, receipts, transfers, owner-money,
loans, loan-repayments, settlements, write-offs, opening-balances, depreciation, allocations/{id}, and reports
(income-statement, cash-flow, dues, balances, activity, summary). Removed: `/finance/entries`, `/finance/summary`.
New permissions: `FINANCE_REVERSE`, `FINANCE_MANAGE` (owner, farm admin, accountant).

### Tests and evidence

| Command | Result |
|---|---|
| `backend: gradlew clean test bootJar` | Passed: **237 tests, 0 failures**, including the golden June scenario (every figure in section 8 of the posting rules), 15 finance rule tests (lifecycle, lock date, allocation checks, rollback, idempotency, two concurrent payments, rounding, database guards, list filters, every read endpoint), finance isolation and permissions, the legacy-data migration test, and 166 generated cross-tenant cases (83 tenant endpoints) |
| `frontend: npm run lint`, `tsc --noEmit`, `npm run build` | Passed |
| `frontend: npx vitest run` | Passed: **26 tests**; the catalog test now reads the backend source and fails if any error code or audit action lacks English or Tamil text |
| `mobile: tsc --noEmit`, `npx jest --ci`, `expo install --check` | Passed: **15 tests** |
| Manual browser check (backend :8081 on local PostgreSQL 18, Vite :5174) | Overview in Tamil → English; cash expense ₹1,250.50 → `BILL/2026-27/0001` paid; party added; sale 20 × ₹2,250 with ₹20,000 received → partly paid, ₹25,000 open, receipt matched; overview: revenue ₹45,000, profit ₹43,749.50, cash ₹18,749.50, to receive ₹25,000; cash-basis P&L ₹18,749.50. Backend log: 0 errors, no secrets |

The browser check found a real bug the tests had missed (the records list built invalid SQL). It was fixed, and list
and read-endpoint tests were added.

Not verified: GitHub Actions (nothing pushed); Electron packaging; a real phone.

## Phase 4 — Inventory, purchasing and sales (2026-10-20)

Commits on `feature/phase-4-inventory`: `8688193`, `67041de` (design), `5fdbc12` (backend and web), `7334594` (mobile),
plus the verification and docs commit.

### Features completed

- **Approved stock movement design** implemented (E1–E12 as recommended): unit catalog (mass, volume, count, length,
  area) with each item kept in one base unit and its own pack units (bag = 45 kg); items (input, farm produce, bought to
  sell) with reorder level, lots and default categories; locations with a default *Main store*; lots with expiry.
- **Movements:** append-only (a database trigger refuses edits), on-hand per item, location and lot updated in the same
  transaction under row locks, moving-average value per item, no negative stock, the last unit out takes the
  remaining value.
- **Records** (all finance records: numbered, lock-date checked, idempotent, audited, reversible): item lines on bills
  (stock at cost, not an expense) and sales (cost of goods sold at average), use on an activity, transfers, wastage,
  stock counts, farm produce at nil value, returns to suppliers and from customers, opening stock.
- **Reports:** stock on hand (by location and lot), value on any date, item movement history with running balance,
  ledger reconciliation (Stock 1200 = stock value), low-stock and expiry alerts, CSV exports (E9).
- **Web:** new *Stock* section (overview, items, item detail, locations, reports, a form for every stock record);
  item lines and opening stock in the finance forms; record pages show stock movements and offer returns.
- **Mobile:** stock view with value, quantities in base and pack units, low-stock and expiry alerts on the home screen.
- **Retired:** `/suppliers`, `/inventory/items`, `/purchases` and the *Stock & purchases* page (E12).

### Database migrations

`006-stock-core.yaml`: `035-stock-schema`, `036-stock-guards-and-seed`, `037-stock-legacy-migration`. The migration
turns Phase 1 items and purchases into stock items and movements, links each purchase to its bill, records opening
stock (Dr 1200 / Cr 3900), records any shortfall as a stock count to review, verifies inside the changeset that
quantities, values and the Stock (1200) balance agree, and makes `sf_inventory_items`, `sf_purchases` and
`sf_suppliers` read-only. Applied to the local database on 2026-10-20 after a `pg_dump` backup; the local database had
no Phase 1 stock rows.

### APIs added

All under `/api/v1/tenants/{tenantId}/stock`: units, items (list, create, detail, update, pack units, lots, movements,
movements.csv), locations (list, create, update), issues, transfers, wastage, counts, produce, purchase-returns,
sale-returns, and reports (on-hand, on-hand.csv, valuation, valuation.csv, reconciliation, alerts). Finance bills,
sales and opening balances accept item lines. Reads need `INVENTORY_READ`; stock records `INVENTORY_WRITE`; returns
`FINANCE_WRITE`; reversals `FINANCE_REVERSE` (through the finance API).

### Tests and evidence

| Command | Result |
|---|---|
| `backend: gradlew test bootJar` | Passed: **297 tests, 0 failures**, including the golden July scenario (every figure in section 7 of the stock design), 18 stock rule tests (units, negative stock, last-unit value, transfers, reversal with corrections, sales and drafts, lots and expiry, farm produce, counts, returns both ways, opening stock, idempotent retries, two concurrent issues, lock date, permissions, alerts, CSV safety), stock invariants checked after every stock test, the Phase 1 stock migration test (opening, shortfall, unknown units, duplicate names, ledger = store, read-only tables), and 202 generated cross-tenant cases (101 tenant endpoints) plus object-level cross-tenant stock checks |
| `frontend: npm run lint`, `tsc --noEmit`, `npm run build` | Passed |
| `frontend: npx vitest run` | Passed: **31 tests** (catalog test covers every new error code and audit action in English and Tamil) |
| `mobile: npm run typecheck`, `npm test`, `expo install --check` | Passed: **17 tests** |
| Manual browser check (backend :8081 on local PostgreSQL 18, Vite :5174) | Migrations 035–037 applied to the local database; item *Urea* with bag = 45 kg created; bill for 10 bags × ₹270 → `BILL/2026-27/0002`, +450 kg ₹2,700; use of 300 kg → `ISSUE/2026-27/0001`, ₹1,800 at ₹6/kg; overview 150 kg ₹900; item history with running balance; reconciliation ₹900 = ₹900; Tamil screens. Backend log: 0 errors, no secrets |

Not verified: GitHub Actions (nothing pushed); Electron packaging; a real phone; CSV download in the browser (covered
by API tests).

## Phase 5 — Crop management v2 (2026-10-20)

Commits on `feature/phase-5-crops`: `79d616d` (design), `0f2b0a4` (backend), `2f5bc5a` (web), `e6538dd` (mobile), plus the
verification and docs commit.

### Features completed

- **Approved crop design** implemented (F1–F12 as recommended): cycle stages Planned → Growing → Harvesting → Closed
  (and Cancelled), moved by sowing and harvests, closed by hand, reopened only by owners and farm admins; cycles can be
  edited; area checked against the field, intercropping warned (F11).
- **Field work recorded once** (F2, F3): an activity can use stock (a stock-use record for the cycle) and pay for labour
  or machine hire (a bill for the cycle); notes-only work can be edited or deleted; work with stock or money is
  reversed. Retries with the same key record nothing twice.
- **Harvests into stock** (F4, F5): each harvest is a farm-produce record and a lot of the crop's produce item; a sale
  from the lot is assigned to the cycle by default. Phase 1 harvests stay as history.
- **Overhead allocation** (F6, P16): preview and post farm overhead to growing crops by area × days, farm-aware,
  exact to the paisa, one per period, reversible.
- **Crop reports** (F7, F8): revenue, direct costs, allocated overhead, profit, cost per acre, yield and yield per acre,
  cost per kg, unsold produce with a labelled estimate, plan against actual, Phase 1 memo figures, season comparison,
  CSV exports.
- **Field notes** (F9, F10): pest, disease and other observations with severity; farmer-entered rainfall and weather.
- **API:** typed and paged crop responses.
- **Web:** new Crops section (cycles, cycle page with figures and field records, crops and seasons, season comparison,
  weather) and Finance → Overhead allocation.
- **Mobile** (F12): the Crops tab placeholder is replaced by real cycles with stage and figures, and forms for field work
  (with a stock input), harvests and observations (online).

### Database migrations

`007-crop-v2.yaml`: `038-crop-v2-schema`, `039-crop-v2-backfill` (maps Phase 1 statuses, keeps activities and harvests as
history with their memo values, gives every cycle's cost object its area in acres and dates, and verifies that no
journal line or stock movement was added). Applied to the local database on 2026-10-20 after a `pg_dump` backup; the
local *GroundNut* cycle became Planned with 1.7 acres.

The new migration test caught an ordering defect before the changeset was applied anywhere: the harvest check
constraint was added before Phase 1 harvests were marked as history.

### APIs added

Under `/api/v1/tenants/{tenantId}`: `PATCH /crops/{id}`, `GET|PUT /crops/{id}/produce-item`, `GET /crops/{id}/comparison(.csv)`,
`GET|PATCH /cultivation-cycles/{id}`, `POST /cultivation-cycles/{id}/status`, `GET /cultivation-cycles/{id}/report(.csv)`,
`GET|POST /cultivation-cycles/{id}/observations`, `PUT|DELETE /observations/{id}`, `PATCH|DELETE /activities/{id}`,
`POST /activities/{id}/reverse`, `POST /harvests/{id}/reverse`, `GET /farms/{id}/weather`, `PUT|DELETE /farms/{id}/weather/{date}`,
`POST /finance/overhead-allocations(/preview)`. Changed: `POST /activities` and `POST /harvests` take the new request
shapes; cycle, activity and harvest lists are paged.

### Tests and evidence

| Command | Result |
|---|---|
| `backend: gradlew test bootJar` | Passed: **355 tests, 0 failures**, including the golden crop scenario (every figure in section 9 of the crop design), 8 crop rule tests (stages and closed cycles, area and intercropping, notes-only edits and reversal, permissions and idempotent retries, harvest lots and reversal, allocation per period and per farm with exact rounding, observations and weather, comparison, CSV and paging), the Phase 1 crop migration test, module boundaries (7 rules), and 248 generated cross-tenant cases (124 tenant endpoints) |
| `frontend: npm run lint`, `tsc --noEmit`, `npm run build` | Passed (Vite now warns that the bundle is over 500 kB) |
| `frontend: npx vitest run` | Passed: **35 tests** |
| `mobile: npm run typecheck`, `npm test`, `expo install --check` | Passed: **21 tests** |
| Manual browser check (backend :8081 on local PostgreSQL 18, Vite :5174) | Migrations 038–039 applied locally; crop *Paddy* and cycle *Samba 2026* (2 acres) created; fertilizer work with 50 kg urea from stock and ₹400 labour → figures: fertilizer ₹300, labour ₹400, cost per acre ₹350; harvest 10 quintal → lot `SAMBA-2026-H1`, 1,000 kg, 500 kg per acre, ₹0.70 per kg, stage Harvesting. A label glitch found (estimate price before any harvest) was fixed. Backend log: 0 errors, no secrets |

Not verified: GitHub Actions (nothing pushed); Electron packaging; a real phone; CSV downloads in the browser (covered
by API tests).

## Phase 6 — Workforce and assets (2026-10-20)

Commits on `feature/phase-6-workforce` (based on `feature/phase-5-crops`):

- `c703473`: design.
- `a90b032`: backend.
- `ee4be0e`: web.
- `8186d97`: mobile.
- A final commit: verification, the G7 warning and docs.

### Features completed

- **Approved design.** [workforce and assets](workforce/workforce-and-assets.md) is implemented with G1–G15 as recommended.
  Implementation notes are in section 14 of the design.
- **Workers** (G1, G2):
  - Each worker is a party, so wages owed sit on 2100 and advances on 1150 in that party's ledger.
  - Pay types are daily, monthly or piece rate, with dated rates.
  - Leaving is refused while wages or an advance are outstanding. A worker can rejoin.
- **Attendance** (G3, G7):
  - The daily muster roll records present, half day, absent, paid leave or unpaid leave, with the crop worked on.
  - Two quick actions: "all present" and "all present on <crop>".
  - Piece work is recorded by quantity.
  - A day is locked once a posted pay run covers it.
  - The muster warns when a worker is also on a labour bill for the same day.
- **Pay runs** (G4, G5):
  - Each run is previewed, then posted.
  - Labour cost goes to each crop by days worked; the rest goes to overhead.
  - Extras can be added.
  - A worker can be in only one pay run per period.
  - Monthly workers are paid for whole calendar months only.
  - A pay run is reversible until wages are paid against it.
- **Wage payments and advances** (W1–W3):
  - Each worker's payment is its own record, and it recovers their advance.
  - Advances can also be repaid in cash.
  - Each worker has a statement.
  - Reports cover labour by crop and the month's attendance.
  - Wages and advances are reconciled with the ledger.
- **Assets** (G8–G13):
  - An asset can be bought on a bill and registered in one step.
  - Costs already on Farm assets (1300), from bills or opening balances, can be registered.
  - Depreciation is monthly straight-line, posted as runs in month order and reversible from the latest month.
  - Breeding animals are registered but not depreciated.
  - Selling or scrapping posts the gain to 4900 or the loss to 5980.
  - Each asset has a use log, maintenance plans by days or hours (due and overdue), and a maintenance log.
  - Running costs come from bills and stock issues tagged with the asset, and give a cost per hour.
  - The register is reconciled against 1300 and 1310 and can be exported as CSV.
- **Modules and permissions** (G14):
  - *Workforce* and *Assets* are on for every farm business.
  - New permissions: `WORKFORCE_READ`, `WORKFORCE_WRITE`, `PAYROLL_MANAGE`, `ASSETS_READ`, `ASSETS_WRITE`.
  - Depreciation runs and disposals need `FINANCE_MANAGE`.
- **Web:**
  - New *Workers & wages* section: attendance, workers, worker page, pay, reports.
  - New *Assets* section: register, asset page, depreciation, maintenance.
  - An optional "for asset" field on expense bill lines and stock issues.
- **Mobile** (G15):
  - Workers screen under *More*: mark the day's attendance (all present, or per worker, with the crop).
  - It shows what each worker is owed and the advance still to recover.
  - Online only.

### Database migrations

`008-workforce-assets.yaml` contains two changesets:

- `040-workforce-assets-schema`:
  - Adds the worker, rate, attendance, piece-work, pay-run-worker, asset, depreciation, usage and maintenance tables.
  - Adds `is_worker` on parties.
  - Adds `asset_id` on finance documents and lines.
  - Extends the document type and period checks.
- `041-workforce-assets-seed`: permissions, role grants, and both modules turned on for every tenant.

These were applied to the local database on 2026-10-20, after a `pg_dump` backup (`smartfarm360-before-phase6.backup`).
No business data was changed. A new migration test upgrades a database from changeset 015 to the latest.

### APIs added

Under `/api/v1/tenants/{tenantId}/workforce`:

- `GET|POST /workers`, `GET|PATCH /workers/{id}`, `POST /workers/{id}/rates`, `GET /workers/{id}/statement`
- `GET|PUT /attendance/{date}`, `GET /attendance-sheet`
- `GET|POST /piece-work`, `DELETE /piece-work/{id}`
- `POST /advances`, `POST /advance-repayments`
- `POST /pay-runs/preview`, `POST /pay-runs`
- `POST /wage-payments`
- `GET /reports/labour`, `GET /reports/reconciliation`

Under `/api/v1/tenants/{tenantId}/assets`:

- `GET|POST /`, `GET|PATCH|DELETE /{id}`, `POST /purchases`, `GET /unregistered`
- `GET /{id}/schedule`, `POST /{id}/disposal`
- `GET|POST /depreciation-runs`, `POST /depreciation-runs/preview`
- `GET|POST /{id}/usage`, `DELETE /usage/{id}`
- `GET /maintenance-plans`, `GET|POST /{id}/maintenance-plans`, `PUT /maintenance-plans/{id}`
- `GET|POST /{id}/maintenance`, `GET /{id}/costs`
- `GET /reports/reconciliation`, `GET /reports/register.csv`

Changed: bill lines and stock issues accept an optional `assetId`.

### Tests and evidence

| Command | Result |
|---|---|
| `backend: gradlew test --rerun bootJar` | Passed: **460 tests, 0 failures** (see below) |
| `frontend: npm run lint`, `tsc --noEmit`, `npm run build` | Passed; the bundle is still over 500 kB |
| `frontend: npm test` | Passed: **42 tests**, including 7 new workforce and asset screen tests and the catalog completeness test |
| `mobile: npm run typecheck`, `npm test`, `npx expo install --check` | Passed: **26 tests**, including 5 new attendance and balance tests |
| Manual browser check | Passed (see below) |

The backend run includes:

- The golden scenario from section 7 of the design. Every figure is asserted: labour by crop, operating costs, other
  income, cash flow, the tractor's book value, and an unexplained reconciliation difference of 0.
- 8 workforce rule tests: pay arithmetic, locking, advances, retries, attendance and the G7 warning, permissions.
- 7 asset rule tests: schedules, first and last month, catch-up and reversal, disposal gain and loss, breeding animals,
  maintenance, reconciliation.
- The upgrade migration test.
- 9 module-boundary rules.
- Generated cross-tenant cases for every tenant endpoint, including the new ones.

The manual browser check ran against backend :8081 on local PostgreSQL 18, with Vite on :5174:

- Migrations 040–041 were applied.
- Worker *Murugan* was added at ₹500 a day and marked "all present on Samba 2026" for 20 Oct.
- A pay run was previewed and posted: ₹500, all of it to Samba 2026.
- ₹500 was paid from *Cash in hand*.
- The labour report showed Samba 2026: 1 day, ₹500, and the workforce reconciliation agreed.
- *Power sprayer* was bought for ₹12,000 cash and registered with a 60-month life, giving ₹200 a month.
- The journal balances to ₹0.00.
- The backend log shows 0 errors and no secret values.
- One wording defect was found and fixed ("1 payments recorded").

Not verified:

- GitHub Actions (nothing has been pushed).
- A real phone.
- A depreciation run on the local database (the sprayer's first month, November, has not ended yet; runs are covered by
  the API tests).
- CSV download in the browser (covered by API tests).

## Phase 7 — Dashboard and core reports (2026-10-21)

Commits on `feature/phase-7-reports` (based on `feature/phase-6-workforce`):

- `b57f368`: design.
- A build commit with the backend, web, mobile, verification and docs.

### Features completed

- **Approved design.** [dashboard and reports](reports/dashboard-and-reports.md) is implemented with H1–H14 as
  recommended. Implementation notes are in section 14 of the design.
- **Dashboard** (H1–H4). It is now the landing page of a farm business. It shows:
  - money at a glance: cash and bank by account, receivables and overdue, payables, loans, this month, this financial
    year;
  - the last 12 months as a built-in SVG chart, with a table view;
  - this month's five largest costs;
  - crops in the field;
  - a "needs attention" list with links: overdue sales, bills due, old drafts, low stock, expiring lots, wages owed,
    advances, attendance not marked, maintenance due, depreciation not posted, unregistered assets, and any
    reconciliation that does not agree.

  Each part needs its own permission and module. A part that fails to load does not hide the rest.
- **New reports** (H5, H6):
  - monthly profit and loss;
  - costs or income by category, with shares;
  - balance sheet;
  - trial balance (for accountants);
  - statement of account for any party;
  - wages by worker;
  - maintenance and running costs by asset.

  A Reports page lists every report the reader may open.
- **Comparisons and ranges** (H10, H11):
  - The profit and loss and the category breakdown can be compared with the previous period or the same period last
    year, showing the change in rupees and percent ("—" when the earlier amount is zero).
  - Quick ranges follow the fiscal year setting (labelled like "2026-27").
- **Exports** (H7–H9):
  - CSV and Excel for every report, including stock, crops and the asset register. Each file opens with a header block,
    labels in English or Tamil, and plain numbers.
  - A print layout for "Print / Save as PDF".
  - Exports are limited to 5-year ranges and 30 a minute per user, and each one is recorded in the activity history as
    *Report exported*.
- **Phone** (H13):
  - The Home tab shows receivables, payables and the "needs attention" list from the server, as the role may see them.
  - Offline, it shows the last copy with its time.
  - Entries waiting on the phone are shown as "not yet sent".

### Database migrations

`009-reports.yaml`, changeset `042-report-indexes`: two indexes, with no new tables and no data changes.

- `ix_sf_journal_line_tenant_cost_object`: journal lines by cost object.
- `ix_sf_doc_tenant_type_status`: documents by type and status.

They were applied to the local database on 2026-10-21, after a `pg_dump` backup (`smartfarm360-before-phase7.backup`).

### APIs added

Under `/api/v1/tenants/{tenantId}`:

- `GET /dashboard`
- `GET /finance/reports/monthly`
- `GET /finance/reports/categories`
- `GET /finance/reports/balance-sheet`
- `GET /finance/reports/trial-balance`
- `GET /finance/parties/{id}/statement`
- `GET /workforce/reports/wages`
- `GET /assets/reports/running-costs`
- `GET /assets/reports/register?format=`

Changed:

- Every report endpoint accepts `format=csv|xlsx` and `lang=en|ta`. This covers income statement, cash flow, dues,
  activity, stock valuation and on-hand, crop cycle report and comparison, and the new reports.
- The income statement accepts `compare=previous|lastYear`.
- The older `.csv` endpoints are unchanged.

### Tests and evidence

| Command | Result |
|---|---|
| `backend: gradlew test --rerun bootJar` | Passed: **490 tests, 0 failures** (see below) |
| `frontend: npm run lint`, `tsc --noEmit`, `npm run build` | Passed; the bundle is still over 500 kB |
| `frontend: npm test` | Passed: **48 tests**, including 6 new dashboard and report tests |
| `mobile: npm run typecheck`, `npm test`, `npx expo install --check` | Passed: **29 tests**, including 3 new dashboard tests |
| Manual browser check | Passed (see below) |

The backend run includes:

- The golden scenario from section 9 of the design. Every figure is asserted: income statement, balance sheet
  (₹2,84,000 on both sides), trial balance, monthly columns adding up to ₹30,000, category shares, the party statement,
  and dashboard tiles equal to their source reports.
- The balance sheet and trial balance balancing at the end of the finance, stock, crop and workforce golden scenarios.
- 9 report rule tests:
  - XLSX number cells and header block;
  - CSV byte-order mark, Tamil labels and plain numbers;
  - export audit, range limit, format check and the rate limit;
  - CSV and XLSX for every report;
  - comparison periods and percent from zero;
  - tiles, reports and exports by role;
  - running costs and hidden amounts;
  - party statement tenant scoping;
  - every export label in the catalog.
- The dashboard performance test: **471 ms** at 50,000 journal lines (limit 1,000 ms).
- 10 module-boundary rules.
- 356 generated cross-tenant cases.

The manual browser check ran against backend :8081 on local PostgreSQL 18, with Vite on :5174:

- Migration 042 was applied.
- The dashboard opened as the landing page. It showed cash ₹3,149.50, receivables ₹25,000 (overdue), this year's profit
  ₹40,749.50, the 12-month chart, the largest costs and Samba 2026 in the field.
- The balance sheet balanced at ₹40,749.50 and matched the dashboard.
- The reports page listed all 16 reports for the owner.
- The wages report showed Murugan with 1 day and ₹500 earned and paid.
- One defect was found and fixed: the overdue line showed "{{amount}} overdue".
- The backend log shows 0 errors and no secret values.

Not verified:

- Saving an export file through the browser. It needs a download approval; exports are covered by the API tests.
- Print preview on paper.
- A real phone.
- GitHub Actions (nothing has been pushed).

## Phase 8 — Livestock and dairy (2026-10-21)

Commits on `feature/phase-8-livestock` (based on `feature/phase-7-reports`):

- `5fc76bc`: design.
- A build commit with the backend, web, mobile, verification and docs.

### Features completed

- **Approved design.** [livestock and dairy](livestock/livestock-and-dairy.md) is implemented with I1–I16 as recommended.
  Implementation notes are in section 14 of the design.
- **Herd** (I1–I4):
  - Each cow and buffalo has a record: tag, breed, dam and sire, origin, status.
  - Groups are cost objects, and moves between groups are dated.
  - A bought animal is bought on a bill and registered as a farm asset in one step. It is depreciated to its cull value
    over a productive life (this settles G10).
  - Home-bred animals have nil value.
- **Milk** (I5–I8):
  - Morning and evening sessions by group, as a total or per animal, with fat and SNF.
  - Each day's milk is kept in stock as nil-value produce. Corrections are refused once the milk has been used.
  - Where milk goes: home use, fed to calves, discarded with a reason.
  - Dairy society sales, one per bill period, assigned to the group.
- **Health and breeding** (I9–I11):
  - Treatments, vaccinations and dewormings, for one animal or a whole group. They can carry medicine from stock or a
    vet bill to the group.
  - Milk withdrawal shows warnings, and next-due dates show reminders.
  - From an insemination the app works out:
    - the pregnancy check, from day 60;
    - expected calving, at 283 days for a cow and 310 for a buffalo;
    - dry-off, 60 days before calving.

    Every date can be overridden. A negative check or a new heat clears them.
  - A calving creates the calves and starts a lactation. Dry-off ends the lactation and moves the cow to *Dry cows*.
- **Leaving the herd** (I14):
  - A registered animal goes through asset disposal: a gain or a loss.
  - A home-bred animal's sale is *Livestock sales*.
  - A death of a home-bred animal needs no money record.
  - An insurance claim is other income.
  - Reversing the record returns the animal to the herd.
- **Labour** (I12): attendance can charge days to an animal group.
- **Reports** (I13). All export as CSV and Excel.
  - Herd profitability with **cost per litre produced** and average price per litre.
  - The milk report: produced, sold, home, calves, discarded, with fat and SNF.
  - The breeding calendar.
  - The health record.
  - The animal card.
  - The herd register.
- **Dashboard tiles:**
  - milk withheld;
  - breeding events due within 7 days;
  - health events due within 7 days;
  - morning milk not recorded by 10 a.m.
- **Web:** a new *Livestock* section with Herd, animal page, Milk, Breeding and health, Groups, and Reports.
- **Phone** (I15):
  - The Livestock tab replaces the placeholder. It offers milk entry by session (a group total or per animal), "heat
    seen" and "treatment given" with withdrawal days, and the animal list with flags.
  - Online only.
- **Module** (I16):
  - *Livestock and dairy* is available.
  - It is on by default for new farm businesses and off for existing ones until switched on in Settings.
  - New permissions: `LIVESTOCK_READ` and `LIVESTOCK_WRITE`.

### Database migrations

`010-livestock.yaml` contains two changesets:

- `043-livestock-schema`:
  - Adds the cost object kind `ANIMAL_GROUP`.
  - Adds the tables `sf_animal_groups`, `sf_animals`, `sf_animal_group_moves`, `sf_milk_sessions`, `sf_milk_yields`,
    `sf_milk_postings`, `sf_animal_events`, `sf_lactations` and `sf_livestock_settings`. All have tenant keys and
    composite foreign keys.
- `044-livestock-seed`: the permissions and role grants. Existing tenants keep the module off.

These were applied to the local database on 2026-10-21, after a `pg_dump` backup (`smartfarm360-before-phase8.backup`).
No business data was changed.

### APIs added

Under `/api/v1/tenants/{tenantId}/livestock` (module *Dairy*):

- `GET|POST /groups`, `PATCH /groups/{id}`
- `GET|POST /animals`, `GET|PATCH /animals/{id}`
- `POST /animals/{id}/moves`, `POST /animals/{id}/exit`, `POST /animals/{id}/insurance`
- `POST /animals/{id}/breeding`, `POST /animals/{id}/calving`
- `GET /events`, `POST /health`, `DELETE /events/{id}`
- `GET|PUT /milk/{date}`, `POST /milk-uses`, `POST /milk-sales`
- `GET /reports/milk`, `GET /reports/herd`, `GET /reports/breeding`, `GET /reports/health`

Report endpoints and the herd list accept `format=csv|xlsx`.

Changed: breeding and milch animals with a useful life are depreciated by the asset runs.

### Tests and evidence

| Command | Result |
|---|---|
| `backend: gradlew test --rerun bootJar` | Passed: **547 tests, 0 failures** (see below) |
| `frontend: npm run lint`, `tsc --noEmit`, `npm run build` | Passed; the bundle is still over 500 kB |
| `frontend: npm test` | Passed: **53 tests**, including 5 new livestock screen tests |
| `mobile: npm run typecheck`, `npm test`, `npx expo install --check` | Passed: **33 tests**, including 4 new livestock tests |
| Manual browser check | Passed (see below) |

The backend run includes:

- The golden scenario from section 7 of the design. Every figure is asserted:
  - milk balance 0 after 558 litres in and 425 sold, 31 home use, 62 to calves and 40 discarded;
  - feed 50 kg at ₹1,400;
  - Milking herd costs ₹16,050 and profit ₹100;
  - cost per litre ₹28.76 and price per litre ₹38.00;
  - the society's receivable ₹4,150;
  - Lakshmi's book value ₹58,500;
  - Ganga's dates.
- 8 livestock rule tests:
  - milk stock and corrections after use;
  - yields only for animals in the group that day;
  - breeding dates for cow and buffalo, a negative check, a heat and a re-insemination;
  - calvings, lactations and dry-off moves;
  - depreciation to the cull value and following the group;
  - every exit's postings and their reversal;
  - permissions, including the worker and viewer, and the module switch;
  - withdrawals and next-due dates on the dashboard.
- An upgrade migration test: existing farms keep Dairy off, the grants are in place, and the database accepts animal
  groups.
- 11 module-boundary rules.
- 402 generated cross-tenant cases.
- The dashboard performance test: **522 ms** at 50,000 journal lines.

Two existing tests changed with the approved design:

- New farm businesses now get Dairy by default (I16).
- The "unavailable module" check uses Poultry, because Dairy is now available.

The manual browser check ran against backend :8081 on local PostgreSQL 18, with Vite on :5174:

- Migrations 043–044 were applied.
- Livestock and dairy was switched on in Settings, and the menu showed *Livestock*.
- Groups *Milking herd* and *Calves* were added, and Ganga (home-bred) was added to Milking herd.
- 6.5 litres were recorded for this morning, putting 6.5 litres in stock.
- Home use of 1.5 litres and then 1 litre left 4 litres in stock.
- The dashboard loaded with no failed tiles.
- One defect was found and fixed: the "Records saved" confirmation disappeared because the page reload cleared it.
- The backend log shows 0 errors and no secret values.

Not verified:

- A real phone.
- Buying an animal, exits and a society sale in the browser (they are covered by the API golden and rule tests).
- GitHub Actions (nothing has been pushed).

## Phase 9 — Poultry, goats and sheep (2026-10-21)

Commits on `feature/phase-9-poultry-goats` (based on `feature/phase-8-livestock`):

- `7e49e7f`: design.
- A build commit with the backend, web, mobile, verification and docs.

### Features completed

- **Approved design.** [poultry, goats and sheep](livestock/poultry-goats-sheep.md) is implemented with J1–J15 as
  recommended. Implementation notes are in section 15 of the design.
- **Batches and herds** (J1, J2, J7):
  - Poultry batches (layers, broilers, country birds) and goat and sheep herds are counted groups and cost objects.
  - A batch starts with its placement date, age, strain and first count.
  - Head counts change only through dated movements: started, bought, born, moved, died, culled, sold and count checks.
    A count never goes below zero on any day.
  - A batch closes when its count reaches zero, and reopens when that sale is reversed.
- **Buying** (J3): bought chicks, pullets, kids and lambs are a bill to the group under the new category *Chicks and
  young stock*.
- **Daily batch record** (J4, J5, J13):
  - Deaths, culls, good, cracked and broken eggs, feed and an optional weight sample, one record per day.
  - Feed is one stock use to the batch.
  - Good and cracked eggs go into stock at nil value; broken eggs are recorded only.
  - Warnings for eggs withheld after treatment and for deaths above 1% in a day.
- **Eggs** (J5): home use and damaged eggs from the egg stock; egg sales in pieces, dozens or trays (new unit, 30).
- **Selling** (J6): birds and animals by head with live weight, priced per kg or per head, with the count movement.
- **Goats and sheep** (J10): herd-level kidding and lambing (born alive and dead). Tagged does and ewes keep their
  individual breeding events, with a 150-day expected date.
- **Health** (J9): a health plan per batch or herd, marked done through the Phase 8 group health event, and copied from
  an earlier batch at the same bird age.
- **Figures** (J8, J14), for the period and since the batch started:
  - hen-day %, feed per dozen and cost per egg for layers;
  - mortality, average weight, FCR and cost per kg for broilers;
  - revenue, costs and profit, and cost per head per month for herds.
- **Reports**: *Batches and herds* on the livestock reports page, exported as CSV and Excel.
- **Dashboard**: today's batch record missing, and high mortality. Health due now includes plan items.
- **Modules** (J11): *Poultry* and *Goats and sheep* are available, off by default, and reuse the livestock
  permissions. Each part of Livestock shows only with its module; without Dairy the section opens on Poultry.
- **Web**: Poultry and Goats and sheep tabs, and a page for each batch or herd.
- **Phone** (J12): the Livestock tab lists open batches and herds. A batch takes today's record; a herd takes born, died
  or culled counts. Online only.

### Database migrations

`011-poultry-goats.yaml` contains two changesets:

- `045-poultry-goats-schema`:
  - Adds species, placement date, age at placement, strain and closing date to `sf_animal_groups`, and widens its
    `purpose` column to 16 characters.
  - Allows goats and sheep as animals, and kidding events with born alive and dead.
  - Adds the tables `sf_batch_days`, `sf_head_movements`, `sf_egg_postings` and `sf_health_plan_items`. All have
    tenant keys and composite foreign keys.
  - Adds the egg item to the livestock settings; the milk item becomes optional.
- `046-poultry-goats-seed`: the unit *tray* and the category *Chicks and young stock*.

These were applied to the local database on 2026-10-21, after a `pg_dump` backup (`smartfarm360-before-phase9.backup`).
No business data was changed.

### APIs added

Under `/api/v1/tenants/{tenantId}/livestock` (module *Poultry* or *Goats and sheep*):

- `GET|POST /flocks`
- `GET|POST /flocks/{id}/movements`, `DELETE /flock-movements/{id}`
- `POST /flocks/{id}/purchases`, `POST /flocks/{id}/sales`, `POST /flocks/{id}/births` (Goats and sheep)
- `GET|PUT /flocks/{id}/days/{date}`, `POST /egg-uses`, `POST /egg-sales` (Poultry)
- `GET|POST /flocks/{id}/plan`, `POST /flocks/{id}/plan/copy`
- `GET /flocks/{id}/report`, `GET /reports/flocks` (accepts `format=csv|xlsx`)

Changed:

- `@RequiresModule` accepts several modules (any of them).
- The livestock endpoints need any livestock module; milk and calving still need Dairy.
- `POST /health` accepts `planItemId`.

### Tests and evidence

| Command | Result |
|---|---|
| `backend: gradlew test --rerun bootJar` | Passed: **591 tests, 0 failures** (see below) |
| `frontend: npm run lint`, `tsc --noEmit`, `npm run build` | Passed; the bundle is still over 500 kB |
| `frontend: npm test` | Passed: **60 tests**, including 7 new poultry, goat and sheep screen tests |
| `mobile: npx tsc --noEmit`, `npm test`, `npx expo install --check` | Passed: **37 tests**, including 4 new batch and herd tests |
| Manual browser check | Passed (see below) |

The backend run includes:

- The golden scenario from section 10 of the design. Every figure is asserted:
  - layers: 30,588 hen-days, hen-day 85.0%, 1.57 kg feed per dozen, mortality 0.61%, costs ₹1,10,300, revenue
    ₹1,26,000, profit ₹15,700, cost per egg ₹4.24, costs since placement ₹2,90,300, 400 eggs in stock;
  - broilers: mortality 3.00%, average weight 2.20 kg, FCR 1.64, costs ₹3,56,000, revenue ₹4,69,480, profit
    ₹1,13,480, ₹83.41 per kg, batch closed;
  - goats: 22 heads, costs ₹4,900, revenue ₹26,000, profit ₹21,100.
- 9 rule tests:
  - head counts change only by movements and never go below zero;
  - eggs follow the daily record until used, and broken eggs are not stocked;
  - daily feed is one stock use, and a change reverses the earlier one;
  - an empty batch closes, and reversing its sale reopens it;
  - health plans are copied with shifted dates and marked done;
  - kidding in the herd, and a 150-day date for a tagged doe;
  - withdrawal warnings on the daily record, and high mortality on the dashboard;
  - modules and permissions, including a poultry-only farm refused the dairy endpoints;
  - another farm business's batches cannot be read, changed or referenced (moves, plan copies, egg sales).
- The migration test with changesets 045–046 and the four new tables.
- 11 module-boundary rules.
- 436 generated cross-tenant cases.
- The dashboard performance test: **558 ms** at 50,000 journal lines.

One existing test changed with the approved design: the "unavailable module" check now uses Fish farming, because
Poultry is now available (J11).

The manual browser check ran against backend :8081 on local PostgreSQL 18, with Vite on :5174:

- Migrations 045–046 were applied at startup.
- Poultry was switched on in Settings, and Livestock showed a *Poultry* tab next to Herd and Milk.
- Batch *Layer shed A* was started on 1 Oct 2026 with 200 birds.
- Today's record with 2 deaths and 150 good eggs left 198 birds, put 150 eggs in stock at nil value, and showed
  hen-day 3.6% (150 eggs over 4,198 bird-days) and mortality 1%.
- The livestock reports page showed the batch under *Batches and herds*; the dashboard loaded with no failed tiles.
- The backend log shows 0 errors and no secret values.

Not verified:

- A real phone.
- Buying, selling, egg sales, goats and copied plans in the browser (they are covered by the API golden and rule tests
  and the screen tests).
- GitHub Actions (nothing has been pushed).

## Phase 10 — Aquaculture (2026-10-21)

Commits on `feature/phase-10-aquaculture` (based on `feature/phase-9-poultry-goats`):

- `2f10bee`: design.
- A build commit with the backend, web, mobile, verification and docs.

### Features completed

- **Approved design.** [aquaculture](aquaculture/aquaculture.md) is implemented with K1–K15 as recommended.
  Implementation notes are in section 15 of the design.
- **Ponds** (K1): ponds under a farm with kind, water spread area (kept in acres too), depth and an optional land
  parcel. A pond is not a cost object and holds one open crop at a time.
- **Culture cycles** (K2): one crop per pond from preparation to final harvest, as a cost object. Preparation costs
  (lime from stock, manure bills) belong to the crop. Polyculture is normal.
- **Stocking** (K3, K4, K11): species, number and total kg, with the seed bill as a cost of the crop under the new
  category *Fish and shrimp seed*. 13 species, and *Other* with a name.
- **Estimated counts** (K3): stocked − deaths seen − fish harvested, never negative for deaths. The final harvest shows
  survival and unseen losses.
- **Daily pond record** (K5): feed lines as one stock use to the crop per day, and deaths seen by species.
- **Weight samples** (K6): average weight, estimated kg in the pond and estimated FCR to date, always labelled.
- **Water quality** (K7): readings per pond (dissolved oxygen, pH, temperature, ammonia, transparency, salinity) compared
  with limits the farmer sets for the farm business or one pond. No limits are filled in.
- **Harvests** (K8): partial or final, by species and size grade, sold at the pond bank as one *Fish sales* record; home
  use and fish given away without value; the buyer's commission and transport as a cost of the crop. Fish are not
  kept in stock.
- **Figures** (K9), for the period and the whole crop: days of culture, survival, unseen losses, FCR, yield and profit
  per acre, cost per kg and average price.
- **Reports** (K14): *Pond crops* and the *Water quality log*, exported as CSV and Excel, and in the report catalogue.
- **Dashboard** (K14): water outside the limits today, today's pond record missing after 10 a.m., and crops past their
  planned harvest.
- **Module** (K12): *Fish farming* is available, off by default, with new permissions `AQUACULTURE_READ` and
  `AQUACULTURE_WRITE` granted like the livestock ones.
- **Web**: a *Fish farming* section with Ponds, a pond page, a crop page, Water quality and Reports.
- **Phone** (K13): *More → Ponds*: today's feed and deaths seen, a water reading with its flags, and a weight sample.
  Online only.

### Database migrations

`012-aquaculture.yaml` contains two changesets:

- `047-aquaculture-schema`:
  - Adds the cost object kind `POND_CYCLE`.
  - Adds the tables `sf_ponds`, `sf_pond_cycles`, `sf_pond_stockings`, `sf_pond_days`, `sf_pond_day_feeds`,
    `sf_pond_deaths`, `sf_pond_samples`, `sf_water_readings`, `sf_water_limits`, `sf_pond_harvests` and
    `sf_pond_harvest_lines`. All have tenant keys and composite foreign keys.
- `048-aquaculture-seed`: the *Fish and shrimp seed* category, and the permissions with their role grants. Existing
  farm businesses keep the module off.

These were applied to the local database on 2026-10-21, after a `pg_dump` backup (`smartfarm360-before-phase10.backup`).
No business data was changed.

### APIs added

Under `/api/v1/tenants/{tenantId}/aquaculture` (module *Fish farming*):

- `GET|POST /ponds`, `GET|PUT /ponds/{id}`
- `GET /cycles`, `POST /ponds/{id}/cycles`, `GET|PUT /cycles/{id}`
- `GET|POST /cycles/{id}/stockings`, `DELETE /stockings/{id}`
- `GET|PUT /cycles/{id}/days/{date}`
- `GET|POST /cycles/{id}/samples`, `DELETE /samples/{id}`
- `GET|POST /cycles/{id}/harvests`, `DELETE /harvests/{id}`
- `GET /readings`, `POST /ponds/{id}/readings`, `DELETE /readings/{id}`
- `GET|PUT /water-limits`
- `GET /cycles/{id}/report`, `GET /reports/cycles`

`/readings` and `/reports/cycles` accept `format=csv|xlsx`.

### Tests and evidence

| Command | Result |
|---|---|
| `backend: gradlew test --rerun bootJar` | Passed: **654 tests, 0 failures** (see below) |
| `frontend: npm run lint`, `tsc --noEmit`, `npm run build` | Passed; the bundle is still over 500 kB |
| `frontend: npm test` | Passed: **66 tests**, including 6 new fish farming screen tests |
| `mobile: npx tsc --noEmit`, `npm test`, `npx expo install --check` | Passed: **41 tests**, including 4 new pond tests |
| Manual browser check | Passed (see below) |

The backend run includes:

- The golden scenario from section 10 of the design. Every figure is asserted:
  - growing, 1 August: average weight 600 g, estimated 2,310 kg in the pond, estimated FCR 1.85, 1,550 rohu estimated;
  - the 12 June reading of DO 3.2 flagged against the lowest of 4.0;
  - the crop: 241 days of culture, 4,000 stocked, 200 deaths seen, 3,200 fish and 3,000 kg harvested, 6,000 kg feed,
    survival 80.0%, 600 unseen losses, FCR 2.03, 3,000 kg per acre, costs ₹2,29,000, revenue ₹4,04,000, profit
    ₹1,75,000 (also per acre), ₹76.33 per kg, average price ₹134.67;
  - July alone: costs ₹30,000, no revenue, a loss;
  - the exports, and the books balance.
- 8 rule tests:
  - ponds in acres, unique names, one open crop, and cycles as cost objects;
  - deaths never above the fish stocked, only stocked species, *Other* needs a name;
  - changing the day's feed reverses the earlier stock use;
  - a harvested crop refuses records, the buyer's charges are costs, and reversing the sale reopens it;
  - survival unknown without counts, FCR and yield from a partial and a final harvest, charges need a sale;
  - water limits for the farm business and one pond, and the dashboard item;
  - permissions (worker, viewer, money) and the module switch;
  - another farm business's ponds, crops, stockings, samples, readings, harvests, farm and feed cannot be reached or
    referenced.
- An upgrade migration test: existing farms keep Fish farming off, the grants and the seed category are in place, the
  database accepts pond cycles and refuses an inverted limit.
- 12 module-boundary rules.
- 488 generated cross-tenant cases.
- The dashboard performance test: **553 ms** at 50,000 journal lines.

One existing test changed with the approved design: the "unavailable module" check now uses Organic manure, because
Fish farming is now available (K12).

The manual browser check ran against backend :8081 on local PostgreSQL 18, with Vite on :5174:

- Migrations 047–048 were applied at startup.
- Fish farming was switched on in Settings, and the menu showed *Fish farming*.
- *Pond 1* was added with 50 cents of water, shown as 0.5 acres.
- A crop was started (*Preparing*), and 500 rohu stocked without a bill turned it to *Growing*.
- Today's record with 5 deaths seen left 495 rohu estimated.
- The lowest DO limit of 4 was saved for the farm business. A reading of DO 3.2 and pH 7.8 was flagged at once, and the
  dashboard showed "Water outside your limits today in 1 ponds", linked to Water quality.
- The reports page listed the crop. The backend log shows 0 errors and no secret values.

Not verified:

- A real phone.
- Seed bills, feed from stock, samples and harvest sales in the browser (they are covered by the API golden and rule
  tests and the screen tests).
- GitHub Actions (nothing has been pushed).

## Phase 11 — Organic resources (2026-10-21)

Commits on `feature/phase-11-organic` (based on `feature/phase-10-aquaculture`):

- `b67c3b5`: design.
- A build commit with the backend, web, mobile, verification and docs.

### Features completed

- **Approved design.** [organic resources](organic/organic-resources.md) is implemented with L1–L15 as recommended.
  Implementation notes are in section 13 of the design.
- **Internal transfers** (L4, L5, L9, L12; posting rule P17):
  - A new reversible record, *Own produce used* (`ITRF`), moves the farm's own produce from stock to another activity:
    compost to a field, fodder to the herd, dung into a compost pit.
  - It is never income. When cost moves, it is a net-zero line on the new account *Own farm inputs* (5650). The farm's
    total profit is unchanged.
  - Cost moves at the source's average cost per unit so far, capped at its costs not yet moved, and is shown before
    saving. Organic batch output moves at average cost by default; other produce moves at nil unless an owner or
    accountant chooses average cost.
  - A stock feature for every farm business: *Stock → Use own produce on another activity*, with lots for harvests
    that track them.
- **Organic batches** (L1, L2, L6, L7): compost pits, vermicompost beds and liquid manures as cost objects, with a log
  (turned, watered, added, checked, worms), output into stock at nil value, and finishing and reopening.
- **Manure collection** (L3): dung and litter into stock as produce of the herd or batch, from the organic and
  livestock pages.
- **Figures** (L8): input kg, output, yield, costs, cost moved out, revenue, profit, cost per kg and stock left, for
  the period and the whole batch.
- **Reports** (L14): *Compost and manure batches* and *Own produce used on the farm*, exported as CSV and Excel, and
  in the report catalogue. Activity reports show *Own farm inputs* as a cost line.
- **Dashboard** (L14): batches past their expected ready date.
- **Module** (L11): *Organic manure and compost* is available, off by default, with new permissions `ORGANIC_READ`
  and `ORGANIC_WRITE`.
- **Web**: an *Organic manure* section with Batches, a batch page and Reports, and the own-produce page in Stock.
- **Phone** (L13): *More → Compost*: a batch log entry, output, compost spread on a field, and manure collected.
  Online only.

### Database migrations

`013-organic.yaml` contains two changesets:

- `049-organic-schema`:
  - Adds the cost object kind `ORGANIC_BATCH`, the document type `INTERNAL_TRANSFER` (allowed a zero total), and the
    account 5650 *Own farm inputs*.
  - Adds the tables `sf_internal_transfers`, `sf_organic_batches` and `sf_organic_logs`. All have tenant keys and
    composite foreign keys.
- `050-organic-seed`: the permissions and their role grants. Existing farm businesses keep the module off.

These were applied to the local database on 2026-10-21, after a `pg_dump` backup (`smartfarm360-before-phase11.backup`).
No business data was changed.

### APIs added

- Under `/api/v1/tenants/{tenantId}/stock/internal-transfers` (every farm business): `POST /preview`, `POST`, and
  `GET` (accepts `format=csv|xlsx`).
- Under `/api/v1/tenants/{tenantId}/organic` (module *Organic manure and compost*):
  - `GET|POST /batches`, `GET|PUT /batches/{id}`, `POST /batches/{id}/finish`, `POST /batches/{id}/reopen`
  - `GET|POST /batches/{id}/logs`, `DELETE /logs/{id}`
  - `GET|POST /batches/{id}/outputs`
  - `GET /batches/{id}/report`, `GET /reports/batches` (accepts `format=csv|xlsx`)

### Tests and evidence

| Command | Result |
|---|---|
| `backend: gradlew test --rerun bootJar` | Passed: **697 tests, 0 failures** (see below) |
| `frontend: npm run lint`, `tsc --noEmit`, `npm run build` | Passed; the bundle is still over 500 kB |
| `frontend: npm test` | Passed: **71 tests**, including 5 new organic and own-produce screen tests |
| `mobile: npx tsc --noEmit`, `npm test`, `npx expo install --check` | Passed: **44 tests**, including 3 new compost tests |
| Manual browser check | Passed after one fix (see below) |

The backend run includes:

- The golden scenario from section 8 of the design. Every figure is asserted:
  - fodder: nil by default; at average cost ₹0.50 per kg, ₹3,000 moves; fodder plot costs ₹9,000 and herd costs ₹3,000;
    the farm's July profit unchanged;
  - vermicompost: 75 days, 2,500 kg in, 1,000 kg out, yield 40.0%, costs ₹10,000, ₹10.00 per kg;
  - 600 kg to Groundnut at average cost: ₹6,000 moved, Groundnut costs ₹6,000, the farm's October profit unchanged;
  - VC-1: ₹6,000 moved out, revenue ₹4,500, profit ₹500, 100 kg in stock;
  - the reports and exports, and the books balance.
- 8 rule tests:
  - average cost capped at the costs not yet moved, and the quantity check against what the source produced;
  - reversing a transfer restores both activities and the stock;
  - only farm produce, a real target and a different activity; average cost needs a source;
  - only an owner or accountant changes the default method;
  - harvests that track lots are moved lot by lot;
  - a finished batch refuses log entries and transfers in until reopened; input kg and the overdue dashboard item;
  - permissions and the module switch, and transfers without the module;
  - another farm business's batches, logs, items, farm and activities cannot be reached or referenced.
- An upgrade migration test: existing farms keep Organic manure off; the grants, account 5650, the new cost object kind
  and the zero-total transfer record are in place.
- 13 module-boundary rules.
- 520 generated cross-tenant cases.
- The dashboard performance test: **689 ms** at 50,000 journal lines (the budget is 1,000 ms).

One existing test changed with the approved design: the "unavailable module" check now uses Processing, because
Organic manure is now available (L11).

The manual browser check ran against backend :8081 on local PostgreSQL 18, with Vite on :5174:

- Migrations 049–050 were applied at startup.
- *Organic manure and compost* was switched on in Settings, and the menu showed *Organic manure*.
- *Compost pit 1* was started.
- **Defect found and fixed:** moving 50 kg of Samba paddy into the pit was refused ("Enter the lot or batch number"),
  because harvests track lots and the transfer had no lot. The transfer now takes a lot, the web form offers the item's
  open lots, and a rule test covers it. After the fix the transfer was recorded as `ITRF/2026-27/0001` at nil cost, and
  the batch showed 50 kg of input.
- A *Turned* log entry was recorded. The dashboard loaded with no failed tiles. The backend log shows 0 errors and no
  secret values.

Not verified:

- A real phone.
- Batch output, compost sales and average-cost transfers in the browser (they are covered by the API golden and rule
  tests and the screen tests).
- GitHub Actions (nothing has been pushed).

## Phase 12 — Processing and trading (2026-10-21)

Commits on `feature/phase-12-processing` (based on `feature/phase-11-organic`):

- `4bae8ce`: design.
- A build commit with the backend, web, mobile, verification and docs.

### Features completed

- **Approved design.** [processing and trading](processing/processing-and-trading.md) is implemented with M1–M15 as
  recommended. Implementation notes are in section 15 of the design.
- **Processing batches** (M1, M2): one run of cleaning and grading, drying, milling, oil pressing or packing, as a cost
  object with a code and a main product. Inputs come through internal transfers (own produce), stock uses (bought
  produce, bags) and bills (hired milling, labour).
- **Raw material carries its cost** (M3): own produce moved into a batch moves at average cost by default, so the crop's
  cost follows its harvest; the farm's total profit is unchanged.
- **Steps** (M4): kg in and out, moisture before and after, grade and notes; the step's loss is shown.
- **Output** (M5): main product, byproducts and waste. Products go into stock at nil value with lots made from the batch
  code; waste is recorded in kg only.
- **Weight balance and figures** (M6, M7): input kg, each output's yield (the outturn), process loss, costs, revenue,
  profit, cost per kg of the main product by the byproduct method, and the raw material's cost per kg.
- **Lot trace** (M8): backward from a lot to its batch, input lots and crop cycle or supplier; forward to the batches, their
  output lots and the customers. Printable.
- **Trading margin** (M10): bought, sold, revenue, cost of sales, margin and stock for goods bought for resale, without the
  module.
- **Reports and dashboard** (M14): processing batches and trading margin, exported as CSV and Excel, in the report
  catalogue; batches past their expected end on the dashboard.
- **Module** (M12): *Processing and trading* is available, off by default, with new permissions `PROCESSING_READ` and
  `PROCESSING_WRITE`. Every module is now available.
- **Web**: a *Processing* section with Batches, a batch page, Lot trace and Reports; *Stock → Trading*.
- **Phone** (M13): *More → Processing*: own produce into a batch (with lots), a step, and output. Online only.

### Database migrations

`014-processing.yaml` contains two changesets:

- `051-processing-schema`: the cost object kind `PROCESSING_BATCH`, and the tables `sf_processing_batches`,
  `sf_processing_steps` and `sf_processing_outputs`. All have tenant keys and composite foreign keys.
- `052-processing-seed`: the permissions and their role grants. Existing farm businesses keep the module off.

These were applied to the local database on 2026-10-21, after a `pg_dump` backup (`smartfarm360-before-phase12.backup`).
No business data was changed.

### APIs added

- Under `/api/v1/tenants/{tenantId}/processing` (module *Processing and trading*):
  - `GET|POST /batches`, `GET|PUT /batches/{id}`, `POST /batches/{id}/finish`, `POST /batches/{id}/reopen`
  - `GET|POST /batches/{id}/steps`, `DELETE /steps/{id}`
  - `GET|POST /batches/{id}/outputs`, `DELETE /outputs/{id}` (waste only)
  - `GET /batches/{id}/report`, `GET /reports/batches` (accepts `format=csv|xlsx`)
  - `GET /trace?lotNo=`
- `GET /api/v1/tenants/{tenantId}/stock/reports/trading` (no module; `FINANCE_READ`; accepts `format=csv|xlsx`).

### Tests and evidence

| Command | Result |
|---|---|
| `backend: gradlew test --rerun bootJar` | Passed: **738 tests, 0 failures** (see below) |
| `frontend: npm run lint`, `tsc --noEmit`, `npm run build` | Passed; the bundle is still over 500 kB |
| `frontend: npm test` | Passed: **77 tests**, including 6 new processing and trading screen tests |
| `mobile: npx tsc --noEmit`, `npm test`, `npx expo install --check` | Passed: **47 tests**, including 3 new processing tests |
| Manual browser check | **Skipped** at the product owner's request (see below) |

The backend run includes:

- The golden scenario from section 10 of the design (dated 30 September – 9 October). Every figure is asserted:
  - 2,000 kg of Samba paddy into batch RM-2610-01 at average cost: ₹15.00 per kg, ₹30,000 moved; Samba's costs
    ₹30,000 after; the farm's October profit unchanged;
  - the drying step's 80 kg loss;
  - lots RM-2610-01-1, -2 and -3; waste with no stock record; the batch finished after 4 days;
  - input 2,000 kg; rice 1,300 kg (65.0%), broken rice 100 kg (5.0%), bran 160 kg (8.0%), husk 340 kg (17.0%); process
    loss 100 kg (5.0%);
  - costs ₹37,000, revenue ₹83,400, profit ₹46,400, byproduct sales ₹5,400, rice ₹24.31 per kg, paddy ₹15.00 per kg;
  - the trace backward from RM-2610-01-1 to SAMBA-2026-H1 and *Samba 2026*, with the sale to Lakshmi Stores, and forward
    from SAMBA-2026-H1 to the batch, its three lots and their sales;
  - trading: 2,000 kg bought for ₹1,60,000, 1,500 kg sold for ₹1,50,000, cost of sales ₹1,20,000, margin ₹30,000
    (20.0%), 500 kg (₹40,000) in stock;
  - the exports, and the books balance.
- 6 rule tests:
  - output roles, lots only for lot-tracked items, steps cannot gain weight, unique codes, code and main product fixed
    after output;
  - byproduct sales lower the main product's cost; a reversed output no longer counts;
  - own produce into a batch carries its cost (a farm manager cannot change it); a finished batch refuses steps, output
    and transfers until reopened; the overdue dashboard item;
  - the trace through a bought lot to its supplier, with bought produce as raw material at its cost;
  - permissions (viewer, worker, stock permission for output) and the module switch, and trading without the module;
  - another farm business's batches, steps, outputs, lots, items, farm and batch cannot be reached or referenced.
- An upgrade migration test: existing farms keep Processing off and the grants are in place.
- 14 module-boundary rules.
- 552 generated cross-tenant cases.
- The dashboard performance test: **636 ms** at 50,000 journal lines (the budget is 1,000 ms).

One existing test changed with the approved design: the permission test that tried to switch on an unavailable module now
checks that every module reports available and can be switched on, because Processing was the last unavailable module
(M12). The `MODULE_NOT_AVAILABLE` guard stays for future modules.

Migrations 051–052 were applied at startup of the new jar, with no errors in the backend log. The **manual browser check was
skipped** at the product owner's request on 2026-10-22. The browser session had been revoked, and signing in needs the
account's password, which the agent does not have. The revoked session comes from a defect in the existing sign-in
handling, recorded under known gaps below.

Not verified:

- The browser check (skipped), and a real phone.
- GitHub Actions (nothing has been pushed).

## Phase 13 — Mobile offline (2026-10-22)

Commits on `feature/phase-13-mobile-offline` (based on `feature/phase-12-processing`):

- `cab3593`: design.
- A build commit with the backend, web, mobile, verification and docs.

### Features completed

- **Approved design.** [mobile offline](mobile/mobile-offline.md) is implemented with N1–N15 as recommended.
  Implementation notes are in section 14 of the design. The server decisions are in [ADR 0010](adr/0010-offline-sync.md).
- **The phone keeps one store per user** (N1, `expo-sqlite`). It holds:
  - the outbox;
  - a read-through cache of everything the user opened online (reference lists and last views, each with its "as of"
    time, N3).
- **Offline records** (N2):
  - the day's attendance;
  - crop activities, harvests and field notes;
  - milk by session, heat seen and treatments;
  - the poultry daily record and herd head counts;
  - the pond daily record, water readings and weight samples;
  - compost log entries and manure collected;
  - processing steps;
  - cash quick entries.

  Online, every one of these is sent at once, as before. Set-up work, stock output, transfers and party records stay
  online only.
- **Client ids and the server idempotency store** (N4):
  - each record has a UUIDv7 id that is its `Idempotency-Key`;
  - the server keeps the reply for 60 days and replays it to the same user;
  - a different body or user is refused;
  - a failed request frees its key.
- **The sync engine** (N5, N6):
  - it sends oldest first;
  - only the target of a refused record is held back;
  - network and server failures wait 30 s, 2 min, 10 min, then every 30 min;
  - it sends at most 5 requests a second;
  - it runs when the app opens or comes to the front, when the network returns, on *Sync now*, and every 5 minutes
    while records are waiting.
- **Day-record versions** (N7). Attendance, milking sessions, the poultry day and the pond day return a version, and
  saves send `baseVersion`. A stale save is refused with `STALE_RECORD`. This applies:
  - on the phone: *Compare* shows yours and the server's, then *Keep mine*, *Use the server's* or *Edit*;
  - on the web: *Load the latest version*.
- **Needs attention** (N8): the server's reason in the user's language, edit and resend, or discard. Discarded records
  stay in a 30-day list; sent records stay 7 days.
- **Offline sign-in and protection** (N9–N12):
  - the app opens offline with the last user and farm business;
  - only that user's store is opened, so another user's records are never sent;
  - signing out with records waiting asks *Send now*, *Keep for my next sign-in* or *Discard them*;
  - a farm business the user has left is cleared from the phone, and its records are locked;
  - phone clocks more than 10 minutes off the server's get a warning.
- **Status** (N13): a status line on every phone screen, record badges, and *More → Sync* with the outbox, *Sync now*,
  the last sync, the clock check and the app version.
- **Compatibility** (N14): the phone sends `X-Client-Version`. `APP_MIN_CLIENT_VERSION` (empty by default) refuses
  older apps with `CLIENT_UPGRADE_REQUIRED`, and the phone keeps the outbox.
- **Quick entries** saved before this phase move into the outbox once, keeping their ids.

### Database migrations

`015-offline.yaml` contains one changeset:

- `053-idempotent-requests`: the table `sf_idempotent_requests`, with:
  - a unique key on (tenant, key);
  - a check that a reply is stored only with its completion time;
  - an index on (tenant, created at).

No version columns were added: versions are hashes of the stored values (ADR 0010).

It was applied to the local database on 2026-10-22 at startup of the new jar, after a `pg_dump` backup
(`smartfarm360-before-phase13.backup`, 89 tables). No business data was changed.

### APIs changed

- New request header `Idempotency-Key` on the `@Idempotent` endpoints listed in the design (section 14).
  - Replays carry `Idempotent-Replayed: true`.
  - New error codes: `IDEMPOTENCY_KEY_INVALID`, `IDEMPOTENCY_KEY_REUSED`, `IDEMPOTENCY_IN_PROGRESS`.
- `GET|PUT /workforce/attendance/{date}`, `GET|PUT /livestock/milk/{date}` (per session),
  `GET|PUT /livestock/flocks/{id}/days/{date}` and `GET|PUT /aquaculture/cycles/{id}/days/{date}`:
  - reads return `version`;
  - saves take an optional `baseVersion`;
  - a stale save is refused with 409 `STALE_RECORD`.
- Header `X-Client-Version` (logged; 426 `CLIENT_UPGRADE_REQUIRED` below `app.min-client-version`).

### Tests and evidence

| Command | Result |
|---|---|
| `backend: gradlew test bootJar` | Passed: **746 tests, 0 failures** (8 new) |
| `frontend: npm run lint`, `npm test`, `npm run build` | Passed: **78 tests** (1 new); the bundle is still over 500 kB |
| `mobile: npx tsc --noEmit`, `npm test`, `npx expo install --check` | Passed: **74 tests** (27 new); dependencies up to date |
| Migration on the local database | Applied (`053-idempotent-requests`), no errors in the log; health 200 |
| Manual browser check | **Not done**: restarting the backend ended the browser session, and signing in needs the owner's password |

The new backend tests (`OfflineSyncTest`) are:

- **Replay.** The same key and body get the stored reply and record once. Without a key, every save records.
- **Refusals.** A different body or a different user is refused, as is a key still in progress or a badly formed key.
- **Release.** A refused request frees its key, so the corrected record can be sent with the same id.
- **Cross-tenant.** Another farm business's owner gets 404 and no replay. The same key in their own business is a new
  record there.
- **Stale saves.** These are refused on attendance, milking sessions (per group and session), the poultry day and the
  pond day. Saves without a base version are unchecked.
- **Client version.** The filter compares versions (1.10 is newer than 1.9) and refuses below the minimum. Requests
  without the header pass.
- **The section 10 example**, end to end (dated 20 October 2026):
  - attendance for 6 workers, sent;
  - the milk based on version 1 is refused as stale after Selvi's correction;
  - the poultry day is sent, with feed issued;
  - the Urea activity is refused for short stock, then resent with 10 kg under the same id;
  - the diesel expense is sent;
  - the water reading is sent, flagged out of limits;
  - the retried attendance returns the stored reply byte for byte, and there are 6 attendance rows, not 12;
  - Urea ends at 0 kg, the feed at 390 kg, and the books balance.

  Murugan is a farm manager in the test: a field supervisor has neither stock nor finance permission (open decision
  below).

The new mobile tests cover:

- UUIDv7 ids;
- reply classification;
- oldest-first order with only the refused target held;
- backoff;
- the 5-a-second pacing;
- a lost reply resent under the same id and recorded once;
- stops for an old app version and an ended session;
- lost membership locking a business's records;
- a later save of the same day carried onto the version just sent;
- the 7- and 30-day pruning;
- keep mine, edit and discard;
- the status line, the conflict figures and the edit form;
- the hub: online send, online refusal not kept, offline wait then send with the same key, order behind an earlier
  record of the same target, keep mine and use the server's;
- another user on the same phone, the sign-out choices, lost membership clearing the cache, and quick entries moved
  once without duplicates;
- the API client: the version header, offline reads served from the cache, outbox sends that never throw, and a 401
  refreshed once.

Existing tests changed with the approved design:

- 9 phone view tests now also expect the record's `Idempotency-Key`.
- 3 web tests now also expect the `baseVersion` sent.

Nothing was removed from those tests.

Not verified:

- The web pages in a browser.
- A real phone: SQLite, NetInfo, the app going to the background, and a signal lost in the middle of a send.
- GitHub Actions (nothing has been pushed).

## Phase 14 — SaaS operations and production readiness (2026-10-27)

Commits on `feature/phase-14-saas-ops` (based on `feature/phase-13-mobile-offline`):

- `3db7e9d`: design.
- A build commit with the backend, web, mobile, deployment files, runbooks and docs.

### Features completed

- **Approved design.** [SaaS operations](platform/saas-operations.md) is implemented with O1–O15 as recommended.
  Implementation notes are in section 18.
  - ADRs: [0011](adr/0011-row-level-security.md) Row-Level Security, [0012](adr/0012-hosting-and-operations.md) hosting
    and operations, [0013](adr/0013-owners-data-export-and-deletion.md) export and deletion.
  - ADR 0002 and ADR 0003 are amended.
- **Production profile (O2).** `SPRING_PROFILES_ACTIVE=prod` refuses to start with unsafe settings, one line per
  problem. It gives JSON logs, metrics and probes on a private port (8091), the built web app served from the same
  address, and security headers (CSP, HSTS, no framing).
- **Deployment (O1, O2).**
  - `deploy/` holds the image, the Compose file with Caddy, and the settings template.
  - The CI image job builds and scans the image.
  - Runbooks in `docs/ops/`: deployment, release, incident, restore, secrets.
- **Backups (O3).** `deploy/backup.sh` (nightly, encrypted, off-region) and `deploy/restore-check.sh` (monthly). Both
  record their result on the console.
- **Email (O5).**
  - An SMTP sender, with English and Tamil texts from the shared catalogs and three retries.
  - Email verification: new accounts verify before creating a farm business; accepting an invitation verifies too;
    existing accounts are treated as verified.
- **Platform console (O6).**
  - Farm businesses: search, detail and usage; suspension with a reason shown to members; limit overrides.
  - Users: lock and unlock.
  - Operations checks and a test alert.
  - Announcements and read-only maintenance.
  - Terms and privacy publishing, and the platform audit log.
  - Two-step sign-in (authenticator app, recovery codes): required for the console, optional for everyone.
- **Support access (O7):** owner-granted, read-only, at most 72 hours, every page recorded in the business's history.
- **Plans and limits (O8):** `FREE` with members 25, farms 20, farm businesses per user 3, and exports 3 a day. A notice
  appears at 80%, and only new additions are refused (`PLAN_LIMIT_REACHED`).
- **Owners' data (O9–O11):**
  - the whole-business export (CSV and JSON with a README, kept 7 days);
  - deletion after 30 days' grace (read-only meanwhile, cancellable), then a final delete keeping only a deletion
    record;
  - account deletion with a former-member label.
- **Privacy and consent (O12):** versioned terms and privacy notice in both languages, accepted before going on, with a
  consent history, a marketing opt-in (off by default) and a copy of one's personal data.
- **Security (O13):**
  - Row-Level Security forced on all 81 tenant tables;
  - the 30-second refresh grace, which fixes the two-tab sign-out defect;
  - rate limits in PostgreSQL.
- **Load tests and checklists (O14):** k6 scripts in `deploy/load/` with the section 12 targets, and the release,
  incident and restore checklists.
- **Releases (O15):** Android through Play testing tracks (`docs/ops/release.md`); Electron frozen.
- **Web:** *Your account* (email, two-step, privacy, deletion), the consent page, the verify-email page, the second
  sign-in step, the settings sections (plan, support, data, deletion), announcement banners and the new console.
- **Phone:** the second sign-in step, accepting new terms, and announcement lines.

### Database migrations

`016-saas-operations.yaml` contains four changesets:

- `054-saas-operations-schema`:
  - plans and limits, and limit overrides;
  - suspension and deletion columns on `sf_tenants`;
  - support grants, the platform audit log and tenant exports;
  - two-step secrets and recovery codes, and the `mfa` flag on refresh tokens;
  - legal documents and consents;
  - email verification;
  - rate-limit counters, announcements and operations checks.

  Existing users are marked verified.
- `055-saas-operations-seed`: the `FREE` plan and its limits, and a foreign key from `sf_tenants.plan_code`.
- `056-row-level-security`: `sf_rls_allows()`, and the forced policy on every table with a `tenant_id`.
- `057-guards-allow-tenant-deletion`: the append-only and read-only guards allow DELETE only inside a farm business's
  deletion.

They were applied to the local database on 2026-10-27 at startup of the new jar, after a `pg_dump` backup
(`smartfarm360-before-phase14.backup`, 90 tables). No business data was changed; existing users were marked verified.

### APIs added

| Area | Endpoints |
|---|---|
| Auth | `POST /auth/login/second-step`, `POST /auth/verify-email`, `POST /auth/me/verification`, `POST /auth/me/two-step/{setup,enable,disable,recovery-codes}` |
| Account | `GET /me/consents`, `POST /me/consents`, `PUT /me/marketing`, `GET /me/data`, `POST /me/deletion` |
| Public | `GET /legal/current`, `GET /announcements` |
| Farm business | `GET /tenants/{t}/usage`; `GET`, `POST /support-grants` and `POST /support-grants/{id}/end`; `GET`, `POST /exports` and `GET /exports/{id}/download`; `GET`, `POST`, `DELETE /deletion` |
| Platform | `GET /platform/tenants?q=`, `GET /platform/tenants/{id}`, `PATCH /platform/tenants/{id}` (with a reason), `PUT /platform/tenants/{id}/limits/{code}`, `GET` and `PATCH /platform/users`, `GET /platform/ops`, `POST /platform/ops/checks`, `POST /platform/ops/test-alert`, `GET /platform/audit`, `GET`, `POST /platform/announcements` and `POST /platform/announcements/{id}/end`, `GET`, `POST /platform/legal-documents` |

Changed:

- The login response carries `secondStepChallenge` when two-step sign-in is on.
- `UserResponse` adds `emailVerified`, `twoStepEnabled` and `pendingConsents`.
- `TenantSummary` adds `suspendedReason` and `deletionScheduledFor`.

### Tests and evidence

| Command | Result |
|---|---|
| `backend: gradlew test bootJar` | Passed: **787 tests, 0 failures** (41 new); the dashboard test answers in 483 ms (budget 1,000 ms) |
| `frontend: npm run lint`, `npm test`, `npm run build` | Passed: **83 tests** (5 new); the bundle is still over 500 kB |
| `mobile: npx tsc --noEmit`, `npm test`, `npx expo install --check` | Passed: **75 tests** (1 new); dependencies up to date |
| `bash -n deploy/backup.sh deploy/restore-check.sh` | Syntax OK |
| Migration on the local database | Applied (054–057) on 2026-10-27; no errors in the log; 81 tables under the policy; health 200; security headers present |
| `deploy/restore-check.sh` | **Not run**: it creates a scratch database, and the local user cannot create databases; the superuser password was not used |
| `docker build -f deploy/Dockerfile` | **Not run**: no Docker on this machine (ADR 0007); CI builds it once pushed |
| k6 load tests | **Not run**: they need staging |
| Manual browser check | **Not done** (sign-in needs the owner's password) |

The new backend tests are:

- **`SaasOperationsTest`** (12):
  - email verification and invitation verification;
  - two-step sign-in: codes, recovery codes, replay refused, the challenge not a session, the refreshed session keeps
    the step;
  - the console needs the second step and records changes;
  - support access: none without a grant; with one, read-only, viewer permissions only, closed to other businesses,
    recorded, ended and expired;
  - limits and overrides, and the 3-business limit;
  - read-only maintenance;
  - export: contents, no secrets, no other business, the journal balances in the export, the daily limit;
  - deletion: owner only, name check, 30 days read-only, cancel, every row of that business removed, others untouched,
    the deletion record;
  - account deletion: the sole-owner refusal, the label, the books intact;
  - consent versions;
  - rate limits surviving a restart;
  - RLS hiding and refusing other businesses' rows inside a business's scope.
- **`RowLevelSecurityCoverageTest`:** every tenant table has the forced policy.
- **`ProductionSettingsTest`:** each unsafe production setting is refused and named.
- **`EmailTemplatesTest`:** every email in both languages, with its parameters filled.
- **`TotpTest`:** the RFC 6238 vectors, the time window, base32, and the encryption round trip.
- **`AuthIntegrationTest`**, one new test: two tabs refreshing together stay signed in; a signed-out family stays
  ended.

**Row-Level Security and the dashboard.** With the policies on, the dashboard test first took 1.1–1.7 s, against its
1-second budget. The policy filter itself was not the cause: it costs the same in either scope. The cause was generic
plans for prepared report queries with optional filters (about a second for the income statement). The fix:

- each connection uses custom plans, set with the tenant scope;
- the dashboard works out the dues once per request;
- the policy reads the session's business once per query.

Changeset 056 was corrected for this before its first run. The dashboard now takes 467–491 ms, faster than before RLS.
The budget was not changed.

The whole suite now runs as an ordinary database role, under the RLS policies, including the 552 cross-tenant cases.

Existing tests changed with the approved design:

- **New accounts verify their email address** in the test helper `newUser`, as a real user would before creating a
  farm business (O5).
- **The refresh-reuse test** ages the rotation past the 30-second grace, so it still proves that reuse revokes the
  family (O13).
- **The platform administrator tests** sign in with the second step (O6), and the suspension test gives a reason
  after checking that a suspension without one is refused.

Nothing was removed from these tests.

Not verified:

- The production image and the Compose set-up, real email, point-in-time recovery, the monitoring alerts and the load
  tests. These need the chosen host and staging.
- The browser check, and a real phone.
- GitHub Actions (nothing has been pushed).

## Phase 15 — Billing and payments (2026-10-27)

Approved design: [billing](platform/billing.md), P1–P15 as recommended (2026-10-27). New ADRs:
[0014](adr/0014-payment-gateway.md) (gateway and webhooks), [0015](adr/0015-gst-invoices.md) (GST invoices) and
[0016](adr/0016-plans-trial-and-grace.md) (plans, trial and grace). ADRs 0011 and 0013 are amended. The design's
implementation notes are in its section 17.

### Features completed

- **Plans (P1, P2).** FREE (5 members, 3 farms, for new businesses), GROWER and ESTATE, with monthly and yearly
  prices as data. Every existing farm business moved to FREE_LEGACY, with the limits it had.
- **Trial (P3).** A new farm business gets 30 days of GROWER, once per user, with reminders 7 days and 1 day before
  the end. Then FREE applies, and nothing recorded is hidden.
- **Checkout (P4, P5).** An owner fills in the billing details (legal name, Indian address, optional GSTIN checked with
  its check digit), picks a plan and period, and pays on the gateway's hosted page. Only a signed webhook activates the
  plan.
- **Webhooks.** The signature is checked first and each event is stored once. Events apply in order, and old state
  changes are ignored. A failed event is kept for a replay.
- **GST invoices (P6, P7).** CGST and SGST, or IGST, by place of supply, SAC 998314. Numbers are gapless per financial
  year (`SF/2026-27/000041`). An invoice adds up to exactly what was paid. It cannot be changed, and it is kept 8 years
  after a business is deleted. PDF download; credit notes for refunds. No IRN yet.
- **Renewals and grace (P8, P9).** A failed renewal gives 14 days' grace, with emails and a banner, then the business
  falls back to its free plan's limits. Paying later reactivates the plan. There is no suspension for non-payment.
- **Changes (P10, P11).** An upgrade for the same period is charged pro rata through a payment link. A downgrade or a
  change of period waits for the renewal. Cancelling keeps the plan until the end of the paid period. Asking for
  deletion ends the subscription at once.
- **Console Billing tab (P12).** Subscriptions by state, extending a trial or grace, comps, refunds with credit notes,
  re-sending invoices, prices with 30 days' notice (emailed to subscribers), events that did not apply with replay, and
  the nightly reconciliation (alerts only, never changes anything).
- **Record in my books (P13).** On request, a posted supplier bill from SmartFarm360 under the new system category
  *Software and subscriptions*, paid from a chosen account, once per invoice.
- **Phone (P14).** The plan and the banner, with a line saying the plan is managed on the web. There is no purchase in
  the app.
- **Emails** in English and Tamil: trial ending and ended, payment received, renewal failed, grace ending, plan lapsed,
  price change, refund.
- **Production settings** refuse the fake gateway, Razorpay test keys, a missing webhook secret and a missing or
  invalid seller GSTIN.

### Database migrations

`017-billing.yaml`:

- **058** schema: plan prices, tax rates, subscriptions and their events, billing details, trials used, gateway plans,
  gateway events, payments, document counters, invoices and lines, credit notes, reconciliation runs. It also adds RLS:
  the standard policy on the tenant tables, and `sf_customer_isolation` on the billing documents. A trigger keeps
  issued documents immutable.
- **059** seed: the plans and their limits; existing businesses to FREE_LEGACY; example prices; GST rates;
  `BILLING_VIEW` (owner, accountant) and `BILLING_MANAGE` (owner); the *Software and subscriptions* category.

Changeset 058 was corrected before its first run: the trigger function needed its own SQL block. The migrations were
applied to the local database on 2026-10-27, after a `pg_dump` backup (`smartfarm360-before-phase15.backup`, 104
tables).

### APIs added

| Area | Endpoints (under `/api/v1`) |
|---|---|
| Farm business | `GET /tenants/{t}/billing`; `GET`, `PUT /billing/profile`; `POST /billing/checkout`, `/billing/change`, `/billing/cancel`; `GET /billing/invoices`, `/billing/invoices/{id}/pdf`, `/billing/invoices/{id}/credit-notes/{cn}/pdf`; `POST /tenants/{t}/finance/subscription-invoices/{id}/book` |
| Public | `POST /billing/webhooks/{gateway}` (signed) |
| Platform | `GET /platform/billing`, `/subscriptions`, `/payments`, `/invoices`, `/prices`, `/reconciliation`; `POST /payments/{id}/refund`, `/invoices/{id}/resend`, `/tenants/{id}/trial`, `/tenants/{id}/grace`, `/tenants/{id}/comp/end`, `/prices`, `/reconciliation`, `/events/{id}/replay`; `PUT /tenants/{id}/comp` |

### Tests and evidence

| Command | Result |
|---|---|
| `backend: gradlew test bootJar` | Passed: **826 tests, 0 failures** (39 new, counting the cross-tenant cases the new endpoints add); the dashboard test answers in 365 ms (budget 1,000 ms) |
| `frontend: npm run lint`, `npm test`, `npm run build` | Passed: **88 tests** (5 new); the bundle is still over 500 kB |
| `mobile: npx tsc --noEmit`, `npm test`, `npx expo install --check` | Passed: **76 tests** (1 new); dependencies up to date |
| `pg_dump --enable-row-security` (local backup) | 104 tables; without the flag `pg_dump` refuses tables under forced RLS, so `deploy/backup.sh` was fixed |
| Migration on the local database | Applied (058–059) on 2026-10-27; no errors in the log; the 3 existing businesses are on FREE_LEGACY; 88 tables under a policy; health 200; an unsigned webhook is refused (400) |
| Razorpay test mode end to end | **Not run**: it needs staging and the account |
| Manual browser check | **Not done** (sign-in needs the owner's password) |

The new backend tests are:

- **`BillingTest`** (12):
  - the trial once per user, its reminders and its end, with nothing hidden;
  - checkout only for owners, the billing details and GSTIN checks, nothing paid before the webhook, and the gateway
    being down;
  - a signed charge activating the plan and issuing the GST invoice once; a bad signature, an unknown subscription and
    repeated deliveries; immutable invoices; the PDF;
  - IGST for another state;
  - a failed renewal, 14 days' grace, reminders, the lapse to FREE with every record kept, and paying again;
  - the pro-rata upgrade by payment link, and a downgrade waiting for the renewal;
  - cancelling to the end of the period; re-subscribing stops the earlier gateway subscription; deletion ends the plan
    at once and keeps the invoices after the final delete; no second trial;
  - the console: refunds with credit notes and their limits, comps, trial and grace extensions with reasons, prices
    with 30 days' notice;
  - the reconciliation, and a failed event replayed;
  - *Record in my books*, once, with the books balanced;
  - isolation from other farm businesses in the API and in the database;
  - gapless invoice numbers under 8 concurrent payments, with a rolled-back issue taking no number.
- **`GstTest`** (6): the GSTIN check digit, CGST and SGST or IGST with rounding, invoices built from a paid total,
  credit-note shares, financial years, and the effective plan.
- **`LiquibaseMigrationIntegrationTest`**, one new test: existing businesses keep their limits on FREE_LEGACY, the
  billing permissions, and the billing documents' policy.
- The automatic cross-tenant test covers every new tenant endpoint.

Existing tests changed with the approved design:

- `ProductionSettingsTest` and `EmailTemplatesTest` gained the billing settings and the new email parameters.
- The migration test lists changesets 058–059 and the new tables.

Nothing was removed from these tests.

Not verified: the real gateway, the browser check, a real phone, and GitHub Actions (nothing has been pushed).

## Staging readiness (2026-10-28)

Getting staging ready without a host. Docker is not installed on this machine, so the image itself was not built.
Instead the built jar was started locally with the `prod` profile in staging mode (ports 8082 and 8092, random
secrets for the run, the local database, the built web app). That rehearsal found three defects, now fixed.

### Found and fixed

- **The home page was blank in production.** `/` answered 404: Spring's resource handler never sees an empty path,
  so the web app was served only on deep links. `SpaConfig` now forwards `/` to `index.html`. The file lookup became a
  testable `resolve` method, which also refuses `..`.
- **The monitoring agent could not read metrics.** `/actuator/prometheus` on the management port answered 401, because
  the security chain required sign-in there too. `SecurityConfig` now permits `GET /actuator/**` only on
  `management.server.port`, which the compose file binds to `127.0.0.1`. The public port still answers 401 and Caddy
  still answers 404.
- **Staging could not start with Razorpay test keys.** The `prod` profile refused them, while the deployment page said
  staging uses them. The new `APP_ENVIRONMENT` setting takes `production` (the default) or `staging`:
  - staging requires a test key (`rzp_test_`) and a public address whose host name contains `staging`, so production
    cannot be switched to test payments by this setting alone;
  - production still refuses test keys;
  - any other value is refused.

### Added

- `.github/workflows/release.yml`: a `v*` tag builds, scans (Trivy, critical findings that have a fix) and publishes
  `ghcr.io/<owner>/smartfarm360:<version>`. Before this, CI built the image but published it nowhere.
- `docs/ops/staging.md`: what is ready, the steps only the product owner can take, how to start staging, and the
  checks before production. Linked from `deployment.md`.
- `deploy/env.example`: `APP_ENVIRONMENT`; the image tag example is now `0.15.0`.

### Tests and evidence

| Command | Result |
|---|---|
| `backend: gradlew test bootJar` | Passed: **831 tests, 0 failures** (5 new: `ProductionSettingsTest` staging case, `SpaConfigTest` 2, `WebAppServingTest` 2) |
| Rehearsal: jar with `prod` and `APP_ENVIRONMENT=staging` | Started in 16 s with JSON logs and no errors. Unsafe settings check passed. `/`, `/t/x/settings` and the hashed bundle answer 200; a missing asset 404; `/api/v1/announcements` 200; unsigned webhook 400; `/actuator/prometheus` 401 on the public port and 200 on the management port; readiness 200; `/v3/api-docs` serves only the web app (springdoc is off) |
| `docker build`, the release workflow, Caddy | **Not run**: no Docker here, and nothing is pushed |

Frontend and mobile are unchanged since Phase 15 (88 and 76 tests), so they were not re-run.

Not done (needs the product owner): the provider and the staging resources, DNS, the email provider, the Razorpay test
account and seller details, pushing `main` and the first tag, and then the checks in `docs/ops/staging.md`.

## UI refresh (2026-10-28)

The look of the web app was redesigned. Only the frontend changed: no backend, API, permission, route or business-rule
changes, and no new features.

### What changed

- **Design system:** `frontend/src/theme.css`, loaded after `styles.css`. It defines tokens for colour, type, radius,
  shadow and motion, then restyles the shared classes every screen already uses, so all modules change together without
  edits to their markup:
  - the shell: a deep charcoal sidebar with an accent bar on the active item, and a sticky frosted top bar;
  - buttons (`primary`, `secondary`, `danger`, `icon-button`) with hover, press, focus and disabled states;
  - cards and panels;
  - KPI cards;
  - form fields, with hover, focus ring, invalid and disabled states;
  - tabs, with an animated underline;
  - tables, with uppercase headers and row hover;
  - status and stage badges;
  - info, error and warning banners;
  - empty states, disclosure triangles, the sign-in pages and the busy toast.

  `prefers-reduced-motion` turns the animations and hover movement off. Print keeps its plain layout.
- **Sidebar:** an icon for each section (`Layout.tsx`). Presentation only; the sections and permissions still come
  from `NAV_ITEMS`.
- **Dashboard:**
  - KPI cards with icons; profit is green and a loss red; overdue money shows as a warning.
  - The 12-month chart now spans the full card width. It had been squeezed into the narrow column of the `form-card`
    form grid. It has rounded money axes, month names with the year, and a tooltip for each month (mouse or
    keyboard), with the same table below it.
  - Largest costs show their existing share as a bar.
  - Attention items have an icon for each area.
  - Crops in the field show a stage badge.
  - Loading shows skeleton placeholders, and an error has *Retry*.
  - Same data, links and permissions as before.
- **Strings:** three new dashboard strings, in English and Tamil:
  - the chart's message when nothing is recorded;
  - the attention count's label;
  - the all-clear help text.

### Tests and evidence

| Command | Result |
|---|---|
| `frontend: npm run lint` | Passed |
| `frontend: npx tsc --noEmit` | Passed |
| `frontend: npm test` | Passed: **89 tests** (1 new: dashboard loading and retry) |
| `frontend: npm run build` | Passed; the bundle is still over 500 kB (known) |
| `mobile: npx tsc --noEmit`, `npm test` (shared strings changed) | Passed: **76 tests** |
| Browser, sign-in page at 1024 px and 375 px | New styling applied; no horizontal scroll at phone width |
| Browser, signed-in screens | **Not reviewed**: the browser pane had no signed-in session |

### Left for later

These are visual items not done, and features that were deliberately not added:

- Screens with their own one-off markup (for example the platform console tabs and long finance forms) were restyled
  only through the shared classes. They have not been reviewed screen by screen.
- Confirmations still use the browser's `window.confirm` (30 places). A styled dialog would change the tests that rely
  on it.
- The phone app (Expo) is unchanged.
- Possible features, not built:
  - a dashboard *as of* date picker (the API already accepts `asOf`);
  - quick actions on the dashboard;
  - turning chart series on and off;
  - 12-month totals.

## Farm and crop-cycle workspaces, colour and glass design (2026-11-02)

Asked for: an international-standard look across the app, and simple farm-wise money: open a farm and manage its
expenses there.

### Farm workspace

- **Farms page:** each farm is a card that opens the farm, showing its type as a badge and its place. Land parcels moved
  into the farm.
- **Farm page** (`/t/{tenant}/farms/{farm}`, `FarmPage.tsx`):
  - the farm's income, costs and profit for a chosen period (quick ranges, the financial year by default);
  - where the money goes (costs by category, with share bars);
  - the records booked to the farm, with *Show more*;
  - its land parcels, with *Add land parcel*.
  - *Add expense* and *Record sale* open the usual forms with the farm already chosen.
  - People without finance access see the land and a note, and no money.
- **Expense and sale form:** a new *Farm* field, filled from `?farm=` and kept when a draft is edited. *Whole business* is
  the default, as before.
- **Backend, one addition:** `GET /finance/documents?farmId=` lists a farm's records. A record belongs to a farm when its
  header or any line names that farm, the same rule the reports already use when they post by farm. Another tenant's
  farm answers 404 `FARM_NOT_FOUND`. No schema change: documents and lines already had `farm_id`, and the
  income-statement and category reports already took `farmId`.

### Crop-cycle expenses, and fixing mistakes

- **Crop cycle page:** a new *Expenses & sales* tab. It lists the records with a line booked to the cycle. *Add expense*
  and *Record sale* open the forms with the cycle's farm and the cycle itself already chosen (`?farm=&activity=`).
- **Records panel** (`finance/RecordsPanel.tsx`), shared by the farm and cycle pages. Each row offers:
  - **a draft:** *Edit draft*, and *Discard draft* (nothing was posted, so the accounts do not change);
  - **a posted record:** *Reverse*, which posts an opposite entry, and *Correct*, which reverses the record today and
    opens a copy to fix and save.

  Posted records are never deleted: the journal is append-only (ADR 0004), and reversal is the audited way to undo a
  mistake. Each action asks first. The buttons follow the existing permissions (`FINANCE_WRITE`, `FINANCE_REVERSE`).
- **Record form:** `?copy=` starts from a reversed record and shows a *Correcting …* note. `?activity=` books each line
  to that cycle.
- **Backend:** `GET /finance/documents?costObjectId=` lists an activity's records. Another tenant's activity answers 404
  `COST_OBJECT_NOT_FOUND`.

### Design upgrade

- **Font:** Inter (with Noto Sans Tamil) instead of DM Sans. DM Sans drew the rupee sign as an odd glyph. Inter's
  figures also line up in tables.
- **Top bar:** compact, a breadcrumb (*SMARTFARM360 / business*) instead of a large repeated title.
- **Cards:** KPI cards fit five across on a laptop. There are new farm cards, the farm hero and records list, and a
  lighter welcome banner.
- **Colour and glass:** a soft multi-colour backdrop, with frosted cards, panels, tables and controls over it.
  - Every KPI grid in the app colours its cards in turn: green, sky, amber, violet, rose and teal.
  - Page titles use a green-to-teal gradient.
  - Tabs are a frosted segmented control.
  - Buttons have gradients.
  - Print stays plain.
- **Fields:** one style for every text field, date, number, select and textarea across the app, including fields outside
  cards. Fields are 44 px tall with a soft inset, a hover border, a green focus glow, and invalid and disabled states.

### Tests and evidence

| Command | Result |
|---|---|
| `backend: gradlew test --tests FarmRecordsTest, CrossTenantIsolationTest, TenantEndpointInventoryTest` | Passed (FarmRecordsTest now 5 tests, 592, 2) |
| `backend: gradlew test bootJar` | **834 of 835 passed.** `DashboardPerformanceTest` failed at 1,748 ms against its 1,000 ms budget (also 1,268 ms in an earlier full run) |
| `DashboardPerformanceTest` alone, previous commit vs this change | 846 ms vs 754 ms: both pass, no regression. The dashboard never calls the changed records query |
| `frontend: npm run lint`, `npx tsc --noEmit` | Passed |
| `frontend: npm test` | Passed: **99 tests** (10 new: `farm.test.tsx` 4, `records-panel.test.tsx` 6). An earlier run during the backend suite failed one sign-in test on vitest's 5-second timeout; that file passed alone, and the full suite passed when run again |
| `frontend: npm run build` | Passed |
| `mobile: npm test` | Passed: **76 tests** |
| Browser review of the signed-in screens | **Not done**: the browser pane had no signed-in session |

The dashboard performance budget is now tight on this machine when it is busy. The shared machine was running IntelliJ,
three Gradle daemons, another project's app on port 8091 and the IDE backend. Phase 15's full run measured 365 ms. Re-check
it on CI or a quiet machine; the test was not relaxed.

The farm records list needs the new backend. A backend started before this change ignores `farmId` and would list
every record on the farm page, so restart it.


## Spending by category in a crop cycle (2026-11-02)

Asked for: each record's category in the cycle's list, a category filter that shows how much the cycle spent on it, and
more polish.

- **Records panel** (crop cycle and farm pages):
  - A colourful **category bar** sits above the records. It has *All categories* with the total, and one card per category
    showing what was spent on it here, its share of the costs and a share bar.
  - Picking a category lists only its records and shows a summary: *Spent on Seeds*, the amount, the share and the
    number of records. Picking it again clears the filter.
  - Each record shows its **category tags**, in the same colours as the bar, with the amount of each when a record has
    several.
  - The figures come from the existing cost-by-category report for the cycle or farm, so they match the cycle report.
- **Crop cycle header** is now a hero card:
  - the stage badge;
  - chips for crop, farm and field, area, season and dates;
  - a back link.

  The profit card turns green or red.
- **Backend:**
  - Record summaries in lists carry `categories` (category id, code, name and amount of the record's lines; with an
    activity filter, only that activity's lines).
  - `GET /finance/documents` takes `categoryId`. With `costObjectId`, the same line must match both.
  - Another tenant's category answers 404 `CATEGORY_NOT_FOUND`.

  The report counts lines with no activity under overhead, while the records filter matches lines booked explicitly.
  The two agree for crop cycles, herds, ponds and batches.

| Command | Result |
|---|---|
| `backend: gradlew test --tests FarmRecordsTest` | Passed: **7 tests** (2 new: categories and filter, cross-tenant category) |
| `backend: gradlew test bootJar` | **836 of 837 passed.** Only `DashboardPerformanceTest` failed, at 1,474 ms on the busy machine; see the entry above for the no-regression comparison |
| `frontend: npm run lint`, `npx tsc --noEmit`, `npm run build` | Passed |
| `frontend: npm test` | Passed: **100 tests** (1 new: category bar, filter and tags) |
| `mobile: npm test` | Passed: **76 tests** |
| Browser review | Not done by me; the owner is checking the screens |

## Pop-up details, profit and loss by crop cycle (2026-11-02)

Asked for: the expenses filter on the same screen, cycle-wise profit and loss on the farm page, a pop-up with the
details behind any card (like BizManager and SmartBill360), and a more professional look.

- **Fixed: the category bar did not appear.** It asked the cost report for everything since 2000, and the reports refuse
  ranges over about ten years (`INVALID_DATE_RANGE`, 3,700 days). The error was swallowed and the unit test faked the
  report. The bar now asks for the last ten years, and the test checks the range sent.
- **Pop-up** (`components/InsightModal.tsx`), the same pattern as SmartBill360's InsightModal:
  - a coloured header in the card's tone, summary tiles, and a colourful list (searchable when long);
  - closes with Esc, the close button or a click outside, and focus returns to the card;
  - a bottom sheet on phones;
  - rows are real buttons or links inside list items.
- **Farm page:**
  - The *Income*, *Costs* and *Profit* cards open pop-ups: income or costs by category, or profit and loss per cycle.
  - A new **Profit & loss by crop cycle** card shows one coloured card per crop cycle, herd or pond on the farm, with
    income, costs, bars and profit (green or red). It comes from the activity report, kept to the farm's cost objects, and
    lists cycles with no money yet at zero. Clicking a cycle opens its income and costs by category, with *Open the cycle*.
  - Rows under *Where the money goes* open that category's records for the farm.
- **Crop cycle page:** the revenue, direct cost, overhead and profit cards open the cycle's breakdown pop-up.
- **Look:**
  - every plain link in the app is green with an animated underline (no more default blue);
  - crop rows have an icon;
  - the period buttons are a frosted segmented control;
  - cards show *Click for details* on hover.
- No backend change in this step.

| Command | Result |
|---|---|
| `frontend: npm run lint`, `npx tsc --noEmit`, `npm run build` | Passed |
| `frontend: npm test` | Passed: **101 tests** (1 new farm pop-up test; the panel test now checks the date range). One full run failed an unrelated registration test on vitest's 5-second timeout; it passed alone and the next full run passed, so the 5-second default is tight on this busy machine |
| `mobile: npm test` | Passed: **76 tests** |
| Backend | Unchanged since the previous entry (836 of 837; see the dashboard timing note there) |

## Simpler crop page: one place for money (2026-11-03)

The owner asked what the difference is between *Field work* and *Expenses & sales*, and to keep the system simple.
Field work is a diary of what was done and the stock used; Expenses & sales is the money. They overlapped: the work form
could also book paid labour or machine hire, so money could be entered in two places.

- The first tab is now **Work diary**. Its form records the work and any stock used, and no longer has the *"Labour or
  machine hire was paid"* option. A note explains that money goes under *Expenses & sales*, and its *Add an expense*
  button opens that tab, where new expenses are booked to the cycle.
- The line *"Recorded before the accounts (not in profit): activity costs …"* (pre-accounts memo costs) is no longer shown
  on the cycle page.
- No data changes. Existing paid-work bills stay, with their link on the diary entry. The API still accepts `paidWork`
  (for example from the phone app, which has no such form today); the web no longer offers it.
- Poultry, as asked: batches (bird count, purchase price and bill), the daily record (eggs, deaths, feed), egg and bird
  sales, cost per egg, and profit per batch are already built in the livestock module. They appear once *Poultry* is
  turned on in *Settings → Farming modules*.

| Command | Result |
|---|---|
| `frontend: npm run lint`, `npx tsc --noEmit`, `npm run build` | Passed |
| `frontend: npm test` | Passed: **102 tests**. The crop test now checks the diary has no money option and sends money to the Expenses tab (1 new). One earlier full run timed out five unrelated tests at just over vitest's 5 seconds while the machine was loaded; those files passed alone (29 tests) and the next full run passed |
| `mobile: npx tsc --noEmit`, `npm test` | Passed: **76 tests** |
| Backend | Unchanged |

## New crop cycle: say what is missing; animal farms go elsewhere (2026-11-03)

The owner tried to start a crop cycle for a poultry farm and got only *"This field is required."*

- The new-cycle form now names the missing fields (*"Please fill in: Field, Crop."*), and marks them red with
  `aria-invalid`.
- If the chosen farm has no fields, a note says so, with a link to add one on the farm page. If no crops are set up, a
  link goes to crop setup.
- Choosing a poultry, dairy, goat and sheep or fish farm shows a note: it is not a crop farm, and its birds or animals are
  tracked in their own section. The note links to that section (Livestock → Poultry, Livestock, Small stock, Fish farming),
  or to *Settings* to turn the module on when it is off.

| Command | Result |
|---|---|
| `frontend: npm run lint`, `npx tsc --noEmit`, `npm run build` | Passed |
| `frontend: npm test` | Passed: **104 tests** (2 new: missing fields named and marked; poultry farm pointed to its section) |
| `mobile: npm test` | Passed: **76 tests** |
| Backend | Unchanged |

## Delete a farm; work and expenses merged into one (2026-11-03)

Asked for: deleting a farm added by mistake, at any time, and one function for work and expenses, because two tabs were
ambiguous. This replaces the "Work diary" and "Expenses & sales" split of the previous entry.

- **Delete a farm:**
  - *Delete farm* on the farm page, and a bin button on each farm card, both after a question.
  - Fields have a remove button on the farm page.
  - The existing `DELETE /farms/{id}` and `DELETE /land-parcels/{id}` archive the record: it leaves every list, while
    money already recorded for it stays in the accounts and reports. No business data is erased, so the books stay
    correct. These endpoints existed but had no button.
- **One *Work & expenses* tab on the crop page:**
  - *Timeline:* work done and money records appear on one list, newest first. A work entry carries its own cost (amount,
    category tags and a link to its bill) instead of a second row for the bill. Sales and other expenses are their own
    rows. The colourful category bar still filters, and keeps work entries whose cost is in the chosen category.
  - *One entry form (Add entry):* the work, date, workers, hours, notes, stock used, and an optional **Cost** section that
    is always visible (amount, what it was for, paid from or owed to). With an amount, the entry is booked to the crop as
    an expense (`paidWork`); without one, it is a note of the work. *Record sale* sits next to it.
  - *Row actions:* work notes can be deleted; work that moved stock or money is reversed with them; other records keep
    edit, discard, correct and reverse.
  - People without finance access still see and add work entries, with no cost or stock sections.
- No backend change. The records panel takes `timeline` and `actions` and reads up to 100 records when merging.

| Command | Result |
|---|---|
| `frontend: npm run lint`, `npx tsc --noEmit`, `npm run build` | Passed |
| `frontend: npm test` | Passed: **107 tests**. New: work with its cost in one entry; an entry without a cost; the merged list; deleting a farm from the list; deleting a farm and removing a field from the farm page. The permissions test keeps its checks: no stock or cost section without those rights |
| `mobile: npm test` | Passed: **76 tests** |
| Backend | Unchanged |

## Poultry in the menu (2026-11-03)

The owner turned Poultry on and did not see it in the menu: it was a tab inside *Livestock*, which showed for any animal
module.

- The menu has its own **Poultry** item (bird icon) when the Poultry module is on. It opens the batches
  (`livestock/poultry`).
- **Livestock** now shows for dairy, goats or sheep only. With both on, both items show, and Livestock still holds the
  poultry tab. On poultry pages, Poultry is the highlighted item.
- New string `nav.poultry` (English and Tamil).

| Command | Result |
|---|---|
| `frontend: npm run lint`, `npx tsc --noEmit`, `npm run build` | Passed |
| `frontend: npm test` | Passed: **108 tests** (1 new: poultry entry and when Livestock shows) |
| `mobile: npm test` | Passed: **76 tests** |

## Cows, poultry, goats and sheep as separate sections (2026-11-03)

The owner asked for each kind of animal to be its own simple section, like fish farming: no poultry tab among the cows,
and no herd or milk tabs in poultry.

- **Menu:** one item per section, each shown only with its module: *Cows & dairy* (`DAIRY`), *Poultry* (`POULTRY`) and
  *Goats & sheep* (`GOAT_SHEEP`). On a poultry or goat page, that section's item is the highlighted one.
- **Tabs per section** (`LivestockLayout`, by path):
  - *Cows & dairy:* Herd, Milk, Breeding and health, Groups, Reports.
  - *Poultry:* Batches and Reports.
  - *Goats & sheep:* Herds and Reports.
- **Pages stay in their section:** batch and herd pages are now at `livestock/poultry/{id}` and
  `livestock/small-stock/{id}`. The old `livestock/flocks/{id}` still works, and lists and reports link to the new paths.
- **Reports per section:**
  - `livestock/poultry/reports` lists poultry batches only, and `livestock/small-stock/reports` goat and sheep herds only.
  - The cows' reports show milk and herd profitability without the poultry and goat groups. The herd rows carry no
    species, so the batch report's group ids are used to leave those groups out.
- No backend change.

| Command | Result |
|---|---|
| `frontend: npm run lint`, `npx tsc --noEmit`, `npm run build` | Passed |
| `frontend: npm test` | Passed: **109 tests**. Updated: batch links; the reports test now checks a section shows only its own animals; the poultry tabs test now checks Batches and Reports and no cattle tabs. New: the cows section shows no poultry or goat tabs; a menu item per section |
| `mobile: npm test` | Passed: **76 tests** |

## Known gaps and defects

Security and platform:
- **Fixed in Phase 14:** two open tabs signing each other out (the 30-second refresh grace), rate limits in memory (now
  in PostgreSQL), Row-Level Security (ADR 0011), and account deletion and tenant export (O9–O11).
- **Fixed in Phase 15:** `deploy/backup.sh` would have failed every night under the forced RLS policies, because
  `pg_dump` refuses such tables unless it is given `--enable-row-security`. It was found while backing up the local
  database; with no tenant scope set the policy shows every row, so the dump is complete.
- **Fixed in staging readiness:** in production the root address answered 404, metrics on the management port
  answered 401, and staging could not start with Razorpay test keys (found by running the jar in production mode).
- **Billing has not met the real gateway:** Razorpay is called only through its documented API; the end-to-end run in
  test mode needs staging and the account.
- **No email provider chosen yet:** the SMTP sender is built, but until `SPRING_MAIL_HOST` is set emails are only logged
  (production refuses to start without it).
- **Nothing is hosted yet:** the image, Compose set-up, backups, restore test, alerts and load tests are written but not run
  on a real host (Phase 14, not verified).
- Export files are on the server's disk; a second instance needs shared storage first (ADR 0009). The web app loads Google
  Fonts (allowed by the CSP); self-hosting them would keep visitors' addresses from Google.
- Mobile has no registration or password reset, and no account deletion (use the web).
- Tamil strings are unreviewed (`shared/i18n/ta.json` → `_meta.reviewStatus`).

Finance and stock:
- No purchase orders (E8, deferred); XLSX/PDF exports arrive with Phase 7 (CSV only now).
- An item's default categories can be changed but not cleared; negative stock cannot be allowed per tenant yet.
- Phase 1 crop activity `cost` and harvest `sale_value` stay memo history outside the accounts (D7, F4).
- Crop photos wait for attachment storage (ADR 0009); weather comes only from farmer entries.
- The web bundle is over 500 kB; split the bundle by section before production.
- Bills assigned to a finished organic batch are not refused (finance does not know about batches).
- The workforce labour report still shows labour cost to anyone with WORKFORCE_READ, unlike the new wages report.
- Animal depreciation goes to the group the animal is in when the run is posted, not the group of that month (post runs monthly).
- The daily batch record offers every stock input measured by weight as feed, not only items in the feed category.
- Harvest charges are a separate bill; reversing a harvest sale does not reverse its charges bill.
- Overhead is not shared out to herds, batches or pond crops (I12, J15, K15).
- Excel exports group thousands as #,##0.00, not Indian lakh grouping (an Excel format limit).
- Payroll has no PF, ESI, TDS or minimum-wage rules (G6). There is no WDV depreciation and no machine-hour allocation to
  crops (G9, G12). Breeding animals are not depreciated until the livestock phases (G10). Recurring expenses are still
  open.
- Removing a match after a later report date is not reflected historically in dues ageing (matches have no history).

Clients:
- Phone lists loaded from the server do not yet show the phone's waiting records inline (the status line and the Sync
  screen do).
- On the phone, a record that needs attention can be corrected only in its amounts, dates and notes. Records with the
  wrong crop, item or kind must be discarded and recorded again.
- The phone's offline mode has not been tried on a real device.

Tooling (left unchanged by policy — user scripts):
- `dev.bat` uses port 5173 (Vite runs on 5174) and sets `SMART_BILLING_LOG_DIR` while logback reads `SMARTFARM_LOG_DIR`.
- `copy ai to org.bat` overwrites source folders without backup; `copy org to back.bat` mirror-deletes in `smartfarmBack`.
- Changeset `026` rollback block is broken (ADR 0006); recovery is forward-fix.
- Test PostgreSQL is 17, local/production target is 18.
- Mobile `npm audit`: transitive findings in Expo/React Native tooling (see Phase 1).
