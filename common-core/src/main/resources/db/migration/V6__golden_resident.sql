CREATE TABLE golden_resident (
  golden_resident_id uuid PRIMARY KEY,
  matching_run_id uuid NOT NULL REFERENCES matching_run(run_id),
  survivorship_policy_version text NOT NULL,
  member_set_key text NOT NULL,
  golden_payload jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (matching_run_id, survivorship_policy_version, member_set_key)
);
CREATE TABLE golden_resident_member (
  golden_resident_id uuid NOT NULL REFERENCES golden_resident(golden_resident_id),
  canonical_resident_id uuid NOT NULL REFERENCES canonical_resident(canonical_resident_id),
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (golden_resident_id, canonical_resident_id)
);
CREATE INDEX golden_resident_member_canonical_idx ON golden_resident_member(canonical_resident_id);
CREATE TABLE golden_resident_field_provenance (
  golden_resident_id uuid NOT NULL REFERENCES golden_resident(golden_resident_id),
  field_path text NOT NULL,
  canonical_resident_id uuid NOT NULL REFERENCES canonical_resident(canonical_resident_id),
  selected_value jsonb NOT NULL,
  rule_applied text NOT NULL,
  contributing_canonical_ids jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (golden_resident_id, field_path)
);
