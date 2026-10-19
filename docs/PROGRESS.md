# SmartFarm360 progress log

## Current state

- **Phase:** 1 (baseline and hygiene) complete on branch `feature/phase-1-foundation`. Next: Phase 2 (platform foundation).
- **Production readiness:** not ready. Auth is HTTP Basic, a default `admin`/`admin123` account is created unless
  disabled, there is no permission model, and stock/finance totals can double count (see Known gaps).

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
| **Email provider for password reset** | **Open** — needed in Phase 2 | ADR 0003 |

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

## Known gaps and defects (carried into later phases)

Security (Phase 2):
- HTTP Basic auth with the encoded password in `sessionStorage`; no token refresh, logout, lockout, rate limiting or password reset.
- Bootstrap admin `admin`/`admin123` created by default (`APP_BOOTSTRAP_ADMIN_ENABLED` defaults to true).
- Roles are free-text strings; write permission is a hard-coded list repeated in three services; no platform admin.
- No global exception handler; the web UI shows raw response bodies. `/actuator/health` is permitted but Actuator is not installed.
- Registration reveals whether an email exists (409).

Finance and inventory (Phases 3–5):
- Stock quantities change without movement records; crop activities do not consume stock.
- Purchases are not expenses; activity costs and harvest `sale_value` are separate money stores → double counting risk.
- `PARTIAL` payment status has no paid amount; "net cash" ignores purchase payments; summary is all-time only.
- Average unit cost rounded to 2 dp; units are free text.
- No pagination (lists capped at 500 or unbounded), no idempotency, no update/delete, no audit trail.

Clients:
- Web: no router; translations inline and duplicated; hard-coded English strings; no tests or linter.
- Mobile: not connected to the backend; local entries use `Date.now()` ids and JS floats; "coming soon" screens; no tests.

Tooling (left unchanged by policy — user scripts):
- `dev.bat` uses port 5173 (Vite runs on 5174) and sets `SMART_BILLING_LOG_DIR` while logback reads `SMARTFARM_LOG_DIR`.
- `copy ai to org.bat` overwrites source folders without backup; `copy org to back.bat` mirror-deletes in `smartfarmBack`
  and mislabels its third step.
- Changeset `026` rollback block is broken (ADR 0006); recovery is forward-fix.
- Test PostgreSQL is 17, local/production target is 18.
