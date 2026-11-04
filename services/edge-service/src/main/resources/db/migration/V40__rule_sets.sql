-- Track A: versioned, department- and scheme-neutral DMN rule configuration.
CREATE TABLE rule_set (
    rule_set_id uuid PRIMARY KEY,
    rule_type text NOT NULL CHECK (rule_type IN ('DATA_QUALITY', 'SURVIVORSHIP', 'ELIGIBILITY')),
    owner_type text NOT NULL CHECK (owner_type IN ('DEPARTMENT', 'SCHEME')),
    owner_id text NOT NULL,
    version integer NOT NULL CHECK (version > 0),
    status text NOT NULL CHECK (status IN ('DRAFT', 'APPROVED', 'RETIRED')),
    effective_from date NOT NULL,
    effective_to date,
    dmn_xml text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (effective_to IS NULL OR effective_to >= effective_from),
    UNIQUE (rule_type, owner_type, owner_id, version)
);
CREATE INDEX rule_set_selection_idx
    ON rule_set (rule_type, owner_type, owner_id, status, effective_from DESC, version DESC);
