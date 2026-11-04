-- Final-rejected records in audit reporting and the department feedback file.
-- A rejected record's payloads follow the raw-payload retention period; the held_record row, its events and the
-- reason, actor and time of the rejection are kept.

ALTER TABLE held_record ADD COLUMN payload_purged_at timestamptz;
CREATE INDEX held_record_event_action_idx ON held_record_event(action, at);

INSERT INTO app_permission (permission_code, description) VALUES
    ('BATCH_VIEW', 'Download the rejection report of a batch for the source department')
ON CONFLICT (permission_code) DO UPDATE SET description = excluded.description;

INSERT INTO app_role_permission (role_code, permission_code)
SELECT rp.role_code, 'BATCH_VIEW' FROM app_role_permission rp WHERE rp.permission_code = 'ONBOARDING_VIEW'
ON CONFLICT DO NOTHING;
