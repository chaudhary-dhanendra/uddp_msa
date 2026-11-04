-- Track A4 completion: the remaining Scheme Pack fields. classification, unit, frequency and go_reference already
-- exist (V42); amount and quantity are added, classification is constrained, and code crosswalks record which source
-- code of which Source Pack means this scheme (imported from the packs' own `scheme` crosswalks).
ALTER TABLE scheme_pack_version
    ADD COLUMN amount numeric(14, 2),
    ADD COLUMN quantity numeric(14, 3),
    ADD CONSTRAINT scheme_pack_version_classification_check
        CHECK (classification IS NULL OR classification IN ('CENTRAL', 'STATE', 'CENTRALLY_SPONSORED'));

CREATE TABLE scheme_pack_code_crosswalk (
    scheme_pack_version_id uuid NOT NULL REFERENCES scheme_pack_version(scheme_pack_version_id),
    pack_id text NOT NULL,
    source_code text NOT NULL,
    PRIMARY KEY (scheme_pack_version_id, pack_id, source_code)
);
