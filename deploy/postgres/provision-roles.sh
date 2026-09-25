#!/bin/sh
set -eu

read_secret() {
  secret_file="$1"
  if [ ! -r "$secret_file" ]; then
    printf 'Required database secret file is not readable: %s\n' "$secret_file" >&2
    exit 1
  fi
  if ! tr -d '\r\n' < "$secret_file" | cmp -s "$secret_file" -; then
    printf 'Database password secret files must not contain CR or LF: %s\n' "$secret_file" >&2
    exit 1
  fi
  cat "$secret_file"
}

PGPASSWORD="$(read_secret "${SENTINELOPS_DB_ADMIN_PASSWORD_FILE:-/run/secrets/sentinelops_db_admin_password}")"
SENTINELOPS_DB_MIGRATOR_PASSWORD="$(read_secret "${SENTINELOPS_DB_MIGRATOR_PASSWORD_FILE:-/run/secrets/sentinelops_db_migrator_password}")"
SENTINELOPS_DB_APP_PASSWORD="$(read_secret "${SENTINELOPS_DB_APP_PASSWORD_FILE:-/run/secrets/sentinelops_db_app_password}")"
export PGPASSWORD SENTINELOPS_DB_MIGRATOR_PASSWORD SENTINELOPS_DB_APP_PASSWORD

script_directory=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec psql \
  --no-psqlrc \
  --set=ON_ERROR_STOP=1 \
  --host="${SENTINELOPS_DB_HOST:-postgres}" \
  --port="${SENTINELOPS_DB_PORT:-5432}" \
  --username="${SENTINELOPS_DB_ADMIN_USER:-sentinelops}" \
  --dbname="${SENTINELOPS_DB_NAME:-sentinelops}" \
  --file="$script_directory/init/001_roles.sql"
