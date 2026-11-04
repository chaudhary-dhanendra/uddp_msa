-- registry-service owns Golden Residents, Golden Families, their versions, provenance, history and UDDP IDs
-- (docs/msa-design.md, section 2). This is the monolith's end-state DDL for these tables (V6..V83, generated with
-- pg_dump from a database migrated to V114) without the foreign keys to canonical, matching and stewardship rows,
-- which live in other services: those ids are logical references to the projections of V3. History tables stay
-- append-only, Golden IDs are never reused (ADR-0001, ADR-0006).

CREATE TABLE golden_family (
    golden_family_id uuid NOT NULL,
    family_record_key text NOT NULL,
    state_code text NOT NULL,
    source_system text NOT NULL,
    source_family_id text NOT NULL,
    status text DEFAULT 'ACTIVE'::text NOT NULL,
    created_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    retired_at timestamp with time zone,
    CONSTRAINT golden_family_status_check CHECK ((status = ANY (ARRAY['ACTIVE'::text, 'RETIRED'::text])))
);
CREATE TABLE golden_family_membership (
    golden_family_membership_id uuid NOT NULL,
    golden_family_id uuid NOT NULL,
    resident_record_key text NOT NULL,
    golden_resident_id uuid NOT NULL,
    relationship_type text NOT NULL,
    canonical_family_membership_id uuid NOT NULL,
    added_in_version integer NOT NULL,
    removed_in_version integer,
    effective_from timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    effective_to timestamp with time zone,
    CONSTRAINT golden_family_membership_check CHECK (((effective_to IS NULL) OR (effective_to >= effective_from)))
);
CREATE TABLE golden_resident (
    golden_resident_id uuid NOT NULL,
    matching_run_id uuid NOT NULL,
    survivorship_policy_version text NOT NULL,
    member_set_key text NOT NULL,
    golden_payload jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    status text DEFAULT 'ACTIVE'::text NOT NULL,
    retired_at timestamp with time zone,
    merged_into_id uuid,
    split_from_id uuid,
    CONSTRAINT golden_resident_status_check CHECK ((status = ANY (ARRAY['ACTIVE'::text, 'RETIRED'::text])))
);
COMMENT ON TABLE golden_resident IS 'Stable Golden identity. matching_run_id, survivorship_policy_version, member_set_key and golden_payload (with golden_resident_member and golden_resident_field_provenance) describe its founding build; current values are in golden_resident_version';
CREATE TABLE golden_resident_membership (
    golden_resident_membership_id uuid DEFAULT gen_random_uuid() NOT NULL,
    golden_resident_id uuid NOT NULL,
    canonical_resident_id uuid NOT NULL,
    source_record_key text NOT NULL,
    added_in_version integer NOT NULL,
    removed_in_version integer,
    effective_from timestamp with time zone DEFAULT now() NOT NULL,
    effective_to timestamp with time zone,
    CONSTRAINT golden_resident_membership_check CHECK (((effective_to IS NULL) OR (effective_to >= effective_from)))
);
CREATE TABLE golden_build (
    golden_build_id uuid NOT NULL,
    matching_run_id uuid,
    survivorship_policy_version text NOT NULL,
    mode text NOT NULL,
    started_at timestamp with time zone DEFAULT now() NOT NULL,
    completed_at timestamp with time zone,
    summary jsonb DEFAULT '{}'::jsonb NOT NULL,
    CONSTRAINT golden_build_mode_check CHECK ((mode = ANY (ARRAY['FULL'::text, 'INCREMENTAL'::text])))
);
CREATE TABLE golden_family_event (
    golden_family_event_id uuid DEFAULT gen_random_uuid() NOT NULL,
    event_sequence bigint NOT NULL,
    golden_family_id uuid NOT NULL,
    event_type text NOT NULL,
    golden_build_id uuid,
    version_number integer,
    resident_record_key text,
    golden_resident_id uuid,
    detail jsonb DEFAULT '{}'::jsonb NOT NULL,
    occurred_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    CONSTRAINT golden_family_event_event_type_check CHECK ((event_type = ANY (ARRAY['CREATED'::text, 'MEMBER_ADDED'::text, 'MEMBER_REMOVED'::text, 'MEMBER_CHANGED'::text, 'RETIRED'::text, 'REACTIVATED'::text])))
);
ALTER TABLE golden_family_event ALTER COLUMN event_sequence ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME golden_family_event_event_sequence_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);
CREATE TABLE golden_family_version (
    golden_family_version_id uuid NOT NULL,
    golden_family_id uuid NOT NULL,
    version_number integer NOT NULL,
    golden_build_id uuid,
    family_payload jsonb NOT NULL,
    change_reasons jsonb DEFAULT '[]'::jsonb NOT NULL,
    effective_from timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    effective_to timestamp with time zone,
    CONSTRAINT golden_family_version_check CHECK (((effective_to IS NULL) OR (effective_to >= effective_from))),
    CONSTRAINT golden_family_version_version_number_check CHECK ((version_number >= 1))
);
CREATE TABLE golden_resident_event (
    golden_resident_event_id uuid DEFAULT gen_random_uuid() NOT NULL,
    event_sequence bigint NOT NULL,
    golden_resident_id uuid NOT NULL,
    event_type text NOT NULL,
    golden_build_id uuid,
    version_number integer,
    related_golden_resident_id uuid,
    canonical_resident_id uuid,
    source_record_key text,
    field_path text,
    old_value jsonb,
    new_value jsonb,
    detail jsonb DEFAULT '{}'::jsonb NOT NULL,
    occurred_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT golden_resident_event_event_type_check CHECK ((event_type = ANY (ARRAY['CREATED'::text, 'MEMBER_ADDED'::text, 'MEMBER_REMOVED'::text, 'MEMBER_VERSION_CHANGED'::text, 'VALUE_CHANGED'::text, 'MERGED'::text, 'SPLIT'::text, 'RETIRED'::text])))
);
ALTER TABLE golden_resident_event ALTER COLUMN event_sequence ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME golden_resident_event_event_sequence_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);
CREATE TABLE golden_resident_field_provenance (
    golden_resident_id uuid NOT NULL,
    field_path text NOT NULL,
    canonical_resident_id uuid NOT NULL,
    selected_value jsonb NOT NULL,
    rule_applied text NOT NULL,
    contributing_canonical_ids jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    survivorship_policy_version text NOT NULL
);
CREATE TABLE golden_resident_member (
    golden_resident_id uuid NOT NULL,
    canonical_resident_id uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);
