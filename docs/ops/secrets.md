# Secrets and their rotation (SaaS operations design, O13)

Secrets live only in `deploy/.env` on the server (`chmod 600`, owned by the service user), or in the host's secret
store. They are never in the repository, the image or the logs.

| Secret | Rotation | Effect |
|---|---|---|
| `APP_JWT_SECRET` | `openssl rand -base64 48`, restart | Everyone signs in again (access tokens and second-step challenges stop working) |
| `APP_MFA_ENCRYPTION_KEY` | Not rotated in place: two-step secrets are encrypted with it. To change it, every user turns two-step sign-in off and on again | Changing it without that makes two-step sign-in fail for everyone |
| `SPRING_DATASOURCE_PASSWORD` | Set the new password on the database role, update `.env`, restart | A few seconds of errors during the restart |
| `SPRING_MAIL_PASSWORD` | Create a new key at the provider, update `.env`, restart, then revoke the old key | None |
| Backup passphrase | Write the new passphrase to `BACKUP_PASSPHRASE_FILE`; old backups keep the old one until they expire (12 months) | Keep the old passphrase until then |
| Platform administrators' passwords | Each administrator, in *Your account*; recovery codes can be renewed there | — |

**When a secret may have leaked:**

1. Rotate it at once.
2. Look at the audit logs for misuse (platform log; `REFRESH_TOKEN_REUSE_DETECTED`, `SUPPORT_VIEW`).
3. Follow the incident checklist.
4. Under the DPDP Act, a personal data breach is reported to the Data Protection Board and to affected users. The
   product owner decides this with legal advice.
