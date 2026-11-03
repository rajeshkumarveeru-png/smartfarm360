# SmartFarm360

Multi-tenant integrated farming management for farmers in India — crops, dairy, poultry, goat/sheep,
aquaculture, organic manure, labour, equipment, processing and trading. Tamil and English.

> **Status: early development (Phase 3 of 14 complete).** Not production-ready. See
> [docs/PROGRESS.md](docs/PROGRESS.md) for what is implemented, verified and still missing.

## Repository layout

| Path | Contents |
|---|---|
| `backend/` | Java 21, Spring Boot 3.5, Gradle, PostgreSQL, Liquibase |
| `frontend/` | Web app: React 18, Vite 6, TypeScript |
| `mobile/` | Expo SDK 54 / React Native 0.81 app (currently local-only, not connected to the API) |
| `electron/` + root `package.json` | Windows desktop wrapper that starts the backend JAR and serves the web build |
| `config/` | External `application.properties` and `logback-spring.xml` used by `dev.bat` and the desktop app |
| `docs/` | Architecture decisions (`adr/`), progress log, backlog |

## Prerequisites

- **JDK 21.** `JAVA_HOME` must point to JDK 21 for Gradle (JDK 8 will not work).
- **Node.js 22** and npm.
- **PostgreSQL** 16+ for running the app locally (tests do not need it — see below).

## Database (local development)

As a PostgreSQL superuser:

```sql
CREATE ROLE smartfarm360 WITH LOGIN PASSWORD 'choose-a-local-password';
CREATE DATABASE smartfarm360 OWNER smartfarm360;
```

Liquibase creates the schema on backend start-up. Copy `.env.example` values into your environment
(the backend reads `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`).

## Run locally

Backend (http://localhost:8080):

```bash
cd backend
./gradlew bootRun
```

Web (http://localhost:5174, proxies `/api` to the backend):

```bash
cd frontend
npm ci
npm run dev
```

Mobile:

```bash
cd mobile
npm ci
npx expo start
```

For a physical phone set `EXPO_PUBLIC_API_URL` to your computer's LAN address (see `mobile/.env.example`).

Create an account at http://localhost:5174/register, then create your farm business. Optional settings (JWT secret,
dev-only email links, platform administrator bootstrap) are listed in `.env.example`. Email delivery is not
configured yet: set `APP_MAIL_DEV_LOG_LINKS=true` locally to see password-reset and invitation links in the log.

> If another app already uses port 8080, start the backend with `PORT=8081` and Vite with
> `SMARTFARM_API_TARGET=http://localhost:8081`.

API documentation: http://localhost:8080/swagger-ui.html (disable with `APP_API_DOCS_ENABLED=false`).

## Tests and quality gates

```bash
cd backend && ./gradlew test bootJar      # unit + migration tests; starts an embedded PostgreSQL
cd frontend && npm run build              # type check + production build
cd frontend && npm run lint && npm test     # lint + unit/component tests
cd mobile && npx tsc --noEmit             # type check
cd mobile && npm test                     # Jest (jest-expo)
cd mobile && npx expo install --check     # Expo SDK dependency alignment
```

Integration tests use [embedded PostgreSQL](https://github.com/zonkyio/embedded-postgres) (real
PostgreSQL 17 binaries, no Docker). CI runs the same commands: [.github/workflows/ci.yml](.github/workflows/ci.yml).

## Desktop build (Windows)

```bash
npm install
npm run dist
```

Produces `release/SmartFarm360-<version>-Portable.exe`. The desktop app still needs a reachable PostgreSQL
configured in `config/application.properties`.

## Finance

Money is recorded as farmer-friendly records (bills, sales, payments, loans…) that post to an append-only journal.
The rules, decisions and a worked example are in [docs/finance/posting-rules.md](docs/finance/posting-rules.md);
`GoldenScenarioTest` checks the example's figures.

## Database migrations

All schema changes are forward-only Liquibase changesets in
`backend/src/main/resources/db/changelog/`. Never edit an applied changeset — see
[ADR 0006](docs/adr/0006-database-migration-policy.md).

## Documentation

- [Progress log and known gaps](docs/PROGRESS.md)
- [Implementation backlog](docs/BACKLOG.md)
- [Architecture decision records](docs/adr/)
- [Contributor and agent guide](CLAUDE.md)
