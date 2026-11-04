-- MSA Phase 3 (identity-service extraction): with uddp.identity.remote=true, matching runs are made by identity-service
-- and projected here from its events, so the Golden build reads them exactly as before. A projected run stays
-- RECEIVING until every pair and cluster member its match-run-completed event announced has arrived.
ALTER TABLE matching_run
  ADD COLUMN origin text NOT NULL DEFAULT 'LOCAL' CHECK (origin IN ('LOCAL', 'IDENTITY_SERVICE')),
  ADD COLUMN correlation_id uuid,
  ADD COLUMN expected_pair_count integer,
  ADD COLUMN expected_cluster_member_count integer;
CREATE INDEX matching_run_correlation_idx ON matching_run (correlation_id) WHERE correlation_id IS NOT NULL;
