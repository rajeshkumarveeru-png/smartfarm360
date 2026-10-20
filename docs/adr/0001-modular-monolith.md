# ADR 0001 — Modular monolith

- Status: Accepted (2026-10-19)

## Context

SmartFarm360 needs many domains (finance, inventory, crops, livestock, aquaculture, processing, reporting)
sharing users, tenants, money and stock. The team is small, there is no production traffic yet, and the
existing code is a single Spring Boot application.

## Decision

Keep one Spring Boot application and one PostgreSQL database, split into modules by package:

| Package | Responsibility |
|---|---|
| `platform` | identity, tenants, memberships, roles/permissions, platform admin, settings, enabled modules and entitlements, audit, attachments, notifications |
| `finance` | financial accounts, expense/income documents, payments and allocations, journal, loans |
| `trading` | customers, suppliers, purchases, sales, returns |
| `inventory` | items, units, lots, locations, movements, valuation |
| `workforce` | workers, attendance, wages, advances, labour allocation |
| `assets` | equipment, maintenance, fuel, depreciation |
| `crop` | fields, seasons, crop cycles, activities, harvests |
| `livestock` | animal core, dairy, goat/sheep |
| `poultry`, `aquaculture`, `organic`, `processing` | farming modules |
| `reporting` | dashboards, reports, exports |

Rules:

- A module exposes a service API (`*.api` package); other modules call that, never another module's repositories or entities.
- Farming modules record money and stock **only** through `finance` and `inventory` service APIs.
- Module boundaries will be enforced with ArchUnit tests once the first module is moved (Phase 2).

## Addendum (2026-10-20, Phase 5)

Field work that moves stock and money (an activity's stock inputs and paid work, a harvest into stock) is recorded by a
small **operations** module that depends on crop, inventory and finance. Crop stays free of finance and stock, finance
of farming and stock, and nothing depends on operations. Finance reaches stock through the `StockHook` interface it
owns (Phase 4). `ModuleBoundaryTest` enforces all of this.

## Consequences

- One deployable, one transaction boundary for money + stock changes, simple local development.
- No message broker, cache cluster or microservices until a measured need exists.
- Existing `controller/service/entity/repository` packages are migrated module by module, not in one rewrite.
