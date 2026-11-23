-- Wakes idle workers (LISTEN jobs) when a job is claimable now: new, or released. Delivered on
-- commit; repeats within a transaction collapse into one. Workers still poll as the fallback.
CREATE FUNCTION notify_jobs() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  PERFORM pg_notify('jobs', '');
  RETURN NULL;
END $$;

CREATE TRIGGER jobs_claimable AFTER INSERT OR UPDATE OF status ON jobs FOR EACH ROW
  WHEN (NEW.status = 'PENDING' AND NEW.run_after <= now())
  EXECUTE FUNCTION notify_jobs();
