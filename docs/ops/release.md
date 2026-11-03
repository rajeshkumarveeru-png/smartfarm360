# Release checklist (SaaS operations design, O14)

Tick each step in the release issue. Releases go to staging first, then production.

1. **CI is green on the release commit:** backend, web and phone tests, and the image build and scan. Critical findings
   with a fix available fail the scan.
2. **Tag:**
   - `git tag v0.14.0 && git push --tags`;
   - the release job pushes `smartfarm360:0.14.0`.
3. **Staging:**
   - set `SMARTFARM_IMAGE` to the new tag;
   - `docker compose ... pull && docker compose ... up -d`;
   - check the migrations in the log (`docker compose logs app | grep ChangeSet`);
   - run the smoke test below.
4. **Production backup:** `deploy/backup.sh`, and wait for *BACKUP ok* on the console.
5. **Deploy production:** the same commands as staging. Migrations run at start-up.
6. **Check:**
   - the migrations ran, with no `ERROR` in the log;
   - `/actuator/health/readiness` is `UP` on port 8091;
   - the console shows the new version.
7. **Smoke test:** with the smoke-test account and its own farm business:
   1. sign in;
   2. record a cash expense of ₹1 and reverse it;
   3. open the dashboard;
   4. sign in on the phone and sync.
8. **Watch** the alerts and the error rate for 30 minutes.
9. **Release note:** what changed, in English and Tamil if users see it. Post an announcement if it matters to users.

## Rolling back

- **No migration changed data:** go back to the previous image tag and `up -d`.
- **A migration changed data, or a release corrupted data:**
  1. turn on read-only maintenance (console);
  2. go back to the previous image;
  3. use point-in-time recovery to just before the deployment (`docs/ops/restore.md`, case 1);
  4. turn maintenance off.
- Migrations are forward-only (ADR 0006). The fix for a bad migration is a new migration, never an edit.

## Phone app releases (O15)

1. Build with EAS:
   - `eas build --platform android --profile production`;
   - `app.json` `expo.version` is the version sent as `X-Client-Version`.
2. Release through the Google Play tracks: internal testing, then closed testing with a few farms, then production.
3. Raise `APP_MIN_CLIENT_VERSION` only when an old app can no longer sync safely. Old outboxes are kept, and those
   users are asked to update.
