# SmartFarm360

Multi-tenant integrated farming management for farmers in India — crops, dairy, poultry, goat/sheep,
aquaculture, organic manure, labour, equipment, processing and trading. Tamil and English.

> **Status: early development (Phase 1 of 14).** Not production-ready. See
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

> **Local bootstrap admin.** Until Phase 2, the backend creates an `admin` / `admin123` account unless
> `APP_BOOTSTRAP_ADMIN_ENABLED=false`. Never deploy with this enabled.

## Tests and quality gates

```bash
cd backend && ./gradlew test bootJar      # unit + migration tests; starts an embedded PostgreSQL
cd frontend && npm run build              # type check + production build
cd mobile && npx tsc --noEmit             # type check
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

## Database migrations

All schema changes are forward-only Liquibase changesets in
`backend/src/main/resources/db/changelog/`. Never edit an applied changeset — see
[ADR 0006](docs/adr/0006-database-migration-policy.md).

## Documentation

- [Progress log and known gaps](docs/PROGRESS.md)
- [Implementation backlog](docs/BACKLOG.md)
- [Architecture decision records](docs/adr/)
- [Contributor and agent guide](CLAUDE.md)
