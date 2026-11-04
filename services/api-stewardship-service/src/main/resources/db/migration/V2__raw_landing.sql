CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE ingestion_batch (
  batch_id uuid PRIMARY KEY,
  state_code text NOT NULL,
  source_system text NOT NULL,
  source_type text NOT NULL,
  source_schema_version text,
  state_pack_version text NOT NULL,
  pipeline_template text NOT NULL,
  started_at timestamptz NOT NULL DEFAULT now(),
  completed_at timestamptz,
  status text NOT NULL CHECK (status IN ('STARTED','COMPLETED','FAILED')),
  record_count integer NOT NULL DEFAULT 0,
  error_count integer NOT NULL DEFAULT 0,
  error_message text
);

CREATE TABLE raw_record (
  raw_record_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  batch_id uuid NOT NULL REFERENCES ingestion_batch(batch_id),
  state_code text NOT NULL,
  source_system text NOT NULL,
  source_record_id text,
  ingested_at timestamptz NOT NULL DEFAULT now(),
  raw_payload text NOT NULL,
  source_metadata jsonb NOT NULL,
  state_pack_version text NOT NULL,
  source_schema_version text,
  pipeline_template text NOT NULL
);
CREATE INDEX raw_record_batch_id_idx ON raw_record(batch_id);
