-- M9: generic platform reference data and auditable data-quality findings.
CREATE TABLE reference_dataset (
  reference_dataset_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  reference_set text NOT NULL,
  version text NOT NULL,
  description text NOT NULL,
  status text NOT NULL DEFAULT 'ACTIVE',
  published_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(reference_set, version)
);
CREATE TABLE reference_value (
  reference_value_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  reference_dataset_id uuid NOT NULL REFERENCES reference_dataset(reference_dataset_id),
  code text NOT NULL,
  display_name text,
  metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
  UNIQUE(reference_dataset_id, code)
);
CREATE TABLE dq_batch_evaluation (
  dq_evaluation_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  batch_id uuid NOT NULL REFERENCES ingestion_batch(batch_id),
  state_code text NOT NULL,
  source_system text NOT NULL,
  state_pack_version text NOT NULL,
  rule_config_version text NOT NULL,
  evaluated_at timestamptz NOT NULL DEFAULT now(),
  records_evaluated integer NOT NULL,
  rules_evaluated integer NOT NULL,
  error_count integer NOT NULL,
  warning_count integer NOT NULL,
  affected_record_count integer NOT NULL
);
CREATE INDEX dq_batch_evaluation_batch_idx ON dq_batch_evaluation(batch_id, evaluated_at DESC);
CREATE TABLE dq_result (
  dq_result_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  dq_evaluation_id uuid NOT NULL REFERENCES dq_batch_evaluation(dq_evaluation_id),
  raw_record_id uuid NOT NULL REFERENCES raw_record(raw_record_id),
  canonical_resident_id uuid REFERENCES canonical_resident(canonical_resident_id),
  rule_id text NOT NULL,
  rule_type text NOT NULL,
  severity text NOT NULL CHECK(severity IN ('ERROR','WARNING')),
  field_path text,
  message text NOT NULL,
  evidence jsonb NOT NULL DEFAULT '{}'::jsonb,
  state_pack_version text NOT NULL,
  rule_config_version text NOT NULL,
  evaluated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX dq_result_evaluation_idx ON dq_result(dq_evaluation_id, rule_id);
