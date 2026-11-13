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

## Open

- MB per minute of output on a real lab video (input for #65). Measured in P0-07, which reads a real video from Box.
