-- identity-service owns matching (docs/msa-design.md, section 2). These tables are the monolith's end-state matching
-- tables (V4..V82) without the foreign keys to canonical_resident, which lives in canonical-service: canonical ids are
-- logical references to candidate_resident, the projection of canonical.resident-created. The Python Splink adapter
-- (matching/run.py) reads and writes them unchanged, through the compatibility views at the end.

-- ---- Outbox and inbox (gov.uddp.messaging.Outbox.DDL) ----------------------------------------------------------
CREATE TABLE outbox_event (
  event_id uuid PRIMARY KEY,
  topic text NOT NULL,
  partition_key text NOT NULL,
  event_type text NOT NULL,
  payload jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  published_at timestamptz
);
CREATE INDEX outbox_event_unpublished_idx ON outbox_event (created_at) WHERE published_at IS NULL;
CREATE TABLE processed_event (
  consumer text NOT NULL,
  event_id uuid NOT NULL,
  processed_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (consumer, event_id)
);

-- ---- Configuration read by the adapter ---------------------------------------------------------------------------
CREATE TABLE matching_profile (
  profile_id uuid PRIMARY KEY,
  name text NOT NULL,
  version integer NOT NULL,
  status text NOT NULL CHECK (status IN ('DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'RETIRED')),
  config jsonb NOT NULL,
  parameters jsonb,
  weights jsonb,
  training_records integer,
  created_by text NOT NULL,
  submitted_by text,
  approved_by text,
  created_at timestamptz NOT NULL DEFAULT now(),
  trained_at timestamptz,
  approved_at timestamptz,
  UNIQUE (name, version)
);
CREATE UNIQUE INDEX matching_profile_one_approved ON matching_profile ((true)) WHERE status = 'APPROVED';

CREATE TABLE identity_policy (
  policy_id uuid PRIMARY KEY,
  version integer NOT NULL UNIQUE CHECK (version >= 1),
  status text NOT NULL CHECK (status IN ('DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'RETIRED')),
  config jsonb NOT NULL,
  created_by text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  submitted_by text,
  submitted_at timestamptz,
  decided_by text,
  decided_at timestamptz,
  decision_reason text
);
CREATE UNIQUE INDEX identity_policy_one_approved ON identity_policy ((true)) WHERE status = 'APPROVED';

-- ---- Matching runs and evidence (append-only per run) --------------------------------------------------------------
CREATE TABLE matching_run (
  run_id uuid PRIMARY KEY,
  model_version text NOT NULL,
  status text NOT NULL,
  started_at timestamptz NOT NULL DEFAULT now(),
  completed_at timestamptz,
  records_considered integer NOT NULL DEFAULT 0,
  threshold numeric NOT NULL,
  possible_match_threshold numeric,
  mode text NOT NULL DEFAULT 'FULL' CHECK (mode IN ('FULL', 'INCREMENTAL')),
  profile_id uuid REFERENCES matching_profile(profile_id),
  -- The request (onboarding run or command) that asked for this run; echoed on match-run-completed.
  correlation_id uuid
);

CREATE TABLE resident_match_pair (
  run_id uuid NOT NULL REFERENCES matching_run(run_id),
  canonical_record_a uuid NOT NULL,
  canonical_record_b uuid NOT NULL,
  match_probability numeric NOT NULL,
  match_weight numeric NOT NULL,
  decision text NOT NULL CHECK (decision IN ('AUTO_MATCH', 'POSSIBLE_MATCH', 'NO_MATCH')),
  evidence jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (run_id, canonical_record_a, canonical_record_b),
  CHECK (canonical_record_a < canonical_record_b)
);

CREATE TABLE resident_match_cluster_member (
  run_id uuid NOT NULL REFERENCES matching_run(run_id),
  cluster_id text NOT NULL,
  canonical_record_id uuid NOT NULL,
  PRIMARY KEY (run_id, cluster_id, canonical_record_id),
  UNIQUE (run_id, canonical_record_id)
);

CREATE TABLE matching_run_metrics (
  run_id uuid PRIMARY KEY REFERENCES matching_run(run_id),
  records_considered integer NOT NULL,
  auto_pairs integer NOT NULL,
  possible_pairs integer NOT NULL,
  no_match_pairs integer NOT NULL,
  matched_records integer NOT NULL,
  match_rate numeric NOT NULL,
  review_rate numeric NOT NULL,
  score_histogram jsonb NOT NULL,
  acceptance_by_band jsonb NOT NULL DEFAULT '{}'::jsonb,
  evaluated_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now()
);

-- ---- Projections (rebuildable from Kafka) --------------------------------------------------------------------------
-- Every canonical Resident version, from canonical.resident-created.
CREATE TABLE candidate_resident (
  canonical_resident_id uuid PRIMARY KEY,
  source_record_key text NOT NULL,
  version_number integer NOT NULL,
  supersedes_canonical_id uuid,
  raw_record_id uuid NOT NULL,
  batch_id uuid NOT NULL,
  state_code text,
  source_system text NOT NULL,
  source_entity text,
  state_pack_version text NOT NULL,
  canonical_payload jsonb NOT NULL,
  mapped_at timestamptz,
  received_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (source_record_key, version_number)
);
CREATE INDEX candidate_resident_supersedes_idx ON candidate_resident (supersedes_canonical_id);

-- Current Golden membership per source record, from registry.golden-resident-changed.
CREATE TABLE current_membership (
  source_record_key text PRIMARY KEY,
  golden_resident_id uuid NOT NULL,
  canonical_resident_id uuid NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX current_membership_golden_idx ON current_membership (golden_resident_id);
CREATE INDEX current_membership_canonical_idx ON current_membership (canonical_resident_id);

-- Every Golden Resident id ever announced (the adapter's "first load" check).
CREATE TABLE known_golden_resident (
  golden_resident_id uuid PRIMARY KEY,
  first_seen_at timestamptz NOT NULL DEFAULT now()
);

-- ---- Compatibility views: the names and columns matching/run.py reads in the monolith ------------------------------
CREATE VIEW canonical_resident_current AS
SELECT c.canonical_resident_id, c.raw_record_id, c.batch_id, 'Resident'::text AS canonical_entity_type, c.state_code,
       c.source_system, c.source_entity, c.state_pack_version, c.canonical_payload, c.mapped_at,
       c.received_at AS created_at, c.source_record_key, c.version_number
  FROM candidate_resident c
 WHERE NOT EXISTS (SELECT 1 FROM candidate_resident s WHERE s.supersedes_canonical_id = c.canonical_resident_id);

-- Same definition as the monolith's V101 view.
CREATE VIEW canonical_resident_match_v AS
SELECT c.canonical_resident_id, c.source_record_key, c.state_code, c.source_system,
       c.canonical_payload ->> 'nameNormalised' AS name_normalised,
       jsonb_path_query_first(c.canonical_payload, '$.relations[*] ? (@.type == "FATHER")') ->> 'nameNormalised'
           AS father_name_normalised,
       coalesce((c.canonical_payload ->> 'birthYear')::int,
                CASE WHEN c.canonical_payload ->> 'dateOfBirth' ~ '^[0-9]{4}-'
                     THEN left(c.canonical_payload ->> 'dateOfBirth', 4)::int END) AS birth_year,
       coalesce(c.canonical_payload ->> 'dateOfBirthPrecision',
                CASE WHEN c.canonical_payload ? 'dateOfBirth' THEN 'DAY' END) AS dob_precision,
       c.canonical_payload #>> '{extensions,uddp.geography,village,lgd}' AS village_lgd,
       c.canonical_payload #>> '{extensions,uddp.identifiers,AADHAAR,last4}' AS aadhaar_last4,
       CASE WHEN length(c.canonical_payload ->> 'mobile') > 4
            THEN repeat('X', length(c.canonical_payload ->> 'mobile') - 4)
                 || right(c.canonical_payload ->> 'mobile', 4) END AS mobile_masked
  FROM canonical_resident_current c;

CREATE VIEW golden_resident_membership AS
SELECT m.golden_resident_id, m.canonical_resident_id, m.source_record_key, m.updated_at AS effective_from,
       NULL::timestamptz AS effective_to
  FROM current_membership m;

CREATE VIEW golden_resident AS
SELECT k.golden_resident_id FROM known_golden_resident k;
