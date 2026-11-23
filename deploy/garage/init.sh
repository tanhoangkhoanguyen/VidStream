#!/bin/sh
# Once per deployment, after `docker compose up -d garage`; a rerun changes nothing.
# Gives the node its role, then imports the server key from .env (see .env.example) and lets it
# create the per-dataset buckets. The image has no shell, hence `exec` from the host.
set -eu
cd "$(dirname "$0")/.."
set -a
. ./.env
set +a

garage() { docker compose exec -T garage /garage "$@"; }

if garage layout show | grep -q "No nodes currently have a role"; then
  node=$(garage node id -q | cut -d@ -f1)
  garage layout assign -z dc1 -c "${GARAGE_CAPACITY:-100G}" "$node"
  garage layout apply --version 1
fi
if ! garage key info vidstream >/dev/null 2>&1; then
  garage key import --yes -n vidstream "$GARAGE_KEY_ID" "$GARAGE_SECRET_KEY"
fi
garage key allow --create-bucket vidstream
