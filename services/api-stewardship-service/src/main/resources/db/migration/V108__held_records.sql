-- Held records: a row that fails an ERROR rule a steward can fix, hits a SEND_TO_STEWARD rule, carries an INVALID
-- identifier or a low-confidence shaped field is held out of canonical, matching and golden until a steward decides.

ALTER TABLE canonical_processing_error ADD COLUMN reason_code text NOT NULL DEFAULT 'OTHER';
ALTER TABLE canonical_processing_chunk ADD COLUMN rows_held integer NOT NULL DEFAULT 0;
ALTER TABLE dq_result DROP CONSTRAINT dq_result_severity_check;
ALTER TABLE dq_result ADD CONSTRAINT dq_result_severity_check CHECK (severity IN ('ERROR','WARNING','SEND_TO_STEWARD'));

ALTER TABLE alert_rule DROP CONSTRAINT alert_rule_metric_check;
ALTER TABLE alert_rule ADD CONSTRAINT alert_rule_metric_check
  CHECK (metric IN ('DQ_SCORE', 'LOAD_FAILURES', 'GATE_FAILURES', 'SCHEDULE_MISSES', 'MATCH_DRIFT', 'PENDING_APPROVALS',
                    'LGD_MASTER_AGE_HOURS', 'LGD_SYNC_FAILURES', 'HELD_OPEN', 'HELD_OLDEST_HOURS', 'HELD_RECTIFIED',
                    'HELD_REJECTED', 'HELD_HOURS_TO_DECIDE'));

CREATE TABLE held_record (
  held_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  batch_id uuid NOT NULL REFERENCES ingestion_batch(batch_id),
  raw_record_id uuid NOT NULL UNIQUE REFERENCES raw_record(raw_record_id),
  source_system text NOT NULL,
  state_code text NOT NULL,
  department text,
  state_pack_version text NOT NULL,
  -- [{code, message, field, severity}]; severity is ERROR, SEND_TO_STEWARD or WARNING (low confidence, invalid id)
  reason_codes jsonb NOT NULL,
  status text NOT NULL DEFAULT 'OPEN'
    CHECK (status IN ('OPEN','IN_REVIEW','RECTIFIED','REJECTED_FINAL','RELEASED_OVERRIDE')),
  created_at timestamptz NOT NULL DEFAULT now(),
  assigned_to text,
  decided_by text,
  decided_at timestamptz,
  decision_reason_code text,
  decision_note text,
  -- the mapped record as it was held; null once the raw payload it came from has been purged
  original_payload jsonb,
  current_payload jsonb NOT NULL,
  correction_version integer NOT NULL DEFAULT 0
);
CREATE INDEX held_record_batch_idx ON held_record(batch_id, status);
CREATE INDEX held_record_status_idx ON held_record(status, created_at);

CREATE TABLE held_record_event (
  event_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  held_id uuid NOT NULL REFERENCES held_record(held_id),
  actor text NOT NULL,
  action text NOT NULL,
  before jsonb,
  after jsonb,
  note text,
  at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX held_record_event_held_idx ON held_record_event(held_id, at);

-- A bulk reject of a group larger than uddp.stewardship.bulk-approval-threshold waits for a second steward
-- (MakerChecker over this table).
CREATE TABLE held_bulk_action (
  action_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  batch_id uuid NOT NULL REFERENCES ingestion_batch(batch_id),
  reason_code text NOT NULL,
  reject_reason_code text NOT NULL,
  note text,
  record_count integer NOT NULL,
  status text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','PENDING_APPROVAL','APPROVED','REJECTED')),
  created_by text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  submitted_by text,
  submitted_at timestamptz,
  decided_by text,
  decided_at timestamptz,
  decision_reason text
);

INSERT INTO app_permission (permission_code, description) VALUES
    ('STEWARD_HELD_VIEW', 'See held records, their reasons and history (personal data masked)'),
    ('STEWARD_HELD_DECIDE', 'Rectify, release, reject and reprocess held records')
ON CONFLICT (permission_code) DO UPDATE SET description = excluded.description;

INSERT INTO app_role_permission (role_code, permission_code)
SELECT r.role_code, p.permission_code
FROM app_role r CROSS JOIN (VALUES ('STEWARD_HELD_VIEW'), ('STEWARD_HELD_DECIDE')) AS p(permission_code)
WHERE r.role_code IN ('PLATFORM_ADMIN', 'DATA_STEWARD')
ON CONFLICT DO NOTHING;

INSERT INTO app_role_permission (role_code, permission_code)
SELECT r.role_code, 'STEWARD_HELD_VIEW' FROM app_role r WHERE r.role_code IN ('STATE_ADMIN', 'AUDITOR')
ON CONFLICT DO NOTHING;
