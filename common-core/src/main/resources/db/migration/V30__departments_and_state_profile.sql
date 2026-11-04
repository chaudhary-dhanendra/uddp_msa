-- R1a: one deployment serves one state; its departments own Source Packs (formerly "State Packs"), each describing
-- one source system. The State Profile describes the deployment's state. Department assignment is data, not code.

CREATE TABLE department (
    department_code text PRIMARY KEY,
    name            text NOT NULL,
    status          text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at      timestamptz NOT NULL DEFAULT now()
);

-- At most one row: the state this deployment serves. Empty until an administrator sets it.
CREATE TABLE state_profile (
    profile_id     smallint PRIMARY KEY DEFAULT 1 CHECK (profile_id = 1),
    state_code     text NOT NULL,
    state_name     text NOT NULL,
    lgd_state_code text,
    languages      text[] NOT NULL DEFAULT '{}',
    updated_at     timestamptz NOT NULL DEFAULT now()
);

-- Department of each Source Pack whose manifest declares none (stored and Studio-generated packs).
CREATE TABLE department_source_pack (
    pack_id         text PRIMARY KEY,
    department_code text NOT NULL REFERENCES department (department_code),
    assigned_at     timestamptz NOT NULL DEFAULT now()
);

-- Backward compatibility: the four sample packs become the sources of four sample departments.
INSERT INTO department (department_code, name) VALUES
    ('DEPT-1', 'Department 1'),
    ('DEPT-2', 'Department 2'),
    ('DEPT-3', 'Department 3'),
    ('DEPT-4', 'Department 4');

INSERT INTO department_source_pack (pack_id, department_code) VALUES
    ('state-1', 'DEPT-1'),
    ('state-2', 'DEPT-2'),
    ('state-3', 'DEPT-3'),
    ('state-4', 'DEPT-4');
