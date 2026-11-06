"""Canonical-Resident-only Splink adapter. No Raw or CSV reads; State Packs supply only decision thresholds.

Only the latest canonical version of each source record takes part (``canonical_resident_current``). A full run
scores every pair; an incremental run (``--incremental``, ADR-0004) scores only pairs that touch a latest canonical
that is not yet a current Golden member, and writes clusters only for the components that contain one.
"""
import json
import os
import re
import sys
import uuid
from datetime import datetime, timezone

import pandas as pd
import psycopg
from splink import DuckDBAPI, Linker
from splink.clustering import cluster_pairwise_predictions_at_threshold

from . import identity, v11
from .metrics import store_run_metrics
from .profile import add_features, comparison_weights, predict_with
from .config import (MODEL_VERSION, Thresholds, NEW_RECORD_COLUMN, load_blocking, load_duckdb_settings, load_geography_level, load_thresholds,
                     resident_match_settings, state_pack_directory)

FULL = "FULL"
INCREMENTAL = "INCREMENTAL"
AUTO_MATCH = "AUTO_MATCH"
POSSIBLE_MATCH = "POSSIBLE_MATCH"
NO_MATCH = "NO_MATCH"

CONNECTION_VARIABLES = {
    "host": "UDDP_POSTGRES_HOST",
    "port": "UDDP_POSTGRES_PORT",
    "dbname": "UDDP_POSTGRES_DB",
    "user": "UDDP_POSTGRES_USER",
    "password": "UDDP_POSTGRES_PASSWORD",
}


def connection_settings(environ=os.environ):
    """Read PostgreSQL settings only from UDDP_POSTGRES_* variables; there are no defaults."""
    missing = [name for name in CONNECTION_VARIABLES.values() if not (environ.get(name) or "").strip()]
    if missing:
        raise RuntimeError("Missing required environment variable(s): " + ", ".join(missing)
                           + "; PostgreSQL settings come only from UDDP_POSTGRES_* variables")
    return {key: environ[name] for key, name in CONNECTION_VARIABLES.items()}


def normalise_name(value):
    return re.sub(r"\s+", " ", (value or "").strip().lower())


def normalise_mobile(value):
    return re.sub(r"\D", "", value or "")


def national_id(payload):
    """The first protected national ID's keyed hash (P1 Part 5b); the clear value is never in canonical data."""
    ids = (payload.get("extensions") or {}).get("uddp.nationalIds") or {}
    for _, value in sorted(ids.items()):
        if isinstance(value, dict) and value.get("keyedHash"):
            return value["keyedHash"]
    return None


def geography_lgd(payload, level):
    """The configured geography code; the legacy district field remains a compatibility projection."""
    if level == "district":
        return ((payload.get("extensions") or {}).get("uddp.geography") or {}).get("district", {}).get("lgd") \
            or payload.get("address", {}).get("districtLGD")
    return ((payload.get("extensions") or {}).get("uddp.geography") or {}).get(level, {}).get("lgd")


def normalise_records(rows, policy=None, geography_level="district"):
    """One row per record. With an Identity Policy (Track E3) the single ``national_id`` column is replaced by one
    column per policy identifier type, and each row also carries its state and source (base-source rule); ``rows``
    are then (id, payload, state_code, source_system). An optional fifth element is the row's V101 match-view
    values (a dict); when any record carries ``name_normalised`` the v1.1 feature columns are added."""
    rows = list(rows)
    with_v11 = any(len(row) > 4 and row[4] and row[4].get("name_normalised") for row in rows)
    records = []
    for row in rows:
        record_id, payload = row[0], row[1]
        record = {
            # Canonical records carry relations[]; relationName is the older flat field.
            "relation_name": normalise_name(payload.get("relationName") or v11.any_relation_name(payload)) or None,
            "canonical_record_id": str(record_id),
            "name": normalise_name(payload.get("name")),
            "date_of_birth": payload.get("dateOfBirth"),
            "gender": payload.get("gender"),
            "mobile": normalise_mobile(payload.get("mobile")),
            "district_lgd": geography_lgd(payload, geography_level),
        }
        if policy is None:
            record["national_id"] = national_id(payload)
        else:
            for code in identity.policy_types(policy):
                record[identity.identifier_column(code)] = identity.identifier_value(payload, code)
            record["state_code"] = row[2] if len(row) > 2 else None
            record["source_system"] = row[3] if len(row) > 3 else None
        if with_v11:
            record.update(v11.record_features(payload, row[4] if len(row) > 4 else None, record["name"]))
            if v11.is_masked(payload.get("mobile")) or not record["mobile"]:
                record["mobile"] = None
            record["state_code"] = record.get("state_code") or (row[2] if len(row) > 2 else None)
            record["source_system"] = record.get("source_system") or (row[3] if len(row) > 3 else None)
            record["husband_name"] = v11.relation_name(payload, "HUSBAND")
        records.append(record)
    frame = pd.DataFrame(records)
    if with_v11:
        frame["birth_year"] = pd.array(frame["birth_year"], dtype="Int64")
        object_columns = [c for c in frame.columns if c != "birth_year"]
        frame[object_columns] = frame[object_columns].astype(object).where(frame[object_columns].notna(), None)
    return frame


