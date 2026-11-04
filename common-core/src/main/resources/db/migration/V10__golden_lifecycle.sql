-- M11: stable Golden identity, Golden versions, membership history, lifecycle events and canonical versions.
-- See docs/golden-lifecycle.md and docs/decisions/0001-0006. canonical_resident, raw_record and matching rows are
-- never updated here; existing golden_resident rows keep their IDs and V6 columns.

-- ---- 1. Canonical versions (append-only) ----------------------------------------------------------------------
CREATE TABLE canonical_resident_version (
  canonical_resident_id uuid PRIMARY KEY REFERENCES canonical_resident(canonical_resident_id),
  source_record_key text NOT NULL,
  version_number integer NOT NULL CHECK (version_number >= 1),
  supersedes_canonical_id uuid UNIQUE REFERENCES canonical_resident(canonical_resident_id),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (source_record_key, version_number),
  CHECK ((version_number = 1) = (supersedes_canonical_id IS NULL))
);
COMMENT ON TABLE canonical_resident_version IS
  'Source record key and version chain of each canonical Resident; the latest version is the one nothing supersedes';

-- Backfill with the default key stateCode|sourceSystem|sourceEntity|metadata.sourceRecordId. Canonicals of one batch
-- that share it are different source records, so each gets a |raw:<raw_record_id> suffix; the same key across
-- batches or State Pack versions forms a version chain in creation order.
INSERT INTO canonical_resident_version(canonical_resident_id, source_record_key, version_number,
                                       supersedes_canonical_id, created_at)
SELECT canonical_resident_id, source_record_key,
       row_number() OVER (PARTITION BY source_record_key ORDER BY created_at, canonical_resident_id),
       lag(canonical_resident_id) OVER (PARTITION BY source_record_key ORDER BY created_at, canonical_resident_id),
       created_at
  FROM (SELECT canonical_resident_id, created_at,
               CASE WHEN count(*) OVER (PARTITION BY base_key, batch_id) > 1
                    THEN base_key || '|raw:' || raw_record_id ELSE base_key END AS source_record_key
          FROM (SELECT c.canonical_resident_id, c.batch_id, c.raw_record_id, c.created_at,
                       concat_ws('|', c.state_code, c.source_system, c.source_entity,
                                 coalesce(c.canonical_payload #>> '{metadata,sourceRecordId}',
                                          'canonical:' || c.canonical_resident_id)) AS base_key
                  FROM canonical_resident c
                 WHERE c.canonical_entity_type = 'Resident') keyed) disambiguated;

-- The latest canonical version per source record: the only canonical Residents that take part in matching and
-- survivorship. A canonical row without a version row (written outside Common Core) counts as its own record.
CREATE VIEW canonical_resident_current AS
SELECT c.canonical_resident_id, c.raw_record_id, c.batch_id, c.canonical_entity_type, c.state_code, c.source_system,
       c.source_entity, c.state_pack_version, c.canonical_payload, c.mapped_at, c.created_at,
       coalesce(v.source_record_key, 'canonical:' || c.canonical_resident_id) AS source_record_key,
       coalesce(v.version_number, 1) AS version_number
  FROM canonical_resident c
  LEFT JOIN canonical_resident_version v ON v.canonical_resident_id = c.canonical_resident_id
 WHERE c.canonical_entity_type = 'Resident'
   AND NOT EXISTS (SELECT 1 FROM canonical_resident_version s WHERE s.supersedes_canonical_id = c.canonical_resident_id);

-- ---- 2. Matching mode -------------------------------------------------------------------------------------------
ALTER TABLE matching_run ADD COLUMN mode text NOT NULL DEFAULT 'FULL' CHECK (mode IN ('FULL', 'INCREMENTAL'));
COMMENT ON COLUMN matching_run.mode IS
  'FULL scores every latest canonical; INCREMENTAL only pairs touching latest canonicals that are not Golden members';

-- Onboarding runs choose full or incremental matching and Golden build (M10 runs were full).
ALTER TABLE onboarding_run ADD COLUMN processing_mode text NOT NULL DEFAULT 'FULL'
  CHECK (processing_mode IN ('FULL', 'INCREMENTAL'));

-- ---- 3. Stable Golden identity ----------------------------------------------------------------------------------
ALTER TABLE golden_resident
  ADD COLUMN status text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'RETIRED')),
  ADD COLUMN retired_at timestamptz,
  ADD COLUMN merged_into_id uuid REFERENCES golden_resident(golden_resident_id),
  ADD COLUMN split_from_id uuid REFERENCES golden_resident(golden_resident_id);
COMMENT ON TABLE golden_resident IS
  'Stable Golden identity. matching_run_id, survivorship_policy_version, member_set_key and golden_payload (with '
  'golden_resident_member and golden_resident_field_provenance) describe its founding build; current values are '
  'in golden_resident_version';

-- Carry-forward, not (matching run, policy, member set), is the idempotency rule from M11 on.
DO $$
DECLARE
  constraint_name text;
