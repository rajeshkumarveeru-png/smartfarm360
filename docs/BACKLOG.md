# SmartFarm360 implementation backlog

Each phase is a vertical slice (migration → domain → service → API → web/mobile UI → tests → docs) and must leave
the build green. A phase is complete only when its acceptance checks have actually been run and recorded in
[PROGRESS.md](PROGRESS.md).

## Phase 1 — Baseline and hygiene ✅ (see PROGRESS.md)

## Phase 2 — Platform foundation ✅ (see PROGRESS.md)

Deferred from Phase 2 (not blocking Phase 3):
- Email provider behind `EmailSender` (decision pending), then email verification.
- Row-Level Security with a separate non-owner database role.
- Shared rate-limit store before running several backend instances.
- Drop the unused `sf_tenant_memberships.role` column (contract step).
- Mobile registration and password reset screens.

## Phase 3 — Finance core ✅ (see PROGRESS.md)

Deferred from Phase 3:
- Overhead allocation runs (P16) → Phase 5; internal transfers of produce/manure (P17) → Phases 5 and 11.
- Asset register with depreciation schedules (manual depreciation records exist) → Phase 6.
- Recurring expenses, worker advances and wages → Phase 6.
- CSV/XLSX/PDF exports of reports → Phase 7.
- GST (D12) → with processing and trading.

## Phase 4 — Inventory, purchasing and sales

- Units catalog, items, lots/expiry, locations, append-only movements, on-hand projection (ADR 0005).
- Multi-line purchases and sales, customers, returns, wastage, adjustments, transfers, low-stock alerts, exports.
- Migrate `sf_inventory_items` quantities to opening balances and `sf_purchases` into purchase documents.
- **Accept when:** on-hand always equals sum of movements under concurrent writes; no duplicate movements on retry.

## Phase 5 — Crop management v2

- Cost objects; activities consume inventory; harvest batches become inventory lots; sales link to harvests;
  pest/disease observations; weather notes; cost per acre / per unit; crop profitability.
- Retire `sf_harvest_records.sale_value` from revenue calculations.

## Phase 6 — Workforce and assets

- Workers, attendance, work logs, wages, advances and settlements, labour cost allocation.
- Equipment registry, fuel, repairs, maintenance, useful life, depreciation postings.

## Phase 7 — Dashboard and core reports

- Income vs expenses, cash/bank balances, receivables/payables, monthly P&L, category breakdown, activity
  profitability, inventory valuation, wages, maintenance. CSV/XLSX/PDF exports with tenant checks.
- Every report states date range, inclusion rules and actual vs estimated.

## Phases 8–12 — Farming modules

8. Livestock core + dairy (animals, milk records and quality, feed, health, breeding, calving, milk sales, cost per litre)
9. Poultry batches and eggs; goat/sheep herds
10. Aquaculture (ponds, stocking batches, water quality, harvest and grading)
11. Organic resources (compost/vermicompost batches, internal transfers without revenue)
12. Processing and trading (grading, drying, packaging, yields, traceability from harvest to finished goods)

## Phase 13 — Mobile offline

- Local persistence (expo-sqlite), UUIDv7 client ids, idempotent sync, retries, explicit conflict resolution,
  protected local data.

## Phase 14 — SaaS operations and production readiness

- Platform admin console, tenant status, usage limits, support tools, tenant export and deletion, privacy/consent.
- Hosting and deployment, backups with a tested restore, monitoring, incident and release checklists, load tests.
