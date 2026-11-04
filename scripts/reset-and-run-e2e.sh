#!/usr/bin/env bash
# One-command end-to-end proof on a throwaway PostgreSQL 16 (Linux/macOS twin of reset-and-run-e2e.ps1):
# Flyway -> generic Hop ingest of every State Pack source -> canonical processing -> Splink matching
# -> Golden build, then prints counts and asserts canonical=7, golden=5, AUTO_MATCH=3.
#
# Never touches your UDDP_POSTGRES_* database: a disposable postgres:16-alpine container gets a random
# localhost port and a random password (never printed); UDDP_POSTGRES_* are overridden for this process
# and its children only. The container is removed at the end (KEEP_DATABASE=1 keeps it).
#
# Needs: HOP_HOME (Apache Hop 2.x, with this repository registered as a Hop project; UDDP_HOP_PROJECT
# names it, default uddp-mvp), Docker, Java 21, Maven, Python 3.12 with matching/requirements.txt.
# Overridable: STATE_PACK_DIRECTORY, EXPECT_RAW, EXPECT_CANONICAL, EXPECT_GOLDEN, EXPECT_AUTO_MATCH, EXPECT_FAMILIES,
# EXPECT_BENEFITS, EXPECT_RAM_BENEFITS, EXPECT_DUPLICATE_FINDINGS, EXPECT_NOT_ENROLLED, ELIGIBILITY_AS_OF (M15).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CORE="$ROOT/common-core"
PACKS="$(cd "$ROOT/${STATE_PACK_DIRECTORY:-state-packs}" && pwd)"
EXPECT_RAW="${EXPECT_RAW:-7}"
EXPECT_CANONICAL="${EXPECT_CANONICAL:-7}"
EXPECT_GOLDEN="${EXPECT_GOLDEN:-5}"
EXPECT_AUTO_MATCH="${EXPECT_AUTO_MATCH:-3}"
EXPECT_FAMILIES="${EXPECT_FAMILIES:-6}"
EXPECT_BENEFITS="${EXPECT_BENEFITS:-5}"
EXPECT_RAM_BENEFITS="${EXPECT_RAM_BENEFITS:-3}"
EXPECT_DUPLICATE_FINDINGS="${EXPECT_DUPLICATE_FINDINGS:-1}"
EXPECT_NOT_ENROLLED="${EXPECT_NOT_ENROLLED:-1}"
ELIGIBILITY_AS_OF="${ELIGIBILITY_AS_OF:-2026-10-25}"
PYTHON="${PYTHON:-python3}"

fail() { echo "E2E FAILED: $*" >&2; exit 1; }
need() { command -v "$1" >/dev/null 2>&1 || fail "$1 not found on PATH. $2"; }

# ---- 1. Prerequisites ------------------------------------------------------------------------------
[ -n "${HOP_HOME:-}" ] || fail "HOP_HOME is not set. Point it at your Apache Hop installation (the folder containing hop-run.sh)."
HOP_RUN="$HOP_HOME/hop-run.sh"
[ -x "$HOP_RUN" ] || fail "HOP_HOME=$HOP_HOME does not contain an executable hop-run.sh."
need docker "Install and start Docker."
need java "Install Java 21."
need mvn "Install Maven 3.9+."
need "$PYTHON" "Install Python 3.12 and run: pip install -r matching/requirements.txt"
docker info >/dev/null 2>&1 || fail "Docker is installed but not running."
HOP_PROJECT="${UDDP_HOP_PROJECT:-uddp-mvp}"

# ---- 2. Throwaway PostgreSQL 16 ----------------------------------------------------------------------
SUFFIX="$(od -An -N4 -tx1 /dev/urandom | tr -d ' \n')"
CONTAINER="uddp-e2e-$SUFFIX"
DATABASE=uddp_e2e
DB_USER=uddp_e2e
export POSTGRES_PASSWORD="$(od -An -N24 -tx1 /dev/urandom | tr -d ' \n')"
TMP="$(mktemp -d)"
APP_PID=""
cleanup() {
  [ -n "$APP_PID" ] && kill "$APP_PID" 2>/dev/null || true
  if [ "${KEEP_DATABASE:-0}" = "1" ]; then
    echo "Keeping container $CONTAINER (remove with: docker rm -f $CONTAINER)"
  else
    docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
  fi
  rm -rf "$TMP"
}
trap cleanup EXIT

