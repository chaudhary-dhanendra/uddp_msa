CREATE TABLE canonical_resident (
  canonical_resident_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  raw_record_id uuid NOT NULL REFERENCES raw_record(raw_record_id),
  batch_id uuid NOT NULL REFERENCES ingestion_batch(batch_id),
  canonical_entity_type text NOT NULL DEFAULT 'Resident',
  state_code text NOT NULL,
  source_system text NOT NULL,
  source_entity text NOT NULL,
  state_pack_version text NOT NULL,
  canonical_payload jsonb NOT NULL,
  mapped_at timestamptz NOT NULL DEFAULT now(),
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT canonical_resident_raw_pack_entity_uk UNIQUE (raw_record_id, state_pack_version, canonical_entity_type)
);
CREATE INDEX canonical_resident_batch_id_idx ON canonical_resident(batch_id);

CREATE TABLE canonical_processing_error (
  canonical_processing_error_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  raw_record_id uuid NOT NULL REFERENCES raw_record(raw_record_id),
  batch_id uuid NOT NULL REFERENCES ingestion_batch(batch_id),
  canonical_entity_type text NOT NULL DEFAULT 'Resident',
  state_pack_version text NOT NULL,
  error_message text NOT NULL,
  occurred_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX canonical_processing_error_batch_id_idx ON canonical_processing_error(batch_id);