def load_records(conn, policy=None, geography_level="district"):
    """Latest canonical versions only: a superseded version never takes part in matching."""
    with conn.cursor() as cur:
        if cur.execute("select to_regclass(%s)", (v11.VIEW,)).fetchone()[0] is None:
            cur.execute("select canonical_resident_id, canonical_payload, state_code, source_system"
                        " from canonical_resident_current order by canonical_resident_id")
            return normalise_records(cur.fetchall(), policy, geography_level)
        columns = ", ".join("v." + c for c in v11.VIEW_COLUMNS)
        cur.execute("select c.canonical_resident_id, c.canonical_payload, c.state_code, c.source_system, " + columns
                    + f" from canonical_resident_current c join {v11.VIEW} v using (canonical_resident_id)"
                    " order by c.canonical_resident_id")
        return normalise_records(
            [(r[0], r[1], r[2], r[3], dict(zip(v11.VIEW_COLUMNS, r[4:]))) for r in cur.fetchall()],
            policy, geography_level)


def load_new_record_ids(conn):
    """Latest canonical versions that are not a current Golden member: new or changed source records."""
    with conn.cursor() as cur:
        cur.execute("select c.canonical_resident_id::text from canonical_resident_current c"
                    " where not exists (select 1 from golden_resident_membership m"
                    " where m.canonical_resident_id = c.canonical_resident_id and m.effective_to is null)")
        return {row[0] for row in cur.fetchall()}


def duckdb_api():
    """DuckDB connection with the memory/thread limits from matching/resident.yaml."""
    import duckdb
    settings = load_duckdb_settings()
    options = {}
    if settings.get("memoryLimit"):
        options["memory_limit"] = str(settings["memoryLimit"])
    if settings.get("threads"):
        options["threads"] = int(settings["threads"])
    return DuckDBAPI(duckdb.connect(":memory:", config=options)) if options else DuckDBAPI()


def print_blocking_estimate(records):
    estimate = v11.blocking_estimate(records, load_blocking())
    if estimate is None:
        return
    detail = ", ".join(f"{'+'.join(r['rule'])}: {r['pairs']:,}" for r in estimate["rules"]) or "no rule has its columns"
    print(f"Blocking on ({estimate['records']:,} records): about {estimate['totalPairs']:,} candidate pairs "
          f"instead of {estimate['allPairs']:,} ({detail})")


def initialise_linker(records, incremental=False, policy=None, geography_level="district"):
    evidence = identity.evidence_columns(policy) if policy else ()
    return Linker(records, resident_match_settings(incremental, evidence_columns=evidence,
                                                    geography_column="district_lgd", records=records,
                                                    blocking=load_blocking()), duckdb_api())


def predict(linker):
    predictions = linker.inference.predict().as_pandas_dataframe()
    if not {"match_weight", "match_probability"}.issubset(predictions.columns):
        raise RuntimeError("Splink prediction did not return native match evidence")
    return predictions


def ordered_pair(left, right):
    return tuple(sorted((str(left), str(right))))


def decision(probability, thresholds):
    """AUTO_MATCH at or above autoMatch, POSSIBLE_MATCH from possibleMatch up to autoMatch, else NO_MATCH."""
    probability = float(probability)
    if probability >= thresholds.auto_match:
        return AUTO_MATCH
    if probability >= thresholds.possible_match:
        return POSSIBLE_MATCH
    return NO_MATCH


