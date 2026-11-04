-- A benefit whose source scheme code the crosswalk does not map is kept with scheme_id 'UNMAPPED' (and a warning)
-- instead of being rejected. Once the code is mapped, its rows are reprocessed and the new observation replaces the
-- UNMAPPED one in the current views. A raw record may therefore hold one UNMAPPED and one mapped observation per
-- State Pack version; each stays idempotent on its own.
DO $$
DECLARE constraint_name text;
BEGIN
  SELECT conname INTO constraint_name FROM pg_constraint
   WHERE conrelid = 'canonical_benefit_observation'::regclass AND contype = 'u';
  EXECUTE format('ALTER TABLE canonical_benefit_observation DROP CONSTRAINT %I', constraint_name);
END $$;
CREATE UNIQUE INDEX canonical_benefit_observation_mapped_once_idx
  ON canonical_benefit_observation(raw_record_id, state_pack_version) WHERE scheme_id IS DISTINCT FROM 'UNMAPPED';
CREATE UNIQUE INDEX canonical_benefit_observation_unmapped_once_idx
  ON canonical_benefit_observation(raw_record_id, state_pack_version) WHERE scheme_id = 'UNMAPPED';

CREATE TABLE canonical_benefit_warning (
  canonical_benefit_warning_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  raw_record_id uuid NOT NULL REFERENCES raw_record(raw_record_id),
  batch_id uuid NOT NULL REFERENCES ingestion_batch(batch_id),
  state_pack_version text NOT NULL,
  warning_type text NOT NULL,
  source_code text,
  message text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  resolved_at timestamptz
);
CREATE UNIQUE INDEX canonical_benefit_warning_once_idx
  ON canonical_benefit_warning(raw_record_id, state_pack_version, warning_type);
CREATE INDEX canonical_benefit_warning_open_idx ON canonical_benefit_warning(batch_id) WHERE resolved_at IS NULL;
