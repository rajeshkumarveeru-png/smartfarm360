# SmartFarm360 progress log

## Current state

- **Phase:** 6 (workforce and assets) complete on branch `feature/phase-6-workforce` (based on `feature/phase-5-crops`).
  Phase 7 (dashboard and core reports) started on `feature/phase-7-reports`: the
  [dashboard and reports design](reports/dashboard-and-reports.md) is awaiting review; no Phase 7 code yet. Nothing is
  pushed yet.
- **Production readiness:** not ready. Authentication, roles, tenant isolation, audit, the finance core and stock with
  movements are in place and tested, but no email provider is configured and hosting/backups are undecided.

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
| **Hosting / deployment target** | **Open** — needed before Phase 14 (and before choosing S3/email providers) | — |
| **Desktop (Electron) scope** | **Open** — kept working as is; needs a decision because it requires a local PostgreSQL | — |
| **Tamil terminology reviewer** | **Open** — required before the first release | ADR 0008 |
| Finance posting rules, D1–D12 | Approved as recommended (2026-10-20) | [posting rules](finance/posting-rules.md) |
| Stock movement design, E1–E12 | Approved as recommended (2026-10-20) | [stock movements](inventory/stock-movements.md) |
| Crop management design, F1–F12 | Approved as recommended (2026-10-20) | [crop management](crops/crop-management.md) |
| **Workforce and assets design, G1–G15** | **Awaiting review** (draft 2026-10-20) | [workforce and assets](workforce/workforce-and-assets.md) |
| **Stock inputs by field supervisors** | **Open** — today only roles with `INVENTORY_WRITE` (owner, farm admin, farm manager, accountant) can record stock used on a crop; field supervisors and workers record notes-only work and harvests. Should field supervisors get stock-use permission? | crop design §15 |
| **Email provider** | **Open** — Phase 2 ships the `EmailSender` interface with a logging stub, as instructed. Reset and invitation emails are not delivered until a provider is chosen | ADR 0003 |

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

## Known gaps and defects

Security and platform:
- **No email provider:** reset and invitation emails are not delivered (invitation links can be shared by hand;
  password reset needs a provider before production).
- Rate limits are in memory per instance; a shared store is needed before running more than one instance.
- Row-Level Security not yet added (ADR 0002, planned after the finance core).
- No account deletion or tenant data export yet (Phase 14). Mobile has no registration or password reset (use web).
- Tamil strings are unreviewed (`shared/i18n/ta.json` → `_meta.reviewStatus`).

Finance and stock:
- No purchase orders (E8, deferred); XLSX/PDF exports arrive with Phase 7 (CSV only now).
- An item's default categories can be changed but not cleared; negative stock cannot be allowed per tenant yet.
- Phase 1 crop activity `cost` and harvest `sale_value` stay memo history outside the accounts (D7, F4).
- Crop photos wait for attachment storage (ADR 0009); weather comes only from farmer entries.
- The web bundle is over 500 kB; split the bundle by section before production.
- No internal transfers of own produce or manure yet (P17, Phases 5 → 11); monthly depreciation runs arrived in Phase 6.
- Finance report exports arrive in Phase 7.
- Payroll has no PF, ESI, TDS or minimum-wage rules (G6). There is no WDV depreciation and no machine-hour allocation to
  crops (G9, G12). Breeding animals are not depreciated until the livestock phases (G10). Recurring expenses are still
  open.
- Removing a match after a later report date is not reflected historically in dues ageing (matches have no history).

Clients:
- Mobile money entries wait on the phone when offline; crop recording and attendance are online only (offline in Phase 13). The
  livestock tab is still a placeholder until the livestock modules (Phases 8–12).

Tooling (left unchanged by policy — user scripts):
- `dev.bat` uses port 5173 (Vite runs on 5174) and sets `SMART_BILLING_LOG_DIR` while logback reads `SMARTFARM_LOG_DIR`.
- `copy ai to org.bat` overwrites source folders without backup; `copy org to back.bat` mirror-deletes in `smartfarmBack`.
- Changeset `026` rollback block is broken (ADR 0006); recovery is forward-fix.
- Test PostgreSQL is 17, local/production target is 18.
- Mobile `npm audit`: transitive findings in Expo/React Native tooling (see Phase 1).