def pair_rows(run_id, predictions, thresholds, with_weights=False):
    rows = []
    for p in predictions.to_dict("records"):
        a, b = ordered_pair(p["canonical_record_id_l"], p["canonical_record_id_r"])
        evidence = {
            "name": [p.get("name_l"), p.get("name_r")],
            "dateOfBirth": [p.get("date_of_birth_l"), p.get("date_of_birth_r")],
            "mobile": [p.get("mobile_l"), p.get("mobile_r")],
            "gender": [p.get("gender_l"), p.get("gender_r")],
            "districtLGD": [p.get("district_lgd_l"), p.get("district_lgd_r")],
            "native_match_weight": float(p["match_weight"]),
            "native_match_probability": float(p["match_probability"]),
        }
        if with_weights:
            evidence["comparisonWeights"] = comparison_weights(p)
            evidence["priorWeight"] = _prior_weight(p)
        rows.append((run_id, a, b, float(p["match_probability"]), float(p["match_weight"]),
                     decision(p["match_probability"], thresholds), json.dumps(evidence)))
    return rows


def _prior_weight(prediction):
    """Match weight a pair starts from before any field is compared."""
    return float(prediction["match_weight"]) - sum(comparison_weights(prediction).values())


def cluster_rows(run_id, linker, records, predictions, thresholds, new_ids=None):
    """Only AUTO_MATCH pairs link records: clustering is cut at the autoMatch threshold.

    With ``new_ids`` (incremental), only the clusters that contain a new or changed record are returned.
    """
    if predictions.empty:
        rows = [(run_id, record_id, record_id) for record_id in records["canonical_record_id"]]
    else:
        clusters = cluster_pairwise_predictions_at_threshold(
            records, predictions, linker._db_api, "canonical_record_id", "canonical_record_id_l", "canonical_record_id_r",
            threshold_match_probability=thresholds.auto_match).as_pandas_dataframe()
        rows = [(run_id, str(row.cluster_id), str(row.canonical_record_id)) for row in clusters.itertuples(index=False)]
    if new_ids is None:
        return rows
    touched = {cluster for _, cluster, record_id in rows if record_id in new_ids}
    return [row for row in rows if row[1] in touched]


def print_requested_evidence(predictions, thresholds):
    pairs = predictions.to_dict("records")
    ram = [p for p in pairs if p.get("name_l") == "ram singh" and p.get("name_r") == "ram singh"]
    other = next((p for p in pairs if p.get("name_l") != "ram singh" or p.get("name_r") != "ram singh"), None)
    labelled = [("Ram Singh", p) for p in ram] + ([("Clearly different", other)] if other else [])
    for label, p in labelled:
        print(json.dumps({
            "pair": label,
            "canonical_ids": [p["canonical_record_id_l"], p["canonical_record_id_r"]],
            "name": [p.get("name_l"), p.get("name_r")],
            "DOB": [p.get("date_of_birth_l"), p.get("date_of_birth_r")],
            "mobile": [p.get("mobile_l"), p.get("mobile_r")],
            "gender": [p.get("gender_l"), p.get("gender_r")],
            "district": [p.get("district_lgd_l"), p.get("district_lgd_r")],
            "native_match_weight": p["match_weight"],
            "native_match_probability": p["match_probability"],
            "auto_match_threshold": thresholds.auto_match,
            "possible_match_threshold": thresholds.possible_match,
            "decision": decision(p["match_probability"], thresholds),
        }, default=str))


def load_approved_profile(conn):
    """The APPROVED matching profile (at most one), or None: resident-match-v1 stays the default until one is approved."""
    row = conn.execute("select profile_id, name, version, config, parameters from matching_profile"
                       " where status = 'APPROVED'").fetchone()
    if row is None or row[4] is None:
        return None
    return {"profile_id": row[0], "model_version": f"{row[1]}-v{row[2]}", "config": row[3], "parameters": row[4]}


def profile_thresholds(profile):
    configured = profile["config"].get("thresholds") or {}
    return Thresholds(float(configured.get("autoMatch", 0.95)), float(configured.get("possibleMatch", 0.70)))


