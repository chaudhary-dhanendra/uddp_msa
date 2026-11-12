-- Database-source connections saved from the Onboarding Studio. Connections configured in this service's settings
-- (uddp.ingestion.connections.<name>.*) win over these; the password is not here but in connector_secret
-- ('source-connection:<name>'), AES-GCM encrypted like every other connector credential.
CREATE TABLE source_connection (
  name text PRIMARY KEY CHECK (name ~ '^[a-z][a-z0-9-]{0,40}$'),
  host text NOT NULL,
  port integer NOT NULL CHECK (port BETWEEN 1 AND 65535),
  database_name text NOT NULL,
  user_name text NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);
