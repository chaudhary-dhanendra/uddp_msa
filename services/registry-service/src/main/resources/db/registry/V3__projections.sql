-- Projections the unchanged Golden build reads (docs/msa-design.md, section 6.1), with the monolith's table and
-- view names, filled only from events: canonical.resident-created and canonical.family-observed (canonical rows),
-- identity.* (matching runs, which stay RECEIVING until every pair and cluster member has arrived) and
-- stewardship.decision-recorded (link constraints). Foreign keys to other services' rows are dropped; the
-- monolith's raw_record and ingestion_batch references remain plain ids.

CREATE TABLE canonical_resident (
    canonical_resident_id uuid DEFAULT gen_random_uuid() NOT NULL,
    raw_record_id uuid NOT NULL,
    batch_id uuid NOT NULL,
    canonical_entity_type text DEFAULT 'Resident'::text NOT NULL,
    state_code text NOT NULL,
    source_system text NOT NULL,
    source_entity text NOT NULL,
    state_pack_version text NOT NULL,
    canonical_payload jsonb NOT NULL,
    mapped_at timestamp with time zone DEFAULT now() NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    payload_purged_at timestamp with time zone
);
CREATE TABLE canonical_resident_version (
    canonical_resident_id uuid NOT NULL,
    source_record_key text NOT NULL,
    version_number integer NOT NULL,
    supersedes_canonical_id uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT canonical_resident_version_check CHECK (((version_number = 1) = (supersedes_canonical_id IS NULL))),
    CONSTRAINT canonical_resident_version_version_number_check CHECK ((version_number >= 1))
);
COMMENT ON TABLE canonical_resident_version IS 'Source record key and version chain of each canonical Resident; the latest version is the one nothing supersedes';
CREATE TABLE canonical_family_membership (
    canonical_family_membership_id uuid NOT NULL,
    canonical_family_observation_id uuid NOT NULL,
    family_record_key text NOT NULL,
    source_family_id text NOT NULL,
    relationship_type text DEFAULT 'UNKNOWN'::text NOT NULL,
    family_payload jsonb DEFAULT '{}'::jsonb NOT NULL,
    CONSTRAINT canonical_family_membership_relationship_type_check CHECK ((relationship_type = ANY (ARRAY['HEAD'::text, 'SPOUSE'::text, 'CHILD'::text, 'PARENT'::text, 'SIBLING'::text, 'OTHER'::text, 'UNKNOWN'::text])))
);
CREATE TABLE canonical_family_observation (
    canonical_family_observation_id uuid NOT NULL,
    raw_record_id uuid NOT NULL,
    batch_id uuid NOT NULL,
    state_code text NOT NULL,
    source_system text NOT NULL,
    source_entity text NOT NULL,
    state_pack_version text NOT NULL,
    mapping_version text NOT NULL,
    resident_record_key text NOT NULL,
    canonical_resident_id uuid NOT NULL,
    observed_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL
);
COMMENT ON TABLE canonical_family_observation IS 'A raw delivery processed with Family mapping rules; its memberships are what that delivery says about the resident';
CREATE TABLE stewardship_decision (
    decision_id uuid NOT NULL,
    decision_sequence bigint NOT NULL,
    action text NOT NULL,
    review_task_id uuid,
    reviewer text NOT NULL,
    reason text NOT NULL,
    golden_resident_ids jsonb DEFAULT '[]'::jsonb NOT NULL,
    detail jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    CONSTRAINT stewardship_decision_action_check CHECK ((action = ANY (ARRAY['ACCEPT_MATCH'::text, 'REJECT_MATCH'::text, 'MERGE'::text, 'SPLIT'::text]))),
    CONSTRAINT stewardship_decision_check CHECK (((action = ANY (ARRAY['ACCEPT_MATCH'::text, 'REJECT_MATCH'::text])) = (review_task_id IS NOT NULL))),
    CONSTRAINT stewardship_decision_reason_check CHECK ((btrim(reason) <> ''::text)),
    CONSTRAINT stewardship_decision_reviewer_check CHECK ((btrim(reviewer) <> ''::text))
);
ALTER TABLE stewardship_decision ALTER COLUMN decision_sequence ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME stewardship_decision_decision_sequence_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);
CREATE TABLE stewardship_link_constraint (
    decision_id uuid NOT NULL,
    record_key_a text NOT NULL,
    record_key_b text NOT NULL,
    constraint_type text NOT NULL,
    CONSTRAINT stewardship_link_constraint_check CHECK ((record_key_a < record_key_b)),
    CONSTRAINT stewardship_link_constraint_constraint_type_check CHECK ((constraint_type = ANY (ARRAY['MUST_LINK'::text, 'CANNOT_LINK'::text])))
);
ALTER TABLE ONLY canonical_family_membership
    ADD CONSTRAINT canonical_family_membership_canonical_family_observation_id_key UNIQUE (canonical_family_observation_id, family_record_key);
ALTER TABLE ONLY canonical_family_membership
    ADD CONSTRAINT canonical_family_membership_pkey PRIMARY KEY (canonical_family_membership_id);
ALTER TABLE ONLY canonical_family_observation
    ADD CONSTRAINT canonical_family_observation_pkey PRIMARY KEY (canonical_family_observation_id);
ALTER TABLE ONLY canonical_family_observation
    ADD CONSTRAINT canonical_family_observation_raw_record_id_state_pack_versi_key UNIQUE (raw_record_id, state_pack_version);
