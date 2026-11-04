-- registry-service baseline (Phase 1). Tables move here from the monolith in Phase 3 (docs/msa-design.md, section 10).
-- This service owns schema(s) registry,lineage only: no cross-schema joins, views or foreign keys.
COMMENT ON SCHEMA registry IS 'Owned by registry-service';
