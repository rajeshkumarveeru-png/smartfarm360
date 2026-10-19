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
| frontend | `npm run build` |
| mobile | `npx tsc --noEmit` and `npx expo install --check` |

Record every command you ran and its result in `docs/PROGRESS.md` at the end of a phase.

## Conventions

- Backend package root `com.smartfarm360`; tables use the `sf_` prefix.
- Target structure is a modular monolith, one package per module (ADR 0001). Phase 1 code still uses the
  older `controller/service/entity` layout; new modules follow the ADR.
- Controllers stay thin; business rules live in services; typed request/response records with Bean Validation.
  Do not add new `Map<String,Object>` request bodies.
- UTC `TIMESTAMP WITH TIME ZONE` for instants; `DATE` for farm-local dates.
- UI text goes through the localization catalog (English and Tamil); no hard-coded user-facing strings in new code.
- Git: work on a feature branch; commits end with the attribution line required by the session.
