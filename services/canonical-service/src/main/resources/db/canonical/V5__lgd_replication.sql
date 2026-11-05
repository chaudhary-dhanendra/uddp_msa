-- Chunks of a replicated LGD snapshot (geography.snapshot-published), kept until all have arrived and the snapshot
-- is imported into this service's geography master (gov.uddp.core.geography.GeographyReplication).
CREATE TABLE IF NOT EXISTS lgd_snapshot_chunk (
  snapshot_id uuid NOT NULL,
  chunk integer NOT NULL,
  chunks integer NOT NULL,
  source_label text NOT NULL,
  as_of date NOT NULL,
  rows jsonb NOT NULL,
  received_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (snapshot_id, chunk)
);
