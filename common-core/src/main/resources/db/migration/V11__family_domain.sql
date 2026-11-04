-- M14: Family domain (docs/family-domain.md, ADR-0009). Families are observed by the generic canonical layer from each
-- State Pack's declarative Family mapping rules, and consolidated into stable Golden Families after every Golden
-- Resident build. Source family identifiers are evidence only. Nothing here reads or changes an earlier table's rows.

-- ---- 1. Canonical Family observations (append-only) ----------------------------------------------------------
-- One observation per raw record whose source has Family mapping rules and whose Resident was stored (or found
-- unchanged). An observation with no membership rows records that the delivery named no family for that resident.
CREATE TABLE canonical_family_observation (
  canonical_family_observation_id uuid PRIMARY KEY,
  raw_record_id uuid NOT NULL REFERENCES raw_record(raw_record_id),
  batch_id uuid NOT NULL REFERENCES ingestion_batch(batch_id),
  state_code text NOT NULL,
  source_system text NOT NULL,
  source_entity text NOT NULL,
  state_pack_version text NOT NULL,
  mapping_version text NOT NULL,
  -- The Resident source record key (ADR-0003) of the person this delivery describes.
  resident_record_key text NOT NULL,
  canonical_resident_id uuid NOT NULL REFERENCES canonical_resident(canonical_resident_id),
  observed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  UNIQUE (raw_record_id, state_pack_version)
);
CREATE INDEX canonical_family_observation_resident_idx
  ON canonical_family_observation(resident_record_key, observed_at);
COMMENT ON TABLE canonical_family_observation IS
  'A raw delivery processed with Family mapping rules; its memberships are what that delivery says about the resident';

CREATE TABLE canonical_family_membership (
  canonical_family_membership_id uuid PRIMARY KEY,
  canonical_family_observation_id uuid NOT NULL
    REFERENCES canonical_family_observation(canonical_family_observation_id),
  -- stateCode|sourceSystem|sourceEntity|sourceFamilyId: a household as one source system names it.
  family_record_key text NOT NULL,
  source_family_id text NOT NULL,
  relationship_type text NOT NULL DEFAULT 'UNKNOWN'
    CHECK (relationship_type IN ('HEAD', 'SPOUSE', 'CHILD', 'PARENT', 'SIBLING', 'OTHER', 'UNKNOWN')),
  family_payload jsonb NOT NULL DEFAULT '{}'::jsonb,
  UNIQUE (canonical_family_observation_id, family_record_key)
);
CREATE INDEX canonical_family_membership_family_idx ON canonical_family_membership(family_record_key);

CREATE TABLE canonical_family_processing_error (
  canonical_family_processing_error_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  raw_record_id uuid NOT NULL REFERENCES raw_record(raw_record_id),
  batch_id uuid NOT NULL REFERENCES ingestion_batch(batch_id),
  state_pack_version text NOT NULL,
  error_message text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);

-- What the latest delivery of each resident source record says: the only memberships Golden Families are built from.
-- The member is the resident record's latest canonical version.
CREATE VIEW canonical_family_membership_current AS
SELECT m.canonical_family_membership_id, m.canonical_family_observation_id, m.family_record_key, m.source_family_id,
       m.relationship_type, m.family_payload, o.raw_record_id, o.batch_id, o.state_code, o.source_system,
       o.source_entity, o.resident_record_key, c.canonical_resident_id, o.observed_at
  FROM (SELECT DISTINCT ON (resident_record_key) *
          FROM canonical_family_observation
         ORDER BY resident_record_key, observed_at DESC, canonical_family_observation_id DESC) o
  JOIN canonical_family_membership m ON m.canonical_family_observation_id = o.canonical_family_observation_id
  JOIN canonical_resident_current c ON c.source_record_key = o.resident_record_key;

-- ---- 2. Golden Families ----------------------------------------------------------------------------------------
-- One stable platform identity per source household (family_record_key); households are never merged across
-- sources automatically (ADR-0009). Retired when no current resident belongs to it, reactivated (same ID, next version)
-- if a later delivery names it again; never deleted, and its ID never names another household.
CREATE TABLE golden_family (
  golden_family_id uuid PRIMARY KEY,
  family_record_key text NOT NULL UNIQUE,
  state_code text NOT NULL,
  source_system text NOT NULL,
  source_family_id text NOT NULL,
  status text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'RETIRED')),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  retired_at timestamptz
);

CREATE TABLE golden_family_version (
  golden_family_version_id uuid PRIMARY KEY,
  golden_family_id uuid NOT NULL REFERENCES golden_family(golden_family_id),
  version_number integer NOT NULL CHECK (version_number >= 1),
  golden_build_id uuid REFERENCES golden_build(golden_build_id),
  family_payload jsonb NOT NULL,
  change_reasons jsonb NOT NULL DEFAULT '[]'::jsonb,
  effective_from timestamptz NOT NULL DEFAULT clock_timestamp(),
  effective_to timestamptz,
  UNIQUE (golden_family_id, version_number),
  CHECK (effective_to IS NULL OR effective_to >= effective_from)
);
CREATE UNIQUE INDEX golden_family_version_current_idx ON golden_family_version(golden_family_id)
  WHERE effective_to IS NULL;

CREATE TABLE golden_family_membership (
  golden_family_membership_id uuid PRIMARY KEY,
  golden_family_id uuid NOT NULL REFERENCES golden_family(golden_family_id),
  resident_record_key text NOT NULL,
  golden_resident_id uuid NOT NULL REFERENCES golden_resident(golden_resident_id),
  relationship_type text NOT NULL,
  -- The canonical membership observation that established this row (lineage to raw).
  canonical_family_membership_id uuid NOT NULL
    REFERENCES canonical_family_membership(canonical_family_membership_id),
  added_in_version integer NOT NULL,
  removed_in_version integer,
  effective_from timestamptz NOT NULL DEFAULT clock_timestamp(),
  effective_to timestamptz,
  CHECK (effective_to IS NULL OR effective_to >= effective_from)
);
CREATE UNIQUE INDEX golden_family_membership_current_idx
  ON golden_family_membership(golden_family_id, resident_record_key) WHERE effective_to IS NULL;
CREATE INDEX golden_family_membership_resident_idx ON golden_family_membership(golden_resident_id);

CREATE TABLE golden_family_event (
  golden_family_event_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  event_sequence bigint GENERATED ALWAYS AS IDENTITY,
  golden_family_id uuid NOT NULL REFERENCES golden_family(golden_family_id),
  event_type text NOT NULL CHECK (event_type IN ('CREATED', 'MEMBER_ADDED', 'MEMBER_REMOVED',
                                                 'MEMBER_CHANGED', 'RETIRED', 'REACTIVATED')),
  golden_build_id uuid REFERENCES golden_build(golden_build_id),
  version_number integer,
  resident_record_key text,
  golden_resident_id uuid REFERENCES golden_resident(golden_resident_id),
  detail jsonb NOT NULL DEFAULT '{}'::jsonb,
  occurred_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX golden_family_event_family_idx ON golden_family_event(golden_family_id, event_sequence);
