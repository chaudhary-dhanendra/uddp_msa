-- The LGD geography master (Track F) the canonical engine's geography crosswalks and free-address parser resolve
-- against: the monolith's end-state lgd_* tables and their seeded entity types (V90..V107). The master is loaded
-- through the LGD adapter into this schema; canonical-service owns it (docs/msa-design.md, section 2).

CREATE TABLE lgd_change (
    change_id bigint NOT NULL,
    snapshot_id uuid NOT NULL,
    entity_type text NOT NULL,
    code text NOT NULL,
    change_type text NOT NULL,
    old_value text,
    new_value text,
    effective_from date NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT lgd_change_change_type_check CHECK ((change_type = ANY (ARRAY['ADDED'::text, 'RENAMED'::text, 'REPARENTED'::text, 'INACTIVATED'::text, 'REACTIVATED'::text])))
);
CREATE SEQUENCE lgd_change_change_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE lgd_change_change_id_seq OWNED BY lgd_change.change_id;
CREATE TABLE lgd_entity (
    entity_type text NOT NULL,
    code text NOT NULL,
    first_snapshot_id uuid NOT NULL
);
CREATE TABLE lgd_entity_type (
    entity_type text NOT NULL,
    level_order integer NOT NULL,
    label text NOT NULL,
    CONSTRAINT lgd_entity_type_entity_type_check CHECK ((entity_type ~ '^[A-Z][A-Z0-9_]{1,39}$'::text))
);
CREATE TABLE lgd_entity_version (
    version_id bigint NOT NULL,
    entity_type text NOT NULL,
    code text NOT NULL,
    name text NOT NULL,
    status text NOT NULL,
    effective_from date NOT NULL,
    effective_to date,
    snapshot_id uuid NOT NULL,
    CONSTRAINT lgd_entity_version_status_check CHECK ((status = ANY (ARRAY['ACTIVE'::text, 'INACTIVE'::text])))
);
CREATE SEQUENCE lgd_entity_version_version_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE lgd_entity_version_version_id_seq OWNED BY lgd_entity_version.version_id;
CREATE TABLE lgd_master_state (
    singleton boolean DEFAULT true NOT NULL,
    active_snapshot_id uuid NOT NULL,
    activated_at timestamp with time zone NOT NULL,
    CONSTRAINT lgd_master_state_singleton_check CHECK (singleton)
);
CREATE TABLE lgd_relationship (
    relationship_id bigint NOT NULL,
    parent_type text NOT NULL,
    parent_code text NOT NULL,
    child_type text NOT NULL,
    child_code text NOT NULL,
    valid_from date NOT NULL,
    valid_to date,
    snapshot_id uuid NOT NULL
);
CREATE SEQUENCE lgd_relationship_relationship_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE lgd_relationship_relationship_id_seq OWNED BY lgd_relationship.relationship_id;
CREATE TABLE lgd_snapshot (
    snapshot_id uuid NOT NULL,
    source_label text NOT NULL,
    as_of date NOT NULL,
    checksum text NOT NULL,
    status text NOT NULL,
    row_count integer DEFAULT 0 NOT NULL,
    change_count integer DEFAULT 0 NOT NULL,
    errors jsonb DEFAULT '[]'::jsonb NOT NULL,
    created_by text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    activated_at timestamp with time zone,
    CONSTRAINT lgd_snapshot_status_check CHECK ((status = ANY (ARRAY['STAGED'::text, 'ACTIVE'::text, 'UNCHANGED'::text, 'FAILED'::text])))
);
CREATE TABLE lgd_staging_row (
    snapshot_id uuid NOT NULL,
    "position" integer NOT NULL,
    line_no integer,
    entity_type text,
    code text,
    name text,
    status text,
    parent_type text,
    parent_code text,
    effective_from date
);
CREATE TABLE lgd_sync_run (
    run_id uuid NOT NULL,
    run_no bigint NOT NULL,
    trigger_kind text NOT NULL,
    actor text NOT NULL,
    status text NOT NULL,
    snapshot_id uuid,
    change_count integer DEFAULT 0 NOT NULL,
    error text,
    started_at timestamp with time zone DEFAULT now() NOT NULL,
    finished_at timestamp with time zone,
    source_label text,
    CONSTRAINT lgd_sync_run_status_check CHECK ((status = ANY (ARRAY['RUNNING'::text, 'SUCCEEDED'::text, 'UNCHANGED'::text, 'FAILED'::text]))),
    CONSTRAINT lgd_sync_run_trigger_kind_check CHECK ((trigger_kind = ANY (ARRAY['SCHEDULED'::text, 'MANUAL'::text, 'IMPORT'::text, 'UPLOAD'::text])))
);
CREATE SEQUENCE lgd_sync_run_run_no_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE lgd_sync_run_run_no_seq OWNED BY lgd_sync_run.run_no;
CREATE TABLE lgd_sync_schedule (
    singleton boolean DEFAULT true NOT NULL,
    cron_expression text NOT NULL,
    timezone text DEFAULT 'UTC'::text NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    last_evaluated_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_by text NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT lgd_sync_schedule_singleton_check CHECK (singleton)
);
ALTER TABLE ONLY lgd_change ALTER COLUMN change_id SET DEFAULT nextval('lgd_change_change_id_seq'::regclass);
ALTER TABLE ONLY lgd_entity_version ALTER COLUMN version_id SET DEFAULT nextval('lgd_entity_version_version_id_seq'::regclass);
ALTER TABLE ONLY lgd_relationship ALTER COLUMN relationship_id SET DEFAULT nextval('lgd_relationship_relationship_id_seq'::regclass);
ALTER TABLE ONLY lgd_sync_run ALTER COLUMN run_no SET DEFAULT nextval('lgd_sync_run_run_no_seq'::regclass);
ALTER TABLE ONLY lgd_change
    ADD CONSTRAINT lgd_change_pkey PRIMARY KEY (change_id);
