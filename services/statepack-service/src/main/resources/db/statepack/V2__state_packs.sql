-- statepack-service: published State Pack versions, their immutable files, and the maker-checker decision log
-- (docs/msa-design.md, section 2; ARCHITECTURE.md E-F). A version is identified by (pack_id, version) and by its
-- content digest; its files never change once published, decisions are append-only, and at most one version of a
-- pack is ACTIVE.

CREATE TABLE state_pack_version (
  pack_id text NOT NULL,
  version text NOT NULL,
  state_code text NOT NULL,
  digest text NOT NULL UNIQUE CHECK (digest ~ '^sha256:[0-9a-f]{64}$'),
  manifest jsonb NOT NULL,
  status text NOT NULL CHECK (status IN ('PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'ACTIVE', 'RETIRED')),
  maker text NOT NULL,
  published_at timestamptz NOT NULL DEFAULT now(),
  checker text,
  decided_at timestamptz,
  activated_at timestamptz,
  PRIMARY KEY (pack_id, version),
  CHECK (checker IS NULL OR checker <> maker)
);
CREATE UNIQUE INDEX state_pack_one_active ON state_pack_version (pack_id) WHERE status = 'ACTIVE';
CREATE INDEX state_pack_version_state_idx ON state_pack_version (state_code, version);

CREATE TABLE state_pack_file (
  pack_id text NOT NULL,
  version text NOT NULL,
  path text NOT NULL,
  content text NOT NULL,
  PRIMARY KEY (pack_id, version, path),
  FOREIGN KEY (pack_id, version) REFERENCES state_pack_version (pack_id, version)
);

CREATE TABLE state_pack_decision (
  decision_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  pack_id text NOT NULL,
  version text NOT NULL,
  action text NOT NULL CHECK (action IN ('PUBLISH', 'APPROVE', 'REJECT', 'ACTIVATE', 'RETIRE')),
  actor text NOT NULL,
  reason text,
  decided_at timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (pack_id, version) REFERENCES state_pack_version (pack_id, version)
);

CREATE FUNCTION state_pack_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '% is append-only', TG_TABLE_NAME;
END $$;
CREATE TRIGGER state_pack_file_immutable BEFORE UPDATE OR DELETE ON state_pack_file
  FOR EACH ROW EXECUTE FUNCTION state_pack_immutable();
CREATE TRIGGER state_pack_decision_immutable BEFORE UPDATE OR DELETE ON state_pack_decision
  FOR EACH ROW EXECUTE FUNCTION state_pack_immutable();

-- Only the lifecycle columns of a version change, never what it is.
CREATE FUNCTION state_pack_version_identity_fixed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' OR (NEW.pack_id, NEW.version, NEW.state_code, NEW.digest, NEW.manifest, NEW.maker,
                          NEW.published_at)
      IS DISTINCT FROM (OLD.pack_id, OLD.version, OLD.state_code, OLD.digest, OLD.manifest, OLD.maker,
                        OLD.published_at) THEN
    RAISE EXCEPTION 'a published State Pack version cannot be changed or deleted';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER state_pack_version_identity BEFORE UPDATE OR DELETE ON state_pack_version
  FOR EACH ROW EXECUTE FUNCTION state_pack_version_identity_fixed();

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
