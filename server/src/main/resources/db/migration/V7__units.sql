-- int4range equality with = on bigint in one GiST index (trusted extension: no superuser needed).
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- What annotators label: one clip at first; a merge (Phase 6) replaces neighbours with one unit.
CREATE TABLE units (
  id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  video_id         bigint NOT NULL REFERENCES videos,
  first_clip_index int    NOT NULL CHECK (first_clip_index >= 0),
  last_clip_index  int    NOT NULL CHECK (last_clip_index >= first_clip_index),
  start_ms         bigint NOT NULL CHECK (start_ms >= 0),
  end_ms           bigint NOT NULL CHECK (end_ms > start_ms),
  object_key       text   NOT NULL,
  ls_task_id       bigint UNIQUE,
  superseded_at    timestamptz,
  -- Active units of a video never overlap.
  EXCLUDE USING gist (video_id WITH =, int4range(first_clip_index, last_clip_index, '[]') WITH &&)
    WHERE (superseded_at IS NULL)
);
