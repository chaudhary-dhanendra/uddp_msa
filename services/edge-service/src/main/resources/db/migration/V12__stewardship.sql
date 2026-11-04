-- M13: stewardship and manual review (docs/stewardship.md, ADR-0010). Review tasks, decisions and their link
-- constraints are append-only; nothing here reads or changes matching evidence, canonical rows or earlier tables.

-- One review task per POSSIBLE_MATCH source-record pair, created by the Golden build that first sees the pair.
-- OPEN until a decision references it (derived; tasks are never updated).
CREATE TABLE stewardship_review_task (
  review_task_id uuid PRIMARY KEY,
  task_type text NOT NULL DEFAULT 'POSSIBLE_MATCH' CHECK (task_type IN ('POSSIBLE_MATCH')),
  record_key_a text NOT NULL,
  record_key_b text NOT NULL,
  matching_run_id uuid NOT NULL REFERENCES matching_run(run_id),
  canonical_record_a uuid NOT NULL REFERENCES canonical_resident(canonical_resident_id),
  canonical_record_b uuid NOT NULL REFERENCES canonical_resident(canonical_resident_id),
  match_probability numeric NOT NULL,
  match_weight numeric NOT NULL,
  golden_build_id uuid REFERENCES golden_build(golden_build_id),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  CHECK (record_key_a < record_key_b),
  UNIQUE (record_key_a, record_key_b, task_type)
);

CREATE TABLE stewardship_decision (
  decision_id uuid PRIMARY KEY,
  decision_sequence bigint GENERATED ALWAYS AS IDENTITY UNIQUE,
  action text NOT NULL CHECK (action IN ('ACCEPT_MATCH', 'REJECT_MATCH', 'MERGE', 'SPLIT')),
  review_task_id uuid UNIQUE REFERENCES stewardship_review_task(review_task_id),
  reviewer text NOT NULL CHECK (btrim(reviewer) <> ''),
  reason text NOT NULL CHECK (btrim(reason) <> ''),
  golden_resident_ids jsonb NOT NULL DEFAULT '[]'::jsonb,
  detail jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  CHECK ((action IN ('ACCEPT_MATCH', 'REJECT_MATCH')) = (review_task_id IS NOT NULL))
);

-- What a decision says about pairs of source records; the latest decision on a pair is the effective one.
CREATE TABLE stewardship_link_constraint (
  decision_id uuid NOT NULL REFERENCES stewardship_decision(decision_id),
  record_key_a text NOT NULL,
  record_key_b text NOT NULL,
  constraint_type text NOT NULL CHECK (constraint_type IN ('MUST_LINK', 'CANNOT_LINK')),
  CHECK (record_key_a < record_key_b),
  PRIMARY KEY (decision_id, record_key_a, record_key_b)
);
CREATE INDEX stewardship_link_constraint_pair_idx ON stewardship_link_constraint(record_key_a, record_key_b);

CREATE VIEW stewardship_link_constraint_effective AS
SELECT DISTINCT ON (l.record_key_a, l.record_key_b)
       l.record_key_a, l.record_key_b, l.constraint_type, l.decision_id, d.decision_sequence
  FROM stewardship_link_constraint l
  JOIN stewardship_decision d ON d.decision_id = l.decision_id
 ORDER BY l.record_key_a, l.record_key_b, d.decision_sequence DESC;
