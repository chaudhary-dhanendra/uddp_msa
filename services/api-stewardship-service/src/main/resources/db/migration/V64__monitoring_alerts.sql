-- Track C6: alert rules, in-app notifications and the outgoing webhook queue.
CREATE TABLE alert_rule (
  rule_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  name text NOT NULL,
  metric text NOT NULL CHECK (metric IN ('DQ_SCORE', 'LOAD_FAILURES', 'GATE_FAILURES', 'SCHEDULE_MISSES', 'MATCH_DRIFT')),
  comparison text NOT NULL CHECK (comparison IN ('GT', 'LT')),
  threshold numeric NOT NULL,
  severity text NOT NULL CHECK (severity IN ('INFO', 'WARNING', 'CRITICAL')),
  -- Null state or source means every one.
  state_code text,
  source_system text,
  enabled boolean NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE alert_notification (
  notification_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  rule_id uuid NOT NULL REFERENCES alert_rule(rule_id) ON DELETE CASCADE,
  state_code text NOT NULL,
  source_system text NOT NULL,
  metric text NOT NULL,
  observed numeric NOT NULL,
  threshold numeric NOT NULL,
  severity text NOT NULL,
  message text NOT NULL,
  created_at timestamptz NOT NULL,
  read_at timestamptz
);
CREATE INDEX alert_notification_unread_idx ON alert_notification(read_at, created_at DESC);
CREATE INDEX alert_notification_rule_idx ON alert_notification(rule_id, state_code, source_system, read_at);

CREATE TABLE webhook_delivery (
  delivery_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  notification_id uuid NOT NULL REFERENCES alert_notification(notification_id) ON DELETE CASCADE,
  status text NOT NULL CHECK (status IN ('PENDING', 'DELIVERED', 'FAILED')),
  attempts integer NOT NULL DEFAULT 0,
  next_attempt_at timestamptz NOT NULL,
  last_error text,
  delivered_at timestamptz
);
CREATE INDEX webhook_delivery_due_idx ON webhook_delivery(status, next_attempt_at);
