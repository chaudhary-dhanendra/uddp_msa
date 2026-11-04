-- MSA (canonical-service extraction): a batch's benefit, application, asset, DQ and error rows arrive as one
-- canonical.batch-observed event; this records that they have been applied, so the MAPPED stage waits for them.
CREATE TABLE remote_canonical_observed (
  batch_id uuid PRIMARY KEY,
  received_at timestamptz NOT NULL DEFAULT now()
);