CREATE TABLE golden_resident_version (
    golden_resident_version_id uuid DEFAULT gen_random_uuid() NOT NULL,
    golden_resident_id uuid NOT NULL,
    version_number integer NOT NULL,
    golden_build_id uuid,
    matching_run_id uuid,
    survivorship_policy_version text NOT NULL,
    member_set_key text NOT NULL,
    golden_payload jsonb NOT NULL,
    change_reasons jsonb DEFAULT '[]'::jsonb NOT NULL,
    effective_from timestamp with time zone DEFAULT now() NOT NULL,
    effective_to timestamp with time zone,
    CONSTRAINT golden_resident_version_check CHECK (((effective_to IS NULL) OR (effective_to >= effective_from))),
    CONSTRAINT golden_resident_version_version_number_check CHECK ((version_number >= 1))
);
CREATE TABLE golden_resident_version_provenance (
    golden_resident_version_id uuid NOT NULL,
    field_path text NOT NULL,
    canonical_resident_id uuid NOT NULL,
    selected_value jsonb NOT NULL,
    rule_applied text NOT NULL,
    survivorship_policy_version text NOT NULL,
    contributing_canonical_ids jsonb NOT NULL
);
CREATE TABLE uddp_id (
    uddp_id text NOT NULL,
    kind text NOT NULL,
    golden_id uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT uddp_id_kind_check CHECK ((kind = ANY (ARRAY['RESIDENT'::text, 'FAMILY'::text])))
);
ALTER TABLE ONLY golden_build
    ADD CONSTRAINT golden_build_pkey PRIMARY KEY (golden_build_id);
ALTER TABLE ONLY golden_family_event
    ADD CONSTRAINT golden_family_event_pkey PRIMARY KEY (golden_family_event_id);
