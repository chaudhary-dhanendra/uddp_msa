-- Studio-authored configuration is an immutable, versioned JSONB document.  CSV bytes remain in landing storage.
CREATE TABLE studio_draft (
  draft_id uuid PRIMARY KEY,
  pack_id text NOT NULL UNIQUE,
  state_code text NOT NULL UNIQUE,
  draft jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE state_pack_document (
  pack_id text NOT NULL,
  version text NOT NULL,
  state_code text NOT NULL,
  display_name text NOT NULL,
  configuration jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (pack_id, version)
);
CREATE INDEX state_pack_document_state_idx ON state_pack_document(state_code, created_at DESC);
