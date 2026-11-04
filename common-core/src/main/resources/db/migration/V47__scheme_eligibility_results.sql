-- Track A5 (4): eligibility recomputed after every Golden build, for each scheme's effective approved version with an
-- eligibility rule set. Append-only: each computation keeps its own results and explanations; readers use the latest.
CREATE TABLE scheme_eligibility_computation (
    computation_id uuid PRIMARY KEY,
    scheme_id text NOT NULL REFERENCES scheme_pack(scheme_id),
    scheme_pack_version_id uuid NOT NULL REFERENCES scheme_pack_version(scheme_pack_version_id),
    rule_set_id uuid NOT NULL REFERENCES rule_set(rule_set_id),
    matching_run_id uuid,
    evaluated integer NOT NULL,
    eligible integer NOT NULL,
    not_eligible integer NOT NULL,
    indeterminate integer NOT NULL,
    computed_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX scheme_eligibility_computation_scheme_idx ON scheme_eligibility_computation(scheme_id, computed_at DESC);

CREATE TABLE scheme_eligibility_result (
    computation_id uuid NOT NULL REFERENCES scheme_eligibility_computation(computation_id),
    entity_id uuid NOT NULL,
    outcome text NOT NULL CHECK (outcome IN ('ELIGIBLE', 'NOT_ELIGIBLE', 'INDETERMINATE', 'UNCONFIGURED')),
    clause_references jsonb NOT NULL DEFAULT '[]'::jsonb,
    missing_attributes jsonb NOT NULL DEFAULT '[]'::jsonb,
    explanation text,
    PRIMARY KEY (computation_id, entity_id)
);