ALTER TABLE ONLY lgd_entity
    ADD CONSTRAINT lgd_entity_pkey PRIMARY KEY (entity_type, code);
ALTER TABLE ONLY lgd_entity_type
    ADD CONSTRAINT lgd_entity_type_pkey PRIMARY KEY (entity_type);
ALTER TABLE ONLY lgd_entity_version
    ADD CONSTRAINT lgd_entity_version_pkey PRIMARY KEY (version_id);
ALTER TABLE ONLY lgd_master_state
    ADD CONSTRAINT lgd_master_state_pkey PRIMARY KEY (singleton);
ALTER TABLE ONLY lgd_relationship
    ADD CONSTRAINT lgd_relationship_pkey PRIMARY KEY (relationship_id);
ALTER TABLE ONLY lgd_snapshot
    ADD CONSTRAINT lgd_snapshot_pkey PRIMARY KEY (snapshot_id);
ALTER TABLE ONLY lgd_staging_row
    ADD CONSTRAINT lgd_staging_row_pkey PRIMARY KEY (snapshot_id, "position");
ALTER TABLE ONLY lgd_sync_run
    ADD CONSTRAINT lgd_sync_run_pkey PRIMARY KEY (run_id);
ALTER TABLE ONLY lgd_sync_run
    ADD CONSTRAINT lgd_sync_run_run_no_key UNIQUE (run_no);
ALTER TABLE ONLY lgd_sync_schedule
    ADD CONSTRAINT lgd_sync_schedule_pkey PRIMARY KEY (singleton);
