# SaaS operations and production readiness design (Phase 14)

- Status: **Approved** (2026-10-22, O1–O15 as recommended) and **built** in Phase 14. Implementation notes are in section 18.
- Builds on:
  - the platform after Phase 13:
    - a platform administrator can list farm businesses and suspend or reactivate them;
    - every farm business has a `plan_code` (`FREE`) but no limits;
    - email is only logged;
    - rate limits are kept in memory;
    - there is no deployment setup, backup or monitoring;
  - ADR 0002 (tenant isolation; Row-Level Security planned "later"), ADR 0003 (authentication) and ADR 0006 (migration
    policy).
- Decisions marked **O1–O15** need an answer. Each has a recommendation. **O1–O4**, **O10** and **O13** will get ADRs
  once approved.
- Legal text (terms of service, privacy notice) is not written here. The product owner provides it, reviewed by a
  lawyer; this phase builds where it is shown, accepted and recorded.

## 1. What this phase fixes

SmartFarm360 works end to end on a developer machine, but it cannot yet be run as a service for paying farm businesses:

- **Nowhere to run it.** There is no agreed host, no container image, no TLS set-up and no production configuration
  that refuses to start with unsafe settings.
- **No safety net.** There are no backups, no tested restore, no monitoring and no alerts. A lost disk loses every
  farm's books.
- **Accounts cannot be recovered.** Email is only logged, so a farmer who forgets a password cannot reset it.
- **Operators cannot help users safely.** There is no support access, no usage view and no record of why a farm
  business was suspended.
- **Owners cannot take their data or leave.** There is no export of a whole farm business, no deletion of a farm
  business or an account, and no record of consent to the terms and privacy notice (needed under India's Digital
  Personal Data Protection Act, 2023).
- **Known defects and gaps from earlier phases:**
  - two open tabs can sign each other out;
  - tenant isolation is enforced only by the application (no database Row-Level Security);
  - rate limits do not survive a restart.
- **Nobody knows the limits.** No load test has been run beyond the dashboard test, and there are no incident, release
  or restore checklists.

## 2. Principles

1. **Boring and portable.** One container image, PostgreSQL and a reverse proxy. Nothing that ties the product to one
   cloud's special services, so the host can change.
2. **A backup is only real once it has been restored.** Restores are scripted and run on a schedule, and their result is
   recorded.
3. **Operators see as little farm data as possible.** The platform console shows counts and status, not books.
   Reading a farm business's records needs the owner's permission, for a limited time, and is recorded where the owner
   can see it.
4. **The owner's data is the owner's.** An owner can export everything and can delete the farm business. Deletion is
   delayed so a mistake can be undone, then final.
5. **Production refuses unsafe settings.** It will not start without a signing secret, with the API docs open, with
   development email links, or with an insecure cookie.
6. **Every operational promise has a check.** A backup has a restore test, an alert has a test alert, and a limit has a
   load test.

## 3. Hosting and deployment (O1, O2)

**Where it runs (O1).**
- One application server and a **managed PostgreSQL 18** in an **Indian region** (Mumbai, Hyderabad or Bengaluru), so
  farm data stays in India and pages are quick for Indian users.
- The product owner chooses the provider. The reference set-up is provider-neutral:
  - a virtual machine with Docker;
  - a managed database with point-in-time recovery;
  - an object-storage bucket in another region for backup copies.
- A second environment, **staging**, mirrors production at a smaller size. Releases go to staging first.

**What runs (O2).**
- **One container image** holds the backend, with the built web app served from the same address:
  - the web app at `/`;
  - the API at `/api`;
  - no separate web host, so cookies and CORS stay simple.
- **A reverse proxy (Caddy)** in front:
  - TLS certificates that renew themselves;
  - HTTP to HTTPS redirect, compression and security headers;
  - forwarded client addresses, for rate limits and audit.
- **One application instance at launch.** The design keeps the door open for more (section 9), but a single instance
  is enough for the expected first year (hundreds of farm businesses) and keeps operations simple.