ALTER TABLE ONLY golden_family
    ADD CONSTRAINT golden_family_family_record_key_key UNIQUE (family_record_key);
ALTER TABLE ONLY golden_family_membership
    ADD CONSTRAINT golden_family_membership_pkey PRIMARY KEY (golden_family_membership_id);
ALTER TABLE ONLY golden_family
    ADD CONSTRAINT golden_family_pkey PRIMARY KEY (golden_family_id);
ALTER TABLE ONLY golden_family_version
    ADD CONSTRAINT golden_family_version_golden_family_id_version_number_key UNIQUE (golden_family_id, version_number);
ALTER TABLE ONLY golden_family_version
    ADD CONSTRAINT golden_family_version_pkey PRIMARY KEY (golden_family_version_id);
ALTER TABLE ONLY golden_resident_event
    ADD CONSTRAINT golden_resident_event_pkey PRIMARY KEY (golden_resident_event_id);
ALTER TABLE ONLY golden_resident_field_provenance
    ADD CONSTRAINT golden_resident_field_provenance_pkey PRIMARY KEY (golden_resident_id, field_path);
ALTER TABLE ONLY golden_resident_member
    ADD CONSTRAINT golden_resident_member_pkey PRIMARY KEY (golden_resident_id, canonical_resident_id);
ALTER TABLE ONLY golden_resident_membership
    ADD CONSTRAINT golden_resident_membership_pkey PRIMARY KEY (golden_resident_membership_id);
ALTER TABLE ONLY golden_resident
    ADD CONSTRAINT golden_resident_pkey PRIMARY KEY (golden_resident_id);
ALTER TABLE ONLY golden_resident_version
    ADD CONSTRAINT golden_resident_version_golden_resident_id_version_number_key UNIQUE (golden_resident_id, version_number);
ALTER TABLE ONLY golden_resident_version
    ADD CONSTRAINT golden_resident_version_pkey PRIMARY KEY (golden_resident_version_id);
ALTER TABLE ONLY golden_resident_version_provenance
    ADD CONSTRAINT golden_resident_version_provenance_pkey PRIMARY KEY (golden_resident_version_id, field_path);
ALTER TABLE ONLY uddp_id
    ADD CONSTRAINT uddp_id_kind_golden_id_key UNIQUE (kind, golden_id);
ALTER TABLE ONLY uddp_id
    ADD CONSTRAINT uddp_id_pkey PRIMARY KEY (uddp_id);
CREATE INDEX golden_family_event_family_idx ON golden_family_event USING btree (golden_family_id, event_sequence);
CREATE UNIQUE INDEX golden_family_membership_current_idx ON golden_family_membership USING btree (golden_family_id, resident_record_key) WHERE (effective_to IS NULL);
CREATE INDEX golden_family_membership_resident_idx ON golden_family_membership USING btree (golden_resident_id);
CREATE UNIQUE INDEX golden_family_version_current_idx ON golden_family_version USING btree (golden_family_id) WHERE (effective_to IS NULL);
CREATE INDEX golden_resident_event_golden_idx ON golden_resident_event USING btree (golden_resident_id, event_sequence);
CREATE INDEX golden_resident_member_canonical_idx ON golden_resident_member USING btree (canonical_resident_id);
CREATE INDEX golden_resident_membership_canonical_idx ON golden_resident_membership USING btree (canonical_resident_id);
CREATE UNIQUE INDEX golden_resident_membership_current_key_idx ON golden_resident_membership USING btree (source_record_key) WHERE (effective_to IS NULL);
CREATE INDEX golden_resident_membership_golden_idx ON golden_resident_membership USING btree (golden_resident_id, effective_from);
CREATE INDEX golden_resident_run_policy_idx ON golden_resident USING btree (matching_run_id, survivorship_policy_version);
CREATE UNIQUE INDEX golden_resident_version_current_idx ON golden_resident_version USING btree (golden_resident_id) WHERE (effective_to IS NULL);
CREATE FUNCTION uddp_id_is_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'UDDP IDs are never changed or deleted';
END;
$$;
CREATE TRIGGER uddp_id_append_only BEFORE DELETE OR UPDATE ON uddp_id FOR EACH ROW EXECUTE FUNCTION uddp_id_is_append_only();
ALTER TABLE ONLY golden_family_event
    ADD CONSTRAINT golden_family_event_golden_build_id_fkey FOREIGN KEY (golden_build_id) REFERENCES golden_build(golden_build_id);
