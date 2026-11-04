-- Track E4: the UDDP ID, an opaque, non-guessable public ID minted for every Golden Resident and Golden Family, kept
-- beside the stable Golden UUID. Rows are never updated or deleted, so an ID is never reused; a retired or merged
-- identity's UDDP ID resolves through the Golden merge chain to the surviving one.
CREATE TABLE uddp_id (
  uddp_id text PRIMARY KEY,
  kind text NOT NULL CHECK (kind IN ('RESIDENT', 'FAMILY')),
  golden_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (kind, golden_id)
);

CREATE FUNCTION uddp_id_is_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'UDDP IDs are never changed or deleted';
END;
$$;
CREATE TRIGGER uddp_id_append_only BEFORE UPDATE OR DELETE ON uddp_id
  FOR EACH ROW EXECUTE FUNCTION uddp_id_is_append_only();
