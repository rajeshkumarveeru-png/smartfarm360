# Incident checklist (SaaS operations design, O4, O14)

## On call

Two operators share the on-call week and both receive the alert emails. The one who answers first owns the incident
and says so in the operators' chat.

## The first 15 minutes

1. **Acknowledge** in the chat: what alerted, the time, *I have it*.
2. **Is it real?**
   - open the site;
   - check `/actuator/health/readiness` on the server (port 8091, local only);
   - check the console's *Operations* tab.
3. **How bad?** Down for everyone, slow, errors for some actions, or data looking wrong?
4. **Stop harm first:**
   - **Data looks wrong**, or a release is suspect: turn on **read-only maintenance** (console, *Announcements*, with a
     message in English and Tamil). Phones keep their records and send them later.
   - **One farm business is being attacked** (a stolen password): lock the user (console, *Users*). Suspend the
     business only if its owner asks.
   - **Overload:** see the *Slow* section below.
5. **Tell users** when it lasts more than 15 minutes: an announcement banner, and an email to affected owners if their
   data is involved.

## Common causes

| Symptom | Look at | Usual fix |
|---|---|---|
| Site down | `docker compose ps`, `docker compose logs app --tail 200` | Restart the app; if it refuses to start, fix the setting it names |
| Slow | p95 by endpoint (metrics), database CPU and connections | Exports run 2 at a time; a slow report: find it in the metrics; scale the database |
| Many 401s / sign-ins failing | The sign-in attack alert, `REFRESH_TOKEN_REUSE_DETECTED` in the audit log | Rate limits hold; lock abused accounts |
| Emails not arriving | `sf_mail_failures_total`, the provider's dashboard | The provider's credentials or sending limits |
| Phones not syncing | `X-Client-Version` in logs, `CLIENT_UPGRADE_REQUIRED` counts | Lower `APP_MIN_CLIENT_VERSION` if it was raised too far |
| Backup or restore test failed | `sf_ops_checks` details | Rerun by hand; fix storage or credentials; do not deploy until a backup succeeds |

## Afterwards

Write the incident up within 2 working days, in `docs/ops/incidents/YYYY-MM-DD-short-name.md`:

- what happened and when (in UTC);
- who was affected;
- what we did;
- why it happened;
- what we change so it does not happen again, each with an owner.

No blame. Link the write-up in the next release note if users were affected.
