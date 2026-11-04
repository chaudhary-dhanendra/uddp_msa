ALTER TABLE matching_run ADD COLUMN threshold numeric NOT NULL DEFAULT 0.95;

CREATE TABLE resident_match_cluster_member (
  run_id uuid NOT NULL REFERENCES matching_run(run_id),
  cluster_id text NOT NULL,
  canonical_record_id uuid NOT NULL REFERENCES canonical_resident(canonical_resident_id),
  PRIMARY KEY (run_id, cluster_id, canonical_record_id),
  UNIQUE (run_id, canonical_record_id)
);