psql_() { docker exec "$CONTAINER" psql -X -q -t -A -v ON_ERROR_STOP=1 -U "$DB_USER" -d "$DATABASE" -c "$1"; }

echo "Starting throwaway PostgreSQL 16 container $CONTAINER ..."
# The password reaches Docker through the POSTGRES_PASSWORD environment variable, not the command line.
docker run -d --rm --name "$CONTAINER" -e POSTGRES_PASSWORD -e "POSTGRES_USER=$DB_USER" -e "POSTGRES_DB=$DATABASE" \
  -p 127.0.0.1::5432 postgres:16-alpine >/dev/null
for _ in $(seq 60); do
  docker exec "$CONTAINER" pg_isready -q -h 127.0.0.1 -U "$DB_USER" -d "$DATABASE" && break
  sleep 1
done
docker exec "$CONTAINER" pg_isready -q -h 127.0.0.1 -U "$DB_USER" -d "$DATABASE" || fail "PostgreSQL did not become ready."
PORT="$(docker port "$CONTAINER" 5432/tcp | head -1 | awk -F: '{print $NF}')"

unset UDDP_POSTGRES_URL
export UDDP_POSTGRES_HOST=127.0.0.1 UDDP_POSTGRES_PORT="$PORT" UDDP_POSTGRES_DB="$DATABASE"
export UDDP_POSTGRES_USER="$DB_USER" UDDP_POSTGRES_PASSWORD="$POSTGRES_PASSWORD"
export UDDP_STATE_PACK_DIRECTORY="$PACKS" HOP_AUDIT_FOLDER="$TMP/hop-audit"

# ---- 3. Build and Flyway -----------------------------------------------------------------------------
echo "Building Common Core ..."
(cd "$CORE" && mvn -q -B -DskipTests compile dependency:build-classpath \
  -Dmdep.outputFile=target/classpath.txt -Dmdep.includeScope=runtime) || fail "Maven build failed."
CLASSPATH_="$CORE/target/classes:$(cat "$CORE/target/classpath.txt")"
echo "Running Flyway migrate ..."
java -cp "$CLASSPATH_" gov.uddp.core.database.DatabaseMigrationCommand migrate || fail "Flyway migrate failed."

# ---- 4. Generic Hop ingest of every State Pack source --------------------------------------------
# Hop resolves ${UDDP_POSTGRES_*} in its metadata from Java system properties; hop-run echoes its
# arguments, so they go in a JVM argument file named by JDK_JAVA_OPTIONS (only the file name is printed).
HOP_ARGS="$TMP/hop.args"
for name in UDDP_POSTGRES_HOST UDDP_POSTGRES_PORT UDDP_POSTGRES_DB UDDP_POSTGRES_USER UDDP_POSTGRES_PASSWORD; do
  printf -- '-D%s=%s\n' "$name" "${!name}"
done > "$HOP_ARGS"
chmod 600 "$HOP_ARGS"

field() { "$PYTHON" -c 'import json,sys; print(json.loads(sys.argv[1])[sys.argv[2]])' "$1" "$2"; }
BATCHES=()
while IFS= read -r source; do
  batch="$("$PYTHON" -c 'import uuid; print(uuid.uuid4())')"
  pack="$(field "$source" packId)"; source_id="$(field "$source" sourceId)"; template="$(field "$source" hopTemplate)"
  state="$(field "$source" stateCode)"; version="$(field "$source" statePackVersion)"
  schema="$(field "$source" sourceSchemaVersion)"; type="$(field "$source" sourceType)"
  echo "Ingesting $pack/$source_id with $template (batch $batch) ..."
  psql_ "insert into ingestion_batch(batch_id,state_code,source_system,source_type,source_schema_version,state_pack_version,pipeline_template,status) values('$batch','$state','$source_id','$type','$schema','$version','$template','STARTED')" >/dev/null
  params="SOURCE_FILE=$(field "$source" sourceFile)@@CSV_HEADER=$(field "$source" csvHeader)@@STATE_CODE=$state"
  params="$params@@SOURCE_SYSTEM=$source_id@@SOURCE_ENTITY=$source_id@@SOURCE_SCHEMA_VERSION=$schema"
  params="$params@@STATE_PACK_VERSION=$version@@BATCH_ID=$batch"
  if ! JDK_JAVA_OPTIONS="@$HOP_ARGS" "$HOP_RUN" -j "$HOP_PROJECT" -r local -l MINIMAL -f "$ROOT/hop/pipelines/$template" -ps '@@' -p "$params"; then
    psql_ "update ingestion_batch set status='FAILED', completed_at=now(), error_message='hop-run failed' where batch_id='$batch'" >/dev/null
    fail "Hop ingest failed for $pack/$source_id."
  fi
  psql_ "update ingestion_batch set status='COMPLETED', completed_at=now(), record_count=(select count(*) from raw_record where batch_id='$batch') where batch_id='$batch'" >/dev/null
  BATCHES+=("$batch")
