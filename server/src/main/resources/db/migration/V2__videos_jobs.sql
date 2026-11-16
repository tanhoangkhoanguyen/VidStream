-- Box files in a dataset folder. Box IDs only, no file names.
CREATE TABLE videos (
  id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  dataset_id  bigint NOT NULL REFERENCES datasets,
  box_file_id text   NOT NULL,
  sha1        text   NOT NULL,
  size_bytes  bigint NOT NULL CHECK (size_bytes >= 0),
  -- NULL until probed.
  duration_ms bigint CHECK (duration_ms > 0),
  status      text   NOT NULL DEFAULT 'DISCOVERED' CHECK (status IN
                ('DISCOVERED', 'PROBED', 'CLIPPING', 'CLIPPED', 'IMPORTED', 'DUPLICATE', 'FAILED')),
  last_error  text,
  UNIQUE (dataset_id, box_file_id)
);

-- Same content under another file is a DUPLICATE: never clipped or labeled.
CREATE UNIQUE INDEX videos_sha1_once ON videos (dataset_id, sha1) WHERE status <> 'DUPLICATE';

-- Work queue shared by all workers; they coordinate only through these rows.
CREATE TABLE jobs (
  id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  type          text   NOT NULL CHECK (type IN ('PROBE', 'CHUNK', 'MERGE', 'RETENTION')),
  dataset_id    bigint NOT NULL REFERENCES datasets,
  video_id      bigint REFERENCES videos,
  payload       jsonb  NOT NULL DEFAULT '{}',
  status        text   NOT NULL DEFAULT 'PENDING'
                  CHECK (status IN ('PENDING', 'LEASED', 'DONE', 'FAILED')),
  -- Not claimable before this: retry and release backoff.
  run_after     timestamptz NOT NULL DEFAULT now(),
  lease_until   timestamptz,
  -- Fencing token: +1 per claim; every transition must present the current one.
  lease_version int    NOT NULL DEFAULT 0,
  worker_id     text,
  attempts      int    NOT NULL DEFAULT 0,
  last_error    text,
  created_at    timestamptz NOT NULL DEFAULT now(),
  CHECK (status <> 'LEASED' OR (lease_until IS NOT NULL AND worker_id IS NOT NULL)),
  CHECK (type NOT IN ('PROBE', 'CHUNK') OR video_id IS NOT NULL)
);

-- A rescan or re-probe never enqueues the same work twice.
CREATE UNIQUE INDEX jobs_once ON jobs (type, video_id, (payload ->> 'chunk')) NULLS NOT DISTINCT
  WHERE type IN ('PROBE', 'CHUNK');

-- The claim walks open jobs in id order; DONE and FAILED rows stay out.
CREATE INDEX jobs_open ON jobs (id) WHERE status IN ('PENDING', 'LEASED');
