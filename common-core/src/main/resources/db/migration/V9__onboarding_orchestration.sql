CREATE TABLE state_pack_lifecycle (
  state_pack_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  pack_id text NOT NULL,
  state_code text NOT NULL,
  version text NOT NULL,
  status text NOT NULL CHECK (status IN ('DRAFT','VALIDATED','ACTIVE','RETIRED')),
  validated_at timestamptz,
  activated_at timestamptz,
  retired_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (pack_id, version)
);
CREATE TABLE onboarding_run (
  onboarding_run_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  state_pack_id uuid NOT NULL REFERENCES state_pack_lifecycle(state_pack_id),
  state_code text NOT NULL,
  source_system text NOT NULL,
  state_pack_version text NOT NULL,
  status text NOT NULL CHECK (status IN ('RUNNING','FAILED','COMPLETED')),
  current_stage text NOT NULL,
  started_at timestamptz NOT NULL DEFAULT now(),
  completed_at timestamptz,
  failure_message text,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE onboarding_stage_execution (
  onboarding_stage_execution_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  onboarding_run_id uuid NOT NULL REFERENCES onboarding_run(onboarding_run_id),
  stage text NOT NULL CHECK (stage IN ('PACK_VALIDATED','SOURCE_TESTED','INGESTED','MAPPED','DQ_CHECKED','MATCHED','GOLDEN_BUILT','PUBLISHED')),
  status text NOT NULL CHECK (status IN ('RUNNING','COMPLETED','FAILED')),
  attempt integer NOT NULL,
  started_at timestamptz NOT NULL DEFAULT now(),
  completed_at timestamptz,
  input_count integer,
  output_count integer,
  ingestion_batch_id uuid REFERENCES ingestion_batch(batch_id),
  matching_run_id uuid REFERENCES matching_run(run_id),
  error_message text,
  UNIQUE (onboarding_run_id, stage, attempt)
);
CREATE INDEX onboarding_run_status_idx ON onboarding_run(status, created_at DESC);
CREATE INDEX onboarding_stage_run_idx ON onboarding_stage_execution(onboarding_run_id, started_at);
-- At most one in-flight stage execution per run: concurrent callers cannot execute a run twice.
CREATE UNIQUE INDEX onboarding_stage_one_running_idx ON onboarding_stage_execution(onboarding_run_id) WHERE status = 'RUNNING';
