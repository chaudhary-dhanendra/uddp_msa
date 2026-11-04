-- A source pack is a configuration owner for DATA_QUALITY rules; V40 remains immutable.
ALTER TABLE rule_set DROP CONSTRAINT rule_set_owner_type_check;
ALTER TABLE rule_set ADD CONSTRAINT rule_set_owner_type_check
    CHECK (owner_type IN ('DEPARTMENT', 'SCHEME', 'SOURCE_PACK'));
