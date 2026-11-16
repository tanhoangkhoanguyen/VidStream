# Failure modes

What VidStream does when a dependency fails. **Fail-closed**: stop rather than risk wrong access or wrong data. The linked issue implements each row.

| Failure | Stance | Behaviour | Users see | Issue |
|---|---|---|---|---|
| Box 429 | Wait | Honour `Retry-After` | Slower listing and clipping | #97 |
| Box 5xx or unreachable | Retry, then fail | Bounded retries with jitter; a failed job attempt is retried later | Sign-in error; clipping pauses | #97 |
| A member's Box token revoked or expired | Fail over, then fail-closed | Tokens cleared; workers use another member's token. No member left: the dataset's jobs are released with backoff, not failed | Report: "a member must sign in" | #96, #107 |
| Download URL expires mid-chunk | Retry | Attempt fails; retried with a new URL | Nothing | #109 |
| Worker crash mid-chunk | Retry | Lease expires; another worker redoes the chunk; the fencing token blocks the stale worker's commit; no duplicate clips | The chunk finishes later | #104, #110, #113 |
| Crash during task import | Retry | Reconcile with Label Studio before importing; no duplicate tasks | Nothing | #118 |
| Free disk below the threshold | Fail-closed | Workers claim only cleanup and merge jobs | Clipping pauses | #111 |
| Webhook lost (Label Studio sends once, 1 s timeout, no retry) | Eventual | Inbox plus periodic reconciliation from the Label Studio API | Report lags until the next reconcile run | #122, #127 |
| Label Studio down | Fail-closed, per dataset | Caddy returns 502 for that host | Annotators of that dataset get an error page; other datasets work | #114 |
| VidStream web down | Fail-closed | The gate cannot answer, so Caddy lets no request into any Label Studio | Nobody can sign in or label | #99 |
| Postgres down | Fail-closed | Health DOWN; requests and job claims fail; leases expire and the work is redone after recovery | Errors until Postgres is back | #103 |
