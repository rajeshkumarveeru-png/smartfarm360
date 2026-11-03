# SmartFarm360 — contributor and agent guide

Read [docs/PROGRESS.md](docs/PROGRESS.md) first: it records the current phase, what is verified, and open decisions.
Architecture decisions live in [docs/adr/](docs/adr/). Follow them; if a change needs to deviate, write a new ADR.

## Hard rules

- **Never edit an applied Liquibase changeset.** `001`–`003` (ids `025`–`027`) are applied to real databases.
  Add a new numbered file and include it from `db.changelog-master.yaml`. Never drop or rewrite business data.
- **Tenant isolation is mandatory.** Every tenant-owned table has `tenant_id`, a `(tenant_id, id)` unique key,
  and composite foreign keys to other tenant-owned tables. Never trust a client tenant id without a membership
  check. Every new tenant-scoped endpoint needs a cross-tenant negative test.
- **Money is `NUMERIC` / `BigDecimal`.** Never `double`/`float` for amounts or quantities.
- **Stock never changes without a movement record** (from Phase 4; see ADR 0005).
- **Never weaken or delete a test to make a build pass.** Never report a check as passing unless it ran.
- **No secrets in the repo.** Use environment variables; `.env` is git-ignored.
- **SmartBill360 is a separate repository** (`D:\source_code\smartbill360`). Do not modify sibling repositories.
- Do not run `copy ai to org.bat` or `copy org to back.bat`: they overwrite or mirror-delete source trees.

## Build and test

`JAVA_HOME` must point to JDK 21 (on this machine: `%USERPROFILE%\.jdks\ms-21.0.12`; the system JDK is 8).

| Area | Command (from the directory) |
|---|---|
| backend | `./gradlew test bootJar` — includes migration tests on embedded PostgreSQL |
| frontend | `npm run lint`, `npm test`, `npm run build` |
| mobile | `npx tsc --noEmit`, `npm test`, `npx expo install --check` |

Port 8080 may be taken locally by another project (BIZ360); use `PORT=8081` and `SMARTFARM_API_TARGET=http://localhost:8081`.

Record every command you ran and its result in `docs/PROGRESS.md` at the end of a phase.

## Conventions

- Backend package root `com.smartfarm360`; tables use the `sf_` prefix.
- Modular monolith, one package per module (ADR 0001): `platform` (auth, tenant, membership, rbac, security, audit,
  web, admin, mail, billing), `farm`, `crop`, `finance`, `inventory` (stock), `costing`, `operations` (field work that moves stock and money),
  `workforce` (workers, attendance, pay runs), `assets` (register, depreciation, maintenance), `livestock` (herd, milk,
  health, breeding, poultry batches, goat and sheep herds; only reporting depends on it), `aquaculture` (ponds, culture
  cycles, water quality, harvests; only reporting depends on it), `organic` (compost and manure batches; only reporting
  depends on it; internal transfers of own produce live in `inventory`), `processing` (processing batches and the lot
  trace; only reporting depends on it; the trading margin report lives in `inventory`) and `reporting`
  (the dashboard: reads other modules, nothing depends on it). Report exports go through `platform.export`.
  Finance reaches stock only through
  `StockHook`; `ModuleBoundaryTest` enforces the boundaries.
- Tenant endpoints live under `/api/v1/tenants/{tenantId}/...`, take a `TenantContext` parameter and must declare
  `@RequiresPermission` (and `@RequiresModule` for optional modules). Never accept a tenant id in a request body.
- Endpoints the phone uses offline are marked `@Idempotent` and get a replay test (ADR 0010). Records that replace a
  day return a `version` and refuse a stale `baseVersion` with `STALE_RECORD` (`Versions`). Phone forms save
  through the outbox (`mobile/src/offline/hub.ts` `record()`), never with `apiRequest` directly.
- Every table with a `tenant_id` gets the forced `sf_tenant_isolation` Row-Level Security policy in its own migration
  (ADR 0011; `RowLevelSecurityCoverageTest`). Tests run as an ordinary role, so RLS applies to them. Code outside a
  tenant request runs in the "all" scope and must filter by tenant itself.
- The platform console (`/api/v1/platform`) shows counts and status, never books; every change goes to `PlatformAudit`.
  Plan limits go through `PlanLimits`. The `prod` profile refuses unsafe settings (`ProductionSettings`); new
  production settings get a rule there. Operations runbooks are in `docs/ops/`.
- Billing (ADRs 0014–0016): plans change only through signed gateway webhooks (`WebhookService`), never from the browser.
  A farm business's `plan_code` is the subscription's effective plan; billing never hides or locks records, it only lowers
  limits. Invoices and credit notes are immutable, gapless per financial year, and use `customer_tenant_id` so they outlive
  a deleted business. Never store card, UPI or bank details. Money for invoices goes through `Gst`.
- Errors: throw `ApiException` with a stable `code`, and add that code to `errors.*` in both catalogs.
- Every tenant-data change that matters to owners is recorded with `AuditService` (no secrets in details).
- Controllers stay thin; business rules live in services; typed request/response records with Bean Validation.
  Do not add new `Map<String,Object>` request bodies.
- UTC `TIMESTAMP WITH TIME ZONE` for instants; `DATE` for farm-local dates.
- UI text goes through `shared/i18n/{en,ta}.json` (used by web and mobile); keys must exist in both files
  (a test enforces it). No hard-coded user-facing strings.
- Git: work on a feature branch; commits end with the attribution line required by the session.
