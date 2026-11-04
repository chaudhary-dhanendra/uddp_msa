-- canonical-service owns canonical processing (docs/msa-design.md, section 2): the monolith's end-state canonical,
-- family, benefit, application, asset, data-quality and reference-set tables (generated with pg_dump from a database
-- migrated to V114), so the unchanged canonical engine runs here. raw_record and ingestion_batch are the projection
-- of ingestion-service's events (same ids); remote_* record what ingestion-service reported for a batch.

CREATE TABLE application (
    application_id uuid NOT NULL,
    application_record_key text NOT NULL,
    state_code text NOT NULL,
    source_system text NOT NULL,
    source_entity text NOT NULL,
    scheme_id text NOT NULL,
    source_application_id text NOT NULL,
    created_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL
);
CREATE TABLE application_observation (
    application_observation_id uuid NOT NULL,
    raw_record_id uuid NOT NULL,
    batch_id uuid NOT NULL,
    state_code text NOT NULL,
    source_system text NOT NULL,
    source_entity text NOT NULL,
    state_pack_version text NOT NULL,
    mapping_version text NOT NULL,
    scheme_catalogue_version text NOT NULL,
    application_id uuid NOT NULL,
    source_scheme_code text,
    scheme_id text NOT NULL,
    source_application_id text NOT NULL,
    applicant_source_record_key text NOT NULL,
    canonical_resident_id uuid,
    submitted_on date,
    current_stage text NOT NULL,
    current_status text NOT NULL,
    next_stage text,
    last_action_date date,
    attributes jsonb DEFAULT '{}'::jsonb NOT NULL,
    metadata jsonb DEFAULT '{}'::jsonb NOT NULL,
    observed_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL
);
CREATE TABLE application_processing_error (
    application_processing_error_id uuid DEFAULT gen_random_uuid() NOT NULL,
    raw_record_id uuid NOT NULL,
    batch_id uuid NOT NULL,
    state_pack_version text NOT NULL,
    error_message text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);
CREATE TABLE application_stage_event (
    application_stage_event_id uuid NOT NULL,
    application_id uuid NOT NULL,
    application_observation_id uuid NOT NULL,
    stage text NOT NULL,
    status text NOT NULL,
    previous_stage text,
    previous_status text,
    occurred_on date,
    recorded_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL
);
CREATE VIEW application_state AS
 SELECT a.application_id,
    a.application_record_key,
    a.state_code,
    a.source_system,
    a.source_entity,
    a.scheme_id,
    a.source_application_id,
    a.created_at,
    o.application_observation_id,
    o.source_scheme_code,
    o.applicant_source_record_key,
    o.submitted_on,
    o.current_stage,
    o.current_status,
    o.next_stage,
    o.last_action_date,
    o.attributes,
    o.metadata,
    o.observed_at,
    o.scheme_catalogue_version
   FROM (application a
     JOIN LATERAL ( SELECT x.application_observation_id,
            x.raw_record_id,
            x.batch_id,
            x.state_code,
            x.source_system,
            x.source_entity,
            x.state_pack_version,
            x.mapping_version,
            x.scheme_catalogue_version,
            x.application_id,
            x.source_scheme_code,
            x.scheme_id,
            x.source_application_id,
            x.applicant_source_record_key,
            x.canonical_resident_id,
            x.submitted_on,
            x.current_stage,
            x.current_status,
            x.next_stage,
            x.last_action_date,
            x.attributes,
            x.metadata,
            x.observed_at
           FROM application_observation x
          WHERE (x.application_id = a.application_id)
          ORDER BY x.observed_at DESC, x.application_observation_id DESC
         LIMIT 1) o ON (true));
CREATE TABLE asset (
    asset_id uuid NOT NULL,
    asset_record_key text NOT NULL,
    state_code text NOT NULL,
    source_system text NOT NULL,
    source_entity text NOT NULL,
    source_asset_id text NOT NULL,
    created_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL
);
CREATE TABLE asset_observation (
    asset_observation_id uuid NOT NULL,
    raw_record_id uuid NOT NULL,
    batch_id uuid NOT NULL,
    state_code text NOT NULL,
    source_system text NOT NULL,
    source_entity text NOT NULL,
    state_pack_version text NOT NULL,
    mapping_version text NOT NULL,
    asset_id uuid NOT NULL,
    source_asset_id text NOT NULL,
    asset_type text NOT NULL,
    description text,
    quantity numeric,
    unit text,
    value numeric,
    linked_source_benefit_id text,
    holder_source_record_key text NOT NULL,
    canonical_resident_id uuid,
    attributes jsonb DEFAULT '{}'::jsonb NOT NULL,
    metadata jsonb DEFAULT '{}'::jsonb NOT NULL,
    observed_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    CONSTRAINT asset_observation_asset_type_check CHECK ((asset_type = ANY (ARRAY['VEHICLE'::text, 'LIVESTOCK'::text, 'SOLAR_INSTALLATION'::text, 'LAND'::text, 'EQUIPMENT'::text, 'OTHER'::text])))
);
CREATE TABLE asset_processing_error (
    asset_processing_error_id uuid DEFAULT gen_random_uuid() NOT NULL,
    raw_record_id uuid NOT NULL,
    batch_id uuid NOT NULL,
    state_pack_version text NOT NULL,
    error_message text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);