BEGIN
  FOR constraint_name IN
      SELECT conname FROM pg_constraint WHERE conrelid = 'golden_resident'::regclass AND contype = 'u'
  LOOP
    EXECUTE 'ALTER TABLE golden_resident DROP CONSTRAINT ' || quote_ident(constraint_name);
  END LOOP;
END $$;
CREATE INDEX golden_resident_run_policy_idx ON golden_resident(matching_run_id, survivorship_policy_version);

CREATE TABLE golden_build (
  golden_build_id uuid PRIMARY KEY,
  matching_run_id uuid REFERENCES matching_run(run_id),
  survivorship_policy_version text NOT NULL,
  mode text NOT NULL CHECK (mode IN ('FULL', 'INCREMENTAL')),
  started_at timestamptz NOT NULL DEFAULT now(),
  completed_at timestamptz,
  summary jsonb NOT NULL DEFAULT '{}'::jsonb
);

CREATE TABLE golden_resident_version (
  golden_resident_version_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  golden_resident_id uuid NOT NULL REFERENCES golden_resident(golden_resident_id),
  version_number integer NOT NULL CHECK (version_number >= 1),
  golden_build_id uuid REFERENCES golden_build(golden_build_id),
  matching_run_id uuid REFERENCES matching_run(run_id),
  survivorship_policy_version text NOT NULL,
  member_set_key text NOT NULL,
  golden_payload jsonb NOT NULL,
  change_reasons jsonb NOT NULL DEFAULT '[]'::jsonb,
  effective_from timestamptz NOT NULL DEFAULT now(),
  effective_to timestamptz,
  UNIQUE (golden_resident_id, version_number),
  CHECK (effective_to IS NULL OR effective_to >= effective_from)
);
CREATE UNIQUE INDEX golden_resident_version_current_idx ON golden_resident_version(golden_resident_id)
  WHERE effective_to IS NULL;

CREATE TABLE golden_resident_version_provenance (
  golden_resident_version_id uuid NOT NULL REFERENCES golden_resident_version(golden_resident_version_id),
  field_path text NOT NULL,
  canonical_resident_id uuid NOT NULL REFERENCES canonical_resident(canonical_resident_id),
  selected_value jsonb NOT NULL,
  rule_applied text NOT NULL,
  survivorship_policy_version text NOT NULL,
  contributing_canonical_ids jsonb NOT NULL,
  PRIMARY KEY (golden_resident_version_id, field_path)
);

CREATE TABLE golden_resident_membership (
  golden_resident_membership_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  golden_resident_id uuid NOT NULL REFERENCES golden_resident(golden_resident_id),
  canonical_resident_id uuid NOT NULL REFERENCES canonical_resident(canonical_resident_id),
  source_record_key text NOT NULL,
  -- A member of versions added_in_version up to, but not including, removed_in_version.
  added_in_version integer NOT NULL,
  removed_in_version integer,
  effective_from timestamptz NOT NULL DEFAULT now(),
  effective_to timestamptz,
  CHECK (effective_to IS NULL OR effective_to >= effective_from)
);
-- A source record is a current member of at most one Golden identity.
CREATE UNIQUE INDEX golden_resident_membership_current_key_idx ON golden_resident_membership(source_record_key)
  WHERE effective_to IS NULL;
CREATE INDEX golden_resident_membership_golden_idx ON golden_resident_membership(golden_resident_id, effective_from);
CREATE INDEX golden_resident_membership_canonical_idx ON golden_resident_membership(canonical_resident_id);

