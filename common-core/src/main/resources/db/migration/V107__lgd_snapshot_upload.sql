-- Geography master upload: a snapshot can be uploaded by a platform administrator; the run records its file name.
ALTER TABLE lgd_sync_run DROP CONSTRAINT lgd_sync_run_trigger_kind_check;
ALTER TABLE lgd_sync_run ADD CONSTRAINT lgd_sync_run_trigger_kind_check
    CHECK (trigger_kind IN ('SCHEDULED', 'MANUAL', 'IMPORT', 'UPLOAD'));
ALTER TABLE lgd_sync_run ADD COLUMN source_label text;

INSERT INTO app_permission (permission_code, description) VALUES
    ('GEOGRAPHY_UPLOAD', 'Check and load an uploaded LGD snapshot into the geography master')
ON CONFLICT (permission_code) DO UPDATE SET description = excluded.description;

INSERT INTO app_role_permission (role_code, permission_code)
SELECT r.role_code, 'GEOGRAPHY_UPLOAD'
FROM app_role r
WHERE r.role_code = 'PLATFORM_ADMIN'
ON CONFLICT DO NOTHING;
