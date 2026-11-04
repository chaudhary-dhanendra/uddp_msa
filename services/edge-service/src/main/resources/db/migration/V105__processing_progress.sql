-- Chunked canonical processing: the last committed chunk of a batch (resume point) and one row per committed chunk.
CREATE TABLE canonical_processing_progress (
  batch_id uuid PRIMARY KEY REFERENCES ingestion_batch(batch_id),
  chunk_size integer NOT NULL,
  last_committed_chunk integer NOT NULL DEFAULT 0,
  status text NOT NULL CHECK (status IN ('RUNNING','COMPLETED')),
  started_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE canonical_processing_chunk (
  batch_id uuid NOT NULL REFERENCES ingestion_batch(batch_id),
  chunk_number integer NOT NULL,
  rows_read integer NOT NULL,
  rows_mapped integer NOT NULL,
  rows_skipped integer NOT NULL,
  rows_failed integer NOT NULL,
  started_at timestamptz NOT NULL,
  completed_at timestamptz NOT NULL,
  PRIMARY KEY (batch_id, chunk_number)
);