done < <("$PYTHON" "$ROOT/scripts/e2e/list_sources.py" "$PACKS" "$ROOT")

# ---- 5. Common Core: canonical processing ---------------------------------------------------------
APP_PORT="$("$PYTHON" -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])')"
echo "Starting Common Core on port $APP_PORT ..."
(cd "$CORE" && exec java -cp "$CLASSPATH_" gov.uddp.core.UddpCommonCoreApplication --server.port="$APP_PORT" \
  --uddp.state-pack-directory="$PACKS" --uddp.fixture-state-packs-enabled=true \
  --uddp.security.demo.seed-users=true --uddp.security.demo.credentials-file="$TMP/demo-users.txt" \
  >"$TMP/app.log" 2>&1) &
APP_PID=$!
BASE="http://127.0.0.1:$APP_PORT"
for _ in $(seq 90); do
  curl -fsS "$BASE/health" >/dev/null 2>&1 && break
  kill -0 "$APP_PID" 2>/dev/null || { cat "$TMP/app.log" >&2; fail "Common Core exited during startup."; }
  sleep 1
done
curl -fsS "$BASE/health" >/dev/null || fail "Common Core did not start."

# P1: every API call is signed in. The demo users of this throwaway database have one-time passwords (read from
# the seeder's file into this shell, then the file is deleted; nothing is printed). use_user signs a user in once,
# replaces the one-time password with a random one and keeps one cookie jar per user; api() sends the session
# cookie and, on writes, the CSRF token from the XSRF-TOKEN cookie.
for _ in $(seq 60); do [ -s "$TMP/demo-users.txt" ] && break; sleep 1; done
[ -s "$TMP/demo-users.txt" ] || fail "The demo users were not created."
sleep 1
DEMO_USERS="$(cat "$TMP/demo-users.txt")"
rm -f "$TMP/demo-users.txt"
JAR=""
use_user() {
  JAR="$TMP/cookies-$1.txt"
  [ -f "$JAR.ready" ] && return 0
  local otp me
  otp="$(printf '%s\n' "$DEMO_USERS" | awk -v u="$1" '$1 == u { print $2 }')"
  [ -n "$otp" ] || fail "No demo user $1."
  api GET /api/v1/auth/csrf >/dev/null
  me="$(api POST /api/v1/auth/login "{\"username\": \"$1\", \"password\": \"$otp\"}")" || fail "Cannot sign in as $1."
  if printf '%s' "$me" | grep -q '"mustChangePassword":true'; then
    api POST /api/v1/auth/change-password "{\"currentPassword\": \"$otp\", \"newPassword\": \"Ua1-$(od -An -N18 -tx1 /dev/urandom | tr -d ' \n')\"}" \
      >/dev/null || fail "Cannot change the one-time password of $1."
  fi
  touch "$JAR.ready"
}
api() {
  local method="$1" path="$2" body="${3:-}"
  local token
  token="$(awk '$6 == "XSRF-TOKEN" { print $7 }' "$JAR" 2>/dev/null | tail -1)"
  if [ -n "$body" ]; then
    curl -fsS -b "$JAR" -c "$JAR" -X "$method" -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $token" \
      -d "$body" "$BASE$path"
  elif [ "$method" = "GET" ]; then
    curl -fsS -b "$JAR" -c "$JAR" "$BASE$path"
  else
    curl -fsS -b "$JAR" -c "$JAR" -X "$method" -H "X-XSRF-TOKEN: $token" "$BASE$path"
  fi
}
use_user platform.admin
for batch in "${BATCHES[@]}"; do
  echo "Canonical batch $batch: $(api POST "/api/canonical/residents/process/batches/$batch")"
