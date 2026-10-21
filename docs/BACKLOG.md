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
- Asset register with depreciation schedules; worker advances and wages → done in Phase 6.
- Recurring expenses → still open (not in the Phase 6 design); candidate for Phase 7.
- CSV/XLSX/PDF exports of reports → Phase 7.
- GST (D12) → with processing and trading.

## Phase 4 — Inventory, purchasing and sales ✅ (see PROGRESS.md)

Deferred from Phase 4:
- Purchase orders (E8) → when traders need them.
- Tenant setting to allow negative stock; clearing an item's default categories.
- XLSX/PDF stock exports → Phase 7 (CSV exists).
- Stock records from the phone (the mobile app shows stock; recording is web-only) → Phase 13.
- Harvests as stock lots and activities consuming stock → done in Phase 5.

## Phase 5 — Crop management v2 ✅ (see PROGRESS.md)

Deferred from Phase 5:
- Crop photos (attachment storage, ADR 0009); weather service integration.
- Other allocation bases (direct cost share, fixed percentages).
- Offline crop recording on the phone → Phase 13.
- Internal transfers of own produce/manure (P17) → Phase 11.
- Decide whether field supervisors may record stock inputs (open question in PROGRESS).

## Phase 6 — Workforce and assets (done 2026-10-20)

Approved design: [workforce and assets](workforce/workforce-and-assets.md) (G1–G15). Left for later:

- PF, ESI, TDS and minimum-wage rules (G6) → before larger farms use payroll.
- WDV depreciation and machine-hour cost allocation to crops (G9, G12).
- Depreciation of breeding animals → livestock phases (G10).
- Offline attendance on the phone → Phase 13.

## Phase 7 — Dashboard and core reports (done 2026-10-21)

Approved design: [dashboard and reports](reports/dashboard-and-reports.md) (H1–H14). Left for later:

- Emailed and scheduled reports (needs a server PDF and an email provider) → after Phase 14.
- Hide labour cost in the workforce labour report from readers without pay access, as the wages report does.
- Recurring expenses (H14) → Phase 14 or a small follow-up.

## Phases 8–12 — Farming modules

8. Livestock core + dairy — done 2026-10-21 ([livestock and dairy](livestock/livestock-and-dairy.md), I1–I16). Left for later: overhead allocation to herds by animal-days (I12); depreciation by the group of each month; offline livestock on the phone (Phase 13)
9. Poultry batches and eggs; goat/sheep herds — done 2026-10-21 ([poultry, goats and sheep](livestock/poultry-goats-sheep.md), J1–J15). Left for later: overhead allocation to batches and herds (J15); a weekly feed entry that splits across days; offline batch records on the phone (Phase 13)
10. Aquaculture — done 2026-10-21 ([aquaculture](aquaculture/aquaculture.md), K1–K15). Left for later: overhead allocation to pond cycles (K15); fish into stock when processing and cold storage arrive (Phase 12); reversing harvest charges with the sale; offline pond records on the phone (Phase 13)
11. Organic resources — done 2026-10-21 ([organic resources](organic/organic-resources.md), L1–L15). Left for later: certification records (L10); overhead allocation to organic batches (L15); refusing bills to finished batches; offline compost records on the phone (Phase 13)
12. Processing and trading (grading, drying, packaging, yields, traceability from harvest to finished goods) — design for review: [processing and trading](processing/processing-and-trading.md) (M1–M15)

## Phase 13 — Mobile offline

- Local persistence (expo-sqlite), UUIDv7 client ids, idempotent sync, retries, explicit conflict resolution,
  protected local data.

## Phase 14 — SaaS operations and production readiness

- Platform admin console, tenant status, usage limits, support tools, tenant export and deletion, privacy/consent.
- Hosting and deployment, backups with a tested restore, monitoring, incident and release checklists, load tests.
