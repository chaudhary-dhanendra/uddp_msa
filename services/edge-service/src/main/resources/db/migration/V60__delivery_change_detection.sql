-- Track C1: per-source-record change detection between deliveries (NEW / CHANGED / UNCHANGED / REMOVED).
-- Raw, canonical and matching rows are never updated here; these tables only describe how a delivery compared.

-- What a source does when a record is no longer delivered.
CREATE TABLE source_delivery_policy (
  state_code text NOT NULL,
  source_system text NOT NULL,
  removal_handling text NOT NULL DEFAULT 'MARK_ONLY' CHECK (removal_handling IN ('MARK_ONLY', 'END_BENEFITS')),
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (state_code, source_system)
);

-- The last known delivery state of every source record (identity = the canonical source record key, ADR-0003).
CREATE TABLE source_record_state (
  source_record_key text PRIMARY KEY,
  state_code text NOT NULL,
  source_system text NOT NULL,
  content_hash text NOT NULL,
  status text NOT NULL CHECK (status IN ('DELIVERED', 'REMOVED')),
  first_batch_id uuid NOT NULL REFERENCES ingestion_batch(batch_id),
  last_batch_id uuid NOT NULL REFERENCES ingestion_batch(batch_id),
  removed_at timestamptz,
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX source_record_state_source_idx ON source_record_state(state_code, source_system, status);

-- One comparison per delivery (ingestion batch).
CREATE TABLE delivery_change_set (
  batch_id uuid PRIMARY KEY REFERENCES ingestion_batch(batch_id),
  onboarding_run_id uuid REFERENCES onboarding_run(onboarding_run_id),
  state_code text NOT NULL,
  source_system text NOT NULL,
  new_count integer NOT NULL,
  changed_count integer NOT NULL,
  unchanged_count integer NOT NULL,
  removed_count integer NOT NULL,
  removal_handling text NOT NULL,
  evaluated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX delivery_change_set_run_idx ON delivery_change_set(onboarding_run_id);

-- A removed record's benefits end when its source is configured END_BENEFITS: append-only markers.
CREATE TABLE benefit_end_marker (
  benefit_end_marker_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  resident_record_key text NOT NULL,
  batch_id uuid NOT NULL REFERENCES ingestion_batch(batch_id),
  ended_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX benefit_end_marker_resident_idx ON benefit_end_marker(resident_record_key, ended_at);

-- Same columns as V13; a benefit whose resident record was ended after its latest observation is no longer current.
CREATE OR REPLACE VIEW canonical_benefit_current AS
SELECT o.canonical_benefit_observation_id, o.raw_record_id, o.batch_id, o.state_code, o.source_system,
       o.source_entity, o.state_pack_version, o.mapping_version, o.scheme_catalogue_version, o.resident_record_key,
       c.canonical_resident_id, o.benefit_id, o.benefit_record_key, o.source_scheme_code, o.scheme_id,
       o.source_benefit_id, o.beneficiary_level, o.family_record_key, o.source_family_id, o.benefit_status,
       o.start_date, o.entitlement, o.benefit_payload, o.observed_at
  FROM (SELECT DISTINCT ON (resident_record_key) *
          FROM canonical_benefit_observation
         ORDER BY resident_record_key, observed_at DESC, canonical_benefit_observation_id DESC) o
  JOIN canonical_resident_current c ON c.source_record_key = o.resident_record_key
 WHERE o.benefit_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM benefit_end_marker m
                    WHERE m.resident_record_key = o.resident_record_key AND m.ended_at > o.observed_at);
