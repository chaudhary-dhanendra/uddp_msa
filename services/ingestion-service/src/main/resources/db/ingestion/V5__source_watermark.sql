-- Delta loads: per database source, the change-column value the last successful batch read up to. A batch reads only
-- rows past it, up to the high-water mark taken when it started; the mark moves when Hop has landed the rows.
CREATE TABLE source_watermark (
  state_code text NOT NULL,
  source_system text NOT NULL,
  change_column text NOT NULL,
  last_value text NOT NULL,
  batch_id uuid NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (state_code, source_system)
);
ALTER TABLE ingestion_batch ADD COLUMN delta_column text;
ALTER TABLE ingestion_batch ADD COLUMN delta_after text;
ALTER TABLE ingestion_batch ADD COLUMN delta_up_to text;