CREATE TABLE golden_resident_event (
  golden_resident_event_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  event_sequence bigint GENERATED ALWAYS AS IDENTITY,
  golden_resident_id uuid NOT NULL REFERENCES golden_resident(golden_resident_id),
  event_type text NOT NULL CHECK (event_type IN ('CREATED', 'MEMBER_ADDED', 'MEMBER_REMOVED', 'MEMBER_VERSION_CHANGED',
                                                 'VALUE_CHANGED', 'MERGED', 'SPLIT', 'RETIRED')),
  golden_build_id uuid REFERENCES golden_build(golden_build_id),
  version_number integer,
  related_golden_resident_id uuid REFERENCES golden_resident(golden_resident_id),
  canonical_resident_id uuid REFERENCES canonical_resident(canonical_resident_id),
  source_record_key text,
  field_path text,
  old_value jsonb,
  new_value jsonb,
  detail jsonb NOT NULL DEFAULT '{}'::jsonb,
  occurred_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX golden_resident_event_golden_idx ON golden_resident_event(golden_resident_id, event_sequence);

-- ---- 4. Migrate existing per-run Golden rows --------------------------------------------------------------------
-- Every existing row becomes an identity with version 1. The rows of the most recent build are the current
-- identities; older rows (and current rows left without a current member) retire into the current identity that
-- holds most of their source records.
CREATE TEMPORARY TABLE m11_current_build ON COMMIT DROP AS
SELECT matching_run_id, survivorship_policy_version
  FROM golden_resident
 GROUP BY matching_run_id, survivorship_policy_version
 ORDER BY max(created_at) DESC, matching_run_id, survivorship_policy_version
 LIMIT 1;

INSERT INTO golden_resident_version(golden_resident_id, version_number, matching_run_id, survivorship_policy_version,
                                    member_set_key, golden_payload, change_reasons, effective_from)
SELECT golden_resident_id, 1, matching_run_id, survivorship_policy_version, member_set_key, golden_payload,
       '["CREATED"]'::jsonb, created_at
  FROM golden_resident;

INSERT INTO golden_resident_version_provenance(golden_resident_version_id, field_path, canonical_resident_id,
                                               selected_value, rule_applied, survivorship_policy_version,
                                               contributing_canonical_ids)
SELECT v.golden_resident_version_id, p.field_path, p.canonical_resident_id, p.selected_value, p.rule_applied,
       p.survivorship_policy_version, p.contributing_canonical_ids
  FROM golden_resident_field_provenance p
  JOIN golden_resident_version v ON v.golden_resident_id = p.golden_resident_id AND v.version_number = 1;

INSERT INTO golden_resident_membership(golden_resident_id, canonical_resident_id, source_record_key, added_in_version,
                                       removed_in_version, effective_from, effective_to)
SELECT golden_resident_id, canonical_resident_id, source_record_key, 1,
       CASE WHEN open_rank = 1 THEN NULL ELSE 2 END,
       created_at,
       CASE WHEN open_rank = 1 THEN NULL ELSE greatest(now(), created_at) END
  FROM (SELECT candidates.*,
               CASE WHEN current_candidate
                    THEN row_number() OVER (PARTITION BY source_record_key, current_candidate
                                            ORDER BY created_at, golden_resident_id)
                    ELSE 0 END AS open_rank
          FROM (SELECT g.golden_resident_id, m.canonical_resident_id, g.created_at,
                       coalesce(v.source_record_key, 'canonical:' || m.canonical_resident_id) AS source_record_key,
                       -- Only a member of the current build that is still the latest version stays a current member.
                       (cb.matching_run_id IS NOT NULL
                        AND NOT EXISTS (SELECT 1 FROM canonical_resident_version s
                                         WHERE s.supersedes_canonical_id = m.canonical_resident_id)) AS current_candidate
                  FROM golden_resident_member m
                  JOIN golden_resident g ON g.golden_resident_id = m.golden_resident_id
                  LEFT JOIN canonical_resident_version v ON v.canonical_resident_id = m.canonical_resident_id
                  LEFT JOIN m11_current_build cb ON cb.matching_run_id = g.matching_run_id
                                                AND cb.survivorship_policy_version = g.survivorship_policy_version
               ) candidates) members;

UPDATE golden_resident g
   SET status = 'RETIRED', retired_at = greatest(now(), g.created_at)
 WHERE NOT EXISTS (SELECT 1 FROM golden_resident_membership m
                    WHERE m.golden_resident_id = g.golden_resident_id AND m.effective_to IS NULL);

UPDATE golden_resident r
   SET merged_into_id = (
         SELECT a.golden_resident_id
           FROM golden_resident a
           JOIN golden_resident_membership am ON am.golden_resident_id = a.golden_resident_id
                                             AND am.effective_to IS NULL
          WHERE a.status = 'ACTIVE'
            AND am.source_record_key IN (SELECT rm.source_record_key FROM golden_resident_membership rm
                                          WHERE rm.golden_resident_id = r.golden_resident_id)
          GROUP BY a.golden_resident_id, a.created_at
          ORDER BY count(DISTINCT am.source_record_key) DESC, a.created_at, a.golden_resident_id
          LIMIT 1)
 WHERE r.status = 'RETIRED';

UPDATE golden_resident_version v
   SET effective_to = g.retired_at
  FROM golden_resident g
 WHERE g.golden_resident_id = v.golden_resident_id AND g.status = 'RETIRED' AND v.effective_to IS NULL;

INSERT INTO golden_resident_event(golden_resident_id, event_type, version_number, detail, occurred_at)
SELECT golden_resident_id, 'CREATED', 1,
       jsonb_build_object('migration', 'V10', 'matchingRunId', matching_run_id,
                          'survivorshipPolicyVersion', survivorship_policy_version),
       created_at
  FROM golden_resident
 ORDER BY created_at, golden_resident_id;

INSERT INTO golden_resident_event(golden_resident_id, event_type, version_number, related_golden_resident_id, detail,
                                  occurred_at)
SELECT golden_resident_id, 'RETIRED', 1, merged_into_id,
       jsonb_build_object('migration', 'V10', 'reason',
                          'per-run Golden result superseded when Golden identities became stable'),
       retired_at
  FROM golden_resident
 WHERE status = 'RETIRED'
 ORDER BY created_at, golden_resident_id;
