-- Large batches (millions of rows) are announced in committed chunks: after Hop lands the rows the batch is
-- PUBLISHING, each chunk of raw-record-received events commits with the position it reached, and a restarted service
-- resumes from that position. batch-completed follows the last chunk.
ALTER TABLE ingestion_batch DROP CONSTRAINT ingestion_batch_status_check;
ALTER TABLE ingestion_batch ADD CONSTRAINT ingestion_batch_status_check
  CHECK (status IN ('STARTED', 'PUBLISHING', 'COMPLETED', 'FAILED'));
ALTER TABLE ingestion_batch ADD COLUMN published_line integer;
ALTER TABLE ingestion_batch ADD COLUMN published_record_id uuid;
ALTER TABLE ingestion_batch ADD COLUMN published_count integer NOT NULL DEFAULT 0;
-- The landing order the chunks walk (source_line, rows without a line last, then the record id).
CREATE INDEX raw_record_batch_order_idx
  ON raw_record (batch_id, (coalesce(source_line, 2147483647)), raw_record_id);
