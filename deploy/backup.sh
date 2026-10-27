#!/usr/bin/env bash
# Nightly logical backup (SaaS operations design, O3): pg_dump, encrypted, copied to object storage in another region,
# and recorded on the platform console. Run from cron on a machine that can reach the database, for example:
#   30 1 * * *  /opt/smartfarm360/deploy/backup.sh >> /var/log/smartfarm360-backup.log 2>&1
#
# Needs: pg_dump 18, gpg, aws (any S3-compatible storage via BACKUP_S3_ENDPOINT), psql.
# Environment (from a file readable only by the backup user):
#   PGHOST PGPORT PGDATABASE PGUSER PGPASSWORD   the production database (a read-only role is enough for the dump)
#   OPS_PGUSER OPS_PGPASSWORD                     a role that may insert into sf_ops_checks (defaults to PGUSER)
#   BACKUP_PASSPHRASE_FILE                        the encryption passphrase; kept outside the backup storage
#   BACKUP_S3_URI                                 for example s3://smartfarm360-backups-hyd/nightly
#   BACKUP_S3_ENDPOINT                            optional, for non-AWS storage
#   BACKUP_KEEP_DAYS                              local copies kept (default 3)
set -euo pipefail

started=$(date +%s)
stamp=$(date -u +%Y%m%dT%H%M%SZ)
work=${BACKUP_WORK_DIR:-/var/backups/smartfarm360}
mkdir -p "$work"
dump="$work/smartfarm360-$stamp.dump"
endpoint_args=()
[[ -n "${BACKUP_S3_ENDPOINT:-}" ]] && endpoint_args=(--endpoint-url "$BACKUP_S3_ENDPOINT")

record() {
  local ok=$1 details=$2 ms=$(( ($(date +%s) - started) * 1000 ))
  PGUSER=${OPS_PGUSER:-$PGUSER} PGPASSWORD=${OPS_PGPASSWORD:-$PGPASSWORD} psql -v ON_ERROR_STOP=1 -q \
    -c "insert into sf_ops_checks (kind, ok, details, duration_ms) values ('BACKUP', $ok, '$details', $ms)" || true
}
trap 'record false "backup failed at line $LINENO"' ERR

pg_dump --format=custom --no-owner --no-privileges --file="$dump"
# A dump that cannot be listed is not a backup
pg_restore --list "$dump" > /dev/null
tables=$(pg_restore --list "$dump" | grep -c "TABLE DATA" || true)

gpg --batch --yes --pinentry-mode loopback --passphrase-file "$BACKUP_PASSPHRASE_FILE" --symmetric --cipher-algo AES256 \
    --output "$dump.gpg" "$dump"
rm -f "$dump"
aws "${endpoint_args[@]}" s3 cp "$dump.gpg" "$BACKUP_S3_URI/$(basename "$dump").gpg" --only-show-errors
# Monthly copies are kept 12 months; the bucket's lifecycle rules remove nightly copies after 35 days
if [[ $(date -u +%d) == "01" ]]; then
  aws "${endpoint_args[@]}" s3 cp "$dump.gpg" "${BACKUP_S3_URI%/*}/monthly/$(basename "$dump").gpg" --only-show-errors
fi
find "$work" -name 'smartfarm360-*.dump.gpg' -mtime +"${BACKUP_KEEP_DAYS:-3}" -delete

size=$(du -h "$dump.gpg" | cut -f1)
record true "$tables tables, $size encrypted, $(basename "$dump").gpg"
echo "Backup $stamp done: $tables tables, $size"
