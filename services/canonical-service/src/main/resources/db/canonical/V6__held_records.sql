-- Held records live with canonical processing (docs/msa-design.md, section 2): a row that fails an ERROR rule a
-- steward can fix, hits a SEND_TO_STEWARD rule, carries an INVALID identifier or a low-confidence shaped field is held
-- out of canonical, matching and golden until a steward decides (through the edge). Same shape as the monolith's
-- V108-V110, plus held_reprocess_job: a group reprocess runs in the background and keeps its progress here.
CREATE TABLE held_record (
  held_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  batch_id uuid NOT NULL,
  raw_record_id uuid NOT NULL UNIQUE REFERENCES raw_record(raw_record_id),
  source_system text NOT NULL,
  state_code text NOT NULL,
  department text,
  state_pack_version text NOT NULL,
  reason_codes jsonb NOT NULL,
  status text NOT NULL DEFAULT 'OPEN'
    CHECK (status IN ('OPEN','IN_REVIEW','RECTIFIED','REJECTED_FINAL','RELEASED_OVERRIDE')),
  created_at timestamptz NOT NULL DEFAULT now(),
  assigned_to text,
  decided_by text,
  decided_at timestamptz,
  decision_reason_code text,
  decision_note text,
  original_payload jsonb,
  current_payload jsonb NOT NULL,
  correction_version integer NOT NULL DEFAULT 0,
  payload_purged_at timestamptz,
  pack_id text,
  draft_id uuid
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
CREATE INDEX held_record_event_action_idx ON held_record_event(action, at);

CREATE TABLE held_reprocess_job (
  job_id uuid PRIMARY KEY,
  batch_id uuid NOT NULL,
  reason_code text NOT NULL,
  status text NOT NULL DEFAULT 'RUNNING' CHECK (status IN ('RUNNING','COMPLETED','FAILED')),
  total integer NOT NULL,
  processed integer NOT NULL DEFAULT 0,
  released integer NOT NULL DEFAULT 0,
  still_held integer NOT NULL DEFAULT 0,
  requested_by text NOT NULL,
  started_at timestamptz NOT NULL DEFAULT now(),
  finished_at timestamptz,
  error text
);
