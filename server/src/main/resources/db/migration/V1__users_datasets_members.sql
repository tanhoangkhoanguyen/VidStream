CREATE TABLE users (
  id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  -- Stable identity; the login email can change in Box.
  box_user_id       text NOT NULL UNIQUE,
  login             text NOT NULL,
  -- Box tokens, encrypted; NULL when the user has no usable token.
  -- Workers use any member's token while users are offline.
  refresh_token_enc bytea,
  -- Cached ~1 h so jobs do not refresh on every call.
  access_token_enc  bytea,
  access_expires_at timestamptz,
  CHECK ((access_token_enc IS NULL) = (access_expires_at IS NULL))
);

CREATE TABLE datasets (
  id                    bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  -- A DNS label: Label Studio runs at <name>.<domain>.
  name                  text    NOT NULL UNIQUE CHECK (name ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'),
  box_folder_id         text    NOT NULL UNIQUE,
  clip_seconds          int     NOT NULL CHECK (clip_seconds BETWEEN 5 AND 60),
  -- A shorter final remainder merges into the previous clip.
  min_last_clip_seconds int     NOT NULL CHECK (min_last_clip_seconds BETWEEN 1 AND clip_seconds),
  fps                   int     NOT NULL CHECK (fps IN (24, 25, 30)),
  chunk_clips           int     NOT NULL CHECK (chunk_clips > 0),
  -- Removed from datasets.yml: kept with its data, no new work.
  active                boolean NOT NULL DEFAULT true
);

-- Who Box lets read a dataset's folder, as of their last sign-in.
CREATE TABLE dataset_members (
  user_id    bigint NOT NULL REFERENCES users,
  dataset_id bigint NOT NULL REFERENCES datasets,
  PRIMARY KEY (user_id, dataset_id)
);
