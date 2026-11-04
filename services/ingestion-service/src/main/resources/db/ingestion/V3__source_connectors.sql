-- Source connectors (Track C5) moved from the edge (docs/msa-design.md, section 2): the connector a source pulls its
-- deliveries through, by state and source, and the credentials it names, encrypted (AES-GCM) by this service.
CREATE TABLE source_connector (
  state_code text NOT NULL,
  source_system text NOT NULL,
  connector_type text NOT NULL CHECK (connector_type IN ('excel', 'jdbc', 'sftp', 'rest')),
  config jsonb NOT NULL DEFAULT '{}'::jsonb,
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (state_code, source_system)
);

CREATE TABLE connector_secret (
  name text PRIMARY KEY,
  ciphertext text NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);
