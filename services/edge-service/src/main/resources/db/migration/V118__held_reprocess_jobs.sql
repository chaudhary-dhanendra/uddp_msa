-- A group reprocess runs in the background and keeps its progress here (held records kept in this database; in the
-- microservice deployment canonical-service keeps them and this table stays empty).
CREATE TABLE held_reprocess_job (
  job_id uuid PRIMARY KEY,
  batch_id uuid NOT NULL,
  reason_code text NOT NULL,
  status text NOT NULL DEFAULT 'RUNNING' CHECK (status IN ('RUNNING','COMPLETED','FAILED')),
  total integer NOT NULL,
  processed integer NOT NULL DEFAULT 0,
  released integer NOT NULL DEFAULT 0,
  still_held integer NOT NULL DEFAULT 0,
  requested_by text NOT NULL,
  started_at timestamptz NOT NULL DEFAULT now(),
  finished_at timestamptz,
  error text
);
