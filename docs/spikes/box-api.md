# Box API

Goal: the server reads a USF Box dataset for a user, read-only, without USF IT. Checked 2026-11-11/12 with a temporary "User" (OAuth 2.0) app and the owner's USF account on dataset folder `335647443718`. Only `GET` on data. Fixtures: `server/src/test/resources/fixtures/box/*.json` (names and tokens redacted).

## App and tokens

| Topic | Result |
|---|---|
| App | A USF account can create a "User" app in the Developer Console; USF does not block the consent screen. |
| Scopes | **Write** is required to download content (Read only → `/content` 403 `access_denied_insufficient_permissions`). |
| Access token | ~1 h (`expires_in` 3600–3800). |
| Refresh | Returns a new refresh token each time, but the **old one is still accepted** (right away and after 2 min). |
| Revoke | `POST /oauth2/revoke` on one token ends the **whole grant**: every access/refresh pair from that sign-in → 401. |
| Downscope | Token exchange to `item_download item_preview` with `resource = …/files/{id}` → 200, `restricted_to` that file. With it: that file's info and content work; another file and folder listings → 404. |
| Rate limits (docs) | 1000 calls/min per user; search 6/s and 60/min per user, 12/s per enterprise. 429 with `retry-after` (seconds) and `code: rate_limit_exceeded`. Normal responses carry no rate-limit headers (seen: `box-request-id`). |

## Reading the dataset

| Call | Result |
|---|---|
| `GET /folders/{id}?fields=…` | Shared dataset folder sits under the user's root (`path_collection` = `["0"]`). |
| `GET /folders/{id}/items?limit=1000&fields=id,type,size,extension` | Names can be left out. Whole tree: **171 calls (one per folder), depth 4, 74 s** sequential. |
| `GET /search?query=mp4&ancestor_folder_ids=…&file_extensions=mp4` | 38 results, same as the listing. `query=*` returns nothing; `size_range` works. |
| No access / unknown folder | **404 `not_found`** for info, items and search (not 403). Search with ancestor `1` → 200, empty. |
| `GET /files/{id}?fields=sha1,size,extension` | `sha1` present; matches the SHA-1 of the downloaded file (4.7 MB). |

Dataset contents: 38 `.mp4` (248 GiB), 2 `.mpg` (9.8 GiB), 28 `.lrf` (54 GiB, DJI low-res proxies: skip), documents and images. A sample video: HEVC 1920×1080 at 29.97 fps, AAC audio, data tracks and an MJPEG cover; 55 min, 8.85 GB.

## Download URL

| Topic | Result |
|---|---|
| `GET /files/{id}/content` (no redirect) | 302 to `dl.boxcloud.com`; the URL itself is the credential (no query string). |
| Using it | Works **without** `Authorization`; `Range` → 206. |
| Lifetime | Works at 10 min, **404 at 15 min**. Still works after the grant is revoked. |
| In ffmpeg | See [ffmpeg.md](ffmpeg.md#chunks-from-a-box-url): chunks seek with Range; an expired URL fails with 404. |

## Consequences for the build

- Treat the download URL like a token: never log it; request a fresh one per chunk attempt.
- Disconnect = revoke (ends every token of that sign-in). Still refresh under a row lock: Box may enforce single-use refresh tokens later.
- A full rescan is cheap (~171 calls). Video extensions: `mp4`, `mpg` (skip `lrf`).
- "Not found" and "no access" look the same (404): show one message.
