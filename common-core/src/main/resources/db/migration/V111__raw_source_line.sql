-- WP7d: the line of the delivered file each raw row came from (CSV: the physical line, the header being line 1;
-- Excel: the sheet row). Null for rows landed before V111 and for database sources.
ALTER TABLE raw_record ADD COLUMN source_line integer;
