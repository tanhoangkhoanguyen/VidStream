-- The claim takes each dataset's oldest open job (fairness across datasets), so it walks jobs per
-- dataset: a paused dataset's jobs are no longer scanned on every claim.
DROP INDEX jobs_open;
CREATE INDEX jobs_open ON jobs (dataset_id, id) WHERE status IN ('PENDING', 'LEASED');

-- Leased jobs per dataset, which the claim ranks datasets by: a few rows, one per running job.
CREATE INDEX jobs_leased ON jobs (dataset_id) WHERE status = 'LEASED';
