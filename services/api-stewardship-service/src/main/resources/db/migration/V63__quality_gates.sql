-- Track C3: quality gates, release decisions and the PAUSED run state.
ALTER TABLE onboarding_run DROP CONSTRAINT onboarding_run_status_check;
ALTER TABLE onboarding_run ADD CONSTRAINT onboarding_run_status_check
  CHECK (status IN ('RUNNING', 'FAILED', 'COMPLETED', 'PAUSED'));
-- Who started the run (null for scheduled runs); the maker of a maker-checker release decision.
ALTER TABLE onboarding_run ADD COLUMN started_by text;

CREATE TABLE source_quality_gate (
  state_code text NOT NULL,
  source_system text NOT NULL,
  gate_type text NOT NULL CHECK (gate_type IN ('MAPPING_COVERAGE_PCT', 'ERROR_RATE_PCT', 'UNMAPPED_CODE_COUNT',
                                               'CHANGE_VOLUME_PCT', 'MATCH_RATE_CHANGE_PCT')),
  threshold numeric NOT NULL,
  enabled boolean NOT NULL DEFAULT true,
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (state_code, source_system, gate_type)
);

-- Every evaluation, on runs and on dry runs (onboarding_run_id is null for a dry run).
CREATE TABLE quality_gate_result (
  gate_result_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  onboarding_run_id uuid REFERENCES onboarding_run(onboarding_run_id),
  state_code text NOT NULL,
  source_system text NOT NULL,
  gate_type text NOT NULL,
  observed numeric,
  threshold numeric NOT NULL,
  status text NOT NULL CHECK (status IN ('PASS', 'FAIL', 'NOT_AVAILABLE')),
  dry_run boolean NOT NULL DEFAULT false,
  evaluated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX quality_gate_result_run_idx ON quality_gate_result(onboarding_run_id);
CREATE INDEX quality_gate_result_source_idx ON quality_gate_result(state_code, source_system, evaluated_at DESC);

-- Append-only release decisions of a run before its Golden build.
CREATE TABLE run_release_decision (
  decision_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  onboarding_run_id uuid NOT NULL REFERENCES onboarding_run(onboarding_run_id),
  decision text NOT NULL CHECK (decision IN ('AUTO_APPROVED', 'PENDING', 'APPROVED', 'REJECTED')),
  reasons text,
  decided_by text NOT NULL,
  decided_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX run_release_decision_run_idx ON run_release_decision(onboarding_run_id, decided_at);

INSERT INTO app_permission (permission_code, description) VALUES
    ('ONBOARDING_APPROVE', 'Approve or reject the release of a paused onboarding run started by someone else')
ON CONFLICT (permission_code) DO UPDATE SET description = excluded.description;

INSERT INTO app_role_permission (role_code, permission_code)
SELECT r.role_code, 'ONBOARDING_APPROVE'
FROM app_role r
WHERE r.role_code IN ('PLATFORM_ADMIN', 'DATA_STEWARD')
ON CONFLICT DO NOTHING;
