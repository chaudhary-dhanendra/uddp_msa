-- MSA Phase 2 (docs/msa-design.md, section 5): transactional outbox and idempotent-consumer inbox. Rows are written
-- only when uddp.events.enabled=true; the relay publishes them to Kafka. Same DDL as gov.uddp.messaging.Outbox.DDL.
CREATE TABLE outbox_event (
  event_id uuid PRIMARY KEY,
  topic text NOT NULL,
  partition_key text NOT NULL,
  event_type text NOT NULL,
  payload jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  published_at timestamptz
);
CREATE INDEX outbox_event_unpublished_idx ON outbox_event (created_at) WHERE published_at IS NULL;

CREATE TABLE processed_event (
  consumer text NOT NULL,
  event_id uuid NOT NULL,
  processed_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (consumer, event_id)
);
