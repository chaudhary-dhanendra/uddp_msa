-- Shaping administration: a change to a platform word list, or to the set of approved Tier-3 plug-ins, is proposed
-- and then approved or rejected by someone other than its author (maker-checker, rules/MakerChecker).
CREATE TABLE shaping_change (
  change_id uuid PRIMARY KEY,
  kind text NOT NULL CHECK (kind IN ('WORD_LIST', 'PLUGIN')),
  action text NOT NULL CHECK (action IN ('UPDATE', 'APPROVE', 'REVOKE')),
  target text NOT NULL,
  payload jsonb NOT NULL,
  status text NOT NULL CHECK (status IN ('DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'REJECTED')),
  created_by text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  submitted_by text,
  submitted_at timestamptz,
  decided_by text,
  decided_at timestamptz,
  decision_reason text
);

INSERT INTO app_permission (permission_code, description) VALUES
    ('SHAPING_VIEW', 'See shaping word lists, built-in operations and plug-ins, and test a value'),
    ('SHAPING_MANAGE', 'Propose word list changes and plug-in approvals or revocations'),
    ('SHAPING_APPROVE', 'Approve or reject a shaping change proposed by someone else')
ON CONFLICT (permission_code) DO UPDATE SET description = excluded.description;

INSERT INTO app_role_permission (role_code, permission_code)
SELECT r.role_code, p.permission_code
FROM app_role r CROSS JOIN (VALUES ('SHAPING_VIEW'), ('SHAPING_MANAGE'), ('SHAPING_APPROVE')) AS p(permission_code)
WHERE r.role_code = 'PLATFORM_ADMIN'
ON CONFLICT DO NOTHING;

INSERT INTO app_role_permission (role_code, permission_code)
SELECT r.role_code, p.permission_code
FROM app_role r CROSS JOIN (VALUES ('SHAPING_VIEW'), ('SHAPING_MANAGE')) AS p(permission_code)
WHERE r.role_code IN ('STATE_ADMIN', 'DATA_ENGINEER')
ON CONFLICT DO NOTHING;

INSERT INTO app_role_permission (role_code, permission_code)
SELECT r.role_code, 'SHAPING_VIEW'
FROM app_role r
WHERE r.role_code IN ('DATA_STEWARD', 'AUDITOR')
ON CONFLICT DO NOTHING;
