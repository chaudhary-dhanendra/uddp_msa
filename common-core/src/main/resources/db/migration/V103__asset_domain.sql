-- Asset domain: a thing a benefit gave or relates to (vehicle, livestock, solar installation, land...). Observed by the
-- generic canonical layer from each State Pack's declarative Asset mapping rules, same layering as Application (V102).
-- The holder is the Resident source record of the delivery; the benefit link is the source's benefit ID, resolved on read.

CREATE TABLE asset (
  asset_id uuid PRIMARY KEY,
  -- stateCode|sourceSystem|sourceEntity|sourceAssetId
  asset_record_key text NOT NULL UNIQUE,
  state_code text NOT NULL,
  source_system text NOT NULL,
  source_entity text NOT NULL,
  source_asset_id text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

-- One observation per raw delivery that names an asset (append-only, idempotent per raw record and pack version).
CREATE TABLE asset_observation (
  asset_observation_id uuid PRIMARY KEY,
  raw_record_id uuid NOT NULL REFERENCES raw_record(raw_record_id),
  batch_id uuid NOT NULL REFERENCES ingestion_batch(batch_id),
  state_code text NOT NULL,
  source_system text NOT NULL,
  source_entity text NOT NULL,
  state_pack_version text NOT NULL,
  mapping_version text NOT NULL,
  asset_id uuid NOT NULL REFERENCES asset(asset_id),
  source_asset_id text NOT NULL,
  asset_type text NOT NULL CHECK (asset_type IN ('VEHICLE','LIVESTOCK','SOLAR_INSTALLATION','LAND','EQUIPMENT','OTHER')),
  description text,
  quantity numeric,
  unit text,
  value numeric,
  linked_source_benefit_id text,
  -- Resident source record key (ADR-0003) of the holder.
  holder_source_record_key text NOT NULL,
  canonical_resident_id uuid REFERENCES canonical_resident(canonical_resident_id),
  attributes jsonb NOT NULL DEFAULT '{}'::jsonb,
  metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
  observed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  UNIQUE (raw_record_id, state_pack_version)
);
CREATE INDEX asset_observation_asset_idx ON asset_observation(asset_id, observed_at);
CREATE INDEX asset_observation_holder_idx ON asset_observation(holder_source_record_key);

CREATE TABLE asset_processing_error (
  asset_processing_error_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  raw_record_id uuid NOT NULL REFERENCES raw_record(raw_record_id),
  batch_id uuid NOT NULL REFERENCES ingestion_batch(batch_id),
  state_pack_version text NOT NULL,
  error_message text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);

-- The present statement of each asset: its latest observation.
CREATE VIEW asset_state AS
SELECT a.asset_id, a.asset_record_key, a.state_code, a.source_system, a.source_entity, a.source_asset_id, a.created_at,
       o.asset_observation_id, o.asset_type, o.description, o.quantity, o.unit, o.value, o.linked_source_benefit_id,
       o.holder_source_record_key, o.attributes, o.metadata, o.observed_at
  FROM asset a
  JOIN LATERAL (SELECT x.* FROM asset_observation x WHERE x.asset_id = a.asset_id
                 ORDER BY x.observed_at DESC, x.asset_observation_id DESC LIMIT 1) o ON true;
