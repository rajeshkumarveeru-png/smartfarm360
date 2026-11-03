# ADR 0011 — Row-Level Security as defence in depth

- Status: Accepted (2026-10-22), decision O13 of the [SaaS operations design](../platform/saas-operations.md). Carries out
  point 4 of ADR 0002.

## Decision

- **Every table with a `tenant_id` has Row-Level Security**, enabled and **forced**. Forcing applies the policies to the
  table owner, which is the role the application uses.
  - The policy `sf_tenant_isolation` is the same on every table: `USING` and `WITH CHECK` allow a row when the session is unrestricted, the row is shared (`tenant_id IS NULL`), or it belongs to the session's business. The session's business comes from `sf_rls_tenant()`, read once per query through a scalar subquery, not once per row.
  - Shared seed rows (`tenant_id IS NULL`, for example system finance categories and roles) stay visible.
- **The scope is a connection setting, `app.tenant_id`:**
  - Inside a request under `/api/v1/tenants/{tenantId}`, after the membership check (`TenantContextInterceptor`), it is
    that business's id. The database then shows and accepts only that business's rows.
  - Everywhere else it is `all`: sign-in, the business list, invitations, the platform console, and background jobs
    (exports, deletions). That code filters by tenant itself, as before.
  - A connection with no setting (migrations, an operator's `psql`) is not restricted.
- **How the setting is applied.** `RlsDataSourceConfig` wraps the connection pool: every borrowed connection gets
  `set_config('app.tenant_id', …)` from the current thread's `RlsScope`. A pooled connection never carries one
  request's scope into another.
- **No separate application role.** The design proposed a non-owner role. Forcing RLS on the owner gives the same
  protection with one role, and keeps migrations simple. Instead:
  - production refuses to start when the database user is a superuser, because superusers bypass RLS
    (`ProductionSettings`);
  - the test suite connects as an ordinary owner role, so all API tests, including the 552 cross-tenant cases, run
    under the policies.
- **New tenant tables need the policy in their own migration.** `RowLevelSecurityCoverageTest` fails for a table with a
  `tenant_id` and no forced policy.

## Consequences

- A query that forgets its tenant filter inside a farm business's request returns nothing from other businesses, and
  a write for another business is refused by the database.
- Code outside tenant requests is not protected by RLS. It stays small, and is reviewed as before.
- Foreign-key checks bypass RLS (PostgreSQL behaviour), so composite keys still guard references across tables.
- Each borrowed connection costs one extra round trip, which sets the scope and `plan_cache_mode = force_custom_plan`.
  - The policy's extra conditions made PostgreSQL choose generic plans for prepared report queries with optional filters.
    On 50,000 journal lines that turned the income statement from 100 ms into about a second.
  - With custom plans the dashboard answers in 470–490 ms, against 560–640 ms before RLS.
  - The dashboard also works out the dues once per request instead of twice.

## Amendment (2026-10-27, billing design)

- The platform's billing documents (`sf_payments`, `sf_invoices`, `sf_invoice_lines`, `sf_credit_notes`) are the
  seller's records, kept 8 years after a farm business is deleted. They have `customer_tenant_id` instead of
  `tenant_id`, so neither the deletion nor `RowLevelSecurityCoverageTest` treats them as tenant tables.
- They get their own forced policy, `sf_customer_isolation`:
  `(SELECT sf_rls_tenant()) IS NULL OR customer_tenant_id = (SELECT sf_rls_tenant())`. Inside a farm business's request
  only its own documents exist; the webhook, the jobs and the console run in the `all` scope.
- The subscription tables (`sf_subscriptions`, `sf_subscription_events`, `sf_billing_profiles`) are ordinary tenant
  tables with the standard policy.