ALTER TABLE ONLY canonical_resident
    ADD CONSTRAINT canonical_resident_pkey PRIMARY KEY (canonical_resident_id);
ALTER TABLE ONLY canonical_resident
    ADD CONSTRAINT canonical_resident_raw_pack_entity_uk UNIQUE (raw_record_id, state_pack_version, canonical_entity_type);
ALTER TABLE ONLY canonical_resident_version
    ADD CONSTRAINT canonical_resident_version_pkey PRIMARY KEY (canonical_resident_id);
ALTER TABLE ONLY canonical_resident_version
    ADD CONSTRAINT canonical_resident_version_source_record_key_version_number_key UNIQUE (source_record_key, version_number);
ALTER TABLE ONLY canonical_resident_version
    ADD CONSTRAINT canonical_resident_version_supersedes_canonical_id_key UNIQUE (supersedes_canonical_id);
ALTER TABLE ONLY stewardship_decision
    ADD CONSTRAINT stewardship_decision_decision_sequence_key UNIQUE (decision_sequence);
ALTER TABLE ONLY stewardship_decision
    ADD CONSTRAINT stewardship_decision_pkey PRIMARY KEY (decision_id);
ALTER TABLE ONLY stewardship_decision
    ADD CONSTRAINT stewardship_decision_review_task_id_key UNIQUE (review_task_id);
ALTER TABLE ONLY stewardship_link_constraint
    ADD CONSTRAINT stewardship_link_constraint_pkey PRIMARY KEY (decision_id, record_key_a, record_key_b);
CREATE INDEX canonical_family_membership_family_idx ON canonical_family_membership USING btree (family_record_key);
CREATE INDEX canonical_family_observation_resident_idx ON canonical_family_observation USING btree (resident_record_key, observed_at);
CREATE INDEX canonical_resident_batch_id_idx ON canonical_resident USING btree (batch_id);
CREATE INDEX stewardship_link_constraint_pair_idx ON stewardship_link_constraint USING btree (record_key_a, record_key_b);

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
  profile_id uuid,
  origin text NOT NULL DEFAULT 'IDENTITY_SERVICE' CHECK (origin IN ('LOCAL', 'IDENTITY_SERVICE')),
  correlation_id uuid,
  expected_pair_count integer,
  expected_cluster_member_count integer
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

CREATE VIEW canonical_resident_current AS
 SELECT c.canonical_resident_id,
    c.raw_record_id,
    c.batch_id,
    c.canonical_entity_type,
    c.state_code,
    c.source_system,
    c.source_entity,
    c.state_pack_version,
    c.canonical_payload,
    c.mapped_at,
    c.created_at,
    COALESCE(v.source_record_key, ('canonical:'::text || c.canonical_resident_id)) AS source_record_key,
    COALESCE(v.version_number, 1) AS version_number
   FROM (canonical_resident c
     LEFT JOIN canonical_resident_version v ON ((v.canonical_resident_id = c.canonical_resident_id)))
  WHERE ((c.canonical_entity_type = 'Resident'::text) AND (NOT (EXISTS ( SELECT 1
           FROM canonical_resident_version s
          WHERE (s.supersedes_canonical_id = c.canonical_resident_id)))));
CREATE VIEW canonical_family_membership_current AS
 SELECT m.canonical_family_membership_id,
    m.canonical_family_observation_id,
    m.family_record_key,
    m.source_family_id,
    m.relationship_type,
    m.family_payload,
    o.raw_record_id,
    o.batch_id,
    o.state_code,
    o.source_system,
    o.source_entity,
    o.resident_record_key,
    c.canonical_resident_id,
    o.observed_at
   FROM ((( SELECT DISTINCT ON (canonical_family_observation.resident_record_key) canonical_family_observation.canonical_family_observation_id,
            canonical_family_observation.raw_record_id,
            canonical_family_observation.batch_id,
            canonical_family_observation.state_code,
            canonical_family_observation.source_system,
            canonical_family_observation.source_entity,
            canonical_family_observation.state_pack_version,
            canonical_family_observation.mapping_version,
            canonical_family_observation.resident_record_key,
            canonical_family_observation.canonical_resident_id,
            canonical_family_observation.observed_at
           FROM canonical_family_observation
          ORDER BY canonical_family_observation.resident_record_key, canonical_family_observation.observed_at DESC, canonical_family_observation.canonical_family_observation_id DESC) o
     JOIN canonical_family_membership m ON ((m.canonical_family_observation_id = o.canonical_family_observation_id)))
     JOIN canonical_resident_current c ON ((c.source_record_key = o.resident_record_key)));
CREATE VIEW stewardship_link_constraint_effective AS
 SELECT DISTINCT ON (l.record_key_a, l.record_key_b) l.record_key_a,
    l.record_key_b,
    l.constraint_type,
    l.decision_id,
    d.decision_sequence
   FROM (stewardship_link_constraint l
     JOIN stewardship_decision d ON ((d.decision_id = l.decision_id)))
  ORDER BY l.record_key_a, l.record_key_b, d.decision_sequence DESC;

-- ---- Lineage (schema lineage): one row per event this service applied or produced -----------------------------
CREATE TABLE lineage.lineage_event (
  lineage_event_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  event_id uuid NOT NULL,
  event_type text NOT NULL,
  subject_key text NOT NULL,
  correlation_id uuid,
  producer text NOT NULL,
  recorded_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (event_id, event_type)
);
CREATE INDEX lineage_event_subject_idx ON lineage.lineage_event (subject_key);

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
