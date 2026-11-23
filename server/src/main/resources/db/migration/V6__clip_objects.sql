-- A clip's object: a key in its dataset's bucket (clips-<dataset>), unique to the attempt that
-- wrote it. Set with sha256 and size_bytes when the chunk commits.
ALTER TABLE clips
  ADD COLUMN object_key text,
  ADD CHECK ((object_key IS NULL) = (sha256 IS NULL));
