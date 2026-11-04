-- WP5: where in the pack a held record or rejected row is fixed. A held record's reasons carry their own fix target
-- (kind and key in reason_codes); the record and the processing error name the pack, source and Studio draft.
ALTER TABLE held_record ADD COLUMN pack_id text;
ALTER TABLE held_record ADD COLUMN draft_id uuid;

ALTER TABLE canonical_processing_error ADD COLUMN pack_id text;
ALTER TABLE canonical_processing_error ADD COLUMN source_id text;
ALTER TABLE canonical_processing_error ADD COLUMN draft_id uuid;
-- MAPPING, CROSSWALK, RULE, SHAPING or PACK_SOURCE, and the rule id, source column or crosswalk name
ALTER TABLE canonical_processing_error ADD COLUMN fix_kind text;
ALTER TABLE canonical_processing_error ADD COLUMN fix_key text;
