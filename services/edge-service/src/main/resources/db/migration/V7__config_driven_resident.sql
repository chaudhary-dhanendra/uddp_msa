-- M7: State Pack configuration drives matching bands, survivorship and validation.

-- Matching: record both configured thresholds and allow the three decision types only.
ALTER TABLE matching_run ADD COLUMN possible_match_threshold numeric;
ALTER TABLE matching_run ALTER COLUMN threshold DROP DEFAULT;
COMMENT ON COLUMN matching_run.threshold IS 'Configured autoMatch threshold: pairs at or above it are AUTO_MATCH and form clusters';
COMMENT ON COLUMN matching_run.possible_match_threshold IS 'Configured possibleMatch threshold: pairs from here up to autoMatch are POSSIBLE_MATCH; NULL for runs before M7';
ALTER TABLE resident_match_pair ADD CONSTRAINT resident_match_pair_decision_ck
  CHECK (decision IN ('AUTO_MATCH', 'POSSIBLE_MATCH', 'NO_MATCH'));

-- Survivorship: each provenance row records the policy version that selected it.
ALTER TABLE golden_resident_field_provenance ADD COLUMN survivorship_policy_version text;
UPDATE golden_resident_field_provenance p
   SET survivorship_policy_version = g.survivorship_policy_version
  FROM golden_resident g
 WHERE g.golden_resident_id = p.golden_resident_id;
ALTER TABLE golden_resident_field_provenance ALTER COLUMN survivorship_policy_version SET NOT NULL;

-- Validation: warning-severity rule results for canonical Residents that were kept.
CREATE TABLE canonical_validation_warning (
  canonical_validation_warning_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  canonical_resident_id uuid NOT NULL REFERENCES canonical_resident(canonical_resident_id),
  raw_record_id uuid NOT NULL REFERENCES raw_record(raw_record_id),
  batch_id uuid NOT NULL REFERENCES ingestion_batch(batch_id),
  state_pack_version text NOT NULL,
  field_path text NOT NULL,
  rule text NOT NULL,
  message text NOT NULL,
  occurred_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX canonical_validation_warning_canonical_idx ON canonical_validation_warning(canonical_resident_id);
