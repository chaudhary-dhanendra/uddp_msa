-- ingestion-service owns Raw/Landing (docs/msa-design.md, section 2): the monolith's end-state ingestion_batch and
-- raw_record (V2, V21, V111). The generic Hop templates write raw_record here with RAW_SCHEMA=ingestion. Raw is
-- append-only and the replay authority: rows are never updated or deleted, except that a privacy purge
-- (payload_purged_at) may blank a payload once.

CREATE TABLE ingestion_batch (
  batch_id uuid PRIMARY KEY,
  state_code text NOT NULL,
  source_system text NOT NULL,
  source_type text NOT NULL,
  source_schema_version text,
  state_pack_version text NOT NULL,
  pipeline_template text NOT NULL,
  started_at timestamptz NOT NULL DEFAULT now(),
  completed_at timestamptz,
  status text NOT NULL CHECK (status IN ('STARTED', 'COMPLETED', 'FAILED')),
  record_count integer NOT NULL DEFAULT 0,
  error_count integer NOT NULL DEFAULT 0,
  error_message text,
  -- The request (onboarding run) that asked for this batch; echoed on batch-completed.
  correlation_id uuid
);

CREATE TABLE raw_record (
  raw_record_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  batch_id uuid NOT NULL REFERENCES ingestion_batch(batch_id),
  state_code text NOT NULL,
  source_system text NOT NULL,
  source_record_id text,
  ingested_at timestamptz NOT NULL DEFAULT now(),
  raw_payload text NOT NULL,
  source_metadata jsonb NOT NULL,
  state_pack_version text NOT NULL,
  source_schema_version text,
  pipeline_template text NOT NULL,
  payload_purged_at timestamptz,
  source_line integer
);
CREATE INDEX raw_record_batch_id_idx ON raw_record(batch_id);

CREATE FUNCTION raw_record_is_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'raw_record is append-only';
  END IF;
  IF OLD.payload_purged_at IS NULL AND NEW.payload_purged_at IS NOT NULL
     AND (NEW.raw_record_id, NEW.batch_id, NEW.state_code, NEW.source_system, NEW.source_record_id, NEW.ingested_at,
          NEW.source_metadata, NEW.state_pack_version, NEW.source_schema_version, NEW.pipeline_template,
          NEW.source_line)
         IS NOT DISTINCT FROM
         (OLD.raw_record_id, OLD.batch_id, OLD.state_code, OLD.source_system, OLD.source_record_id, OLD.ingested_at,
          OLD.source_metadata, OLD.state_pack_version, OLD.source_schema_version, OLD.pipeline_template,
          OLD.source_line) THEN
    RETURN NEW;
  END IF;
  RAISE EXCEPTION 'raw_record is append-only (only a one-time privacy purge of the payload is allowed)';
END $$;
CREATE TRIGGER raw_record_append_only BEFORE UPDATE OR DELETE ON raw_record
  FOR EACH ROW EXECUTE FUNCTION raw_record_is_append_only();

-- ---- Outbox and inbox (gov.uddp.messaging.Outbox.DDL) ----------------------------------------------------------
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
