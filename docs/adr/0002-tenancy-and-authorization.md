# ADR 0002 — Tenancy and authorization

- Status: Accepted (2026-10-19)

## Context

A tenant is a farm business; it can own several farms. A user can belong to several tenants with different
roles. The existing API puts the tenant id in the path (`/api/smartfarm/tenants/{tenantId}/...`) and every
service re-implements its own membership check with hard-coded role lists.

## Decision

1. **Tenant in the path, enforced centrally.** New endpoints are `/api/v1/tenants/{tenantId}/...`. One request
   interceptor resolves the authenticated user's *active* membership for `{tenantId}` and builds a `TenantContext`
   (tenant id, user id, permissions). Missing membership returns **404** (do not reveal tenant existence).
   Services receive `TenantContext`, never a raw tenant id from the client.
2. **Permissions, not role names, in code.** Seeded `sf_permission`, `sf_role`, `sf_membership_role` tables.
   System roles: platform administrator (user-level, not a tenant membership), tenant owner, farm administrator,
   farm manager, accountant, field supervisor, worker, read-only viewer. Endpoints declare a required permission;
   enforcement is on the backend.
3. **Database safeguards.** Every tenant-owned table has `tenant_id NOT NULL`, a `(tenant_id, id)` unique key, and
   composite foreign keys to other tenant-owned tables (already in place for crop and finance tables and covered by
   `LiquibaseMigrationIntegrationTest`).
4. **Row-Level Security as defence in depth, later.** It requires a separate non-owner application role (today the
   app role owns the schema, which bypasses RLS) and `SET LOCAL app.tenant_id` per transaction. Planned after the
   finance core; never a substitute for the checks above.
5. **Tests.** A test enumerates all tenant-scoped endpoints and fails if any lacks a cross-tenant negative test.
6. The legacy `/api/smartfarm/...` routes have no external consumers; they are removed in Phase 2 once web and
   mobile use `/api/v1`.

## Consequences

- Tenant switching is a client-side choice of `{tenantId}`, validated on every request; no tenant state on the server.
- Caches and reports must key on tenant id; background jobs must carry an explicit `TenantContext`.

## Amendment (Phase 14, 2026-10-22)

Point 4 is carried out by [ADR 0011](0011-row-level-security.md). It forces Row-Level Security on the owner role
instead of adding a non-owner role. Platform support reads a business only under an owner's time-limited, read-only
grant, recorded in the business's audit log (O7).
