-- statepack-service baseline (Phase 1). Tables move here from the monolith in Phase 3 (docs/msa-design.md, section 10).
-- This service owns schema(s) statepack only: no cross-schema joins, views or foreign keys.
COMMENT ON SCHEMA statepack IS 'Owned by statepack-service';
