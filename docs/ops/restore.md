# Restore runbook (SaaS operations design, O3)

**Targets:** at most 15 minutes of data lost (recovery point), and service back within 4 hours (recovery time).
Before any restore:

- turn on **read-only maintenance** (console, *Announcements*), so no new records are lost;
- say in the operators' chat what you are restoring and to when.

## 1. Point-in-time recovery after a bad release or a bad change

1. Note the time just before the problem, in UTC (from the release log or the audit log).
2. In the provider's console, restore the database **to a new instance** at that time. Never restore over production.
3. Check the new instance:
   - `select id from databasechangelog order by orderexecuted desc limit 1` is the expected version;
   - the restore check's queries pass (see `deploy/restore-check.sh`, checks 3 and 4).
4. Point the app at it:
   - set `SPRING_DATASOURCE_URL` in `deploy/.env`;
   - if the bad release was the cause, use the previous image tag;
   - `docker compose ... up -d`.
5. Turn maintenance off, and tell users which period was lost, if any. Phones resend what they still hold.

## 2. Full restore from a nightly backup (the database is lost)

1. Create a new managed PostgreSQL 18 with the same roles (owner, read-only).
2. Get the latest backup:
   - `aws s3 cp s3://.../nightly/<latest>.dump.gpg .`;
   - `gpg --decrypt --output backup.dump <file>`, with the passphrase from the key holders.
3. Restore it: `pg_restore --no-owner --no-privileges --dbname=<new database> backup.dump`.
4. Grant ownership of the restored tables to the owner role. Check, as in case 1.
5. Point the app at it and start it. Liquibase finds the schema up to date.
6. Turn maintenance off. Tell owners that records after the backup time are lost, except those still on phones.

## 3. One farm business's data (a support request)

Use this only when an owner asks in writing, for example after deleting by mistake past the 30-day grace.

1. Restore the newest backup that still has the business into a **scratch** database (`deploy/restore-check.sh` does
   this; keep the database instead of dropping it).
2. Export that business from the scratch database. The owner receives the CSV and JSON files, as with *Export everything*.
3. Data is never merged back into production by hand. If the business must be recreated, that is a separate,
   reviewed task.
4. Drop the scratch database, and record the request and the outcome in the platform audit (console).

## The monthly restore test

`deploy/restore-check.sh --latest` runs on the 1st of each month. It records *RESTORE_TEST* with the time taken on
the console. A failed test is an incident: do not deploy until a restore succeeds.
