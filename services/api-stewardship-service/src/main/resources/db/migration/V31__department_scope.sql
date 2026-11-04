-- R1b: users see data from their assigned departments (or ALL) instead of from state codes. A canonical record
-- belongs to the department owning the Source Pack whose source code (state_code) it carries.
CREATE TABLE user_department_scope (
    user_id         uuid NOT NULL REFERENCES app_user (user_id),
    department_code text NOT NULL,
    PRIMARY KEY (user_id, department_code)
);
COMMENT ON TABLE user_department_scope IS
    'Departments a user may see: department codes, or the single value ALL for every department and unassigned source';

-- ALL stays ALL.
INSERT INTO user_department_scope (user_id, department_code)
SELECT DISTINCT user_id, 'ALL' FROM user_state_scope WHERE upper(state_code) = 'ALL';

-- Every other state code becomes the department owning a pack with that source code: stored packs (manifest
-- departmentCode, else department_source_pack) and the four sample packs that V30 assigned. Codes no department owns
-- grant nothing.
WITH pack_source AS (
    SELECT pack_id, upper(state_code) AS source_code, configuration -> 'manifest' ->> 'departmentCode' AS declared
    FROM state_pack_document
    UNION
    SELECT pack_id, source_code, NULL
    FROM (VALUES ('state-1', 'ST1'), ('state-2', 'ST2'), ('state-3', 'ST3'), ('state-4', 'ST4'))
        AS sample (pack_id, source_code)
),
source_department AS (
    SELECT DISTINCT p.source_code, coalesce(nullif(p.declared, ''), a.department_code) AS department_code
    FROM pack_source p
    LEFT JOIN department_source_pack a ON a.pack_id = p.pack_id
)
INSERT INTO user_department_scope (user_id, department_code)
SELECT DISTINCT s.user_id, d.department_code
FROM user_state_scope s
JOIN source_department d ON d.source_code = upper(s.state_code)
WHERE d.department_code IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM user_state_scope a WHERE a.user_id = s.user_id AND upper(a.state_code) = 'ALL');

COMMENT ON TABLE user_state_scope IS 'Superseded by user_department_scope (V31); no longer read or written';
