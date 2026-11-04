-- Track A: maker-checker for rule sets and Scheme Pack versions. A draft is submitted, then a different user with
-- RULES_APPROVE approves or rejects it; nothing is ever auto-approved. V40-V43 remain immutable.
ALTER TABLE rule_set DROP CONSTRAINT rule_set_status_check;
ALTER TABLE rule_set ADD CONSTRAINT rule_set_status_check
    CHECK (status IN ('DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'RETIRED'));
ALTER TABLE rule_set
    ADD COLUMN created_by text,
    ADD COLUMN submitted_by text,
    ADD COLUMN submitted_at timestamptz,
    ADD COLUMN decided_by text,
    ADD COLUMN decided_at timestamptz,
    ADD COLUMN decision_reason text;

ALTER TABLE scheme_pack_version DROP CONSTRAINT scheme_pack_version_status_check;
ALTER TABLE scheme_pack_version ADD CONSTRAINT scheme_pack_version_status_check
    CHECK (status IN ('DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'RETIRED'));
ALTER TABLE scheme_pack_version
    ADD COLUMN created_by text,
    ADD COLUMN submitted_by text,
    ADD COLUMN submitted_at timestamptz,
    ADD COLUMN decided_by text,
    ADD COLUMN decided_at timestamptz,
    ADD COLUMN decision_reason text;

INSERT INTO app_permission (permission_code, description) VALUES
    ('RULES_APPROVE', 'Approve or reject a rule set or Scheme Pack version submitted by someone else')
ON CONFLICT (permission_code) DO UPDATE SET description = excluded.description;

INSERT INTO app_role_permission (role_code, permission_code)
SELECT r.role_code, 'RULES_APPROVE'
FROM app_role r
WHERE r.role_code IN ('PLATFORM_ADMIN', 'DATA_STEWARD')
ON CONFLICT DO NOTHING;
