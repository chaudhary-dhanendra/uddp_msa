-- Monitoring can alert on Track A rule and Scheme Pack versions waiting for a checker (PENDING_APPROVALS).
ALTER TABLE alert_rule DROP CONSTRAINT alert_rule_metric_check;
ALTER TABLE alert_rule ADD CONSTRAINT alert_rule_metric_check
  CHECK (metric IN ('DQ_SCORE', 'LOAD_FAILURES', 'GATE_FAILURES', 'SCHEDULE_MISSES', 'MATCH_DRIFT', 'PENDING_APPROVALS'));