done

# ---- 6. Splink matching -------------------------------------------------------------------------------
echo "Running Splink matching ..."
RUN_ID="$(cd "$ROOT" && "$PYTHON" -m matching.run | tail -1 | "$PYTHON" -c 'import json,sys; print(json.load(sys.stdin)["run_id"])')"

# ---- 7. Golden build ----------------------------------------------------------------------------------
GOLDEN="$(api POST /api/golden-residents/build "{\"matchingRunId\": \"$RUN_ID\"}")"
POLICY="$(field "$GOLDEN" policyVersion)"

# ---- 8. Counts and assertions -------------------------------------------------------------------------
RAW="$(psql_ 'select count(*) from raw_record')"
CANONICAL="$(psql_ 'select count(*) from canonical_resident')"
ERRORS="$(psql_ 'select count(*) from canonical_processing_error')"
AUTO="$(psql_ "select count(*) from resident_match_pair where run_id='$RUN_ID' and decision='AUTO_MATCH'")"
POSSIBLE="$(psql_ "select count(*) from resident_match_pair where run_id='$RUN_ID' and decision='POSSIBLE_MATCH'")"
GOLDEN_COUNT="$(psql_ "select count(*) from golden_resident where status='ACTIVE'")"
FAMILIES="$(psql_ "select count(*) from golden_family where status='ACTIVE'")"
echo
echo "E2E counts"
echo "  raw_record          $RAW"
echo "  canonical_resident  $CANONICAL   (processing errors: $ERRORS)"
echo "  AUTO_MATCH pairs    $AUTO   (POSSIBLE_MATCH: $POSSIBLE)"
echo "  golden_resident     $GOLDEN_COUNT   (active identities; run $RUN_ID, policy $POLICY)"
echo "  golden_family       $FAMILIES   (active households)"
problems=""
[ "$RAW" = "$EXPECT_RAW" ] || problems="$problems raw=$RAW (expected $EXPECT_RAW);"
[ "$CANONICAL" = "$EXPECT_CANONICAL" ] || problems="$problems canonical=$CANONICAL (expected $EXPECT_CANONICAL);"
[ "$GOLDEN_COUNT" = "$EXPECT_GOLDEN" ] || problems="$problems golden=$GOLDEN_COUNT (expected $EXPECT_GOLDEN);"
[ "$AUTO" = "$EXPECT_AUTO_MATCH" ] || problems="$problems AUTO_MATCH=$AUTO (expected $EXPECT_AUTO_MATCH);"
[ "$FAMILIES" = "$EXPECT_FAMILIES" ] || problems="$problems families=$FAMILIES (expected $EXPECT_FAMILIES);"
[ -z "$problems" ] || fail "$problems"
echo "E2E PASSED: canonical=$CANONICAL golden=$GOLDEN_COUNT AUTO_MATCH=$AUTO families=$FAMILIES"

# ---- 9. Benefits through the read API (M15) ----------------------------------------------------------------
RAM="$(psql_ "select string_agg(distinct m.golden_resident_id::text, ',') from golden_resident_membership m join canonical_resident c on c.canonical_resident_id = m.canonical_resident_id where m.effective_to is null and c.canonical_payload->>'name' = 'Ram Singh'")"
BENEFIT_CHECK="$("$PYTHON" - "$(api GET "/api/v1/benefits?size=1")"   "$(api GET "/api/v1/golden-residents/$RAM/benefits")" "$(api GET /api/v1/reports/duplicate-benefits)"   "$(api GET "/api/v1/reports/eligible-not-enrolled?schemeId=OLD-AGE-PENSION&asOf=$ELIGIBILITY_AS_OF")"   "$RAM" "$EXPECT_BENEFITS" "$EXPECT_RAM_BENEFITS" "$EXPECT_DUPLICATE_FINDINGS" "$EXPECT_NOT_ENROLLED" <<'PY'
import json, sys
page, view, duplicates, eligibility = (json.loads(a) for a in sys.argv[1:5])
ram, benefits, ram_benefits, findings, not_enrolled = sys.argv[5], *map(int, sys.argv[6:10])
schemes = {b["benefit"]["schemeId"]: set() for b in view["benefits"]}
for b in view["benefits"]:
    schemes[b["benefit"]["schemeId"]].add(b["benefit"]["stateCode"])
