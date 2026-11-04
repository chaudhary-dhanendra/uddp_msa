-- Track F3: the LGD master sync schedule (driven by the C2 scheduler loop) and its alert metrics (C6).
CREATE TABLE lgd_sync_schedule (
  singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
  cron_expression text NOT NULL,
  timezone text NOT NULL DEFAULT 'UTC',
  enabled boolean NOT NULL DEFAULT true,
  last_evaluated_at timestamptz NOT NULL DEFAULT now(),
  updated_by text NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE alert_rule DROP CONSTRAINT alert_rule_metric_check;
ALTER TABLE alert_rule ADD CONSTRAINT alert_rule_metric_check
  CHECK (metric IN ('DQ_SCORE', 'LOAD_FAILURES', 'GATE_FAILURES', 'SCHEDULE_MISSES', 'MATCH_DRIFT', 'PENDING_APPROVALS',
                    'LGD_MASTER_AGE_HOURS', 'LGD_SYNC_FAILURES'));
