-- Resident canonical v1.1 (additive): match-ready columns over the latest canonical per source record.
-- canonical_resident_current is left untouched; v1.0 payloads yield NULL for the v1.1 columns they do not carry
-- (birth_year and dob_precision fall back to the date of birth).
CREATE VIEW canonical_resident_match_v AS
SELECT c.canonical_resident_id, c.source_record_key, c.state_code, c.source_system,
       c.canonical_payload ->> 'nameNormalised' AS name_normalised,
       jsonb_path_query_first(c.canonical_payload, '$.relations[*] ? (@.type == "FATHER")') ->> 'nameNormalised'
           AS father_name_normalised,
       coalesce((c.canonical_payload ->> 'birthYear')::int,
                CASE WHEN c.canonical_payload ->> 'dateOfBirth' ~ '^[0-9]{4}-'
                     THEN left(c.canonical_payload ->> 'dateOfBirth', 4)::int END) AS birth_year,
       coalesce(c.canonical_payload ->> 'dateOfBirthPrecision',
                CASE WHEN c.canonical_payload ? 'dateOfBirth' THEN 'DAY' END) AS dob_precision,
       c.canonical_payload #>> '{extensions,uddp.geography,village,lgd}' AS village_lgd,
       c.canonical_payload #>> '{extensions,uddp.identifiers,AADHAAR,last4}' AS aadhaar_last4,
       CASE WHEN length(c.canonical_payload ->> 'mobile') > 4
            THEN repeat('X', length(c.canonical_payload ->> 'mobile') - 4)
                 || right(c.canonical_payload ->> 'mobile', 4) END AS mobile_masked
  FROM canonical_resident_current c;
