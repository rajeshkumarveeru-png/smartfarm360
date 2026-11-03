# Deployment (SaaS operations design, sections 3–5)

The reference set-up is provider-neutral. The product owner chooses the provider (O1); this page does not name one.
Start with staging: [staging.md](staging.md) lists what is ready, what only the product owner can do, and the checks to
pass before production.

## What to create (once per environment: staging, then production)

| Piece | Choice |
|---|---|
| Region | An Indian region (Mumbai, Hyderabad or Bengaluru) |
| Database | Managed PostgreSQL 18 with point-in-time recovery (7 days), TLS required, private network only |
| Database roles | An owner role for the schema, used by the application (not a superuser: Row-Level Security, ADR 0011); a read-only role for backups |
| Server | One virtual machine (2 vCPU, 4 GB to start) with Docker; ports 80 and 443 open; SSH by key only |
| Backup storage | An object-storage bucket in another region, with versioning. Lifecycle: nightly copies 35 days, `monthly/` 12 months |
| Email | A transactional email provider with SMTP, with SPF, DKIM and DMARC set for the sending domain |
| Monitoring | An external uptime check on `https://<domain>/api/v1/announcements` every minute; an agent scraping `127.0.0.1:8091/actuator/prometheus` |

## First start

1. On the server, check out the release tag and fill in the settings. The image comes from the registry: a `v*` tag
   publishes `ghcr.io/<owner>/smartfarm360:<version>` (`.github/workflows/release.yml`).
   - copy `deploy/env.example` to `deploy/.env` and fill it in (`chmod 600`);
   - generate the secrets with `openssl rand`, as the comments say.
2. For the very first start only:
   - set `APP_BOOTSTRAP_ADMIN_ENABLED=true` with an email and password to create the first platform administrator;
   - start;
   - set it back to `false`, because production refuses to keep running with it on;
   - restart.
3. Start the app:

   ```bash
   docker compose -f deploy/compose.yaml --env-file deploy/.env up -d
   ```

   The app refuses to start with unsafe settings, and says which. Fix them and start again.
4. Sign in as the administrator, turn on two-step sign-in (*Your account*), and sign in again.
5. Platform console, *Operations*: send a test alert and check that it arrives.
6. Install the backup cron job (`deploy/backup.sh`, nightly) and the restore check (`deploy/restore-check.sh --latest`,
   on the 1st of each month). Then run both once by hand.
7. Publish the terms and privacy notice (*Terms and privacy* tab) with the texts from the product owner.
8. Billing (billing design, Phase 15):
   - in the Razorpay dashboard, add a webhook to `https://<domain>/api/v1/billing/webhooks/razorpay` with the events
     `subscription.*`, `payment.failed` and `payment_link.paid`, and the secret in `APP_BILLING_WEBHOOK_SECRET`;
   - staging uses Razorpay test keys; production refuses them;
   - set the real prices in the console (*Billing*, *Prices*); the seeded prices are examples;
   - on staging, run one subscription end to end in test mode (checkout, renewal, failed renewal, refund) and record the
     result in PROGRESS.

## Alerts (O4)

Configure them in the monitoring tool from the metrics and the uptime check. Send them to `APP_OPS_ALERT_EMAIL`.

| Alert | Rule |
|---|---|
| Site down | Uptime check fails 3 times in a row |
| Errors | `http_server_requests_seconds_count{status=~"5.."}` over 2% of requests for 5 minutes |
| Slow | p95 of `http_server_requests_seconds` above 1.5 s for 10 minutes |
| Database | `hikaricp_connections_pending > 0` for 5 minutes, disk above 80%, or replication/backup lag (provider metric) |
| Backup | No `BACKUP` check with `ok = true` in `sf_ops_checks` in 26 hours; any `RESTORE_TEST` with `ok = false` |
| Certificate | TLS expiry within 14 days (uptime check) |
| Sign-in attacks | `rate(http_server_requests_seconds_count{uri="/api/v1/auth/login",status="401"}[10m])` above 10 times its weekly average |
| Email | `sf_mail_failures_total` increases |

## Logs

Logs are JSON on the container's output, collected by the host's log agent and kept 30 days. They carry the
correlation id, tenant id, user id and client version. They never carry passwords, tokens, email addresses or request
bodies.
