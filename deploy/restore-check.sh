#!/usr/bin/env bash
# Monthly restore test (SaaS operations design, O3): restores a backup into a scratch database, checks it, and records
# the result and the time taken on the platform console. A backup is only real once it has been restored.
#   deploy/restore-check.sh smartfarm360-20261101T013000Z.dump.gpg     # a downloaded nightly backup
#   deploy/restore-check.sh --latest                                     # fetch the newest from BACKUP_S3_URI
#
# Environment:
#   SCRATCH_URL      a database server where the scratch database may be created, for example
#                    postgresql://restore:...@scratch-db:5432/postgres   (never the production server)
#   PGHOST ...       production, read-only, for the row-count comparison and to record the result
#   OPS_PGUSER/OPS_PGPASSWORD, BACKUP_PASSPHRASE_FILE, BACKUP_S3_URI, BACKUP_S3_ENDPOINT as in backup.sh
set -euo pipefail

started=$(date +%s)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
endpoint_args=()
[[ -n "${BACKUP_S3_ENDPOINT:-}" ]] && endpoint_args=(--endpoint-url "$BACKUP_S3_ENDPOINT")

if [[ "${1:-}" == "--latest" ]]; then
  latest=$(aws "${endpoint_args[@]}" s3 ls "$BACKUP_S3_URI/" | sort | tail -1 | awk '{print $4}')
  aws "${endpoint_args[@]}" s3 cp "$BACKUP_S3_URI/$latest" "$work/backup.gpg" --only-show-errors
  source_name=$latest
else
  cp "$1" "$work/backup.gpg"
  source_name=$(basename "$1")
fi
gpg --batch --quiet --pinentry-mode loopback --passphrase-file "$BACKUP_PASSPHRASE_FILE" --decrypt --output "$work/backup.dump" "$work/backup.gpg"

scratch="restore_check_$(date -u +%Y%m%d%H%M%S)"
psql "$SCRATCH_URL" -v ON_ERROR_STOP=1 -q -c "create database $scratch"
scratch_url="${SCRATCH_URL%/*}/$scratch"
cleanup() { psql "$SCRATCH_URL" -q -c "drop database if exists $scratch" || true; rm -rf "$work"; }
trap cleanup EXIT

problems=()
pg_restore --no-owner --no-privileges --dbname="$scratch_url" "$work/backup.dump" || problems+=("pg_restore reported errors")

# q CONNECTION SQL: an empty connection means production (the PG* variables)
q() { if [[ -n "$1" ]]; then psql "$1" -v ON_ERROR_STOP=1 -tAc "$2"; else psql -v ON_ERROR_STOP=1 -tAc "$2"; fi; }
# The restored database runs as a superuser here, so Row-Level Security does not hide anything from these checks.

# 1. The migration version matches production
restored_version=$(q "$scratch_url" "select id from databasechangelog order by orderexecuted desc limit 1")
prod_version=$(q "" "select id from databasechangelog order by orderexecuted desc limit 1")
[[ "$restored_version" == "$prod_version" ]] || problems+=("migration $restored_version, production $prod_version")

# 2. Every table has about as many rows as production (within the day's changes: 10% or 100 rows)
for table in $(q "$scratch_url" "select table_name from information_schema.tables where table_schema = 'public' and table_name like 'sf\_%' and table_type = 'BASE TABLE'"); do
  r=$(q "$scratch_url" "select count(*) from $table")
  p=$(q "" "select count(*) from $table" 2>/dev/null || echo "$r")
  diff=$(( r > p ? r - p : p - r ))
  limit=$(( p / 10 > 100 ? p / 10 : 100 ))
  (( diff <= limit )) || problems+=("$table: $r rows restored, $p in production")
done

# 3. Every farm business's journal balances
unbalanced=$(q "$scratch_url" "select count(*) from (select tenant_id from sf_journal_lines group by tenant_id having sum(debit) <> sum(credit)) x")
[[ "$unbalanced" == "0" ]] || problems+=("$unbalanced farm businesses with an unbalanced journal")

# 4. Stock quantities equal the sum of their movements
stock=$(q "$scratch_url" "
  with m as (select tenant_id, item_id, location_id, coalesce(lot_id, 0) lot, sum(quantity) q from sf_stock_movements group by 1, 2, 3, 4)
  select count(*) from m full join sf_stock_balances b on b.tenant_id = m.tenant_id and b.item_id = m.item_id
    and b.location_id = m.location_id and coalesce(b.lot_id, 0) = m.lot
  where coalesce(m.q, 0) <> coalesce(b.quantity, 0)")
[[ "$stock" == "0" ]] || problems+=("$stock stock balances differ from their movements")

ms=$(( ($(date +%s) - started) * 1000 ))
if (( ${#problems[@]} == 0 )); then ok=true; details="All checks passed for $source_name"; else ok=false; details=$(IFS='; '; echo "${problems[*]}"); fi
details=${details//\'/}
PGUSER=${OPS_PGUSER:-$PGUSER} PGPASSWORD=${OPS_PGPASSWORD:-$PGPASSWORD} psql -v ON_ERROR_STOP=1 -q \
  -c "insert into sf_ops_checks (kind, ok, details, duration_ms) values ('RESTORE_TEST', $ok, '${details:0:1000}', $ms)"
echo "Restore check: ok=$ok in $((ms / 1000)) s. $details"
[[ "$ok" == "true" ]]
