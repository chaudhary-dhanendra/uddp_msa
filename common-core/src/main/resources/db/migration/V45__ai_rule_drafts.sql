-- Track A6: provenance of model-assisted eligibility drafts. The rule set itself stays an ordinary DRAFT in rule_set
-- (maker-checker unchanged, never auto-approved); this table marks it "AI draft - verify against GO" and records the
-- government order it was drafted from. Only the document's hash and name are kept, never its text.
CREATE TABLE ai_rule_draft (
    rule_set_id uuid PRIMARY KEY REFERENCES rule_set(rule_set_id),
    scheme_id text NOT NULL REFERENCES scheme_pack(scheme_id),
    scheme_pack_version_id uuid NOT NULL REFERENCES scheme_pack_version(scheme_pack_version_id),
    marker text NOT NULL,
    document_name text,
    document_sha256 text NOT NULL,
    model text,
    created_by text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