CREATE VIEW asset_state AS
 SELECT a.asset_id,
    a.asset_record_key,
    a.state_code,
    a.source_system,
    a.source_entity,
    a.source_asset_id,
    a.created_at,
    o.asset_observation_id,
    o.asset_type,
    o.description,
    o.quantity,
    o.unit,
    o.value,
    o.linked_source_benefit_id,
    o.holder_source_record_key,
    o.attributes,
    o.metadata,
    o.observed_at
   FROM (asset a
     JOIN LATERAL ( SELECT x.asset_observation_id,
            x.raw_record_id,
            x.batch_id,
            x.state_code,
            x.source_system,
            x.source_entity,
            x.state_pack_version,
            x.mapping_version,
            x.asset_id,
            x.source_asset_id,
            x.asset_type,
            x.description,
            x.quantity,
            x.unit,
            x.value,
            x.linked_source_benefit_id,
            x.holder_source_record_key,
            x.canonical_resident_id,
            x.attributes,
            x.metadata,
            x.observed_at
           FROM asset_observation x
          WHERE (x.asset_id = a.asset_id)
          ORDER BY x.observed_at DESC, x.asset_observation_id DESC
         LIMIT 1) o ON (true));
CREATE TABLE benefit (
    benefit_id uuid NOT NULL,
    benefit_record_key text NOT NULL,
    state_code text NOT NULL,
    source_system text NOT NULL,
    source_entity text NOT NULL,
    scheme_id text NOT NULL,
    source_benefit_id text NOT NULL,
    created_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL
);
CREATE TABLE benefit_end_marker (
    benefit_end_marker_id uuid DEFAULT gen_random_uuid() NOT NULL,
    resident_record_key text NOT NULL,
    batch_id uuid NOT NULL,
    ended_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL
);
CREATE TABLE canonical_benefit_observation (
    canonical_benefit_observation_id uuid NOT NULL,
    raw_record_id uuid NOT NULL,
    batch_id uuid NOT NULL,
    state_code text NOT NULL,
    source_system text NOT NULL,
    source_entity text NOT NULL,
    state_pack_version text NOT NULL,
    mapping_version text NOT NULL,
    scheme_catalogue_version text NOT NULL,
    resident_record_key text NOT NULL,
    canonical_resident_id uuid NOT NULL,
    benefit_id uuid,
    benefit_record_key text,
    source_scheme_code text,
    scheme_id text,
    source_benefit_id text,
    beneficiary_level text,
    family_record_key text,
    source_family_id text,
    benefit_status text,
    start_date date,
    entitlement jsonb DEFAULT '{}'::jsonb NOT NULL,
    benefit_payload jsonb DEFAULT '{}'::jsonb NOT NULL,
    observed_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    CONSTRAINT canonical_benefit_observation_beneficiary_level_check CHECK ((beneficiary_level = ANY (ARRAY['FAMILY'::text, 'RESIDENT'::text]))),
    CONSTRAINT canonical_benefit_observation_benefit_status_check CHECK ((benefit_status = ANY (ARRAY['ACTIVE'::text, 'SUSPENDED'::text, 'CLOSED'::text]))),
    CONSTRAINT canonical_benefit_observation_check CHECK (((benefit_id IS NULL) = (benefit_record_key IS NULL))),
    CONSTRAINT canonical_benefit_observation_check1 CHECK (((benefit_id IS NULL) OR ((scheme_id IS NOT NULL) AND (source_benefit_id IS NOT NULL) AND (beneficiary_level IS NOT NULL) AND (benefit_status IS NOT NULL)))),
    CONSTRAINT canonical_benefit_observation_check2 CHECK (((beneficiary_level IS DISTINCT FROM 'FAMILY'::text) OR (family_record_key IS NOT NULL)))
);
COMMENT ON TABLE canonical_benefit_observation IS 'A raw delivery processed with Benefit mapping rules: the benefit (if any) it says the resident holds';
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
CREATE VIEW canonical_benefit_current AS
 SELECT o.canonical_benefit_observation_id,
    o.raw_record_id,
    o.batch_id,
    o.state_code,
    o.source_system,
    o.source_entity,
    o.state_pack_version,
    o.mapping_version,
    o.scheme_catalogue_version,
    o.resident_record_key,
    c.canonical_resident_id,
    o.benefit_id,
    o.benefit_record_key,
    o.source_scheme_code,
    o.scheme_id,
    o.source_benefit_id,
    o.beneficiary_level,
    o.family_record_key,
    o.source_family_id,
    o.benefit_status,
    o.start_date,
    o.entitlement,
    o.benefit_payload,
    o.observed_at
   FROM (( SELECT DISTINCT ON (canonical_benefit_observation.resident_record_key) canonical_benefit_observation.canonical_benefit_observation_id,
            canonical_benefit_observation.raw_record_id,
            canonical_benefit_observation.batch_id,
            canonical_benefit_observation.state_code,
            canonical_benefit_observation.source_system,
            canonical_benefit_observation.source_entity,
            canonical_benefit_observation.state_pack_version,
            canonical_benefit_observation.mapping_version,
            canonical_benefit_observation.scheme_catalogue_version,
            canonical_benefit_observation.resident_record_key,
            canonical_benefit_observation.canonical_resident_id,
            canonical_benefit_observation.benefit_id,
            canonical_benefit_observation.benefit_record_key,
            canonical_benefit_observation.source_scheme_code,
            canonical_benefit_observation.scheme_id,
            canonical_benefit_observation.source_benefit_id,
            canonical_benefit_observation.beneficiary_level,
            canonical_benefit_observation.family_record_key,
            canonical_benefit_observation.source_family_id,
            canonical_benefit_observation.benefit_status,
            canonical_benefit_observation.start_date,
            canonical_benefit_observation.entitlement,
            canonical_benefit_observation.benefit_payload,
            canonical_benefit_observation.observed_at
           FROM canonical_benefit_observation
          ORDER BY canonical_benefit_observation.resident_record_key, canonical_benefit_observation.observed_at DESC, canonical_benefit_observation.canonical_benefit_observation_id DESC) o
     JOIN canonical_resident_current c ON ((c.source_record_key = o.resident_record_key)))
  WHERE ((o.benefit_id IS NOT NULL) AND (NOT (EXISTS ( SELECT 1
           FROM benefit_end_marker m
          WHERE ((m.resident_record_key = o.resident_record_key) AND (m.ended_at > o.observed_at))))));
