-- Periodic work on the web role: each run is claimed by one replica (Schedules).
CREATE TABLE schedules (
  name text PRIMARY KEY,
  last_run_at timestamptz NOT NULL
);
