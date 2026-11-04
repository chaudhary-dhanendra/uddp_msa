-- The identifier type registry the engine's identifier parsers read (as canonical-service's V4), seeded from
-- platform/identity/identifier-types.yaml on first use, so Studio previews classify identifiers as ingestion does.

CREATE TABLE identifier_type (
    code text NOT NULL,
    definition jsonb NOT NULL,
    version integer DEFAULT 1 NOT NULL,
    updated_by text NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT identifier_type_code_check CHECK ((code ~ '^[A-Z][A-Z0-9_]{1,39}$'::text)),
    CONSTRAINT identifier_type_version_check CHECK ((version >= 1))
);
CREATE TABLE identifier_type_change (
    change_id uuid NOT NULL,
    code text NOT NULL,
    definition jsonb NOT NULL,
    status text NOT NULL,
    created_by text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    submitted_by text,
    submitted_at timestamp with time zone,
    decided_by text,
    decided_at timestamp with time zone,
    decision_reason text,
    CONSTRAINT identifier_type_change_status_check CHECK ((status = ANY (ARRAY['DRAFT'::text, 'PENDING_APPROVAL'::text, 'APPROVED'::text, 'REJECTED'::text])))
);
ALTER TABLE ONLY identifier_type_change
    ADD CONSTRAINT identifier_type_change_pkey PRIMARY KEY (change_id);
ALTER TABLE ONLY identifier_type
    ADD CONSTRAINT identifier_type_pkey PRIMARY KEY (code);
