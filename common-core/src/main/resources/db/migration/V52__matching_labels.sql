-- Matching Studio: steward labels (ground truth for threshold suggestion). One current label per pair.
CREATE TABLE matching_label (
  canonical_record_a uuid NOT NULL REFERENCES canonical_resident(canonical_resident_id),
  canonical_record_b uuid NOT NULL REFERENCES canonical_resident(canonical_resident_id),
  label text NOT NULL CHECK (label IN ('SAME','DIFFERENT','UNSURE')),
  run_id uuid REFERENCES matching_run(run_id),
  match_probability numeric NOT NULL,
  score_band integer NOT NULL,
  reviewer text NOT NULL,
  labelled_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (canonical_record_a, canonical_record_b),
  CHECK (canonical_record_a < canonical_record_b)
);
CREATE INDEX matching_label_band_idx ON matching_label(score_band);
