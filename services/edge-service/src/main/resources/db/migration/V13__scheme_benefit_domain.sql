-- M15: Scheme and Benefit domains (docs/benefit-domain.md, ADR-0011). Benefits are observed by the generic canonical
-- layer from each State Pack's declarative Benefit mapping rules, with scheme codes crosswalked onto the platform
-- scheme catalogue (platform/schemes/catalogue.yaml). Links to Golden Residents and Golden Families are resolved when
-- read, so no table here stores a Golden ID. Nothing here reads or changes an earlier table's rows.

-- ---- 1. Stable benefit identity ---------------------------------------------------------------------------------
-- One platform UUID per benefit as one source names it; created the first time it is observed, never deleted or
-- reused, and never updated.
CREATE TABLE benefit (
  benefit_id uuid PRIMARY KEY,
  -- stateCode|sourceSystem|sourceEntity|schemeId|sourceBenefitId
  benefit_record_key text NOT NULL UNIQUE,
  state_code text NOT NULL,
  source_system text NOT NULL,
  source_entity text NOT NULL,
  scheme_id text NOT NULL,
  source_benefit_id text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX benefit_scheme_idx ON benefit(scheme_id);

-- ---- 2. Canonical Benefit observations (append-only) -----------------------------------------------------------
-- One observation per raw record whose source has Benefit mapping rules and whose Resident was stored (or found
-- unchanged). An observation without a benefit records that the delivery named none for that resident.
CREATE TABLE canonical_benefit_observation (
  canonical_benefit_observation_id uuid PRIMARY KEY,
  raw_record_id uuid NOT NULL REFERENCES raw_record(raw_record_id),
  batch_id uuid NOT NULL REFERENCES ingestion_batch(batch_id),
  state_code text NOT NULL,
  source_system text NOT NULL,
  source_entity text NOT NULL,
  state_pack_version text NOT NULL,
  mapping_version text NOT NULL,
  scheme_catalogue_version text NOT NULL,
  -- The Resident source record key (ADR-0003) of the person this delivery describes.
  resident_record_key text NOT NULL,
  canonical_resident_id uuid NOT NULL REFERENCES canonical_resident(canonical_resident_id),
  benefit_id uuid REFERENCES benefit(benefit_id),
  benefit_record_key text,
  source_scheme_code text,
  scheme_id text,
  source_benefit_id text,
  beneficiary_level text CHECK (beneficiary_level IN ('FAMILY', 'RESIDENT')),
  -- stateCode|sourceSystem|sourceEntity|sourceFamilyId of the household holding a FAMILY-level benefit (ADR-0009).
  family_record_key text,
  source_family_id text,
  benefit_status text CHECK (benefit_status IN ('ACTIVE', 'SUSPENDED', 'CLOSED')),
  start_date date,
  -- amount, quantity, unit, frequency
  entitlement jsonb NOT NULL DEFAULT '{}'::jsonb,
  benefit_payload jsonb NOT NULL DEFAULT '{}'::jsonb,
  observed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  UNIQUE (raw_record_id, state_pack_version),
  CHECK ((benefit_id IS NULL) = (benefit_record_key IS NULL)),
  CHECK (benefit_id IS NULL OR (scheme_id IS NOT NULL AND source_benefit_id IS NOT NULL
                                AND beneficiary_level IS NOT NULL AND benefit_status IS NOT NULL)),
  CHECK (beneficiary_level IS DISTINCT FROM 'FAMILY' OR family_record_key IS NOT NULL)
);
CREATE INDEX canonical_benefit_observation_resident_idx
  ON canonical_benefit_observation(resident_record_key, observed_at);
CREATE INDEX canonical_benefit_observation_benefit_idx ON canonical_benefit_observation(benefit_id);
COMMENT ON TABLE canonical_benefit_observation IS
  'A raw delivery processed with Benefit mapping rules: the benefit (if any) it says the resident holds';

-- A disbursement (payment, ration lifting) a delivery reports; written once per benefit, date and reference.
CREATE TABLE canonical_benefit_disbursement (
  canonical_benefit_disbursement_id uuid PRIMARY KEY,
  benefit_id uuid NOT NULL REFERENCES benefit(benefit_id),
  canonical_benefit_observation_id uuid NOT NULL
    REFERENCES canonical_benefit_observation(canonical_benefit_observation_id),
  disbursed_on date NOT NULL,
  amount numeric(14, 2),
  quantity numeric(14, 3),
  unit text,
  disbursement_reference text,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE UNIQUE INDEX canonical_benefit_disbursement_once_idx
  ON canonical_benefit_disbursement(benefit_id, disbursed_on, coalesce(disbursement_reference, ''));

CREATE TABLE canonical_benefit_processing_error (
  canonical_benefit_processing_error_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  raw_record_id uuid NOT NULL REFERENCES raw_record(raw_record_id),
  batch_id uuid NOT NULL REFERENCES ingestion_batch(batch_id),
  state_pack_version text NOT NULL,
  error_message text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);

-- What the latest delivery of each resident source record says, when it names a benefit: the only observations
-- Resident 360 benefits and the reports read. The resident is the record's latest canonical version.
CREATE VIEW canonical_benefit_current AS
SELECT o.canonical_benefit_observation_id, o.raw_record_id, o.batch_id, o.state_code, o.source_system,
       o.source_entity, o.state_pack_version, o.mapping_version, o.scheme_catalogue_version, o.resident_record_key,
       c.canonical_resident_id, o.benefit_id, o.benefit_record_key, o.source_scheme_code, o.scheme_id,
       o.source_benefit_id, o.beneficiary_level, o.family_record_key, o.source_family_id, o.benefit_status,
       o.start_date, o.entitlement, o.benefit_payload, o.observed_at
  FROM (SELECT DISTINCT ON (resident_record_key) *
          FROM canonical_benefit_observation
         ORDER BY resident_record_key, observed_at DESC, canonical_benefit_observation_id DESC) o
  JOIN canonical_resident_current c ON c.source_record_key = o.resident_record_key
 WHERE o.benefit_id IS NOT NULL;

-- ---- 3. Read views ----------------------------------------------------------------------------------------------
-- Each benefit's present statement: its latest current observation, or (for an ended benefit, which no current
-- observation names any more) its latest observation, with status ENDED.
CREATE VIEW benefit_state AS
SELECT b.benefit_id, b.benefit_record_key, b.state_code, b.source_system, b.source_entity, b.scheme_id,
       b.source_benefit_id, b.created_at, o.canonical_benefit_observation_id, o.source_scheme_code,
       o.beneficiary_level, o.family_record_key, o.source_family_id, o.start_date, o.entitlement, o.benefit_payload,
       o.observed_at, o.scheme_catalogue_version,
       EXISTS (SELECT 1 FROM canonical_benefit_current c WHERE c.benefit_id = b.benefit_id) AS is_current,
       CASE WHEN EXISTS (SELECT 1 FROM canonical_benefit_current c WHERE c.benefit_id = b.benefit_id)
            THEN o.benefit_status ELSE 'ENDED' END AS status
  FROM benefit b
  JOIN LATERAL (SELECT x.*
                  FROM canonical_benefit_observation x
                 WHERE x.benefit_id = b.benefit_id
                 ORDER BY EXISTS (SELECT 1 FROM canonical_benefit_current c
                                   WHERE c.canonical_benefit_observation_id = x.canonical_benefit_observation_id) DESC,
                          x.observed_at DESC, x.canonical_benefit_observation_id DESC
                 LIMIT 1) o ON true;

-- Who currently holds each current benefit, resolved to current, active Golden identities (ADR-0011): the residents
-- whose current observation names it, and every current member of the Golden Family of a FAMILY-level benefit.
CREATE VIEW benefit_holding_current AS
SELECT m.golden_resident_id, c.benefit_id, 'RESIDENT' AS linked_via, NULL::uuid AS golden_family_id,
       c.resident_record_key, c.canonical_benefit_observation_id
  FROM canonical_benefit_current c
  JOIN golden_resident_membership m ON m.source_record_key = c.resident_record_key AND m.effective_to IS NULL
  JOIN golden_resident g ON g.golden_resident_id = m.golden_resident_id AND g.status = 'ACTIVE'
UNION ALL
SELECT fm.golden_resident_id, s.benefit_id, 'FAMILY' AS linked_via, f.golden_family_id, fm.resident_record_key,
       s.canonical_benefit_observation_id
  FROM benefit_state s
  JOIN golden_family f ON f.family_record_key = s.family_record_key AND f.status = 'ACTIVE'
  JOIN golden_family_membership fm ON fm.golden_family_id = f.golden_family_id AND fm.effective_to IS NULL
  JOIN golden_resident g ON g.golden_resident_id = fm.golden_resident_id AND g.status = 'ACTIVE'
 WHERE s.is_current AND s.beneficiary_level = 'FAMILY';
