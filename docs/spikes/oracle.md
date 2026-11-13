# Oracle Cloud Always Free ARM

Goal: confirm the Always Free A1 instance can run VidStream workers (ffmpeg clipping + Label Studio) within free-tier limits. Benchmarked 2026-11-13 on Ubuntu 24.04 aarch64.

## Instance

| Field | Value |
|---|---|
| Shape | VM.Standard.A1.Flex |
| CPU | 2 OCPU — Ampere Altra (Neoverse-N1) 3.0 GHz |
| RAM | 12 GB |
| Disk | 45 GB boot volume (41 GB free after OS) |
| Region | us-ashburn-1 (AD-1) |
| OS | Ubuntu 24.04 aarch64 |

Always Free A1 limits: 4 OCPU / 24 GB total per tenancy, splittable across instances.

## Capacity

A1 capacity is not guaranteed — "Out of host capacity" is common. A retry script cycling 3 ADs every 5 min succeeded after 4 attempts (~20 min). Community reports range from minutes to days. Once running, the instance stays unless idle-reclaimed (see below).

## ffmpeg benchmarks

Source: 5 min 720p H.264 test video (113 MB).

### Stream-copy clip (no re-encode)

| Parallel | Wall time | Notes |
|---|---|---|
| 1 | 127 ms | Near-instant; I/O bound |
| 2 | 159 ms | Scales perfectly |
| 3 | 210 ms | Negligible overhead |

### Re-encode clip (libx264 -preset fast -crf 28, 30s segments)

| Parallel | Wall time | CPU | Notes |
|---|---|---|---|
| 1 | 15.0 s | ~100% | One core saturated |
| 2 | 29.5 s | ~200% (both cores) | Linear scale — no gain vs sequential |
| 3 | 45.1 s | ~200% | Cores saturated, clips queue |

**Takeaway:** stream-copy clipping is effectively free. Re-encode is CPU-bound; 2 OCPU handles 1 concurrent transcode at full speed. VidStream clips use stream-copy (segment muxer), so this is not a bottleneck.

## Label Studio on ARM

Image: `heartexlabs/label-studio:1.16.0` (multi-arch, includes aarch64).

| Metric | Cold start | Warm start |
|---|---|---|
| Time to healthy | 49 s | 36 s |
| Memory | 275 MB | 271 MB |
| CPU at idle | 1.4% | ~0% |

Startup is slower than x86 (~20s) but acceptable for a long-lived container. 275 MB leaves 11+ GB for ffmpeg workers and OS.

## Idle reclamation

Oracle may reclaim Always Free instances that are "idle" (CPU < threshold over 7 days). Mitigations:
- A lightweight cron job (`*/5 * * * * dd if=/dev/urandom bs=1M count=1 of=/dev/null`) prevents reclamation.
- VidStream's own worker activity (ffmpeg, Label Studio) should keep utilisation above threshold.

## Budget alerts

Always Free tier has no billing — budget alerts are not available (and not needed). If the instance is accidentally upgraded to paid, OCI sends email.

## Limits relevant to VidStream

| Resource | Always Free limit |
|---|---|
| A1 OCPU | 4 total |
| A1 RAM | 24 GB total |
| Boot volume | 200 GB total (2 × 100 GB, or 1 × 200 GB) |
| Outbound data | 10 TB/month |
| Object Storage | 20 GB |
| Load Balancer | 1 flexible (10 Mbps) |
| VCN | 2 |

## Decision

The Always Free A1 (2 OCPU / 12 GB) is sufficient for VidStream Phase 1:
- ffmpeg stream-copy clipping is <200ms — no bottleneck.
- Label Studio runs comfortably in ~275 MB.
- 41 GB disk is enough for temp clips (cleaned after upload).
- Remaining 2 OCPU / 12 GB available for a second instance if needed.

Risk: capacity unavailability at launch time. Mitigation: retry script, or Terraform with retry logic.
