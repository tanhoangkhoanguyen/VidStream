# Access gate in front of Label Studio

Goal: only Box-verified members of a dataset reach that dataset's Label Studio, including signup pages and clip files. Checked 2026-11-12: Caddy 2.10.2 (`local_certs`, HTTPS), two Label Studio instances (`a`, `b`), and a mock of VidStream's `/internal/gate`.

## Caddyfile (local)

```caddyfile
{
	local_certs
}

vidstream.localhost {
	handle /internal/* {
		respond 404
	}
	reverse_proxy web:8000
}

a.vidstream.localhost, b.vidstream.localhost {
	forward_auth web:8000 {
		uri /internal/gate
	}
	@a host a.vidstream.localhost
	reverse_proxy @a ls-a:8000
	@b host b.vidstream.localhost
	reverse_proxy @b ls-b:8000
}
```

The gate reads the VidStream session cookie (`vs_session`, `Domain=.vidstream.localhost`) and the dataset from `X-Forwarded-Host` (`a.…` → `a`), then answers 200 or 401. Caddy forwards the request only on 200.

## Results

| Test | Result |
|---|---|
| No session: `/`, `/user/login/`, `/user/signup/?token=…`, `/api/projects`, `/data/local-files/…`, `/static/…` | 401 on all |
| Session for `a` on host `b` (same paths) | 401 on all |
| Session for `a` on host `a`: Label Studio login, `whoami`, create project (CSRF over HTTPS) | 200, 200, 201 |
| Clip through the gate with `Range` | 206 |
| Label Studio `sessionid` from `a` without `vs_session` | 401 |
| Session for `a` and `b` on host `b` | 200 |
| `https://vidstream.localhost/internal/gate` from outside | 404 |

- Label Studio cookies (`sessionid`, `csrftoken`) are host-only: instance `a`'s session never reaches `b`.
- CSRF works over HTTPS behind Caddy **without** `LABEL_STUDIO_HOST` (tested on `b`). Set it anyway, for absolute URLs Label Studio generates.

## Consequences for the build

- The gate runs on every request, including each clip `Range` request: keep it a cheap session lookup (no Box call per request).
- `vs_session`: `HttpOnly`, `Secure`, `SameSite=Lax`, `Domain=.<domain>`. Its name must not clash with Label Studio's `sessionid`.
- Label Studio also receives `vs_session` in the `Cookie` header. Harmless (it ignores it); strip it later if needed.

## Open (P10-03)

- Certificates for `<dataset>.<name>.duckdns.org`: one HTTP-01 certificate per host (no plugin) or a wildcard via DNS-01 (needs a Caddy build with the DuckDNS module).
