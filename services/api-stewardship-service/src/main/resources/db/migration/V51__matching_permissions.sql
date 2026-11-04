-- Matching Studio permissions (Track B). Granted to the admin and steward roles; approval is still maker-checker.
INSERT INTO app_permission (permission_code, description) VALUES
    ('MATCHING_VIEW', 'See matching profiles, training, labels, thresholds and monitoring'),
    ('MATCHING_MANAGE', 'Edit matching profiles, start training, label pairs and propose threshold changes'),
    ('MATCHING_APPROVE', 'Approve or reject a matching profile submitted by someone else')
ON CONFLICT (permission_code) DO UPDATE SET description = excluded.description;

INSERT INTO app_role_permission (role_code, permission_code)
SELECT r.role_code, p.permission_code
FROM app_role r CROSS JOIN (VALUES ('MATCHING_VIEW'), ('MATCHING_MANAGE'), ('MATCHING_APPROVE')) AS p(permission_code)
WHERE r.role_code IN ('PLATFORM_ADMIN', 'DATA_STEWARD')
ON CONFLICT DO NOTHING;
