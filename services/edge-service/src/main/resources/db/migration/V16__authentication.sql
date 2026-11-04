-- P1 Part 1: local accounts with lockout and forced password change, optional OIDC identities, and the platform's
-- roles and permissions (ADR-0017, ADR-0018). V15 is unchanged; this only adds columns and reference rows.

ALTER TABLE app_user
    ADD COLUMN failed_attempts integer NOT NULL DEFAULT 0,
    ADD COLUMN locked_until timestamptz,
    ADD COLUMN must_change_password boolean NOT NULL DEFAULT true,
    ADD COLUMN password_changed_at timestamptz,
    ADD COLUMN last_login_at timestamptz,
    ADD COLUMN auth_source text NOT NULL DEFAULT 'LOCAL' CHECK (auth_source IN ('LOCAL', 'OIDC')),
    ADD COLUMN external_subject text,
    ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();
CREATE UNIQUE INDEX app_user_username_lower_idx ON app_user (lower(username));
CREATE UNIQUE INDEX app_user_external_subject_idx ON app_user (auth_source, external_subject)
    WHERE external_subject IS NOT NULL;

ALTER TABLE app_role ADD COLUMN description text;
ALTER TABLE app_permission ADD COLUMN description text;

INSERT INTO app_permission (permission_code, description) VALUES
    ('STATE_PACK_VIEW', 'See State Packs, Studio drafts and their versions'),
    ('STATE_PACK_CREATE', 'Create Studio drafts'),
    ('STATE_PACK_EDIT', 'Edit drafts and submit a State Pack for publication'),
    ('STATE_PACK_PUBLISH', 'Approve or reject a submitted publication and activate pack versions'),
    ('SOURCE_UPLOAD', 'Upload source files and name source database connections'),
    ('SOURCE_PROFILE', 'Profile sources and see mapping suggestions and previews'),
    ('ONBOARDING_RUN', 'Start, step and resume onboarding runs'),
    ('ONBOARDING_VIEW', 'See onboarding runs, batches and stage timelines'),
    ('CANONICAL_VIEW', 'See canonical Residents'),
    ('MATCH_EVIDENCE_VIEW', 'See matching runs, pairs and their evidence'),
    ('RESIDENT_VIEW', 'Search and see Golden Residents (personal data masked)'),
    ('RESIDENT_VIEW_FULL', 'See personal data unmasked after stating a purpose (audited)'),
    ('FAMILY_VIEW', 'See Golden Families'),
    ('BENEFIT_VIEW', 'See benefits and the scheme catalogue'),
    ('STEWARDSHIP_VIEW', 'See the review queue and the decision log'),
    ('STEWARDSHIP_DECIDE', 'Submit and approve review decisions, merges and splits'),
    ('DATA_QUALITY_VIEW', 'See data quality findings'),
    ('REPORT_VIEW', 'See reports and the overview'),
    ('REPORT_EXPORT', 'Export reports as files'),
    ('LINEAGE_VIEW', 'Follow lineage to raw source records'),
    ('PORTABILITY_VIEW', 'See the portability evidence'),
    ('USER_MANAGE', 'Create and change users, roles and state scopes'),
    ('ROLE_MANAGE', 'See role definitions'),
    ('PLATFORM_SETTINGS', 'Change platform settings, run retention and reset a demo database'),
    ('AUDIT_VIEW', 'See and export the audit trail')
ON CONFLICT (permission_code) DO UPDATE SET description = excluded.description;

INSERT INTO app_role (role_code, description) VALUES
    ('PLATFORM_ADMIN', 'Everything, in every state'),
    ('STATE_ADMIN', 'Approves State Pack publications and runs onboarding for its states'),
    ('DATA_ENGINEER', 'Builds State Packs in the Studio and onboards sources'),
    ('DATA_STEWARD', 'Reviews matches and decides merges and splits'),
    ('PROGRAM_USER', 'Searches residents and benefits with personal data masked'),
    ('AUDITOR', 'Reads everything for oversight, including the audit trail')
ON CONFLICT (role_code) DO UPDATE SET description = excluded.description;

-- Role definitions are platform reference data: replace whatever an earlier demo seed wrote.
DELETE FROM app_role_permission
    WHERE role_code IN ('PLATFORM_ADMIN', 'STATE_ADMIN', 'DATA_ENGINEER', 'DATA_STEWARD', 'PROGRAM_USER', 'AUDITOR');

