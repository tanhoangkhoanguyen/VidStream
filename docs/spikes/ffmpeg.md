# ffmpeg clipping and merge

Goal: frame-accurate clips in one decode pass, and a lossless merge of neighbouring clips. Checked 2026-11-12 on ffmpeg **8.1** (Windows) and **6.1.1** (Ubuntu 24.04, the host OS).

## Commands

Clip (one pass, all clips of a video; `T` = clip boundaries in seconds, e.g. `12,24,36`):

```sh
ffmpeg -i in.mp4 -map 0:v:0 -map 0:a? -vf fps=30 \
  -c:v libx264 -preset veryfast -crf 23 -pix_fmt yuv420p -force_key_frames "$T" -c:a aac \
  -avoid_negative_ts disabled \
  -f segment -segment_times "$T" -segment_time_delta 1/(2*fps) -reset_timestamps 1 \
  -segment_format_options movflags=+faststart clip_%03d.mp4
```

Merge two neighbours (no re-encode):

```sh
printf "file 'clip_000.mp4'\nfile 'clip_001.mp4'\n" > list.txt   # paths are relative to list.txt
ffmpeg -f concat -safe 0 -i list.txt -c copy \
  -bsf:v setts=pts=PTS-STARTPTS:dts=DTS-STARTPTS -movflags +faststart merged.mp4
```

## Results

Sources (40 s): CFR with audio, no audio, VFR (25 fps then 30 fps; 48 s after concat), rotated 90°. Clips at 12/24/36 s; merge of clips 0 + 1.

| Check | 8.1 | 6.1.1 |
|---|---|---|
| Frame count per clip = seconds × 30 (360, 360, 360, last = rest) | 16/16 | 16/16 |
| Merged clip = 720 frames | 4/4 | 4/4 |
| First frame is a keyframe, video starts at 0.000 | all | all |
| Decode errors (`-f null`) | 0 | 0 |
| Rotated source → portrait 360×640 pixels (rotation applied, no metadata) | yes | yes |

Audio starts at 0 and is 50–60 ms shorter than the video per clip (AAC frames of 1024 samples). Fine for labeling.

Labeling UI (2026-11-11): plain, last, merged (seek across the join) and rotated clips play and seek.

## Why each flag

| Flag | Without it |
|---|---|
| `-vf fps=30` | VFR sources keep variable timing; Label Studio needs a fixed fps (see the label config spike). |
| `-avoid_negative_ts disabled` | First clip starts at 0.066 s. |
| `-bsf:v setts=…` on merge | Merged clip starts at 0.021 s. |
| `-segment_time_delta 1/(2*fps)` | Not reproduced at 30 fps with whole-second boundaries (all clips correct). Kept: the ffmpeg docs recommend it with `-force_key_frames`. |

Merging with `-c copy` works because every clip starts on a keyframe and all clips share codec settings.

## Chunks from a Box URL

2026-11-12. Workers clip a chunk of clips per run, straight from the pre-signed Box URL (no token on the command line). `S`/`E` = chunk start/end in seconds, `T` = clip boundaries relative to `S`, `N` = index of the first clip.

```sh
ffmpeg -ss "$S" -to "$E" -i "$URL" -map 0:v:0 -map 0:a? -vf fps=30 \
  -c:v libx264 -preset veryfast -crf 23 -pix_fmt yuv420p -force_key_frames "$T" -c:a aac \
  -avoid_negative_ts disabled -t "$((E - S))" \
  -f segment -segment_times "$T" -segment_time_delta 1/(2*fps) -segment_start_number "$N" -reset_timestamps 1 \
  -segment_format_options movflags=+faststart clip_%03d.mp4
```

Real lab video: HEVC 1080p, 29.97 fps, 55 min, 8.85 GB, `moov` at the end. 60 s chunks (5 clips) on a laptop over home internet:

| Chunk | From Box | Time | Clips |
|---|---|---|---|
| 0–60 s | 167 MiB | 29–39 s | 5 × 360 frames |
| 60–120 s (own run, new URL) | 164 MiB | 32 s | 5 × 360 frames |
| 3000–3060 s | 158 MiB | 30 s | 5 × 360 frames |
| 0–120 s, one run | 317 MiB | 59 s | 10 × 360 frames |

- Box traffic ≈ the chunk's share of the file + ~7 MiB (index). A late chunk seeks directly with Range.
- **Boundary is frame-exact:** clip 5 from the 60–120 s run vs clip 5 from the single run: PSNR 44.8 dB at offset 0, 39.5 dB at ±1 frame (synthetic moving source: 40.9 vs 24.2 dB).
- **`-t` (output) is required:** without it the last clip of a chunk has 361–362 frames when the source is 29.97 fps (reproduced locally; fixed with `-t`).
- **Output: 10.9 MB per minute** (1080p, CRF 23, `veryfast`). Input for #65.
- Expired URL → `Server returned 404 Not Found`, non-zero exit (127 on 8.1/Windows). The worker retries the chunk with a fresh URL. A URL lives 10–15 min, far longer than a chunk.
- First attempt only: the byte counter (a local proxy) showed ~8.6 GB per 60 s chunk and 6 min per run. Not reproduced in 5 later runs; most likely the proxy. Guard anyway: a per-chunk timeout.
