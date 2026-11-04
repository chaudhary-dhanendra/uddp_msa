-- P1 Part 4a: State Pack maker-checker. A Studio publication is submitted as PENDING_APPROVAL; a different user with
-- STATE_PACK_APPROVE approves (the version is then persisted and activated) or rejects it with a reason.

INSERT INTO app_permission (permission_code, description) VALUES
    ('STATE_PACK_APPROVE', 'Approve or reject a State Pack version submitted by someone else')
ON CONFLICT (permission_code) DO UPDATE SET description = excluded.description;

INSERT INTO app_role_permission (role_code, permission_code)
SELECT role_code, 'STATE_PACK_APPROVE' FROM app_role WHERE role_code IN ('PLATFORM_ADMIN', 'STATE_ADMIN')
ON CONFLICT DO NOTHING;

CREATE TABLE state_pack_approval (
    approval_id uuid PRIMARY KEY,
    draft_id text NOT NULL,
    pack_id text NOT NULL,
    state_code text NOT NULL,
    version text NOT NULL,
    status text NOT NULL CHECK (status IN ('PENDING_APPROVAL', 'APPROVED', 'REJECTED')),
    submitted_by text NOT NULL,
    submitted_at timestamptz NOT NULL DEFAULT now(),
    decided_by text,
    decided_at timestamptz,
    decision_reason text,
    pack_document jsonb NOT NULL,
    files jsonb NOT NULL,
    CHECK (decided_by IS NULL OR decided_by <> submitted_by),
    CHECK ((status = 'PENDING_APPROVAL') = (decided_at IS NULL))
);
CREATE UNIQUE INDEX state_pack_approval_one_pending_idx ON state_pack_approval (pack_id)
    WHERE status = 'PENDING_APPROVAL';
CREATE INDEX state_pack_approval_status_idx ON state_pack_approval (status, submitted_at DESC);