ALTER TABLE ONLY golden_family_event
    ADD CONSTRAINT golden_family_event_golden_family_id_fkey FOREIGN KEY (golden_family_id) REFERENCES golden_family(golden_family_id);
ALTER TABLE ONLY golden_family_event
    ADD CONSTRAINT golden_family_event_golden_resident_id_fkey FOREIGN KEY (golden_resident_id) REFERENCES golden_resident(golden_resident_id);
ALTER TABLE ONLY golden_family_membership
    ADD CONSTRAINT golden_family_membership_golden_family_id_fkey FOREIGN KEY (golden_family_id) REFERENCES golden_family(golden_family_id);
ALTER TABLE ONLY golden_family_membership
    ADD CONSTRAINT golden_family_membership_golden_resident_id_fkey FOREIGN KEY (golden_resident_id) REFERENCES golden_resident(golden_resident_id);
ALTER TABLE ONLY golden_family_version
    ADD CONSTRAINT golden_family_version_golden_build_id_fkey FOREIGN KEY (golden_build_id) REFERENCES golden_build(golden_build_id);
ALTER TABLE ONLY golden_family_version
    ADD CONSTRAINT golden_family_version_golden_family_id_fkey FOREIGN KEY (golden_family_id) REFERENCES golden_family(golden_family_id);
ALTER TABLE ONLY golden_resident_event
    ADD CONSTRAINT golden_resident_event_golden_build_id_fkey FOREIGN KEY (golden_build_id) REFERENCES golden_build(golden_build_id);
ALTER TABLE ONLY golden_resident_event
    ADD CONSTRAINT golden_resident_event_golden_resident_id_fkey FOREIGN KEY (golden_resident_id) REFERENCES golden_resident(golden_resident_id);
ALTER TABLE ONLY golden_resident_event
    ADD CONSTRAINT golden_resident_event_related_golden_resident_id_fkey FOREIGN KEY (related_golden_resident_id) REFERENCES golden_resident(golden_resident_id);
ALTER TABLE ONLY golden_resident_field_provenance
    ADD CONSTRAINT golden_resident_field_provenance_golden_resident_id_fkey FOREIGN KEY (golden_resident_id) REFERENCES golden_resident(golden_resident_id);
ALTER TABLE ONLY golden_resident_member
    ADD CONSTRAINT golden_resident_member_golden_resident_id_fkey FOREIGN KEY (golden_resident_id) REFERENCES golden_resident(golden_resident_id);
ALTER TABLE ONLY golden_resident_membership
    ADD CONSTRAINT golden_resident_membership_golden_resident_id_fkey FOREIGN KEY (golden_resident_id) REFERENCES golden_resident(golden_resident_id);
ALTER TABLE ONLY golden_resident
    ADD CONSTRAINT golden_resident_merged_into_id_fkey FOREIGN KEY (merged_into_id) REFERENCES golden_resident(golden_resident_id);
ALTER TABLE ONLY golden_resident
    ADD CONSTRAINT golden_resident_split_from_id_fkey FOREIGN KEY (split_from_id) REFERENCES golden_resident(golden_resident_id);
ALTER TABLE ONLY golden_resident_version
    ADD CONSTRAINT golden_resident_version_golden_build_id_fkey FOREIGN KEY (golden_build_id) REFERENCES golden_build(golden_build_id);
ALTER TABLE ONLY golden_resident_version
    ADD CONSTRAINT golden_resident_version_golden_resident_id_fkey FOREIGN KEY (golden_resident_id) REFERENCES golden_resident(golden_resident_id);
ALTER TABLE ONLY golden_resident_version_provenance
    ADD CONSTRAINT golden_resident_version_provena_golden_resident_version_id_fkey FOREIGN KEY (golden_resident_version_id) REFERENCES golden_resident_version(golden_resident_version_id);
