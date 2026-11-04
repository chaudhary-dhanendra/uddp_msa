-- The matching studio (profiles, labels, impact, monitoring) moved here from the edge reads two more things with the
-- monolith's names: canonical Residents (from the candidate projection) and the effective stewardship link
-- constraints (from stewardship.decision-recorded, replicated row for row).
CREATE VIEW canonical_resident AS
SELECT c.canonical_resident_id, c.raw_record_id, c.batch_id, 'Resident'::text AS canonical_entity_type, c.state_code,
       c.source_system, c.source_entity, c.state_pack_version, c.canonical_payload, c.mapped_at,
       c.received_at AS created_at
  FROM candidate_resident c;

CREATE TABLE link_constraint (
  decision_id uuid NOT NULL,
  record_key_a text NOT NULL,
  record_key_b text NOT NULL,
  constraint_type text NOT NULL,
  decision_sequence bigint NOT NULL,
  PRIMARY KEY (decision_id, record_key_a, record_key_b)
);

CREATE VIEW stewardship_link_constraint_effective AS
SELECT DISTINCT ON (l.record_key_a, l.record_key_b) l.record_key_a, l.record_key_b, l.constraint_type, l.decision_id,
       l.decision_sequence
  FROM link_constraint l
 ORDER BY l.record_key_a, l.record_key_b, l.decision_sequence DESC;
