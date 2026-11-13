# Label Studio API and webhooks

Label Studio 1.23.2, no Redis. Checked 2026-11-12. Fixtures: `server/src/test/resources/fixtures/label-studio/webhook-{created,updated,deleted}.json` (from UI actions; `project` trimmed to its `id`).

## Auth

| Topic | Result |
|---|---|
| Personal access token | `POST /api/token/` → `{"token": <JWT refresh>}`. **One per user**: a second create → 409. `GET /api/token/` returns it without the signature, so it is shown once. Lifetime ~200 years. |
| Access token | `POST /api/token/refresh {"refresh": …}` → `{"access": …}`, lifetime **300 s**. Use `Authorization: Bearer <access>`. The refresh token itself as Bearer → 401. |
| Legacy token | `legacy_api_tokens_enabled: false` by default: `Authorization: Token …` → 401. |

The server keeps one PAT per instance (encrypted), refreshes the access token when it is near expiry, and retries once on 401.

## Tasks

| Call | Result |
|---|---|
| `POST /api/projects/{id}/import` (JSON list) | 201 `{"task_count": 4, …}`. **No task IDs.** Put our own key in `data` (e.g. `clip_id`) and list afterwards. |
| `GET /api/tasks?project=&page_size=&page=` | `{"tasks": [...], "total": …}`; each task has `id`, `data`, `is_labeled`, `total_annotations`, `overlap`. A page past the end → 404. |
| `DELETE /api/tasks/{id}` | 204, also for a task locked by an annotator. That annotator's submit then → 404. |

## Webhooks

| Topic | Result |
|---|---|
| Network | Reaches the server only on the `hooks` network (`192.168.250.0/29`), carved out of the SSRF list: [deploy/label-studio/ssrf.env](../../../deploy/label-studio/ssrf.env). A private IP (`172.17.0.1`) → 403 "reserved network address". |
| Host name | Dotless hosts (`http://echo:8000`) → 400 "Enter a valid URL". Use an alias such as `hooks.internal`. |
| Delivery | Synchronous in the annotator's request (no Redis), `WEBHOOK_TIMEOUT=1.0` s. **No retry**: receiver returns 500 twice → 2 deliveries, none later. |
| Receiver down | The annotator's submit still succeeds (201) but takes ~5.6 s. |
| `PATCH /api/webhooks/{id}` without `actions` | Clears all actions. Always send the full definition. |
| Headers | Custom headers arrive (`X-Vidstream-Secret`). |

Payloads:
- `ANNOTATION_CREATED` / `ANNOTATION_UPDATED`: `action`, `annotation` (`id`, `task`, `result`, `completed_by` = user id, `updated_at`), `task` (`id`, `data`, `is_labeled`, `overlap`), `project` (full object).
- `ANNOTATIONS_DELETED`: `action`, `project`, `annotations: [{"id": 2}]` only. **No task ID.**

## Consequences for the server

- Store the Label Studio annotation ID per unit: a delete only names annotation IDs.
- Map `completed_by` to an email through the Label Studio users API (not tested).
- Webhooks are at-most-once: inbox + periodic reconciliation from `GET /api/tasks`.
- Keep the webhook receiver fast and up; it sits in the annotator's request path.