CREATE INDEX lgd_change_entity_idx ON lgd_change USING btree (entity_type, code);
CREATE INDEX lgd_change_snapshot_idx ON lgd_change USING btree (snapshot_id);
CREATE UNIQUE INDEX lgd_entity_version_current_idx ON lgd_entity_version USING btree (entity_type, code) WHERE (effective_to IS NULL);
CREATE INDEX lgd_entity_version_name_idx ON lgd_entity_version USING btree (entity_type, lower(name)) WHERE (effective_to IS NULL);
CREATE UNIQUE INDEX lgd_relationship_current_idx ON lgd_relationship USING btree (child_type, child_code) WHERE (valid_to IS NULL);
CREATE INDEX lgd_relationship_parent_idx ON lgd_relationship USING btree (parent_type, parent_code) WHERE (valid_to IS NULL);
CREATE UNIQUE INDEX lgd_sync_run_single_running_idx ON lgd_sync_run USING btree ((true)) WHERE (status = 'RUNNING'::text);
ALTER TABLE ONLY lgd_change
    ADD CONSTRAINT lgd_change_snapshot_id_fkey FOREIGN KEY (snapshot_id) REFERENCES lgd_snapshot(snapshot_id);
ALTER TABLE ONLY lgd_entity
    ADD CONSTRAINT lgd_entity_entity_type_fkey FOREIGN KEY (entity_type) REFERENCES lgd_entity_type(entity_type);
ALTER TABLE ONLY lgd_entity
    ADD CONSTRAINT lgd_entity_first_snapshot_id_fkey FOREIGN KEY (first_snapshot_id) REFERENCES lgd_snapshot(snapshot_id);
ALTER TABLE ONLY lgd_entity_version
    ADD CONSTRAINT lgd_entity_version_entity_type_code_fkey FOREIGN KEY (entity_type, code) REFERENCES lgd_entity(entity_type, code);
ALTER TABLE ONLY lgd_entity_version
    ADD CONSTRAINT lgd_entity_version_snapshot_id_fkey FOREIGN KEY (snapshot_id) REFERENCES lgd_snapshot(snapshot_id);
ALTER TABLE ONLY lgd_master_state
    ADD CONSTRAINT lgd_master_state_active_snapshot_id_fkey FOREIGN KEY (active_snapshot_id) REFERENCES lgd_snapshot(snapshot_id);
ALTER TABLE ONLY lgd_relationship
    ADD CONSTRAINT lgd_relationship_child_type_child_code_fkey FOREIGN KEY (child_type, child_code) REFERENCES lgd_entity(entity_type, code);
ALTER TABLE ONLY lgd_relationship
    ADD CONSTRAINT lgd_relationship_parent_type_parent_code_fkey FOREIGN KEY (parent_type, parent_code) REFERENCES lgd_entity(entity_type, code);
ALTER TABLE ONLY lgd_relationship
    ADD CONSTRAINT lgd_relationship_snapshot_id_fkey FOREIGN KEY (snapshot_id) REFERENCES lgd_snapshot(snapshot_id);
ALTER TABLE ONLY lgd_staging_row
    ADD CONSTRAINT lgd_staging_row_snapshot_id_fkey FOREIGN KEY (snapshot_id) REFERENCES lgd_snapshot(snapshot_id) ON DELETE CASCADE;
ALTER TABLE ONLY lgd_sync_run
    ADD CONSTRAINT lgd_sync_run_snapshot_id_fkey FOREIGN KEY (snapshot_id) REFERENCES lgd_snapshot(snapshot_id);

INSERT INTO lgd_entity_type (entity_type, level_order, label) VALUES ('STATE', 10, 'State');
INSERT INTO lgd_entity_type (entity_type, level_order, label) VALUES ('DISTRICT', 20, 'District');
INSERT INTO lgd_entity_type (entity_type, level_order, label) VALUES ('SUB_DISTRICT', 30, 'Sub-district');
INSERT INTO lgd_entity_type (entity_type, level_order, label) VALUES ('BLOCK', 30, 'Block');
INSERT INTO lgd_entity_type (entity_type, level_order, label) VALUES ('LOCAL_BODY', 30, 'Local body');
INSERT INTO lgd_entity_type (entity_type, level_order, label) VALUES ('GRAM_PANCHAYAT', 40, 'Gram panchayat');
INSERT INTO lgd_entity_type (entity_type, level_order, label) VALUES ('VILLAGE', 50, 'Village');
INSERT INTO lgd_entity_type (entity_type, level_order, label) VALUES ('WARD', 50, 'Ward');
