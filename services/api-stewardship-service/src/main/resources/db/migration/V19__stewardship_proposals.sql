-- P1 Part 4b: stewardship maker-checker. Accept, reject, merge and split are proposed by one steward and applied only
-- when a different user with STEWARDSHIP_APPROVE approves; the decision log (V12) is unchanged.

INSERT INTO app_permission (permission_code, description) VALUES
    ('STEWARDSHIP_APPROVE', 'Approve or reject a stewardship proposal made by someone else')
ON CONFLICT (permission_code) DO UPDATE SET description = excluded.description;

INSERT INTO app_role_permission (role_code, permission_code)
SELECT role_code, 'STEWARDSHIP_APPROVE' FROM app_role WHERE role_code IN ('PLATFORM_ADMIN', 'DATA_STEWARD')
ON CONFLICT DO NOTHING;

CREATE TABLE stewardship_proposal (
    proposal_id uuid PRIMARY KEY,
    action text NOT NULL CHECK (action IN ('ACCEPT_MATCH', 'REJECT_MATCH', 'MERGE', 'SPLIT')),
    review_task_id uuid,
    golden_resident_ids jsonb NOT NULL,
    detail jsonb NOT NULL,
    golden_fingerprint text NOT NULL,
    state_codes text[] NOT NULL,
    reason text NOT NULL,
    status text NOT NULL CHECK (status IN ('PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'STALE')),
    proposed_by text NOT NULL,
    proposed_at timestamptz NOT NULL DEFAULT now(),
    decided_by text,
    decided_at timestamptz,
    decision_reason text,
    decision_id uuid,
    CHECK (decided_by IS NULL OR status = 'STALE' OR decided_by <> proposed_by),
    CHECK ((status = 'PENDING_APPROVAL') = (decided_at IS NULL))
);
CREATE INDEX stewardship_proposal_status_idx ON stewardship_proposal (status, proposed_at DESC);
