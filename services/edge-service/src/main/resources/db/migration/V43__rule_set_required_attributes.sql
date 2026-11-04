ALTER TABLE rule_set ADD COLUMN required_attributes jsonb NOT NULL DEFAULT '[]'::jsonb;
