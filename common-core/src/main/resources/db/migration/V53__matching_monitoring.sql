-- Matching Studio monitoring: per-run metrics, configurable bounds, alerts and threshold-change proposals.
CREATE TABLE matching_run_metrics (
  run_id uuid PRIMARY KEY REFERENCES matching_run(run_id),
  records_considered integer NOT NULL,
  auto_pairs integer NOT NULL,
  possible_pairs integer NOT NULL,
  no_match_pairs integer NOT NULL,
  matched_records integer NOT NULL,
  match_rate numeric NOT NULL,
  review_rate numeric NOT NULL,
  score_histogram jsonb NOT NULL,
  acceptance_by_band jsonb NOT NULL DEFAULT '{}'::jsonb,
  evaluated_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now()
);
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
