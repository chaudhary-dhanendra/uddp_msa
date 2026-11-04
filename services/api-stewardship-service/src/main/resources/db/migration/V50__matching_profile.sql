-- Matching Studio (Track B): versioned matching profiles, training jobs, and the profile a matching run used.
CREATE TABLE matching_profile (
  profile_id uuid PRIMARY KEY,
  name text NOT NULL,
  version integer NOT NULL,
  status text NOT NULL CHECK (status IN ('DRAFT','PENDING_APPROVAL','APPROVED','RETIRED')),
  config jsonb NOT NULL,
  parameters jsonb,
  weights jsonb,
  training_records integer,
  created_by text NOT NULL,
  submitted_by text,
  approved_by text,
  created_at timestamptz NOT NULL DEFAULT now(),
  trained_at timestamptz,
  approved_at timestamptz,
  UNIQUE(name, version)
);
CREATE UNIQUE INDEX matching_profile_one_approved ON matching_profile((true)) WHERE status = 'APPROVED';
CREATE TABLE matching_training_job (
  job_id uuid PRIMARY KEY,
  profile_id uuid NOT NULL REFERENCES matching_profile(profile_id),
  status text NOT NULL CHECK (status IN ('QUEUED','RUNNING','COMPLETED','FAILED')),
  message text,
  requested_by text NOT NULL,
  started_at timestamptz NOT NULL DEFAULT now(),
  finished_at timestamptz
);
ALTER TABLE matching_run ADD COLUMN profile_id uuid REFERENCES matching_profile(profile_id);
