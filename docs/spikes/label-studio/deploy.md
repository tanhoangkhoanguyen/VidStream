# Label Studio deploy

`heartexlabs/label-studio:1.23.2` (amd64 + arm64) on `postgres:17`. Checked 2026-11-11.

## Findings

| Topic | Result |
|---|---|
| Database | `DJANGO_DB=default` + `POSTGRE_HOST/PORT/NAME/USER/PASSWORD`. `get_env(X)` reads `LABEL_STUDIO_X`, `HEARTEX_X`, then `X`. |
| Many instances, one Postgres | Works: one database per instance (`ls_<dataset>`). |
| Production mode | `label-studio-migrate` (one-shot, `locked_migrate`) then `label-studio-uwsgi` (port 8000). |
| Bundled nginx | **Not needed.** uwsgi alone serves `/static/`, `/react-app/` and `/data/local-files/` (Range 206). Caddy proxies straight to `:8000`. |
| Local files | `LOCAL_FILES_SERVING_ENABLED=true` + `LOCAL_FILES_DOCUMENT_ROOT`. A file is served only after a Local Storage connection exists for its folder (404 before). The storage path must be inside the document root. |
| Access | Anonymous `/data/local-files/` → 401. Signup without invite link → 403 (`LABEL_STUDIO_DISABLE_SIGNUP_WITHOUT_LINK=true`). |
| Tenancy | Community Edition: every active user sees every project and file. Isolation = one instance per dataset. |
| `SECRET_KEY` | If unset, generated into `<data>/.env` (mode 600). Set it explicitly per instance instead. |
| Labeling UI | Clips play and seek, including a copy-merged clip across the join and a rotated clip (portrait). |
| Startup stability | Random Python errors while loading feature flags and YAML at startup (2026-11-12): 3 of ~16 starts exited with code 1; once a uwsgi worker died with signal 11 (respawned). Cause unknown. Use `restart: unless-stopped` and a healthcheck; re-check on the ARM host (see [oracle.md](../oracle.md)). |
| Healthcheck | `wget -qO- 127.0.0.1:8000/health` → `{"status": "UP"}`. Not `localhost`: it resolves to `::1`, and uwsgi listens on IPv4 only. |

## RAM (idle, after login and a few requests)

| Container | RAM |
|---|---|
| uwsgi, `UWSGI_PROCESSES=2` | ~410 MiB each, with 2 instances running |
| Bundled nginx (if used) | ~11 MiB |
| Postgres (2 databases) | ~58 MiB |

Planning figure: **~420 MiB per dataset** + shared Postgres.

## Production shape (per dataset)

```yaml
x-ls-env: &ls-env
  DJANGO_DB: default
  POSTGRE_HOST: db
  POSTGRE_PORT: "5432"
  POSTGRE_NAME: ls_<dataset>
  POSTGRE_USER: ls_<dataset>
  POSTGRE_PASSWORD: ${LS_<DATASET>_DB_PASSWORD}
  SECRET_KEY: ${LS_<DATASET>_SECRET_KEY}
  LOCAL_FILES_SERVING_ENABLED: "true"
  LOCAL_FILES_DOCUMENT_ROOT: /label-studio/files
  LABEL_STUDIO_DISABLE_SIGNUP_WITHOUT_LINK: "true"
  USE_NGINX_FOR_UPLOADS: "false"   # no nginx; we never upload through the UI
  UWSGI_PROCESSES: "2"

services:
  migrate-<dataset>:
    image: heartexlabs/label-studio:1.23.2
    command: label-studio-migrate
    environment: *ls-env
  ls-<dataset>:
    image: heartexlabs/label-studio:1.23.2
    command: label-studio-uwsgi
    environment: *ls-env
    depends_on: { migrate-<dataset>: { condition: service_completed_successfully } }
    volumes:
      - ls-<dataset>:/label-studio/data
      - /srv/vidstream/clips/<dataset>:/label-studio/files:ro
```

## Open

- Behind Caddy on `https://<dataset>.<domain>`: `LABEL_STUDIO_HOST` and CSRF trusted origin. Checked in [access-gate.md](../access-gate.md).
- A Postgres user per dataset (least privilege) is the plan; the spike used the superuser.
