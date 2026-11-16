# Benchmarks

## Job queue: claim under contention (#105)

`JobQueueCommitIT.concurrentClaimersTakeEachJobExactlyOnce`: 5,000 jobs, 100 virtual-thread claimers, each on its own connection, loop claim → complete (no work) until the queue is empty.

Setup: Windows 11 laptop (12 logical cores), Docker Desktop, `postgres:17` with `fsync=off`. 10 runs, 2026-11-16.

| Metric | Median | Range |
|---|---|---|
| Duplicate claims | 0 | 0 in every run |
| Throughput (claim + complete) | 759 jobs/s | 577–1,274 |
| Claim latency p50 | 57 ms | 34–65 ms |
| Claim latency p99 | 357 ms | 227–453 ms |

Needed: a CHUNK job runs for minutes, so even 10 workers claim well under 1 job/s. Latency comes from 100 claimers contending for the head of the same index; real workers are far fewer.

Rerun: `./gradlew :server:integrationTest --rerun --tests '*JobQueueCommitIT.concurrent*'`, then read the `BENCHMARK` line in the test output.
