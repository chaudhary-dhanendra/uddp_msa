-- MSA Phase 3 (canonical-service extraction): with uddp.canonical.remote=true, canonical-service maps the batch and
-- its canonical.batch-processed report lands here, so the MAPPED stage returns the same summary it computes locally.
CREATE TABLE remote_canonical_batch (
  batch_id uuid PRIMARY KEY,
  summary jsonb NOT NULL,
  received_at timestamptz NOT NULL DEFAULT now()
);
