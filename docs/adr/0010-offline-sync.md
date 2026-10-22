# ADR 0010 — Offline records: client ids, the idempotency store and day-record versions

- Status: Accepted (2026-10-22). Decided in the mobile offline design ([docs/mobile/mobile-offline.md](../mobile/mobile-offline.md)), decisions N4 and N7.

## Context

The phone app must record field work with no signal and send it later. A record can be sent more than once: the phone
may lose the reply after the server saved it. Some records replace a whole day (attendance, milk by session, the
poultry daily record, the pond daily record), and two people can change the same day.

## Decision

**Client ids and the idempotency store (N4).**
- Every record made on the phone gets a UUIDv7 client id. The id is the request's `Idempotency-Key`.
- Endpoints the phone uses offline are marked `@Idempotent`. For those, the server keeps one row per tenant and key in
  `sf_idempotent_requests`: the user, method, path, a SHA-256 of method, path and body, and the successful reply.
  - The key is claimed before the request runs (`insert … on conflict do nothing`).
  - A 2xx reply is stored. Any other reply releases the key, so a corrected record can be sent under the same id.
  - A repeat with the same key, user and body gets the stored reply, with `Idempotent-Replayed: true`.
  - A repeat with a different body or user is refused with `IDEMPOTENCY_KEY_REUSED`.
  - A repeat while the first request is still running is refused with `IDEMPOTENCY_IN_PROGRESS`.
- The check runs after the tenant check (`TenantContextInterceptor`). So a key is honoured only for a member of that
  farm business, and the same key in another business is a different key.
- Rows older than 60 days are removed as new keys are claimed in that tenant. There is no scheduled job.
- Finance records, crop activities and harvests keep their own per-record idempotency. Requests without a key behave as
  before.

**Day-record versions (N7).**
- A day record's version is a short hash of the values the user can change. It is never taken from derived figures,
  and there is no stored version column.
- Reads return the version (`Versions.NONE` = `"0"` when nothing is stored).
- Saves take an optional `baseVersion`. If it differs from the current version, the save is refused with `STALE_RECORD`
  (409). A save without `baseVersion` (older clients) is not checked.
- The version is checked and the write is made under a transaction-scoped advisory lock on the day.

**Client version (N14).** The phone sends `X-Client-Version`. The server logs it, and refuses versions older than
`app.min-client-version` (environment `APP_MIN_CLIENT_VERSION`; empty by default) with 426 `CLIENT_UPGRADE_REQUIRED`.

## Consequences

- Hashing the stored values needs no migration and cannot drift from the data. The version changes only when the
  values change: re-saving the same values does not refuse a later save.
- The refusal does not carry the server's copy of the record. The phone reads the day again (the same GET as its form)
  to show *Yours* and *On the server* side by side.
- A reply is stored in full (at most 1 MB per request body read; replies of these endpoints are small). Stored replies
  are tenant data and follow the same backup and retention rules.
- New offline endpoints must be marked `@Idempotent`. They also need a replay test and a cross-tenant test.
