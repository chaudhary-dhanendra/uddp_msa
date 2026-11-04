-- P1 Part 3: the audit trail is append-only and searchable (ADR-0020). V15 and V16 are unchanged.

ALTER TABLE app_audit_event ADD COLUMN sequence bigserial;
CREATE UNIQUE INDEX app_audit_event_sequence_idx ON app_audit_event (sequence);
CREATE INDEX app_audit_event_time_idx ON app_audit_event (occurred_at DESC);
CREATE INDEX app_audit_event_action_idx ON app_audit_event (action, occurred_at DESC);
CREATE INDEX app_audit_event_actor_idx ON app_audit_event (lower(actor_username), occurred_at DESC);
CREATE INDEX app_audit_event_state_idx ON app_audit_event (state_code, occurred_at DESC);

-- Neither the application nor anyone with ordinary table rights can change or remove an audit event.
CREATE FUNCTION uddp_reject_audit_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'app_audit_event is append-only (% refused)', TG_OP;
END;
$$;
CREATE TRIGGER app_audit_event_append_only BEFORE UPDATE OR DELETE ON app_audit_event
    FOR EACH ROW EXECUTE FUNCTION uddp_reject_audit_change();
CREATE TRIGGER app_audit_event_no_truncate BEFORE TRUNCATE ON app_audit_event
    FOR EACH STATEMENT EXECUTE FUNCTION uddp_reject_audit_change();