def first_load(conn):
    """No Golden Resident exists yet."""
    return conn.execute("select not exists (select 1 from golden_resident)").fetchone()[0]


def run(incremental=False):
    thresholds = load_thresholds(state_pack_directory())
    geography_level = load_geography_level(state_pack_directory())
    mode = INCREMENTAL if incremental else FULL
    with psycopg.connect(**connection_settings()) as conn:
        profile = load_approved_profile(conn)
        if profile:
            thresholds = profile_thresholds(profile)
        policy = identity.load_identity_policy(conn)
        print("Loading canonical residents...")
        records = load_records(conn, policy, geography_level)
        if profile:
            records = add_features(records)
        new_ids = load_new_record_ids(conn) if incremental else None
        considered = len(new_ids) if incremental else len(records)
        linker = None
        if len(records) < 2 or (incremental and not new_ids):
            print("Nothing to compare.")
            predictions = pd.DataFrame()
        else:
            if incremental:
                records[NEW_RECORD_COLUMN] = records["canonical_record_id"].isin(new_ids).astype(int)
            print_blocking_estimate(records)
            print("Initializing Splink...")
            print("Running prediction...")
            if profile:
                linker, predictions = predict_with(records, profile["config"], profile["parameters"], incremental)
            else:
                linker = initialise_linker(records, incremental, policy)
                predictions = predict(linker)
            print("Prediction complete...")
            print_requested_evidence(predictions, thresholds)
        run_id = uuid.uuid4()
        print("Persisting matching run...")
        with conn.cursor() as cur:
            cur.execute("insert into matching_run(run_id,model_version,threshold,possible_match_threshold,status,"
                        "records_considered,mode,profile_id) values(%s,%s,%s,%s,'RUNNING',%s,%s,%s)",
                        (run_id, profile["model_version"] if profile else MODEL_VERSION, thresholds.auto_match,
                         thresholds.possible_match, considered, mode, profile["profile_id"] if profile else None))
        print("Persisting pairs...")
        pairs = pair_rows(run_id, predictions, thresholds, with_weights=bool(profile))
        if policy:
            # Deterministic pre-pass and the policy's never-merge / stricter rules (Track E3).
            pairs = identity.apply_policy([(run_id, *p[1:]) for p in pairs], records, policy, thresholds,
                                          first_load=not incremental and first_load(conn), new_ids=new_ids)
            pairs = [(run_id, *p[1:]) for p in pairs]
        guarded = v11.enabled(records) if len(records) else False
        if guarded:
            pairs = identity.apply_guards(pairs, records, policy)
        with conn.cursor() as cur:
            cur.executemany("insert into resident_match_pair(run_id,canonical_record_a,canonical_record_b,match_probability,"
                            "match_weight,decision,evidence) values(%s,%s,%s,%s,%s,%s,%s::jsonb)", pairs)
        print("Clustering...")
        with conn.cursor() as cur:
            if incremental and not new_ids:
                clusters = []
            elif policy or guarded:
                clusters = identity.cluster_rows(run_id, list(records["canonical_record_id"]) if len(records) else [],
                                                 pairs, new_ids)
            else:
                clusters = cluster_rows(run_id, linker, records, predictions, thresholds, new_ids)
            cur.executemany("insert into resident_match_cluster_member(run_id,cluster_id,canonical_record_id) values(%s,%s,%s)",
                            clusters)
            cur.execute("update matching_run set status='COMPLETED', completed_at=%s where run_id=%s",
                        (datetime.now(timezone.utc), run_id))
        decisions = [p[5] for p in pairs]
        counts = {kind: decisions.count(kind) for kind in (AUTO_MATCH, POSSIBLE_MATCH, NO_MATCH)}
        store_run_metrics(conn, run_id, considered, counts, [p[3] for p in pairs], clusters, new_ids)
        print("Complete.")
        return {
            "run_id": str(run_id),
            "mode": mode,
            "records_considered": considered,
            "candidate_pairs": len(pairs),
            "auto_match_threshold": thresholds.auto_match,
            "possible_match_threshold": thresholds.possible_match,
            "decisions": counts,
        }


if __name__ == "__main__":
    print(json.dumps(run(incremental="--incremental" in sys.argv[1:])))
