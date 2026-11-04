-- Track C5: the connector a source pulls its deliveries through. Settings only; credentials are never stored here,
-- a "<field>Ref" value names a credential in the encrypted local settings store.
CREATE TABLE source_connector (
  state_code text NOT NULL,
  source_system text NOT NULL,
  connector_type text NOT NULL CHECK (connector_type IN ('excel', 'jdbc', 'sftp', 'rest')),
  config jsonb NOT NULL DEFAULT '{}'::jsonb,
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (state_code, source_system)
);
