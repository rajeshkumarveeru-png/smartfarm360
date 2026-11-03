# ADR 0007 — Testing strategy

- Status: Accepted (2026-10-19)

## Context

Docker is not installed on the development machine, and the local application database role cannot create
databases or schemas, so neither Testcontainers nor a disposable schema in the dev database is available.

## Decision

- Backend integration and migration tests use **embedded PostgreSQL** (`io.zonky.test:embedded-postgres`, real
  PostgreSQL 17 binaries downloaded through Maven). Works on Windows, macOS and Linux CI without Docker.
  `EmbeddedPostgresSupport` is the base class.
- Gap: production/local PostgreSQL is 18; the test binaries are 17. Re-check when 18 binaries are published.
- Test pyramid: unit tests for domain rules and calculations; integration tests for repositories, services, security
  and migrations against PostgreSQL; API tests through MockMvc with real security.
- Mandatory suites: cross-tenant negative tests for every tenant endpoint, financial and inventory consistency tests,
  duplicate/idempotency tests, transaction rollback tests.
- Web: Vitest + Testing Library + ESLint. Mobile: Jest with `jest-expo` (logic modules; no component rendering yet).
- CI (`.github/workflows/ci.yml`) runs backend tests and JAR build, web build, mobile type check and Expo dependency check.
