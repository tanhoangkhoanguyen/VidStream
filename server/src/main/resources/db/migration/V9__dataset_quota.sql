-- Backpressure: a dataset's clips stay stored until labeled and deleted (#134). Clipping waits
-- while they reach the quota, so it never runs far ahead of labeling. Default: 20 GiB.
-- stored_bytes is kept by trigger, so the check at every claim never sums clips.
ALTER TABLE datasets
  ADD COLUMN max_pending_bytes bigint NOT NULL DEFAULT 21474836480 CHECK (max_pending_bytes > 0),
  ADD COLUMN stored_bytes bigint NOT NULL DEFAULT 0 CHECK (stored_bytes >= 0);

-- Adds a statement's change in clip sizes (set exactly when a clip has its object) to their
-- datasets: one update per dataset, not per clip. Transition tables allow one event per trigger,
-- so each event has its own; only its branch is planned.
CREATE FUNCTION count_stored_bytes() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'INSERT' THEN
    UPDATE datasets d SET stored_bytes = d.stored_bytes + c.bytes
    FROM (SELECT v.dataset_id, sum(n.size_bytes) AS bytes
          FROM new_clips n JOIN videos v ON v.id = n.video_id GROUP BY v.dataset_id) c
    WHERE d.id = c.dataset_id AND c.bytes <> 0;
  ELSIF TG_OP = 'DELETE' THEN
    UPDATE datasets d SET stored_bytes = d.stored_bytes - c.bytes
    FROM (SELECT v.dataset_id, sum(o.size_bytes) AS bytes
          FROM old_clips o JOIN videos v ON v.id = o.video_id GROUP BY v.dataset_id) c
    WHERE d.id = c.dataset_id AND c.bytes <> 0;
  ELSE
    UPDATE datasets d SET stored_bytes = d.stored_bytes + c.bytes
    FROM (SELECT v.dataset_id, sum(r.bytes) AS bytes
          FROM (SELECT video_id, size_bytes AS bytes FROM new_clips
                UNION ALL SELECT video_id, -size_bytes FROM old_clips) r
          JOIN videos v ON v.id = r.video_id GROUP BY v.dataset_id) c
    WHERE d.id = c.dataset_id AND c.bytes <> 0;
  END IF;
  RETURN NULL;
END $$;

CREATE TRIGGER clips_inserted AFTER INSERT ON clips REFERENCING NEW TABLE AS new_clips
  FOR EACH STATEMENT EXECUTE FUNCTION count_stored_bytes();
CREATE TRIGGER clips_updated AFTER UPDATE ON clips
  REFERENCING OLD TABLE AS old_clips NEW TABLE AS new_clips
  FOR EACH STATEMENT EXECUTE FUNCTION count_stored_bytes();
CREATE TRIGGER clips_deleted AFTER DELETE ON clips REFERENCING OLD TABLE AS old_clips
  FOR EACH STATEMENT EXECUTE FUNCTION count_stored_bytes();

-- Datasets whose PROBE and CHUNK jobs may run. Checked at claim time, so running chunks may pass
-- the quota by at most one chunk per slot. RETENTION and MERGE free space: they must not use this.
CREATE VIEW clippable_datasets AS
SELECT w.id FROM workable_datasets w JOIN datasets d ON d.id = w.id
WHERE d.stored_bytes < d.max_pending_bytes;
