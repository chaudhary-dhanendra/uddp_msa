-- Track C2: per-source schedules and the trigger queue behind the backend scheduler.
CREATE TABLE source_schedule (
  state_code text NOT NULL,
  source_system text NOT NULL,
  cron_expression text NOT NULL,
  timezone text NOT NULL DEFAULT 'UTC',
  enabled boolean NOT NULL DEFAULT true,
  -- What to do with fire times missed while the backend was down: run once now, or skip them.
  catch_up_policy text NOT NULL DEFAULT 'RUN_ONCE' CHECK (catch_up_policy IN ('RUN_ONCE', 'SKIP')),
  last_evaluated_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (state_code, source_system)
);

CREATE TABLE schedule_trigger (
  trigger_id uuid PRIMARY KEY,
  state_code text NOT NULL,
  source_system text NOT NULL,
  due_at timestamptz NOT NULL,
  kind text NOT NULL CHECK (kind IN ('SCHEDULED', 'CATCH_UP')),
  -- SKIPPED rows are missed fire times that were not run (the schedule-miss history).
  status text NOT NULL CHECK (status IN ('QUEUED', 'STARTED', 'SKIPPED', 'START_FAILED')),
  onboarding_run_id uuid REFERENCES onboarding_run(onboarding_run_id),
  note text,
  created_at timestamptz NOT NULL DEFAULT now(),
  started_at timestamptz
);
CREATE INDEX schedule_trigger_queue_idx ON schedule_trigger(status, due_at);
CREATE INDEX schedule_trigger_source_idx ON schedule_trigger(state_code, source_system, due_at DESC);
