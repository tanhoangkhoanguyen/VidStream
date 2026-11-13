# Label Studio label config

Label Studio 1.23.2. Labeled in the UI on 2026-11-11 and 2026-11-12. Fixture: [annotation-timeline-choices.json](../../../server/src/test/resources/fixtures/label-studio/annotation-timeline-choices.json) (`GET /api/annotations/{id}`, after submit and update).

## Config

```xml
<View>
  <Header value="Keep: mark the part to keep (optional, whole clip by default). Merge: the movement continues into the neighbouring clip."/>
  <Video name="video" value="$video" frameRate="$fps"/>
  <TimelineLabels name="segments" toName="video">
    <Label value="Head movement"/>
    <Label value="Hand movement"/>
    <Label value="Foot tapping"/>
    <Label value="Keep"/>
  </TimelineLabels>
  <Choices name="clip_class" toName="video" choice="single" required="true">
    <Choice value="Reacting"/>
    <Choice value="Not reacting"/>
    <Choice value="Unclear"/>
  </Choices>
  <Choices name="merge" toName="video" choice="single">
    <Choice value="Merge with previous clip" alias="PREV"/>
    <Choice value="Merge with next clip" alias="NEXT"/>
  </Choices>
</View>
```

Label names are placeholders until the lab confirms them.

## Result JSON

- One result item per region: `{"from_name": "segments", "type": "timelinelabels", "value": {"ranges": [{"start": 31, "end": 60}], "timelinelabels": ["Hand movement"]}}`. Keep is the same shape with `["Keep"]`.
- Choices store the **alias** when set: `{"from_name": "merge", "value": {"choices": ["NEXT"]}}`.
- `ranges` are **frame numbers, 1-based, end inclusive**: the UI counter shows "1 of 300" on the first frame; frames 31–60 show duration 30.

## Frames to seconds

```
start_s = (start - 1) / fps
end_s   = end / fps          # exclusive
```

Verified with a frame-number overlay at 30 fps: UI frame 31 shows file frame 30 at t = 1.000 s, so frames 31–60 = [1.0, 2.0) s.

**`fps` must be the task data value, and it must match the file.** Label Studio converts frames with `frameRate="$fps"`, not with the file's real rate. A 10 s, 30 fps file with task `fps: 25` shows "1 of 250", and frame 20 seeks to 0.76 s (= 19/25). VidStream clips are always re-encoded at a fixed fps, and the task carries that value.

## UI notes

- To draw a region: press Esc (no region selected), pick a label, then drag on that label's timeline row. With a region selected, clicking a label **relabels** that region instead.
- Keep shares the `segments` timeline (decided: one timeline for annotators). An earlier failure to draw on a second `TimelineLabels` was likely the relabel behaviour above; not re-tested.

## Open (parser, P6)

- Several Keep regions in one clip: one sample per Keep region, or reject? Decide in the parser issue.