- **A `prod` profile that refuses unsafe settings at start-up.** The application stops with a clear message when:
  - `APP_JWT_SECRET` is missing or shorter than 32 bytes;
  - the refresh cookie is not secure;
  - development email links are on;
  - the API docs are on;
  - the database user owns the schema (needed for O13);
  - `APP_PUBLIC_WEB_URL` is not HTTPS.
- **Migrations run at start-up**, as today (Liquibase), after the release checklist's backup step.
- **Configuration only through environment variables.** Secrets come from the host's secret store or an `.env` file
  readable only by the service user; never from the image or the repository.
- **Docker Compose file and image build in the repository** (`deploy/`), with:
  - a GitHub Actions job that builds and scans the image on every push to `main`;
  - a release job that tags the image with the version.

## 4. Backups and restore (O3)

| What | How | Kept |
|---|---|---|
| Point-in-time recovery | The managed database's continuous backup | 7 days |
| Nightly logical backup | `pg_dump -Fc`, encrypted, copied to object storage in another region | 35 days, plus the first of each month for 12 months |
| Before each release | `pg_dump -Fc` (as done locally every phase) | 30 days |
| Attachments (when ADR 0009's storage arrives) | Object-storage versioning and a copy in another region | Same as the nightly backup |

- **Targets:**
  - at most **15 minutes** of data lost (recovery point);
  - service back within **4 hours** (recovery time).
- **Restore test, monthly and scripted** (`deploy/restore-check`). It runs automatically and also when an operator
  starts it. It:
  1. restores the latest nightly backup into a scratch database;
  2. checks:
     - the migration version matches production;
     - every table's row count is close to production's (within the day's changes);
     - every farm business's journal balances;
     - stock quantities equal the sum of their movements;
  3. records the result and the time taken on the platform console;
  4. alerts on failure.
- **The restore runbook** (`docs/ops/restore.md`) covers:
  - point-in-time recovery after a bad release;
  - full restore from a nightly backup after losing the database;
  - restoring one farm business's data from a backup (support request, section 6).
- **Backup encryption keys** are kept outside the backup storage and outside the application server. Two people hold
  access.

## 5. Monitoring and alerts (O4)

- **Logs**
  - In production, logs are JSON with the correlation id, tenant id, user id, client version and request time.
  - They never contain passwords, tokens, email addresses or request bodies.
  - Logs are kept 30 days.
- **Metrics.** The actuator metrics are exposed on a separate management port that the reverse proxy does not serve.
  They cover request counts and times by endpoint, errors, database pool, JVM memory, sync replays and stale
  refusals.
- **Health.** Liveness and readiness probes, as today. An external uptime check calls the readiness endpoint every
  minute from outside the host.
- **Alerts (email to the operators):**

  | Alert | When |
  |---|---|
  | Site down | The uptime check fails 3 times in a row |
  | Errors | More than 2% of requests fail with 5xx over 5 minutes |
  | Slow | p95 response time above 1.5 s over 10 minutes |
  | Database | Pool exhausted, disk above 80%, or replication/backup lag |
  | Backup | The nightly backup or the monthly restore test fails |
  | Certificate | TLS certificate expires within 14 days |
  | Sign-in attacks | Failed sign-ins above 10 times the normal rate |

- **A test alert** is sent from the console, so the alert path is known to work.
- **The incident checklist** (`docs/ops/incident.md`) covers:
  - who is on call, and the first 15 minutes;
  - how to put the site into read-only maintenance;
  - telling farm businesses (a banner in the web and phone apps, section 7);
  - the write-up afterwards.

## 6. The platform console and support access (O6, O7)

**Platform console (O6).** Platform administrators get:
- **Farm businesses**: search by name, slug or owner email; status, plan, members, farms, enabled modules, last
  activity, and storage used. No financial figures.
- **A farm business's page**:
  - its owner(s);
  - usage against its plan limits (section 7);
  - its suspension history;
  - its deletion state (section 8);
  - the support access granted.
- **Suspend and reactivate with a reason.** The reason is shown to the business's members on their sign-in.
  Suspension is recorded in the business's audit log and in the platform audit log.
- **Users**: search by email; lock or unlock a user; see their farm businesses and roles. Passwords and tokens are
  never shown.
- **Operations**: the last backup, the last restore test with its result, the release version, the alert test, and the
  maintenance banner (section 7).
- **The platform audit log**: every platform administrator action, kept 2 years.
- **Two-step sign-in for platform administrators**:
  - a TOTP app code at every sign-in;
  - recovery codes shown once.

  Ordinary users can turn it on too (optional).

**Support access (O7).**
- **No impersonation.** Operators never sign in as a user.
- **An owner grants support access** from *Settings → Support*:
  - read-only;
  - for 72 hours (shorter if the owner chooses);
  - with a reason.

  The owner can end it at any time.
- **With access granted**, a platform administrator opens the business read-only with the permissions of a viewer
  plus reports. Every page opened and export made is recorded in the business's audit log, where the owner sees
  it.
- **Without access**, operators can only see the console's counts and status. For a dispute (for example a lost
  password where email does not reach the owner), they act on users, not on farm data.

## 7. Plans, limits and announcements (O8)

- **No billing in this phase.** Every farm business is on `FREE`. Plans are a list of limits, so paid plans can be
  added later without code changes to the limits themselves.

  | Limit (FREE) | Value |
  |---|---|
  | Farm businesses a user can create | 3 |
  | Active members per farm business | 25 |
  | Farms per farm business | 20 |
  | Attachment storage (when ADR 0009's storage arrives) | 1 GB |
  | Exports of the whole business (section 8) | 3 a day |

- **Soft first.** At 80% the owner sees a notice. At the limit, the action that would pass it is refused with
  `PLAN_LIMIT_REACHED` and the limit's name. Nothing already recorded is ever blocked or hidden.
- **A platform administrator can raise a farm business's limits.** The override is recorded with a reason.
- **Announcements and maintenance.**
  - Platform administrators can show a banner in the web and phone apps (in English and Tamil), for example for
    planned maintenance.
  - In **read-only maintenance**, every write is refused with `MAINTENANCE` (503). The phone keeps its outbox and
    retries later (Phase 13 treats 5xx as retry).

## 8. Owners' data: export and deletion (O9, O10, O11)

**Export a whole farm business (O9).**
- Owners (only `TENANT_OWNER`) can export from *Settings → Your data*. The export is one ZIP with:
  - one CSV file per kind of record (farms, crop cycles, documents and lines, journal, stock movements, workers,
    attendance, animals, ponds, batches and so on), with readable column names;
  - a JSON copy for re-import tools;
  - a README listing the files, the columns and the business's opening date.
- It runs in the background. The owner gets an email and a notice when it is ready, and downloads it through the API
  within 7 days. Then it is removed.
- Each export is recorded in the audit log. At most 3 a day per business.

**Delete a farm business (O10).**
1. The owner asks for deletion, typing the business's name to confirm, and is offered an export first.
2. **30 days' grace.** The business becomes read-only for its members and shows *Deletion on 21 Nov 2026*. Exports
   still work. Any owner can cancel during this time.
3. After 30 days, every row of the business is deleted in one transaction (the composite foreign keys make the order
   reliable), and its files are removed.
4. A **deletion record** stays in the platform log: business id, name, date and who asked. No farm data is kept.
   Backups age out on their normal schedule (35 days, monthly copies 12 months). The privacy notice says so.

- Indian tax law expects books to be kept for years. Before deleting, the confirmation says that keeping the books is
  the owner's responsibility, and the export is the way to keep them.

**Delete a user account (O11).**
- Any user can delete their own account from *Profile*, after typing their password, unless they are the only owner of
  an active farm business. They must first hand the business to another owner, or ask for its deletion.
- The account's name, email and phone are replaced with *Former member #1234*, and its sign-in, sessions and two-step
  codes are removed. Its memberships end.
- The records the user made stay, because they belong to the farm business's books. Their audit lines show the
  former-member label.

## 9. Security hardening (O13)

- **Row-Level Security on every tenant table** (ADR 0002's planned defence in depth):
  - **Roles.** The application connects as a role that does not own the schema. Migrations run as the owner role,
    from a separate connection.
  - **Context per transaction.** Each transaction sets `app.tenant_id` (`SET LOCAL`) from the checked `TenantContext`.
  - **Policies.** Each table allows only rows of that tenant. Platform jobs (deletion, export, restore checks) use a
    separate role that is allowed to bypass the policies, and log that they did.
  - **Enforcement.** A migration test lists every table with a `tenant_id` and fails if one has no policy.
- **Two tabs no longer sign each other out.** A refresh token presented again within **30 seconds** of its rotation,
  from the same family, gets the already-issued successor instead of revoking the family. Reuse after 30 seconds is
  still treated as theft. Tests cover both, and ADR 0003 is updated.
- **Rate limits move to PostgreSQL**:
  - a small table of counters per key and window;
  - they survive restarts;
  - they are ready for a second instance;
  - limits are unchanged.
- **Security headers and checks:**
  - a strict Content-Security-Policy for the web app, HSTS, frame-ancestors none, and a referrer policy;
  - dependency and image scanning in CI (fails on critical findings with a fix available);
  - the OpenAPI UI off in production.
- **Secrets rotation:** documented steps for the signing secret (everyone signs in again), the database passwords,
  the email provider key and the backup key.

## 10. Email (O5)

- `EmailSender` gets an **SMTP implementation**, which works with any transactional email provider (for example one in
  the chosen cloud). The provider is chosen with the host (O1). Settings come from the environment, and production
  refuses to start without them.
- Messages are sent after the transaction commits (as today), with three retries over 15 minutes. A failed send is
  logged with the masked address and counted in the metrics.
- **Templates in English and Tamil**, chosen by the user's language:
  - password reset;
  - invitation;
  - email verification;
  - export ready;
  - deletion scheduled and done;
  - support access granted;
  - a new sign-in to a platform administrator account.
- **Email verification is turned on** (deferred since Phase 2):
  - new accounts can sign in at once but cannot create a farm business until the address is verified;
  - accounts that already exist are treated as verified.
- SMS and push notifications are not in this phase.

## 11. Privacy and consent (O12)

- **The terms of service and the privacy notice are versioned documents** in English and Tamil, served by the app
  (texts from the product owner). Users accept them:
  - at registration;
  - again when a new version is published (a page before anything else, with what changed).
- **Each acceptance is recorded**: user, document, version, time, and the app it came from.
- **The privacy notice states** what is collected, why, where it is kept (India), how long, who processes it (the host
  and the email provider), and the user's rights. It names a **grievance officer** with a contact address, as the DPDP
  Act requires.
- **Users can see and act on their data** from *Profile → Privacy*:
  - their personal data and the businesses they belong to;
  - their consent history;
  - export of their own personal data;
  - deletion of their account (O11).
- **No marketing email** without a separate opt-in (off by default).
- **The phone app** shows the same documents, and records acceptance in the same way.
- **Workers recorded by a farm** (names, phone numbers, villages) are the farm business's data. The privacy notice
  says the farm business is responsible for having its workers' consent.

## 12. Load tests and readiness checks (O14)

- **Load tests with k6**, kept in `deploy/load/` and run against staging before the first release and before each
  major release.
- **Data size**: 500 farm businesses, of which 20 are large (50,000 journal lines, 200 workers, 5,000 animals).
- **Scenarios and targets:**

  | Scenario | Load | Target |
  |---|---|---|
  | Mixed web use (dashboards, lists, saves) | 200 concurrent users for 30 minutes | p95 under 500 ms for reads and 800 ms for saves; no errors |
  | Morning phone sync | 300 phones each sending 30 records within 10 minutes, with 10% retries | All recorded once; p95 under 1 s; no 5xx |
  | Reports and exports | 20 users exporting large reports at once | Each under 10 s; other users' p95 under 1 s |
  | Sign-in burst | 100 sign-ins a minute | Rate limits hold; p95 under 800 ms |

- **The release checklist** (`docs/ops/release.md`):
  1. CI green and the image scanned;
  2. staging deployed and its smoke test passed;
  3. production backup taken;
  4. deploy;
  5. migrations checked in the log;
  6. smoke test;
  7. watch the alerts for 30 minutes;
  8. the release note.

  It also says how to roll back: the previous image, plus point-in-time recovery if a migration changed data.
- **A smoke test script** signs in with a dedicated smoke-test account on its own farm business, records and reverses
  a cash expense, and reads the dashboard.
- **A production-readiness review** before the first paying farm business. It closes each item of section 1 with
  evidence (a restore test result, an alert test, the load test report, the ADRs) and records them in PROGRESS.

## 13. Releasing the apps (O15)

- **Web**: released with the backend (section 3).
- **Android first**:
  - built with Expo Application Services (EAS);
  - released through **Google Play internal testing**, then a closed test with a few farms, then production;
  - the store listing in English and Tamil, linking the privacy notice.

  iOS waits until there is demand. Phone builds point at production or staging by build profile, and send their version
  (Phase 13, N14). `APP_MIN_CLIENT_VERSION` is raised only when an old app can no longer sync safely.
- **The desktop (Electron) app is frozen**:
  - not built or released;
  - kept in the repository unchanged;
  - needs a local PostgreSQL, which farmers cannot run, and adds nothing the web app does not do.

  It is a candidate for removal later, which will be a separate decision.

## 14. Worked example (November 2026)

**Launch week.**
- The product owner picks a provider with a Mumbai region. The operator creates staging and production from
  `deploy/` with a managed PostgreSQL 18.
- The first production start fails: *APP_JWT_SECRET must be set (at least 32 bytes) in the prod profile*. The
  operator adds it from the secret store, and the app starts.
- The release checklist runs, and the smoke test passes.
- The first monthly restore test runs on 1 November: 2 min 40 s, all checks green, recorded on the console.

**Support.**
- Lakshmi, owner of *Lakshmi Farms*, cannot find why October's profit fell. She grants support access for 24 hours
  with the reason *October profit check*.
- Arun, a platform administrator, signs in with his password and authenticator code. He opens Lakshmi Farms read-only
  and finds a bill posted to the wrong crop cycle. He tells Lakshmi; he cannot change it himself.
- Lakshmi's audit log shows *Support access by Arun: dashboard, Samba 2026 report, 14:02–14:20*. She ends the access.

**A suspension.**
- A farm business's owner reports a former partner signing in. Arun locks that user account (users, not farm data).
  The owner removes the member.
- No suspension of the farm business is needed.

**Deletion.**
- *Selvam Agro* closes. Its owner exports the business (a 14 MB ZIP, ready in 40 seconds, email sent), then asks for
  deletion on 22 Nov.
- The business is read-only and shows *Deletion on 22 Dec 2026*.
- On 22 Dec every row is deleted, and the platform log keeps *Selvam Agro, deleted 22 Dec 2026, asked by the owner*.
  The nightly backups that still hold it expire by 26 Jan 2027, and the monthly copies within 12 months, as the
  privacy notice says.

**An incident.**
- At 06:10 an alert says p95 is 2.3 s: a large export is running alongside the morning phone sync.
- The incident checklist leads to the export queue, which is limited to 2 at a time from then on.
- The phones' records all arrive (their retries are replayed, Phase 13). The write-up is filed the same day.

## 15. Data and server changes

**Forward-only migrations (`016-saas-operations.yaml`):**
- `sf_plans` and `sf_plan_limits` (seeded with `FREE`), and `sf_tenant_limit_overrides` (tenant, limit, value, reason,
  by, when).
- `sf_tenants`: suspension reason and time; deletion requested at, by, and scheduled for.
- `sf_support_grants`: tenant, granted by, granted to (or any platform administrator), reason, from, until, ended at.
- `sf_platform_audit`: actor, action, target, details, time (no tenant key: platform scope).
- `sf_tenant_exports`: tenant, requested by, status, file key, size, ready at, expires at.
- `sf_user_mfa`: user, secret (encrypted with a key from the environment), recovery code hashes, enabled at.
- `sf_legal_documents` (kind, version, language, published at, text) and `sf_consents` (user, document, version, time,
  client).
- `sf_users`: email verified at; anonymised at. Existing users are marked verified.
- `sf_rate_limit_counters`: key, window start, count.
- `sf_announcements`: text in English and Tamil, from, until, maintenance flag.
- RLS: the application role, the policies on every tenant table, and the platform job role.
  - These run as separate changesets, so they can be applied after the role is created on the managed database.
  - The local and test databases get the same roles.

**Server:**
- `prod` profile checks;
- the SMTP sender and templates;
- the platform console endpoints under `/api/v1/platform`;
- support grants;
- plan limits with `PLAN_LIMIT_REACHED`;
- the maintenance mode;
- export jobs;
- deletion jobs (a daily job; deletions run one business at a time);
- account anonymisation;
- consent;
- TOTP;
- RLS context per transaction;
- the refresh grace window;
- the PostgreSQL rate limiter;
- JSON logging and metrics.

**Repository:**
- `deploy/` (Dockerfile, Compose file, Caddyfile, restore check, k6 scripts);
- `docs/ops/` (runbooks and checklists);
- CI image build and scans.

All new tenant tables get tenant keys and cross-tenant tests. Support access gets tests that it is read-only, time-bound,
recorded, and refused without a grant.

## 16. Decisions needed

| # | Question | Recommendation |
|---|---|---|
| **O1** | Host in an Indian region: one application server and a managed PostgreSQL 18 with point-in-time recovery, plus staging; the provider chosen by the product owner, with a provider-neutral reference set-up? | **Yes** |
| **O2** | One container image serving the API and the web app behind Caddy with TLS; one instance at launch; a `prod` profile that refuses unsafe settings; migrations at start-up after a backup? | **Yes** |
| **O3** | Backups: point-in-time recovery 7 days, nightly encrypted dumps in another region for 35 days (monthly copies 12 months), a monthly scripted restore test; at most 15 minutes lost and 4 hours to recover? | **Yes** |
| **O4** | JSON logs (30 days, no personal data), metrics on a private port, an external uptime check, and email alerts for the list in section 5, with a test alert? | **Yes** |
| **O5** | Email through SMTP to a provider chosen with the host; English and Tamil templates; email verification turned on (existing users treated as verified)? | **Yes** |
| **O6** | A platform console with farm businesses, users, operations and a platform audit log; suspension with a reason; two-step sign-in required for platform administrators and optional for everyone else? | **Yes** |
| **O7** | Support access only when an owner grants it: read-only, up to 72 hours, recorded in the business's audit log; never impersonation? | **Yes** |
| **O8** | No billing yet; a `FREE` plan with the limits in section 7, soft warnings at 80%, refusals only for new additions, overrides by platform administrators; announcements and read-only maintenance? | **Yes** |
| **O9** | Owners can export the whole farm business as a ZIP of CSV and JSON with a README, prepared in the background and kept 7 days, at most 3 a day? | **Yes** |
| **O10** | Farm business deletion by an owner with 30 days' grace (read-only, cancellable), then a final delete, keeping only a deletion record; backups age out on schedule? | **Yes** |
| **O11** | Users can delete their own account (not while sole owner of an active business): personal data replaced with a former-member label, records kept for the books? | **Yes** |
| **O12** | Versioned terms and privacy notice in English and Tamil, accepted at registration and on each new version, with a consent record, a grievance officer, a personal data export and no marketing without opt-in? | **Yes**; legal texts from the product owner |
| **O13** | Row-Level Security on every tenant table with a non-owner application role; the 30-second refresh grace for two tabs; rate limits in PostgreSQL; security headers and CI scans? | **Yes** |
| **O14** | k6 load tests against staging with the targets in section 12; release, incident and restore checklists; a smoke test; a production-readiness review before the first paying farm business? | **Yes** |
| **O15** | Android first through Google Play testing tracks (iOS later); the web released with the backend; the Electron desktop app frozen (not built or released)? | **Yes** |

**Not in this phase:** billing and payments, SMS and push notifications, recurring expenses (H14), emailed or scheduled
reports, several application instances, iOS, and offline photos. Each stays in the backlog.

## 17. What Phase 14 delivers once this is approved

**Features:**
- the `prod` profile checks, the container image, the Compose and Caddy reference set-up, and CI image builds and
  scans;
- the SMTP sender with English and Tamil templates, and email verification;
- the platform console, two-step sign-in, support grants and the platform audit log;
- plans and limits, announcements and read-only maintenance;
- export and deletion of a farm business, account deletion, consent records and the privacy page;
- Row-Level Security, the refresh grace window and the PostgreSQL rate limiter;
- JSON logs, metrics, the restore check, the k6 scripts and the operations runbooks;
- Tamil and English throughout.

**Tests:**
- the `prod` profile refusing each unsafe setting;
- RLS: every tenant table has a policy, and a query without a tenant context sees nothing; plus the existing
  cross-tenant suite run as the non-owner role;
- support access: refused without a grant, read-only with one, ended on time, recorded;
- plan limits, overrides and maintenance mode;
- export contents for a seeded farm business (every table present, the journal balancing in the CSV);
- deletion: grace, cancel, final delete leaving no rows of that tenant and other businesses untouched;
- account anonymisation;
- consent on new versions;
- TOTP and recovery codes;
- the refresh grace (inside 30 seconds and after);
- rate limits surviving a restart;
- email templates in both languages;
- the restore check against a copy of the local database;
- section 14 as an end-to-end scenario where it touches the API (support access, export, deletion).

**Not testable inside this repository** (recorded in PROGRESS when done on the real host): the managed database's
point-in-time recovery, the uptime check, real email delivery, the load tests on staging, and the Play Store release.

## 18. Implementation notes (2026-10-22)

O1–O15 were approved as recommended. The decisions are recorded in
[ADR 0011](../adr/0011-row-level-security.md) (RLS), [ADR 0012](../adr/0012-hosting-and-operations.md)
(hosting, backups, monitoring, settings) and [ADR 0013](../adr/0013-owners-data-export-and-deletion.md) (export and
deletion), with amendments to ADR 0002 and ADR 0003. Where the build differs from the sections above:

- **Row-Level Security without a second role (O13).** The policies are *forced* on the owner role the application uses.
  That gives the same protection as a separate non-owner role, with simpler migrations.
  - Production refuses a superuser login instead of refusing a schema owner.
  - The scope is set on every borrowed connection: the business inside its own requests, `all` elsewhere.
  - The test suite now connects as an ordinary owner role, so every API test runs under the policies.
- **RLS and query plans.** With the policies on, the dashboard performance test went from 560–640 ms to 1.1–1.7 s. The
  cause was not the filter itself, which costs the same in either scope. The policy's conditions led PostgreSQL to pick
  generic plans for prepared report queries with optional filters, and the income statement took a second instead of
  100 ms.
  - Each connection now uses custom plans (`plan_cache_mode = force_custom_plan`, set with the tenant scope).
  - The dashboard works out the dues once per request.
  - Result: 470–490 ms.
  - The policy reads the session's business once per query (`sf_rls_tenant()` in a scalar subquery). Changeset 056
    was corrected for this before its first run.
- **The refresh grace gives a second successor, not the first (O13).** Only the token's hash is stored, so the
  successor already issued cannot be handed back. Within 30 seconds, the second tab gets its own successor in the same
  family, and both tabs stay signed in. A family that was signed out or revoked is never reopened.
- **Platform support sees viewer permissions** of the business, read-only. Each request opened is recorded as
  `SUPPORT_VIEW` with its path. The design's "plus reports" is covered: viewers can read the reports.
- **Deleting a business needed one change to the append-only guards** (changeset 057). They allow DELETE only in a
  transaction where the deletion job set `app.tenant_deletion = 'on'`.
- **Export files are on the server's disk** (`APP_EXPORT_DIR`), because attachment storage (ADR 0009) is not built yet.
  A second instance would need shared storage for them.
- **Storage limits** are listed in the plan (`STORAGE_MB`), but nothing counts storage until attachment storage exists.
- **Legal documents are published from the console in both languages at once.** Nothing is asked of users until the
  first version is published.
- **The section 14 example** is covered by the API tests for support access, export and deletion. The hosting,
  certificate, real email and incident parts happen on the real host.
- **Not built here:**
  - a staging seed job for the load tests (the scripts expect seeded load-test accounts);
  - Play Store listings;
  - the monitoring tool's alert rules themselves (written in `docs/ops/deployment.md`, configured on the host).
