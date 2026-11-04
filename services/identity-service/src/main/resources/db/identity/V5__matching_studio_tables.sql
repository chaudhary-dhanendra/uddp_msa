-- Matching Studio tables moved from the edge (MSA): training jobs, steward labels, monitoring bounds and alerts,
-- and threshold proposals. Labels name canonical records by id only (canonical rows are a projection here).
CREATE TABLE matching_training_job (
  job_id uuid PRIMARY KEY,
  profile_id uuid NOT NULL REFERENCES matching_profile(profile_id),
  status text NOT NULL CHECK (status IN ('QUEUED','RUNNING','COMPLETED','FAILED')),
  message text,
  requested_by text NOT NULL,
  started_at timestamptz NOT NULL DEFAULT now(),
  finished_at timestamptz
);
CREATE TABLE matching_label (
  canonical_record_a uuid NOT NULL,
  canonical_record_b uuid NOT NULL,
  label text NOT NULL CHECK (label IN ('SAME','DIFFERENT','UNSURE')),
  run_id uuid REFERENCES matching_run(run_id),
  match_probability numeric NOT NULL,
  score_band integer NOT NULL,
  reviewer text NOT NULL,
  labelled_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (canonical_record_a, canonical_record_b),
  CHECK (canonical_record_a < canonical_record_b)
);
CREATE INDEX matching_label_band_idx ON matching_label(score_band);
CREATE TABLE matching_metric_bound (
  metric text PRIMARY KEY CHECK (metric IN ('match_rate','review_rate','auto_pairs','review_volume')),
  min_value numeric,
  max_value numeric,
  max_relative_change numeric,
  updated_by text,
  updated_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO matching_metric_bound(metric, min_value, max_value, max_relative_change) VALUES
  ('match_rate', NULL, 0.9, 0.5),
  ('review_rate', NULL, 0.2, 1.0),
  ('auto_pairs', NULL, NULL, 1.0),
  ('review_volume', NULL, NULL, 1.0);
CREATE TABLE matching_alert (
  alert_id uuid PRIMARY KEY,
  run_id uuid NOT NULL REFERENCES matching_run(run_id),
  metric text NOT NULL,
  kind text NOT NULL CHECK (kind IN ('OUT_OF_BOUNDS','DRIFT')),
  message text NOT NULL,
  observed numeric NOT NULL,
  expected text NOT NULL,
  status text NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','ACKNOWLEDGED')),
  created_at timestamptz NOT NULL DEFAULT now(),
  acknowledged_by text,
  acknowledged_at timestamptz
);
CREATE TABLE matching_threshold_proposal (
  proposal_id uuid PRIMARY KEY,
  kind text NOT NULL CHECK (kind IN ('AUTO_MATCH','POSSIBLE_MATCH')),
  current_value numeric NOT NULL,
  proposed_value numeric NOT NULL,
  rationale text NOT NULL,
  evidence jsonb NOT NULL DEFAULT '{}'::jsonb,
  status text NOT NULL DEFAULT 'PENDING_APPROVAL' CHECK (status IN ('PENDING_APPROVAL','APPLIED','REJECTED','SUPERSEDED')),
  proposed_by text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  decided_by text,
  decided_at timestamptz,
  applied_profile_id uuid REFERENCES matching_profile(profile_id)
);