CREATE TABLE canonical_benefit_disbursement (
    canonical_benefit_disbursement_id uuid NOT NULL,
    benefit_id uuid NOT NULL,
    canonical_benefit_observation_id uuid NOT NULL,
    disbursed_on date NOT NULL,
    amount numeric(14,2),
    quantity numeric(14,3),
    unit text,
    disbursement_reference text,
    created_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL
);
CREATE TABLE canonical_benefit_processing_error (
    canonical_benefit_processing_error_id uuid DEFAULT gen_random_uuid() NOT NULL,
    raw_record_id uuid NOT NULL,
    batch_id uuid NOT NULL,
    state_pack_version text NOT NULL,
    error_message text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);
CREATE TABLE canonical_benefit_warning (
    canonical_benefit_warning_id uuid DEFAULT gen_random_uuid() NOT NULL,
    raw_record_id uuid NOT NULL,
    batch_id uuid NOT NULL,
    state_pack_version text NOT NULL,
    warning_type text NOT NULL,
    source_code text,
    message text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    resolved_at timestamp with time zone
);
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
CREATE TABLE canonical_family_processing_error (
    canonical_family_processing_error_id uuid DEFAULT gen_random_uuid() NOT NULL,
    raw_record_id uuid NOT NULL,
    batch_id uuid NOT NULL,
    state_pack_version text NOT NULL,
    error_message text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);
