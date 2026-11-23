-- A PROBE or CHUNK job out of attempts fails its video, on both paths: a failed last attempt, and
-- a lease that expired on it (the worker died) found by the next claim. The first error is kept.
-- The video's pending jobs go too: no work for a video that never completes. SKIP LOCKED: a job
-- being claimed meanwhile runs, and its commit leaves a failed video alone; concurrent failures
-- of one video do not deadlock on each other's jobs.
CREATE FUNCTION fail_video() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  UPDATE videos SET status = 'FAILED', last_error = NEW.last_error
  WHERE id = NEW.video_id AND status <> 'FAILED';
  DELETE FROM jobs WHERE id IN (
    SELECT id FROM jobs  -- By jobs_once: only PROBE and CHUNK name a video.
    WHERE type IN ('PROBE', 'CHUNK') AND video_id = NEW.video_id AND status = 'PENDING'
    FOR UPDATE SKIP LOCKED);
  RETURN NULL;
END $$;

CREATE TRIGGER jobs_failed AFTER UPDATE OF status ON jobs FOR EACH ROW
  WHEN (NEW.status = 'FAILED' AND OLD.status <> 'FAILED' AND NEW.type IN ('PROBE', 'CHUNK'))
  EXECUTE FUNCTION fail_video();
