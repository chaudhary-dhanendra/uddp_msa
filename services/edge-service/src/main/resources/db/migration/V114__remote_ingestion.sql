-- MSA Phase 3 (ingestion-service extraction): with uddp.ingestion.remote=true, Hop runs in ingestion-service and
-- its raw records are projected into raw_record here (same raw_record_id), so canonical processing reads them
-- unchanged. batch-completed records what ingestion-service landed; the batch is whole when that many rows arrived.
ALTER TABLE ingestion_batch
  ADD COLUMN remote_status text CHECK (remote_status IN ('COMPLETED', 'FAILED')),
  ADD COLUMN remote_record_count integer,
  ADD COLUMN remote_message text;