states = set().union(*schemes.values()) if schemes else set()
missing = eligibility["schemes"][0]["notEnrolledCount"]
problems = []
if page["totalItems"] != benefits: problems.append(f"benefits={page['totalItems']} (expected {benefits})")
if view["benefitCount"] != ram_benefits: problems.append(f"Ram Singh holds {view['benefitCount']} (expected {ram_benefits})")
if not {"PDS-RATION", "OLD-AGE-PENSION"} <= schemes.keys() or len(states) < 2:
    problems.append("Ram Singh does not show PDS and pension benefits from different states")
if duplicates["findingCount"] != findings: problems.append(f"duplicate findings={duplicates['findingCount']} (expected {findings})")
elif findings and duplicates["findings"][0]["goldenResidentId"] != ram: problems.append("duplicate finding is not Ram Singh")
if missing != not_enrolled: problems.append(f"eligible not enrolled={missing} (expected {not_enrolled})")
print("; ".join(problems) if problems else f"OK benefits={page['totalItems']} ram={view['benefitCount']} states={len(states)} duplicates={duplicates['findingCount']} not_enrolled={missing}")
PY
)"
case "$BENEFIT_CHECK" in OK*) echo "BENEFIT E2E PASSED: ${BENEFIT_CHECK#OK }" ;; *) fail "$BENEFIT_CHECK" ;; esac

# ---- 10. Stewardship smoke with maker-checker: one steward proposes, a second approves ---------------------------
proposal_id() { "$PYTHON" -c 'import json,sys; print(json.load(sys.stdin)["proposalId"])'; }
DETACH="$(psql_ "select max(source_record_key) from golden_resident_membership where golden_resident_id = '$RAM' and effective_to is null")"
use_user data.steward
PROPOSAL="$(api POST /api/stewardship/proposals "{\"action\": \"SPLIT\", \"goldenResidentIds\": [\"$RAM\"], \"sourceRecordKeys\": [\"$DETACH\"], \"reason\": \"stewardship smoke\"}" | proposal_id)"
[ "$(psql_ "select count(*) from golden_resident where status='ACTIVE'")" = "$EXPECT_GOLDEN" ] \
  || fail "a pending split proposal changed Golden data"
use_user data.steward2
api POST "/api/stewardship/proposals/$PROPOSAL/approve" '{}' >/dev/null || fail "Approving the split failed."
AFTER_SPLIT="$(psql_ "select count(*) from golden_resident where status='ACTIVE'")"
SIDE="$(psql_ "select golden_resident_id from golden_resident_membership where source_record_key = '$DETACH' and effective_to is null")"
use_user data.steward
PROPOSAL="$(api POST /api/stewardship/proposals "{\"action\": \"MERGE\", \"goldenResidentIds\": [\"$RAM\", \"$SIDE\"], \"reason\": \"stewardship smoke\"}" | proposal_id)"
use_user data.steward2
api POST "/api/stewardship/proposals/$PROPOSAL/approve" '{}' >/dev/null || fail "Approving the merge failed."
AFTER_MERGE="$(psql_ "select count(*) from golden_resident where status='ACTIVE'")"
RAM_NOW="$(psql_ "select golden_resident_id from golden_resident_membership where source_record_key = '$DETACH' and effective_to is null")"
use_user platform.admin
[ "$AFTER_SPLIT" = "$((EXPECT_GOLDEN + 1))" ] || fail "after split golden=$AFTER_SPLIT (expected $((EXPECT_GOLDEN + 1)))"
[ "$AFTER_MERGE" = "$EXPECT_GOLDEN" ] || fail "after merge golden=$AFTER_MERGE (expected $EXPECT_GOLDEN)"
[ "$RAM_NOW" = "$RAM" ] && [ "$SIDE" != "$RAM" ] || fail "Ram Singh Golden ID $RAM -> $RAM_NOW (split side $SIDE)"
echo "STEWARDSHIP E2E PASSED: split $AFTER_SPLIT, merge $AFTER_MERGE (proposed and approved by different stewards)"