CREATE TABLE canonical_processing_chunk (
    batch_id uuid NOT NULL,
    chunk_number integer NOT NULL,
    rows_read integer NOT NULL,
    rows_mapped integer NOT NULL,
    rows_skipped integer NOT NULL,
    rows_failed integer NOT NULL,
    started_at timestamp with time zone NOT NULL,
    completed_at timestamp with time zone NOT NULL,
    rows_held integer DEFAULT 0 NOT NULL
);
CREATE TABLE canonical_processing_error (
    canonical_processing_error_id uuid DEFAULT gen_random_uuid() NOT NULL,
    raw_record_id uuid NOT NULL,
    batch_id uuid NOT NULL,
    canonical_entity_type text DEFAULT 'Resident'::text NOT NULL,
    state_pack_version text NOT NULL,
    error_message text NOT NULL,
    occurred_at timestamp with time zone DEFAULT now() NOT NULL,
    reason_code text DEFAULT 'OTHER'::text NOT NULL,
    pack_id text,
    source_id text,
    draft_id uuid,
    fix_kind text,
    fix_key text
);
CREATE TABLE canonical_processing_progress (
    batch_id uuid NOT NULL,
    chunk_size integer NOT NULL,
    last_committed_chunk integer DEFAULT 0 NOT NULL,
    status text NOT NULL,
    started_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT canonical_processing_progress_status_check CHECK ((status = ANY (ARRAY['RUNNING'::text, 'COMPLETED'::text])))
);
CREATE VIEW canonical_resident_match_v AS
 SELECT canonical_resident_id,
    source_record_key,
    state_code,
    source_system,
    (canonical_payload ->> 'nameNormalised'::text) AS name_normalised,
    (jsonb_path_query_first(canonical_payload, '$."relations"[*]?(@."type" == "FATHER")'::jsonpath) ->> 'nameNormalised'::text) AS father_name_normalised,
    COALESCE(((canonical_payload ->> 'birthYear'::text))::integer,
        CASE
            WHEN ((canonical_payload ->> 'dateOfBirth'::text) ~ '^[0-9]{4}-'::text) THEN ("left"((canonical_payload ->> 'dateOfBirth'::text), 4))::integer
            ELSE NULL::integer
        END) AS birth_year,
    COALESCE((canonical_payload ->> 'dateOfBirthPrecision'::text),
        CASE
            WHEN (canonical_payload ? 'dateOfBirth'::text) THEN 'DAY'::text
            ELSE NULL::text
        END) AS dob_precision,
    (canonical_payload #>> '{extensions,uddp.geography,village,lgd}'::text[]) AS village_lgd,
    (canonical_payload #>> '{extensions,uddp.identifiers,AADHAAR,last4}'::text[]) AS aadhaar_last4,
        CASE
            WHEN (length((canonical_payload ->> 'mobile'::text)) > 4) THEN (repeat('X'::text, (length((canonical_payload ->> 'mobile'::text)) - 4)) || "right"((canonical_payload ->> 'mobile'::text), 4))
            ELSE NULL::text
        END AS mobile_masked
   FROM canonical_resident_current c;
CREATE TABLE canonical_validation_warning (
    canonical_validation_warning_id uuid DEFAULT gen_random_uuid() NOT NULL,
    canonical_resident_id uuid NOT NULL,
    raw_record_id uuid NOT NULL,
    batch_id uuid NOT NULL,
    state_pack_version text NOT NULL,
    field_path text NOT NULL,
    rule text NOT NULL,
    message text NOT NULL,
    occurred_at timestamp with time zone DEFAULT now() NOT NULL
);
CREATE TABLE dq_batch_evaluation (
    dq_evaluation_id uuid DEFAULT gen_random_uuid() NOT NULL,
    batch_id uuid NOT NULL,
    state_code text NOT NULL,
    source_system text NOT NULL,
    state_pack_version text NOT NULL,
    rule_config_version text NOT NULL,
    evaluated_at timestamp with time zone DEFAULT now() NOT NULL,
    records_evaluated integer NOT NULL,
    rules_evaluated integer NOT NULL,
    error_count integer NOT NULL,
    warning_count integer NOT NULL,
    affected_record_count integer NOT NULL
);
CREATE TABLE dq_result (
    dq_result_id uuid DEFAULT gen_random_uuid() NOT NULL,
    dq_evaluation_id uuid NOT NULL,
    raw_record_id uuid NOT NULL,
    canonical_resident_id uuid,
    rule_id text NOT NULL,
    rule_type text NOT NULL,
    severity text NOT NULL,
    field_path text,
    message text NOT NULL,
    evidence jsonb DEFAULT '{}'::jsonb NOT NULL,
    state_pack_version text NOT NULL,
    rule_config_version text NOT NULL,
    evaluated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT dq_result_severity_check CHECK ((severity = ANY (ARRAY['ERROR'::text, 'WARNING'::text, 'SEND_TO_STEWARD'::text])))
);
CREATE TABLE ingestion_batch (
    batch_id uuid NOT NULL,
    state_code text NOT NULL,
    source_system text NOT NULL,
    source_type text NOT NULL,
    source_schema_version text,
    state_pack_version text NOT NULL,
    pipeline_template text NOT NULL,
    started_at timestamp with time zone DEFAULT now() NOT NULL,
    completed_at timestamp with time zone,
    status text NOT NULL,
    record_count integer DEFAULT 0 NOT NULL,
    error_count integer DEFAULT 0 NOT NULL,
    error_message text,
    remote_status text,
    remote_record_count integer,
    remote_message text,
    CONSTRAINT ingestion_batch_remote_status_check CHECK ((remote_status = ANY (ARRAY['COMPLETED'::text, 'FAILED'::text]))),
    CONSTRAINT ingestion_batch_status_check CHECK ((status = ANY (ARRAY['STARTED'::text, 'COMPLETED'::text, 'FAILED'::text])))
);
CREATE TABLE raw_record (
    raw_record_id uuid DEFAULT gen_random_uuid() NOT NULL,
    batch_id uuid NOT NULL,
    state_code text NOT NULL,
    source_system text NOT NULL,
    source_record_id text,
    ingested_at timestamp with time zone DEFAULT now() NOT NULL,
    raw_payload text NOT NULL,
    source_metadata jsonb NOT NULL,
    state_pack_version text NOT NULL,
    source_schema_version text,
    pipeline_template text NOT NULL,
    payload_purged_at timestamp with time zone,
    source_line integer
);
CREATE TABLE reference_dataset (
    reference_dataset_id uuid DEFAULT gen_random_uuid() NOT NULL,
    reference_set text NOT NULL,
    version text NOT NULL,
    description text NOT NULL,
    status text DEFAULT 'ACTIVE'::text NOT NULL,
    published_at timestamp with time zone DEFAULT now() NOT NULL
);
CREATE TABLE reference_value (
    reference_value_id uuid DEFAULT gen_random_uuid() NOT NULL,
    reference_dataset_id uuid NOT NULL,
    code text NOT NULL,
    display_name text,
    metadata jsonb DEFAULT '{}'::jsonb NOT NULL
);
ALTER TABLE ONLY application
    ADD CONSTRAINT application_application_record_key_key UNIQUE (application_record_key);
ALTER TABLE ONLY application_observation
    ADD CONSTRAINT application_observation_pkey PRIMARY KEY (application_observation_id);
ALTER TABLE ONLY application_observation
    ADD CONSTRAINT application_observation_raw_record_id_state_pack_version_key UNIQUE (raw_record_id, state_pack_version);
ALTER TABLE ONLY application
    ADD CONSTRAINT application_pkey PRIMARY KEY (application_id);
ALTER TABLE ONLY application_processing_error
    ADD CONSTRAINT application_processing_error_pkey PRIMARY KEY (application_processing_error_id);
ALTER TABLE ONLY application_stage_event
    ADD CONSTRAINT application_stage_event_pkey PRIMARY KEY (application_stage_event_id);
ALTER TABLE ONLY asset
    ADD CONSTRAINT asset_asset_record_key_key UNIQUE (asset_record_key);
ALTER TABLE ONLY asset_observation
    ADD CONSTRAINT asset_observation_pkey PRIMARY KEY (asset_observation_id);
ALTER TABLE ONLY asset_observation
    ADD CONSTRAINT asset_observation_raw_record_id_state_pack_version_key UNIQUE (raw_record_id, state_pack_version);
ALTER TABLE ONLY asset
    ADD CONSTRAINT asset_pkey PRIMARY KEY (asset_id);
ALTER TABLE ONLY asset_processing_error
    ADD CONSTRAINT asset_processing_error_pkey PRIMARY KEY (asset_processing_error_id);
ALTER TABLE ONLY benefit
    ADD CONSTRAINT benefit_benefit_record_key_key UNIQUE (benefit_record_key);
ALTER TABLE ONLY benefit_end_marker
    ADD CONSTRAINT benefit_end_marker_pkey PRIMARY KEY (benefit_end_marker_id);
ALTER TABLE ONLY benefit
    ADD CONSTRAINT benefit_pkey PRIMARY KEY (benefit_id);
ALTER TABLE ONLY canonical_benefit_disbursement
    ADD CONSTRAINT canonical_benefit_disbursement_pkey PRIMARY KEY (canonical_benefit_disbursement_id);
ALTER TABLE ONLY canonical_benefit_observation
    ADD CONSTRAINT canonical_benefit_observation_pkey PRIMARY KEY (canonical_benefit_observation_id);
ALTER TABLE ONLY canonical_benefit_processing_error
    ADD CONSTRAINT canonical_benefit_processing_error_pkey PRIMARY KEY (canonical_benefit_processing_error_id);
ALTER TABLE ONLY canonical_benefit_warning
    ADD CONSTRAINT canonical_benefit_warning_pkey PRIMARY KEY (canonical_benefit_warning_id);
ALTER TABLE ONLY canonical_family_membership
    ADD CONSTRAINT canonical_family_membership_canonical_family_observation_id_key UNIQUE (canonical_family_observation_id, family_record_key);
ALTER TABLE ONLY canonical_family_membership
    ADD CONSTRAINT canonical_family_membership_pkey PRIMARY KEY (canonical_family_membership_id);
ALTER TABLE ONLY canonical_family_observation
    ADD CONSTRAINT canonical_family_observation_pkey PRIMARY KEY (canonical_family_observation_id);
ALTER TABLE ONLY canonical_family_observation
    ADD CONSTRAINT canonical_family_observation_raw_record_id_state_pack_versi_key UNIQUE (raw_record_id, state_pack_version);
ALTER TABLE ONLY canonical_family_processing_error
    ADD CONSTRAINT canonical_family_processing_error_pkey PRIMARY KEY (canonical_family_processing_error_id);
ALTER TABLE ONLY canonical_processing_chunk
    ADD CONSTRAINT canonical_processing_chunk_pkey PRIMARY KEY (batch_id, chunk_number);
ALTER TABLE ONLY canonical_processing_error
    ADD CONSTRAINT canonical_processing_error_pkey PRIMARY KEY (canonical_processing_error_id);
ALTER TABLE ONLY canonical_processing_progress
    ADD CONSTRAINT canonical_processing_progress_pkey PRIMARY KEY (batch_id);
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
ALTER TABLE ONLY canonical_validation_warning
    ADD CONSTRAINT canonical_validation_warning_pkey PRIMARY KEY (canonical_validation_warning_id);
ALTER TABLE ONLY dq_batch_evaluation
    ADD CONSTRAINT dq_batch_evaluation_pkey PRIMARY KEY (dq_evaluation_id);
ALTER TABLE ONLY dq_result
    ADD CONSTRAINT dq_result_pkey PRIMARY KEY (dq_result_id);
ALTER TABLE ONLY ingestion_batch
    ADD CONSTRAINT ingestion_batch_pkey PRIMARY KEY (batch_id);
ALTER TABLE ONLY raw_record
    ADD CONSTRAINT raw_record_pkey PRIMARY KEY (raw_record_id);
ALTER TABLE ONLY reference_dataset
    ADD CONSTRAINT reference_dataset_pkey PRIMARY KEY (reference_dataset_id);
ALTER TABLE ONLY reference_dataset
    ADD CONSTRAINT reference_dataset_reference_set_version_key UNIQUE (reference_set, version);
ALTER TABLE ONLY reference_value
    ADD CONSTRAINT reference_value_pkey PRIMARY KEY (reference_value_id);
ALTER TABLE ONLY reference_value
    ADD CONSTRAINT reference_value_reference_dataset_id_code_key UNIQUE (reference_dataset_id, code);
CREATE INDEX application_observation_applicant_idx ON application_observation USING btree (applicant_source_record_key);
CREATE INDEX application_observation_application_idx ON application_observation USING btree (application_id, observed_at);
CREATE INDEX application_scheme_idx ON application USING btree (scheme_id);
CREATE INDEX application_stage_event_application_idx ON application_stage_event USING btree (application_id, recorded_at);
CREATE INDEX asset_observation_asset_idx ON asset_observation USING btree (asset_id, observed_at);
CREATE INDEX asset_observation_holder_idx ON asset_observation USING btree (holder_source_record_key);
CREATE INDEX benefit_end_marker_resident_idx ON benefit_end_marker USING btree (resident_record_key, ended_at);
CREATE INDEX benefit_scheme_idx ON benefit USING btree (scheme_id);
CREATE UNIQUE INDEX canonical_benefit_disbursement_once_idx ON canonical_benefit_disbursement USING btree (benefit_id, disbursed_on, COALESCE(disbursement_reference, ''::text));
CREATE INDEX canonical_benefit_observation_benefit_idx ON canonical_benefit_observation USING btree (benefit_id);
CREATE UNIQUE INDEX canonical_benefit_observation_mapped_once_idx ON canonical_benefit_observation USING btree (raw_record_id, state_pack_version) WHERE (scheme_id IS DISTINCT FROM 'UNMAPPED'::text);
CREATE INDEX canonical_benefit_observation_resident_idx ON canonical_benefit_observation USING btree (resident_record_key, observed_at);
CREATE UNIQUE INDEX canonical_benefit_observation_unmapped_once_idx ON canonical_benefit_observation USING btree (raw_record_id, state_pack_version) WHERE (scheme_id = 'UNMAPPED'::text);
CREATE UNIQUE INDEX canonical_benefit_warning_once_idx ON canonical_benefit_warning USING btree (raw_record_id, state_pack_version, warning_type);
CREATE INDEX canonical_benefit_warning_open_idx ON canonical_benefit_warning USING btree (batch_id) WHERE (resolved_at IS NULL);
CREATE INDEX canonical_family_membership_family_idx ON canonical_family_membership USING btree (family_record_key);
CREATE INDEX canonical_family_observation_resident_idx ON canonical_family_observation USING btree (resident_record_key, observed_at);
CREATE INDEX canonical_processing_error_batch_id_idx ON canonical_processing_error USING btree (batch_id);
CREATE INDEX canonical_resident_batch_id_idx ON canonical_resident USING btree (batch_id);
CREATE INDEX canonical_validation_warning_canonical_idx ON canonical_validation_warning USING btree (canonical_resident_id);
CREATE INDEX dq_batch_evaluation_batch_idx ON dq_batch_evaluation USING btree (batch_id, evaluated_at DESC);
CREATE INDEX dq_result_evaluation_idx ON dq_result USING btree (dq_evaluation_id, rule_id);
CREATE INDEX raw_record_batch_id_idx ON raw_record USING btree (batch_id);
CREATE FUNCTION application_stage_event_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'application_stage_event is append-only';
END $$;
CREATE TRIGGER application_stage_event_append_only BEFORE DELETE OR UPDATE ON application_stage_event FOR EACH ROW EXECUTE FUNCTION application_stage_event_immutable();
ALTER TABLE ONLY application_observation
    ADD CONSTRAINT application_observation_application_id_fkey FOREIGN KEY (application_id) REFERENCES application(application_id);
ALTER TABLE ONLY application_observation
    ADD CONSTRAINT application_observation_batch_id_fkey FOREIGN KEY (batch_id) REFERENCES ingestion_batch(batch_id);
ALTER TABLE ONLY application_observation
    ADD CONSTRAINT application_observation_canonical_resident_id_fkey FOREIGN KEY (canonical_resident_id) REFERENCES canonical_resident(canonical_resident_id);
ALTER TABLE ONLY application_observation
    ADD CONSTRAINT application_observation_raw_record_id_fkey FOREIGN KEY (raw_record_id) REFERENCES raw_record(raw_record_id);
ALTER TABLE ONLY application_processing_error
    ADD CONSTRAINT application_processing_error_batch_id_fkey FOREIGN KEY (batch_id) REFERENCES ingestion_batch(batch_id);
ALTER TABLE ONLY application_processing_error
    ADD CONSTRAINT application_processing_error_raw_record_id_fkey FOREIGN KEY (raw_record_id) REFERENCES raw_record(raw_record_id);
ALTER TABLE ONLY application_stage_event
    ADD CONSTRAINT application_stage_event_application_id_fkey FOREIGN KEY (application_id) REFERENCES application(application_id);
ALTER TABLE ONLY application_stage_event
    ADD CONSTRAINT application_stage_event_application_observation_id_fkey FOREIGN KEY (application_observation_id) REFERENCES application_observation(application_observation_id);
ALTER TABLE ONLY asset_observation
    ADD CONSTRAINT asset_observation_asset_id_fkey FOREIGN KEY (asset_id) REFERENCES asset(asset_id);
ALTER TABLE ONLY asset_observation
    ADD CONSTRAINT asset_observation_batch_id_fkey FOREIGN KEY (batch_id) REFERENCES ingestion_batch(batch_id);
ALTER TABLE ONLY asset_observation
    ADD CONSTRAINT asset_observation_canonical_resident_id_fkey FOREIGN KEY (canonical_resident_id) REFERENCES canonical_resident(canonical_resident_id);
ALTER TABLE ONLY asset_observation
    ADD CONSTRAINT asset_observation_raw_record_id_fkey FOREIGN KEY (raw_record_id) REFERENCES raw_record(raw_record_id);
ALTER TABLE ONLY asset_processing_error
    ADD CONSTRAINT asset_processing_error_batch_id_fkey FOREIGN KEY (batch_id) REFERENCES ingestion_batch(batch_id);
ALTER TABLE ONLY asset_processing_error
    ADD CONSTRAINT asset_processing_error_raw_record_id_fkey FOREIGN KEY (raw_record_id) REFERENCES raw_record(raw_record_id);
ALTER TABLE ONLY benefit_end_marker
    ADD CONSTRAINT benefit_end_marker_batch_id_fkey FOREIGN KEY (batch_id) REFERENCES ingestion_batch(batch_id);
ALTER TABLE ONLY canonical_benefit_disbursement
    ADD CONSTRAINT canonical_benefit_disbursemen_canonical_benefit_observatio_fkey FOREIGN KEY (canonical_benefit_observation_id) REFERENCES canonical_benefit_observation(canonical_benefit_observation_id);
ALTER TABLE ONLY canonical_benefit_disbursement
    ADD CONSTRAINT canonical_benefit_disbursement_benefit_id_fkey FOREIGN KEY (benefit_id) REFERENCES benefit(benefit_id);
ALTER TABLE ONLY canonical_benefit_observation
    ADD CONSTRAINT canonical_benefit_observation_batch_id_fkey FOREIGN KEY (batch_id) REFERENCES ingestion_batch(batch_id);
ALTER TABLE ONLY canonical_benefit_observation
    ADD CONSTRAINT canonical_benefit_observation_benefit_id_fkey FOREIGN KEY (benefit_id) REFERENCES benefit(benefit_id);
ALTER TABLE ONLY canonical_benefit_observation
    ADD CONSTRAINT canonical_benefit_observation_canonical_resident_id_fkey FOREIGN KEY (canonical_resident_id) REFERENCES canonical_resident(canonical_resident_id);
ALTER TABLE ONLY canonical_benefit_observation
    ADD CONSTRAINT canonical_benefit_observation_raw_record_id_fkey FOREIGN KEY (raw_record_id) REFERENCES raw_record(raw_record_id);
ALTER TABLE ONLY canonical_benefit_processing_error
    ADD CONSTRAINT canonical_benefit_processing_error_batch_id_fkey FOREIGN KEY (batch_id) REFERENCES ingestion_batch(batch_id);
ALTER TABLE ONLY canonical_benefit_processing_error
    ADD CONSTRAINT canonical_benefit_processing_error_raw_record_id_fkey FOREIGN KEY (raw_record_id) REFERENCES raw_record(raw_record_id);
ALTER TABLE ONLY canonical_benefit_warning
    ADD CONSTRAINT canonical_benefit_warning_batch_id_fkey FOREIGN KEY (batch_id) REFERENCES ingestion_batch(batch_id);
ALTER TABLE ONLY canonical_benefit_warning
    ADD CONSTRAINT canonical_benefit_warning_raw_record_id_fkey FOREIGN KEY (raw_record_id) REFERENCES raw_record(raw_record_id);
ALTER TABLE ONLY canonical_family_membership
    ADD CONSTRAINT canonical_family_membership_canonical_family_observation_i_fkey FOREIGN KEY (canonical_family_observation_id) REFERENCES canonical_family_observation(canonical_family_observation_id);
ALTER TABLE ONLY canonical_family_observation
    ADD CONSTRAINT canonical_family_observation_batch_id_fkey FOREIGN KEY (batch_id) REFERENCES ingestion_batch(batch_id);
ALTER TABLE ONLY canonical_family_observation
    ADD CONSTRAINT canonical_family_observation_canonical_resident_id_fkey FOREIGN KEY (canonical_resident_id) REFERENCES canonical_resident(canonical_resident_id);
ALTER TABLE ONLY canonical_family_observation
    ADD CONSTRAINT canonical_family_observation_raw_record_id_fkey FOREIGN KEY (raw_record_id) REFERENCES raw_record(raw_record_id);
ALTER TABLE ONLY canonical_family_processing_error
    ADD CONSTRAINT canonical_family_processing_error_batch_id_fkey FOREIGN KEY (batch_id) REFERENCES ingestion_batch(batch_id);
ALTER TABLE ONLY canonical_family_processing_error
    ADD CONSTRAINT canonical_family_processing_error_raw_record_id_fkey FOREIGN KEY (raw_record_id) REFERENCES raw_record(raw_record_id);
ALTER TABLE ONLY canonical_processing_chunk
    ADD CONSTRAINT canonical_processing_chunk_batch_id_fkey FOREIGN KEY (batch_id) REFERENCES ingestion_batch(batch_id);
ALTER TABLE ONLY canonical_processing_error
    ADD CONSTRAINT canonical_processing_error_batch_id_fkey FOREIGN KEY (batch_id) REFERENCES ingestion_batch(batch_id);
ALTER TABLE ONLY canonical_processing_error
    ADD CONSTRAINT canonical_processing_error_raw_record_id_fkey FOREIGN KEY (raw_record_id) REFERENCES raw_record(raw_record_id);
ALTER TABLE ONLY canonical_processing_progress
    ADD CONSTRAINT canonical_processing_progress_batch_id_fkey FOREIGN KEY (batch_id) REFERENCES ingestion_batch(batch_id);
ALTER TABLE ONLY canonical_resident
    ADD CONSTRAINT canonical_resident_batch_id_fkey FOREIGN KEY (batch_id) REFERENCES ingestion_batch(batch_id);
ALTER TABLE ONLY canonical_resident
    ADD CONSTRAINT canonical_resident_raw_record_id_fkey FOREIGN KEY (raw_record_id) REFERENCES raw_record(raw_record_id);
ALTER TABLE ONLY canonical_resident_version
    ADD CONSTRAINT canonical_resident_version_canonical_resident_id_fkey FOREIGN KEY (canonical_resident_id) REFERENCES canonical_resident(canonical_resident_id);
ALTER TABLE ONLY canonical_resident_version
    ADD CONSTRAINT canonical_resident_version_supersedes_canonical_id_fkey FOREIGN KEY (supersedes_canonical_id) REFERENCES canonical_resident(canonical_resident_id);
ALTER TABLE ONLY canonical_validation_warning
    ADD CONSTRAINT canonical_validation_warning_batch_id_fkey FOREIGN KEY (batch_id) REFERENCES ingestion_batch(batch_id);
ALTER TABLE ONLY canonical_validation_warning
    ADD CONSTRAINT canonical_validation_warning_canonical_resident_id_fkey FOREIGN KEY (canonical_resident_id) REFERENCES canonical_resident(canonical_resident_id);
ALTER TABLE ONLY canonical_validation_warning
    ADD CONSTRAINT canonical_validation_warning_raw_record_id_fkey FOREIGN KEY (raw_record_id) REFERENCES raw_record(raw_record_id);
ALTER TABLE ONLY dq_batch_evaluation
    ADD CONSTRAINT dq_batch_evaluation_batch_id_fkey FOREIGN KEY (batch_id) REFERENCES ingestion_batch(batch_id);
ALTER TABLE ONLY dq_result
    ADD CONSTRAINT dq_result_canonical_resident_id_fkey FOREIGN KEY (canonical_resident_id) REFERENCES canonical_resident(canonical_resident_id);
ALTER TABLE ONLY dq_result
    ADD CONSTRAINT dq_result_dq_evaluation_id_fkey FOREIGN KEY (dq_evaluation_id) REFERENCES dq_batch_evaluation(dq_evaluation_id);
ALTER TABLE ONLY dq_result
    ADD CONSTRAINT dq_result_raw_record_id_fkey FOREIGN KEY (raw_record_id) REFERENCES raw_record(raw_record_id);
ALTER TABLE ONLY raw_record
    ADD CONSTRAINT raw_record_batch_id_fkey FOREIGN KEY (batch_id) REFERENCES ingestion_batch(batch_id);
ALTER TABLE ONLY reference_value
    ADD CONSTRAINT reference_value_reference_dataset_id_fkey FOREIGN KEY (reference_dataset_id) REFERENCES reference_dataset(reference_dataset_id);

-- remote_* (above) record what ingestion-service reported; processed_at marks a batch this service has mapped.
ALTER TABLE ingestion_batch ADD COLUMN processed_at timestamptz;

-- The engine's failure report links a Studio draft when one exists; drafts are statepack-service's, so none here.
CREATE VIEW studio_draft AS
SELECT NULL::uuid AS draft_id, NULL::text AS pack_id, NULL::timestamptz AS created_at WHERE false;

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
