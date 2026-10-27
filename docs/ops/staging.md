# Staging go-live

Staging is a full copy of the production set-up ([deployment.md](deployment.md)) on its own server and database. It
uses Razorpay test keys, and its address contains `staging` (for example `staging.smartfarm360.in`). It never holds real
farm data. Production is set up the same way once staging has passed the checks below.

## Ready in the repository

| Piece | Where |
|---|---|
| Production image (API and web app in one) | `deploy/Dockerfile`; CI builds and scans it on every pull request |
| Published image | `.github/workflows/release.yml`: a `v*` tag builds, scans and pushes `ghcr.io/<owner>/smartfarm360:<version>` |
| Server set-up (app and TLS proxy) | `deploy/compose.yaml`, `deploy/Caddyfile` |
| Settings template | `deploy/env.example`; the `prod` profile refuses unsafe values and names each one |
| Staging mode | `APP_ENVIRONMENT=staging`: requires a Razorpay test key and a host name containing `staging` |
| Backups and restore check | `deploy/backup.sh`, `deploy/restore-check.sh` |
| Load tests | `deploy/load/` (k6) |

## What only the product owner can do

These need accounts, payment or legal details, so they cannot be done from the repository.

1. **Choose the provider (O1).** It needs an Indian region, managed PostgreSQL 18 with point-in-time recovery, a small
   virtual machine, and object storage in a second region.
2. **Create the staging resources** listed in [deployment.md](deployment.md): the database (owner role, not a
   superuser), the server (Docker, ports 80 and 443, SSH by key), and the backup bucket.
3. **DNS:** an `A` record for `staging.<domain>` pointing to the server.
4. **Email (O5):** a transactional email account with SMTP, and SPF, DKIM and DMARC on the sending domain.
5. **Razorpay:** an account in test mode. Copy the test key id and secret, then add the webhook
   (`https://staging.<domain>/api/v1/billing/webhooks/razorpay`, events `subscription.*`, `payment.failed` and
   `payment_link.paid`) with a secret from `openssl rand -hex 24`.
6. **Seller details:** the legal entity's name, address and GSTIN. A placeholder is not accepted, because every invoice
   prints them.
7. **Publish the image:** push `main` and a tag such as `v0.15.0`, then let the server pull from the registry. Either
   make the package public, or run `docker login ghcr.io` on the server with a token that has `read:packages`.

## Start staging

On the server:

```bash
git clone https://github.com/<owner>/smartfarm360.git && cd smartfarm360 && git checkout v0.15.0
cp deploy/env.example deploy/.env && chmod 600 deploy/.env
```

Fill in `deploy/.env` as follows:

- `APP_ENVIRONMENT=staging`;
- `DOMAIN` and `APP_PUBLIC_WEB_URL` set to the staging address;
- the database, SMTP, Razorpay test keys, webhook secret and seller details;
- secrets generated with `openssl rand`.

Then follow the steps in "First start" in [deployment.md](deployment.md): create the first administrator, set up
two-step sign-in, send a test alert, back up and restore, and publish the terms.

## Checks before production

Record each result in `docs/PROGRESS.md`.

| Check | How |
|---|---|
| Start-up | `docker compose ps` shows the app healthy; `https://staging.<domain>/api/v1/announcements` answers 200 |
| Proxy | `/actuator/health` answers 404 from the internet; the HSTS header is present |
| Sign-up and sign-in | Register, verify the email, sign in, turn on two-step sign-in |
| Billing, in Razorpay test mode | Start a trial; check out on a paid plan; the invoice PDF shows the seller GSTIN and the correct GST split. Then a renewal (record how Razorpay test mode triggered it), a failed renewal (test card that fails) leading to *Past due*, and a refund leading to a credit note |
| Webhook | A webhook with a wrong signature is refused (400); the console *Billing* tab shows no unapplied events |
| Reconciliation | *Run now* in the console reports no differences |
| Backup | `deploy/backup.sh` once by hand; `deploy/restore-check.sh --latest` passes |
| Phone app | A test build pointed at staging signs in, records offline, and syncs |
| Load | `deploy/load/` scripts against staging, within the budgets in their README |
| Alerts | The test alert arrives; stopping the app sets off the uptime alert |

When every check passes, repeat on production with `APP_ENVIRONMENT=production`, live Razorpay keys and the
production address. Production refuses test keys.
