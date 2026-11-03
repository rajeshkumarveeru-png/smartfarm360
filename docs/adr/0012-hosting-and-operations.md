# ADR 0012 — Hosting, backups, monitoring and production settings

- Status: Accepted (2026-10-22), decisions O1–O5 of the [SaaS operations design](../platform/saas-operations.md).

## Decision

- **Where it runs (O1):**
  - one application server and a managed PostgreSQL 18 with point-in-time recovery, in an Indian region;
  - a smaller staging copy;
  - backup copies in object storage in another region.

  The provider is the product owner's choice. Everything in `deploy/` works on any provider.
- **What runs (O2):**
  - one container image (`deploy/Dockerfile`) with the API, and the built web app served at `/` from the same address;
  - Caddy in front for TLS, compression and headers;
  - one instance at launch.

  The `prod` profile refuses to start with unsafe settings (`ProductionSettings`): a short or missing signing secret,
  an insecure cookie, development email links, open API docs, a non-HTTPS web address, no SMTP host, no alert address,
  no two-step encryption key, the bootstrap administrator still on, or a superuser database login.
- **Backups (O3):**
  - point-in-time recovery for 7 days;
  - nightly encrypted `pg_dump` copies in another region, kept 35 days, with monthly copies kept 12 months
    (`deploy/backup.sh`);
  - a monthly scripted restore test (`deploy/restore-check.sh`) that restores into a scratch database and checks the
    migration version, row counts, journal balance and stock against movements.

  Results are recorded in `sf_ops_checks` and shown on the console. Targets: at most 15 minutes lost, back within
  4 hours.
- **Monitoring (O4):**
  - JSON logs (`logging.structured.format.console=logstash`), kept 30 days, with no personal data;
  - metrics and probes on a management port (8091) that Caddy never serves;
  - an external uptime check;
  - email alerts, with a test alert sent from the console;
  - the alert rules in `docs/ops/deployment.md`.
- **Email (O5):** an SMTP `EmailSender`, so any transactional provider works, with texts from the shared catalogs in
  English and Tamil and three retries over 15 minutes.
- **Rate limits** are counted in PostgreSQL (`sf_rate_limit_counters`), so they survive restarts and serve a second
  instance.

## Consequences

- Moving to another provider means new database and storage endpoints, not code changes.
- A single instance is a single point of failure; the recovery targets assume a restart or a new server within 4 hours.
- Several instances need a shared store for nothing else today: rate limits, idempotency and exports' metadata are in
  PostgreSQL. Export files are on the instance's disk and would need shared storage (ADR 0009) first.
