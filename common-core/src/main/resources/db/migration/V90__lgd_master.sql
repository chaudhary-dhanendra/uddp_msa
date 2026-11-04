-- Track F1: the LGD (Local Government Directory) geography master. Entities and their names, statuses and parents are
-- versioned; a snapshot is staged, validated as a whole, diffed against the active master and activated in one
-- transaction. A row missing from a snapshot is never a deletion. Entity types are data, so more can be added.
CREATE TABLE lgd_entity_type (
  entity_type text PRIMARY KEY CHECK (entity_type ~ '^[A-Z][A-Z0-9_]{1,39}$'),
  -- Smaller is higher in the hierarchy: a parent's level order is always below its child's.
  level_order integer NOT NULL,
  label text NOT NULL
);

INSERT INTO lgd_entity_type (entity_type, level_order, label) VALUES
  ('STATE', 10, 'State'),
  ('DISTRICT', 20, 'District'),
  ('SUB_DISTRICT', 30, 'Sub-district'),
  ('BLOCK', 30, 'Block'),
  ('LOCAL_BODY', 30, 'Local body'),
  ('GRAM_PANCHAYAT', 40, 'Gram panchayat'),
  ('VILLAGE', 50, 'Village'),
  ('WARD', 50, 'Ward');

CREATE TABLE lgd_snapshot (
  snapshot_id uuid PRIMARY KEY,
  source_label text NOT NULL,
  as_of date NOT NULL,
  checksum text NOT NULL,
  -- STAGED -> ACTIVE (it changed the master), UNCHANGED (nothing differed) or FAILED (the master was not touched).
  status text NOT NULL CHECK (status IN ('STAGED', 'ACTIVE', 'UNCHANGED', 'FAILED')),
  row_count integer NOT NULL DEFAULT 0,
  change_count integer NOT NULL DEFAULT 0,
  errors jsonb NOT NULL DEFAULT '[]',
  created_by text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  activated_at timestamptz
);

CREATE TABLE lgd_staging_row (
  snapshot_id uuid NOT NULL REFERENCES lgd_snapshot(snapshot_id) ON DELETE CASCADE,
  position integer NOT NULL,
  line_no integer,
  entity_type text,
  code text,
  name text,
  status text,
  parent_type text,
  parent_code text,
  effective_from date,
  PRIMARY KEY (snapshot_id, position)
);

CREATE TABLE lgd_entity (
  entity_type text NOT NULL REFERENCES lgd_entity_type(entity_type),
  code text NOT NULL,
  first_snapshot_id uuid NOT NULL REFERENCES lgd_snapshot(snapshot_id),
  PRIMARY KEY (entity_type, code)
);

CREATE TABLE lgd_entity_version (
  version_id bigserial PRIMARY KEY,
  entity_type text NOT NULL,
  code text NOT NULL,
  name text NOT NULL,
  status text NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE')),
  effective_from date NOT NULL,
  -- Null while current; a version is in force on d when effective_from <= d < effective_to.
  effective_to date,
  snapshot_id uuid NOT NULL REFERENCES lgd_snapshot(snapshot_id),
  FOREIGN KEY (entity_type, code) REFERENCES lgd_entity(entity_type, code)
);
CREATE UNIQUE INDEX lgd_entity_version_current_idx ON lgd_entity_version(entity_type, code) WHERE effective_to IS NULL;
CREATE INDEX lgd_entity_version_name_idx ON lgd_entity_version(entity_type, lower(name)) WHERE effective_to IS NULL;

CREATE TABLE lgd_relationship (
  relationship_id bigserial PRIMARY KEY,
  parent_type text NOT NULL,
  parent_code text NOT NULL,
  child_type text NOT NULL,
  child_code text NOT NULL,
  valid_from date NOT NULL,
  valid_to date,
  snapshot_id uuid NOT NULL REFERENCES lgd_snapshot(snapshot_id),
  FOREIGN KEY (parent_type, parent_code) REFERENCES lgd_entity(entity_type, code),
  FOREIGN KEY (child_type, child_code) REFERENCES lgd_entity(entity_type, code)
);
CREATE UNIQUE INDEX lgd_relationship_current_idx ON lgd_relationship(child_type, child_code) WHERE valid_to IS NULL;
CREATE INDEX lgd_relationship_parent_idx ON lgd_relationship(parent_type, parent_code) WHERE valid_to IS NULL;

CREATE TABLE lgd_sync_run (
  run_id uuid PRIMARY KEY,
  -- Order of starts, independent of clock resolution.
  run_no bigserial NOT NULL UNIQUE,
  trigger_kind text NOT NULL CHECK (trigger_kind IN ('SCHEDULED', 'MANUAL', 'IMPORT')),
  actor text NOT NULL,
  status text NOT NULL CHECK (status IN ('RUNNING', 'SUCCEEDED', 'UNCHANGED', 'FAILED')),
  snapshot_id uuid REFERENCES lgd_snapshot(snapshot_id),
  change_count integer NOT NULL DEFAULT 0,
  error text,
  started_at timestamptz NOT NULL DEFAULT now(),
  finished_at timestamptz
);
-- At most one sync runs at a time.
CREATE UNIQUE INDEX lgd_sync_run_single_running_idx ON lgd_sync_run((true)) WHERE status = 'RUNNING';

CREATE TABLE lgd_change (
  change_id bigserial PRIMARY KEY,
  snapshot_id uuid NOT NULL REFERENCES lgd_snapshot(snapshot_id),
  entity_type text NOT NULL,
  code text NOT NULL,
  change_type text NOT NULL CHECK (change_type IN ('ADDED', 'RENAMED', 'REPARENTED', 'INACTIVATED', 'REACTIVATED')),
  old_value text,
  new_value text,
  effective_from date NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX lgd_change_snapshot_idx ON lgd_change(snapshot_id);
CREATE INDEX lgd_change_entity_idx ON lgd_change(entity_type, code);

-- The one active snapshot; switching it is the atomic activation.
CREATE TABLE lgd_master_state (
  singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
  active_snapshot_id uuid NOT NULL REFERENCES lgd_snapshot(snapshot_id),
  activated_at timestamptz NOT NULL
);
