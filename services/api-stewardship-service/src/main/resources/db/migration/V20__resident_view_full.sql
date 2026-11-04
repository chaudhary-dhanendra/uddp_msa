-- P1 Part 5a: personal data is masked in every API response; RESIDENT_VIEW_FULL allows revealing one resident with a
-- stated purpose (audited). Only platform administrators and data stewards hold it.

INSERT INTO app_permission (permission_code, description) VALUES
    ('RESIDENT_VIEW_FULL', 'Reveal one resident''s personal data unmasked, stating a purpose (audited)')
ON CONFLICT (permission_code) DO UPDATE SET description = excluded.description;

DELETE FROM app_role_permission
    WHERE permission_code = 'RESIDENT_VIEW_FULL' AND role_code NOT IN ('PLATFORM_ADMIN', 'DATA_STEWARD');

INSERT INTO app_role_permission (role_code, permission_code)
SELECT role_code, 'RESIDENT_VIEW_FULL' FROM app_role WHERE role_code IN ('PLATFORM_ADMIN', 'DATA_STEWARD')
ON CONFLICT DO NOTHING;
