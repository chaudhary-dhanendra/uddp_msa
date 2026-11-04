-- Track F2/F3: reading the LGD geography master, and starting a master sync by hand (platform administrators only).
INSERT INTO app_permission (permission_code, description) VALUES
    ('GEOGRAPHY_VIEW', 'Search the LGD geography master, its sync history and change log'),
    ('GEOGRAPHY_SYNC', 'Start an LGD master sync by hand')
ON CONFLICT (permission_code) DO UPDATE SET description = excluded.description;

INSERT INTO app_role_permission (role_code, permission_code)
SELECT r.role_code, 'GEOGRAPHY_VIEW'
FROM app_role r
WHERE r.role_code IN ('PLATFORM_ADMIN', 'STATE_ADMIN', 'DATA_ENGINEER', 'DATA_STEWARD', 'AUDITOR')
ON CONFLICT DO NOTHING;

INSERT INTO app_role_permission (role_code, permission_code)
SELECT r.role_code, 'GEOGRAPHY_SYNC'
FROM app_role r
WHERE r.role_code = 'PLATFORM_ADMIN'
ON CONFLICT DO NOTHING;
