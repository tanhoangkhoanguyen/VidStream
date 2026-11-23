# Clip storage in self-hosted S3 (Garage)

Goal (#152): Label Studio serves clips from a self-hosted S3 store, so workers upload over HTTP and need no shared disk. Checked 2026-11-19 with Garage **v2.5.0** (single node, SQLite metadata) and Label Studio **1.23.2**, local Docker; a generated 12 s test clip.

## Garage

| Topic | Result |
|---|---|
| Image | `dxflrs/garage:v2.5.0`, multi-arch (arm64 included). One binary, one TOML file; layout, bucket and keys by CLI. |
| Resources | 10 MiB RAM idle; 19 MiB and ~40 % of one core during a 100 MiB upload (1.5 s, local). |
| Keys | Permissions are per bucket (read / write / owner), not per prefix. Worker key RW: `PUT` 200. Label Studio key R: `PUT` 403. No key: `GET` 403. |
| Range | `GET` with `Range` → 206, correct `Content-Range`. |

## Label Studio S3 storage

| Topic | Result |
|---|---|
| Custom endpoint | `s3_endpoint`, `region_name`, keys; path style works. |
| SSRF guard | Endpoint on a private IP → 403 "URL resolves to a reserved network address". Works when Garage sits on the carved-out `hooks` subnet (`192.168.250.0/29`, [ssrf.env](../../deploy/label-studio/ssrf.env)) under an alias with a dot (`s3.internal`). |
| Tasks | Imported with `"video": "s3://<bucket>/<key>"`; the storage only resolves them (no sync needed). |
| `presign: true` | `/tasks/{id}/resolve/` → 303 to a presigned URL on the **endpoint host** (`s3.internal:3900`), `X-Amz-Expires=900` (`presign_ttl` 15 min). The presigned URL serves Range (206). A browser cannot reach an internal host: this mode needs Garage public. |
| `presign: false` (proxy) | Label Studio streams the object: 200 full, `Range` → 206 with `Content-Range`. Anonymous → 401. Garage stays private. |

Not checked here: playback in the labeling UI (no browser in this run; only HTTP 200/206).

## Presigned PUT (#153)

Checked 2026-11-22, same Garage, AWS SDK `S3Presigner` with `contentLength` and `checksumSHA256` set: the URL signs `content-length;host;x-amz-checksum-sha256`; the worker sends both headers.

| Upload | Result |
|---|---|
| Right body | 200; stored as sent |
| Same length, other bytes | 400 `InvalidDigest`: Garage checks the SHA-256 |
| Other length | 403 `Invalid signature` |
| Checksum header left out | 400 "signed header `x-amz-checksum-sha256` is not present" |

So a URL writes one key, with exactly the bytes the server signed for, until it expires: a worker holds no key, and an upload cannot be swapped or cut short. The signature covers the host: presign with the host workers reach, and keep it unchanged through the proxy.

## Consequences for the build

- **Proxy mode** (`presign: false`): Garage is never exposed; media go through Label Studio, behind the access gate (#119). Cost: Label Studio streams the bytes (~11 MB per clip-minute, a few annotators per dataset).
- **One bucket per dataset** (`clips-<dataset>`), one read key per Label Studio instance: keys cannot be limited to a prefix, and a project admin could import an `s3://` URI of another dataset.
- Garage joins the `hooks` network as `s3.internal`. The `/29` holds 6 addresses: widen it before many datasets (#114) and regenerate `ssrf.env`.
- Workers hold no key: the server presigns one `PUT` per clip, with its length and SHA-256 (#153).
