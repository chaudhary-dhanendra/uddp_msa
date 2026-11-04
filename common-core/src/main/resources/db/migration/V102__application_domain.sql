-- Application domain: a citizen's request under a platform scheme that moves through stages. Observed by the generic
-- canonical layer from each State Pack's declarative Application mapping rules, same layering as Benefit (V13).
-- Nothing here reads or changes an earlier table's rows; links to Golden Residents are resolved when read.

-- Stable application identity: one platform UUID per application as one source names it; never updated or reused.
CREATE TABLE application (
  application_id uuid PRIMARY KEY,
  -- stateCode|sourceSystem|sourceEntity|schemeId|sourceApplicationId
  application_record_key text NOT NULL UNIQUE,
  state_code text NOT NULL,
  source_system text NOT NULL,
  source_entity text NOT NULL,
  scheme_id text NOT NULL,
  source_application_id text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX application_scheme_idx ON application(scheme_id);

-- One observation per raw delivery that names an application (append-only, idempotent per raw record and pack version).
CREATE TABLE application_observation (
  application_observation_id uuid PRIMARY KEY,
  raw_record_id uuid NOT NULL REFERENCES raw_record(raw_record_id),
  batch_id uuid NOT NULL REFERENCES ingestion_batch(batch_id),
  state_code text NOT NULL,
  source_system text NOT NULL,
  source_entity text NOT NULL,
  state_pack_version text NOT NULL,
  mapping_version text NOT NULL,
  scheme_catalogue_version text NOT NULL,
  application_id uuid NOT NULL REFERENCES application(application_id),
  source_scheme_code text,
  scheme_id text NOT NULL,
  source_application_id text NOT NULL,
  -- Resident source record key (ADR-0003) of the applicant.
  applicant_source_record_key text NOT NULL,
  canonical_resident_id uuid REFERENCES canonical_resident(canonical_resident_id),
  submitted_on date,
  current_stage text NOT NULL,
  current_status text NOT NULL,
  next_stage text,
  last_action_date date,
  attributes jsonb NOT NULL DEFAULT '{}'::jsonb,
  metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
  observed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  UNIQUE (raw_record_id, state_pack_version)
);
CREATE INDEX application_observation_application_idx ON application_observation(application_id, observed_at);
CREATE INDEX application_observation_applicant_idx ON application_observation(applicant_source_record_key);

-- Appended when a delivery's stage or status differs from the application's previous delivery (or it is the first);
-- never updated or deleted.
CREATE TABLE application_stage_event (
  application_stage_event_id uuid PRIMARY KEY,
  application_id uuid NOT NULL REFERENCES application(application_id),
  application_observation_id uuid NOT NULL REFERENCES application_observation(application_observation_id),
  stage text NOT NULL,
  status text NOT NULL,
  previous_stage text,
  previous_status text,
  occurred_on date,
  recorded_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX application_stage_event_application_idx ON application_stage_event(application_id, recorded_at);

CREATE FUNCTION application_stage_event_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'application_stage_event is append-only';
END $$;
CREATE TRIGGER application_stage_event_append_only
  BEFORE UPDATE OR DELETE ON application_stage_event
  FOR EACH ROW EXECUTE FUNCTION application_stage_event_immutable();

CREATE TABLE application_processing_error (
  application_processing_error_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  raw_record_id uuid NOT NULL REFERENCES raw_record(raw_record_id),
  batch_id uuid NOT NULL REFERENCES ingestion_batch(batch_id),
  state_pack_version text NOT NULL,
  error_message text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);

-- The present statement of each application: its latest observation.
CREATE VIEW application_state AS
SELECT a.application_id, a.application_record_key, a.state_code, a.source_system, a.source_entity, a.scheme_id,
       a.source_application_id, a.created_at, o.application_observation_id, o.source_scheme_code,
       o.applicant_source_record_key, o.submitted_on, o.current_stage, o.current_status, o.next_stage,
       o.last_action_date, o.attributes, o.metadata, o.observed_at, o.scheme_catalogue_version
  FROM application a
  JOIN LATERAL (SELECT x.* FROM application_observation x WHERE x.application_id = a.application_id
                 ORDER BY x.observed_at DESC, x.application_observation_id DESC LIMIT 1) o ON true;
