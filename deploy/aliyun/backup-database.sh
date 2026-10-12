#!/usr/bin/env bash
# Read-only PostgreSQL 18 export. Credentials come from PG* environment variables.
set -euo pipefail
umask 077

if [[ $# != 1 ]]; then
  echo 'Usage: PGHOST=... PGPORT=... PGDATABASE=... PGUSER=... PGPASSWORD=... PGSSLMODE=require bash backup-database.sh NEW_DIRECTORY' >&2
  exit 2
fi
for tool in pg_dump pg_restore psql; do
  command -v "$tool" >/dev/null || { echo "Missing $tool (PostgreSQL 18 required)" >&2; exit 2; }
done
[[ $(pg_dump --version) == *' 18.'* ]] || { echo 'PostgreSQL 18 client required' >&2; exit 2; }
: "${PGHOST:?Set the approved source host}"
: "${PGPORT:?Set the approved source port}"
: "${PGDATABASE:?Set the source database}"
: "${PGUSER:?Set the source user}"
: "${PGPASSWORD:?Set the source password through a private environment}"
[[ ${PGSSLMODE:-} == require || ${PGSSLMODE:-} == verify-full ]] || {
  echo 'Require TLS for the Railway source connection' >&2; exit 2;
}
[[ ! -e $1 ]] || { echo 'Output directory already exists; refusing to overwrite' >&2; exit 2; }
script_dir=$(cd "$(dirname "$0")" && pwd)
export PGCONNECT_TIMEOUT=10
export PGOPTIONS='-c default_transaction_read_only=on -c statement_timeout=300000 -c lock_timeout=5000'
[[ $(psql -XAtc 'SHOW server_version_num') == 18???? ]] || {
  echo 'Source is not PostgreSQL 18; recheck the source identity' >&2; exit 2;
}
[[ $(psql -XAtc "SELECT to_regclass('public.qos_conversation_session') IS NOT NULL AND to_regclass('public.qos_conversation_message') IS NOT NULL AND to_regclass('public.qos_user_account') IS NOT NULL") == t ]] || {
  echo 'QuestionOS tables not found; refusing to export a different application' >&2; exit 2;
}
mkdir -m 700 "$1"
pg_dump --format=custom --no-owner --no-acl --file="$1/database.dump.partial"
pg_restore --list "$1/database.dump.partial" > "$1/database-toc.txt"
mv "$1/database.dump.partial" "$1/database.dump"
psql -X -f "$script_dir/inventory.sql" > "$1/inventory.txt"
# Counts are a separate snapshot. Exact comparison requires the final write freeze.
if command -v sha256sum >/dev/null; then
  (cd "$1" && sha256sum database.dump > SHA256SUMS)
else
  (cd "$1" && shasum -a 256 database.dump > SHA256SUMS)
fi
echo 'Export and archive-list check complete; isolated restore is still required.'
