-- MSA (registry-service extraction): a Golden build's replica arrives in ordered chunks; the last one records the
-- build here, so the edge treats a remote build as done only when all of its rows have been applied.
CREATE TABLE remote_golden_replicated (
  golden_build_id uuid PRIMARY KEY,
  matching_run_id uuid,
  received_at timestamptz NOT NULL DEFAULT now()
);
