CREATE TABLE scheme_pack (
  scheme_id text PRIMARY KEY,
  owning_department_code text NOT NULL REFERENCES department(department_code),
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE scheme_pack_version (
  scheme_pack_version_id uuid PRIMARY KEY,
  scheme_id text NOT NULL REFERENCES scheme_pack(scheme_id), version integer NOT NULL,
  status text NOT NULL CHECK (status IN ('DRAFT','APPROVED','RETIRED')),
  name text NOT NULL, description text, classification text,
  beneficiary_level text NOT NULL CHECK (beneficiary_level IN ('INDIVIDUAL','HOUSEHOLD')),
  benefit_type text NOT NULL, benefit_form text NOT NULL, unit text, frequency text,
  effective_from date NOT NULL, effective_to date, go_reference text, eligibility_rule_set_id uuid,
  catalogue_payload jsonb NOT NULL DEFAULT '{}'::jsonb, created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(scheme_id, version)
);
CREATE TABLE scheme_pack_implementing_department (scheme_pack_version_id uuid NOT NULL REFERENCES scheme_pack_version(scheme_pack_version_id), department_code text NOT NULL REFERENCES department(department_code), PRIMARY KEY(scheme_pack_version_id, department_code));
CREATE TABLE scheme_pack_exclusivity_group (scheme_pack_version_id uuid NOT NULL REFERENCES scheme_pack_version(scheme_pack_version_id), group_code text NOT NULL, PRIMARY KEY(scheme_pack_version_id, group_code));
CREATE TABLE scheme_pack_source_pack (scheme_pack_version_id uuid NOT NULL REFERENCES scheme_pack_version(scheme_pack_version_id), pack_id text NOT NULL REFERENCES department_source_pack(pack_id), PRIMARY KEY(scheme_pack_version_id, pack_id));
