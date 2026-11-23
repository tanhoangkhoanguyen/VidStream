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

## Workers: throughput by worker count (#113)

`WorkerEndToEndIT.throughputByWorkers`: the web node and 1, 2 or 3 one-slot workers, each its own application, all on one machine, over the HTTP API. Each run: 4 generated 720p videos of 120 s (H.264, `moov` at the end), served with Range like Box: 4 PROBE and 24 CHUNK jobs, clips uploaded to Garage.

Setup: Windows 11 laptop (12 logical cores), Docker Desktop. 2026-11-23.

| Workers | Wall time | Video s / s | Machine CPU |
|---|---|---|---|
| 1 | 52.3 s | 9.19 | 80% |
| 2 | 42.7 s | 11.25 | 93% |
| 3 | 42.1 s | 11.40 | 97% |

One worker already takes most of the CPU: x264 is multithreaded. Past 2 workers the machine is saturated, so throughput stops growing. The bottleneck is CPU, not the queue or the API, and workers add throughput only with their own machines. Scaling across machines is measured with the GCP workers (#137).

Failure scenarios, each passing 5 runs in a row:
- Worker killed mid-chunk: its lease holds the chunk until it expires, then another worker redoes it; no duplicate clip, and the killed worker's late report is refused (`WorkerEndToEndIT`).
- Stale worker's late CHUNK commit: rejected; its uploads are never referenced and the sweep deletes them (`ChunkIT`).
- All chunks of a video committing at once: exactly one makes the units, 20 rounds per run (`ChunkCommitIT`).

Rerun: `VIDSTREAM_BENCHMARK=true ./gradlew :server:integrationTest --rerun --tests '*WorkerEndToEndIT.throughputByWorkers'`, then read the `BENCHMARK` lines in the test output.
