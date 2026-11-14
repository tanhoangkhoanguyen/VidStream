#!/bin/bash
# Runs once, on an empty data volume: one database and owner per service.
set -euo pipefail

create() {
  psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -v name="$1" -v pw="$2" <<'SQL'
CREATE USER :"name" PASSWORD :'pw';
CREATE DATABASE :"name" OWNER :"name";
SQL
}

create vidstream "$VIDSTREAM_DB_PASSWORD"
create ls_dev "$LS_DEV_DB_PASSWORD"
