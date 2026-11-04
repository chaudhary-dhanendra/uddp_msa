-- Track D1: Studio suggestions below uddp.studio.suggestion-accept-confidence wait here for a person. The rows are
-- replaced whenever a draft's suggestions are computed and cleared once its mappings are saved; they carry column
-- names, targets and code values only, never row data. The exception inbox reads them (type MAPPING_SUGGESTION).
CREATE TABLE studio_suggestion_review (
    draft_id text NOT NULL,
    pack_id text NOT NULL,
    state_code text NOT NULL,
    kind text NOT NULL CHECK (kind IN ('MAPPING', 'CROSSWALK')),
    subject text NOT NULL,
    target text NOT NULL,
    confidence numeric(4, 2) NOT NULL,
    basis text,
    recorded_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (draft_id, kind, subject, target)
);