INSERT INTO app_role_permission (role_code, permission_code)
SELECT 'PLATFORM_ADMIN', permission_code FROM app_permission
WHERE permission_code IN ('STATE_PACK_VIEW', 'STATE_PACK_CREATE', 'STATE_PACK_EDIT', 'STATE_PACK_PUBLISH',
    'SOURCE_UPLOAD', 'SOURCE_PROFILE', 'ONBOARDING_RUN', 'ONBOARDING_VIEW', 'CANONICAL_VIEW', 'MATCH_EVIDENCE_VIEW',
    'RESIDENT_VIEW', 'RESIDENT_VIEW_FULL', 'FAMILY_VIEW', 'BENEFIT_VIEW', 'STEWARDSHIP_VIEW', 'STEWARDSHIP_DECIDE',
    'DATA_QUALITY_VIEW', 'REPORT_VIEW', 'REPORT_EXPORT', 'LINEAGE_VIEW', 'PORTABILITY_VIEW', 'USER_MANAGE',
    'ROLE_MANAGE', 'PLATFORM_SETTINGS', 'AUDIT_VIEW');

INSERT INTO app_role_permission (role_code, permission_code)
SELECT 'STATE_ADMIN', permission_code FROM app_permission
WHERE permission_code IN ('STATE_PACK_VIEW', 'STATE_PACK_CREATE', 'STATE_PACK_EDIT', 'STATE_PACK_PUBLISH',
    'SOURCE_UPLOAD', 'SOURCE_PROFILE', 'ONBOARDING_RUN', 'ONBOARDING_VIEW', 'CANONICAL_VIEW', 'MATCH_EVIDENCE_VIEW',
    'RESIDENT_VIEW', 'RESIDENT_VIEW_FULL', 'FAMILY_VIEW', 'BENEFIT_VIEW', 'STEWARDSHIP_VIEW', 'DATA_QUALITY_VIEW',
    'REPORT_VIEW', 'REPORT_EXPORT', 'LINEAGE_VIEW', 'PORTABILITY_VIEW');

INSERT INTO app_role_permission (role_code, permission_code)
SELECT 'DATA_ENGINEER', permission_code FROM app_permission
WHERE permission_code IN ('STATE_PACK_VIEW', 'STATE_PACK_CREATE', 'STATE_PACK_EDIT', 'SOURCE_UPLOAD',
    'SOURCE_PROFILE', 'ONBOARDING_RUN', 'ONBOARDING_VIEW', 'CANONICAL_VIEW', 'MATCH_EVIDENCE_VIEW',
    'DATA_QUALITY_VIEW', 'LINEAGE_VIEW', 'PORTABILITY_VIEW');

INSERT INTO app_role_permission (role_code, permission_code)
SELECT 'DATA_STEWARD', permission_code FROM app_permission
WHERE permission_code IN ('RESIDENT_VIEW', 'RESIDENT_VIEW_FULL', 'FAMILY_VIEW', 'BENEFIT_VIEW', 'CANONICAL_VIEW',
    'MATCH_EVIDENCE_VIEW', 'STEWARDSHIP_VIEW', 'STEWARDSHIP_DECIDE', 'DATA_QUALITY_VIEW', 'LINEAGE_VIEW');

INSERT INTO app_role_permission (role_code, permission_code)
SELECT 'PROGRAM_USER', permission_code FROM app_permission
WHERE permission_code IN ('RESIDENT_VIEW', 'FAMILY_VIEW', 'BENEFIT_VIEW', 'REPORT_VIEW', 'REPORT_EXPORT');

INSERT INTO app_role_permission (role_code, permission_code)
SELECT 'AUDITOR', permission_code FROM app_permission
WHERE permission_code IN ('STATE_PACK_VIEW', 'ONBOARDING_VIEW', 'CANONICAL_VIEW', 'MATCH_EVIDENCE_VIEW',
    'RESIDENT_VIEW', 'FAMILY_VIEW', 'BENEFIT_VIEW', 'STEWARDSHIP_VIEW', 'DATA_QUALITY_VIEW', 'REPORT_VIEW',
    'LINEAGE_VIEW', 'PORTABILITY_VIEW', 'ROLE_MANAGE', 'AUDIT_VIEW');

-- Audit events name their actor even when no user row exists (a failed sign-in with an unknown username).
ALTER TABLE app_audit_event ADD COLUMN actor_username text;
