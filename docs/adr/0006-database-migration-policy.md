# ADR 0006 — Database migration policy

- Status: Accepted (2026-10-19)

## Decision

- Liquibase is the only way the schema changes. Hibernate runs with `ddl-auto=validate`.
- **Applied changesets are frozen.** Files `001`–`003` (changeset ids `025-smartfarm360-foundation`,
  `026-smartfarm360-crop-operations`, `027-smartfarm360-finance-procurement`) were applied to the local database on
  2026-10-15 and must never be edited. The id numbering (starting at 025) is inherited from the template project and
  kept as is.
- New files are numbered `004-<module>-<change>.yaml` onward; changeset ids follow the next number (`028-...`).
- Changes are forward-only and backward-compatible across one release (expand → migrate → contract).
- Data migrations verify row counts and never delete business data.
- Every changeset is tested on an empty database and on a database populated with representative data
  (`LiquibaseMigrationIntegrationTest`, embedded PostgreSQL).
- Known defect: the rollback block of changeset `026` drops `uq_sf_farm_tenant_id` before the foreign keys that
  depend on it, so `liquibase rollback` past `026` will fail. Recovery is forward-fix, not rollback; the frozen
  changeset is not edited.

## Types

`NUMERIC(14,2)` for money, `NUMERIC(18,4)` for quantities and unit costs, `TIMESTAMP WITH TIME ZONE` (UTC) for
instants, `DATE` for farm-local dates.
