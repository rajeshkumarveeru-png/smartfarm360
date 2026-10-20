# SmartFarm360 progress log

## Current state

- **Phase:** 2 (platform foundation) complete on branch `feature/phase-2-platform` (based on `feature/phase-1-foundation`).
  Phase 3 (finance core) started on `feature/phase-3-finance`: [posting rules](finance/posting-rules.md) are awaiting
  product-owner review; no finance code has been written yet.
- **Production readiness:** not ready. Authentication, roles, tenant isolation and audit are in place and tested, but no
  email provider is configured, hosting/backups are undecided, and the Phase 1 finance and stock records still have the
  accounting gaps listed under Known gaps (fixed in Phases 3–5).

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

## Known gaps and defects

Security and platform:
- **No email provider:** reset and invitation emails are not delivered (invitation links can be shared by hand;
  password reset needs a provider before production).
- Rate limits are in memory per instance; a shared store is needed before running more than one instance.
- Row-Level Security not yet added (ADR 0002, planned after the finance core).
- No account deletion or tenant data export yet (Phase 14). Mobile has no registration or password reset (use web).
- Tamil strings are unreviewed (`shared/i18n/ta.json` → `_meta.reviewStatus`).

Finance and inventory (Phases 3–5, unchanged Phase 1 behaviour):
- Stock quantities change without movement records; crop activities do not consume stock.
- Purchases are not expenses; activity costs and harvest `sale_value` are separate money stores → double counting risk.
- `PARTIAL` payment status has no paid amount; "net cash" ignores purchase payments; summary is all-time only.
- Average unit cost rounded to 2 dp; units are free text; crop/finance responses are untyped maps without paging.

Clients:
- Mobile entries are still device-only (sync is Phase 13); "coming soon" crop/livestock screens remain.

Tooling (left unchanged by policy — user scripts):
- `dev.bat` uses port 5173 (Vite runs on 5174) and sets `SMART_BILLING_LOG_DIR` while logback reads `SMARTFARM_LOG_DIR`.
- `copy ai to org.bat` overwrites source folders without backup; `copy org to back.bat` mirror-deletes in `smartfarmBack`.
- Changeset `026` rollback block is broken (ADR 0006); recovery is forward-fix.
- Test PostgreSQL is 17, local/production target is 18.
- Mobile `npm audit`: transitive findings in Expo/React Native tooling (see Phase 1).
