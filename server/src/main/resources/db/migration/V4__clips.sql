-- A probed video's clip plan, stored so a later change to the dataset's clip settings does not
-- change it. sha256 and size_bytes are set when the clip's chunk commits; the file lives at a path
-- derived from (dataset, video, clip_index).
CREATE TABLE clips (
  video_id   bigint NOT NULL REFERENCES videos,
  clip_index int    NOT NULL CHECK (clip_index >= 0),
  start_ms   bigint NOT NULL CHECK (start_ms >= 0),
  end_ms     bigint NOT NULL CHECK (end_ms > start_ms),
  sha256     text,
  size_bytes bigint CHECK (size_bytes > 0),
  PRIMARY KEY (video_id, clip_index),
  CHECK ((sha256 IS NULL) = (size_bytes IS NULL))
);
