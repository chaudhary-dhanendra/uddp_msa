-- The Onboarding Studio, hosted here (MSA): drafts, approvals of submitted versions and suggestion reviews, as
-- in the edge (V14, V18, V70), and the onboarding runs the edge started for a draft, as the edge reports them.
CREATE TABLE studio_draft (
  draft_id uuid PRIMARY KEY,
  pack_id text NOT NULL UNIQUE,
  state_code text NOT NULL UNIQUE,
  draft jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
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
CREATE TABLE studio_suggestion_review (
    draft_id text NOT NULL,
    pack_id text NOT NULL,
    state_code text NOT NULL,
    kind text NOT NULL CHECK (kind IN ('MAPPING', 'CROSSWALK')),
    subject text NOT NULL,
    target text NOT NULL,
    confidence numeric(4, 2) NOT NULL,
    basis text,
    recorded_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (draft_id, kind, subject, target)
);
CREATE TABLE studio_run (
  run_id text PRIMARY KEY,
  draft_id text NOT NULL,
  status text NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);
