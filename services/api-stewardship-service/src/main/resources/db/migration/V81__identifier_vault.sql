-- Track E2: clear values of VAULT_REF identifier types live only here, AES-GCM encrypted with a key that is never in
-- the database; canonical data holds the opaque reference. Rows are never updated.
CREATE TABLE identifier_vault (
  reference text PRIMARY KEY,
  identifier_type text NOT NULL,
  ciphertext text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
