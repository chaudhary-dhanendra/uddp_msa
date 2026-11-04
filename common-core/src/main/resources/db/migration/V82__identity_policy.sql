-- Track E3: the per-deployment Identity Policy, versioned and maker-checker approved. The latest APPROVED version is
-- in force; approving a version retires the one before it. Without an approved version matching and Golden builds
-- behave exactly as before.
CREATE TABLE identity_policy (
  policy_id uuid PRIMARY KEY,
  version integer NOT NULL UNIQUE CHECK (version >= 1),
  status text NOT NULL CHECK (status IN ('DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'RETIRED')),
  config jsonb NOT NULL,
  created_by text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  submitted_by text,
  submitted_at timestamptz,
  decided_by text,
  decided_at timestamptz,
  decision_reason text
);
CREATE UNIQUE INDEX identity_policy_one_approved ON identity_policy((true)) WHERE status = 'APPROVED';
