-- Steward review outcomes the Python adapter's run metrics read (matching/metrics.py acceptance_by_band). In the
-- monolith they come from stewardship_decision and stewardship_review_task; here they are a projection of
-- stewardship.decision-recorded (owned by api-stewardship-service), exposed through views with the same names.
CREATE TABLE review_outcome (
  review_task_id uuid PRIMARY KEY,
  match_probability numeric NOT NULL,
  action text NOT NULL CHECK (action IN ('ACCEPT_MATCH', 'REJECT_MATCH')),
  decided_at timestamptz NOT NULL DEFAULT now()
);

CREATE VIEW stewardship_review_task AS
SELECT r.review_task_id, r.match_probability FROM review_outcome r;

CREATE VIEW stewardship_decision AS
SELECT r.review_task_id, r.action FROM review_outcome r;
