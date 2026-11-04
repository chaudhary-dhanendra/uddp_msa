-- Track E1: the identifier type registry. Types are seeded from platform/identity/identifier-types.yaml by the
-- application; a change is proposed, submitted and approved by someone other than its author (maker-checker).
CREATE TABLE identifier_type (
  code text PRIMARY KEY CHECK (code ~ '^[A-Z][A-Z0-9_]{1,39}$'),
  definition jsonb NOT NULL,
  version integer NOT NULL DEFAULT 1 CHECK (version >= 1),
  updated_by text NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE identifier_type_change (
  change_id uuid PRIMARY KEY,
  code text NOT NULL,
  definition jsonb NOT NULL,
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
    ('IDENTITY_VIEW', 'See identifier types, identity policies and identifier coverage'),
    ('IDENTITY_MANAGE', 'Propose identifier type changes and identity policies'),
    ('IDENTITY_APPROVE', 'Approve or reject an identifier type change or identity policy submitted by someone else')
ON CONFLICT (permission_code) DO UPDATE SET description = excluded.description;

INSERT INTO app_role_permission (role_code, permission_code)
SELECT r.role_code, p.permission_code
FROM app_role r CROSS JOIN (VALUES ('IDENTITY_VIEW'), ('IDENTITY_MANAGE'), ('IDENTITY_APPROVE')) AS p(permission_code)
WHERE r.role_code IN ('PLATFORM_ADMIN', 'DATA_STEWARD')
ON CONFLICT DO NOTHING;
