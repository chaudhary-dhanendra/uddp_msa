"""Track E3: Identity Policy in matching — per-type identifier columns and the deterministic pre-pass before Splink."""
import json

from matching import identity
from matching.config import Thresholds
from matching.run import normalise_records

BANDS = Thresholds(auto_match=0.95, possible_match=0.75)
POLICY = identity.normalise_policy({
    "deterministicTypes": ["AADHAAR"], "neverMergeTypes": ["AADHAAR", "VOTER_ID"], "evidenceTypes": ["VOTER_ID"],
    "dobToleranceDays": 365, "noIdentifierAutoMatch": 0.99, "baseSource": None,
})


def payload(name, dob, gender, **ids):
    extensions = {"uddp.identifiers": {code: {"subject": "PERSON", "storage": "KEYED_HASH", "value": value}
                                       for code, value in ids.items()}}
    return {"name": name, "dateOfBirth": dob, "gender": gender, "address": {}, "extensions": extensions}


def records(policy=POLICY):
    return normalise_records([
        ("a", payload("Ram Singh", "1980-03-12", "MALE", AADHAAR="h1"), "ST1", "pds"),
        ("b", payload("R. Singh", "1980-03-12", "MALE", AADHAAR="h1"), "ST2", "pension"),
        ("c", payload("Ram Singh", "1980-03-12", "MALE", AADHAAR="h2"), "ST3", "welfare"),
        ("d", payload("Ram Sing", "1991-01-01", "MALE", AADHAAR="h2"), "ST3", "welfare"),
        ("e", payload("Sita Devi", "1975-01-01", "FEMALE", VOTER_ID="v1"), "ST1", "pds"),
        ("f", payload("Sita Devi", "1975-01-01", "FEMALE", VOTER_ID="v2"), "ST2", "pension"),
        ("g", payload("Mohan Lal", "1960-01-01", "MALE"), "ST1", "pds"),
        ("h", payload("Mohan Lal", "1960-01-01", "MALE"), "ST2", "pension"),
    ], policy)


def splink_pair(a, b, probability, decision):
    return ("run", a, b, probability, 5.0, decision, json.dumps({"native_match_probability": probability}))


def test_one_column_per_policy_identifier_type_replaces_national_id():
    frame = records()
    assert "national_id" not in frame.columns
    assert {"id_aadhaar", "id_voter_id", "state_code", "source_system"} <= set(frame.columns)
    assert frame.set_index("canonical_record_id").loc["a", "id_aadhaar"] == "h1"
    assert identity.evidence_columns(POLICY) == ["id_voter_id"]
    legacy = {"name": "X", "address": {}, "extensions": {"uddp.nationalIds": {"AADHAAR": {"keyedHash": "old"}}}}
    assert identity.identifier_value(legacy, "AADHAAR") == "old"
    without_policy = normalise_records([("a", payload("Ram Singh", "1980-03-12", "MALE", AADHAAR="h1"))])
    assert "national_id" in without_policy.columns and "id_aadhaar" not in without_policy.columns


def test_deterministic_pre_pass_then_policy_rules_on_splink_pairs():
    frame = records()
    assert identity.deterministic_pairs(frame, POLICY) == [
        ("a", "b", identity.AUTO_MATCH, "AADHAAR"), ("c", "d", identity.POSSIBLE_MATCH, "AADHAAR")]
    pairs = identity.apply_policy([
        splink_pair("a", "b", 0.40, "NO_MATCH"),       # Splink missed it; the shared Aadhaar decides
        splink_pair("a", "c", 0.999, "AUTO_MATCH"),    # different Aadhaar: never merge
        splink_pair("e", "f", 0.97, "AUTO_MATCH"),     # different voter IDs: never merge
        splink_pair("g", "h", 0.98, "AUTO_MATCH"),     # no identifiers: below the stricter 0.99
    ], frame, POLICY, BANDS)
    decided = {(p[1], p[2]): (p[5], json.loads(p[6]).get("identity", {}).get("rule")) for p in pairs}
    assert decided == {
        ("a", "b"): ("AUTO_MATCH", "DETERMINISTIC"),
        ("c", "d"): ("POSSIBLE_MATCH", "CONFLICT"),
        ("a", "c"): ("NO_MATCH", "NEVER_MERGE"),
        ("e", "f"): ("NO_MATCH", "NEVER_MERGE"),
        ("g", "h"): ("POSSIBLE_MATCH", "NO_IDENTIFIER_THRESHOLD"),
    }
    clusters = identity.cluster_rows("run", list(frame["canonical_record_id"]), pairs)
    assert {record: cluster for _, cluster, record in clusters}["b"] == "a"
    assert len({cluster for _, cluster, _ in clusters}) == 7

    based = dict(POLICY, noIdentifierAutoMatch=None, baseSource="pds")
    first = identity.apply_policy([splink_pair("c", "f", 0.99, "AUTO_MATCH"), splink_pair("g", "h", 0.98, "AUTO_MATCH")],
                                  records(based), based, BANDS, first_load=True)
    first_decisions = {(p[1], p[2]): p[5] for p in first}
    assert first_decisions[("c", "f")] == "POSSIBLE_MATCH", "a first-load fuzzy match outside the base source"
    assert first_decisions[("g", "h")] == "AUTO_MATCH", "a base-source record is involved"
    assert identity.normalise_policy(None) is None


def test_conflicting_records_never_share_a_cluster_even_through_a_weakly_informed_bridge():
    def pair(a, b, probability, decision, rule=None):
        evidence = json.dumps({"identity": {"rule": rule}} if rule else {})
        return ("run", a, b, probability, 0.0, decision, evidence)

    pairs = [
        pair("x1", "bridge", 0.9989, identity.AUTO_MATCH),
        pair("x2", "bridge", 0.9989, identity.AUTO_MATCH),
        pair("y", "bridge", 0.9989, identity.AUTO_MATCH),   # same score, but y conflicts with both x records
        pair("x1", "y", 0.99, identity.NO_MATCH, "FATHER_DIFFERS"),
        pair("x2", "y", 0.99, identity.NO_MATCH, "FATHER_DIFFERS"),
    ]
    clusters = {record: cluster for _, cluster, record in
                identity.cluster_rows("run", ["x1", "x2", "y", "bridge"], pairs)}
    assert clusters["x1"] == clusters["x2"] == clusters["bridge"]
    assert clusters["y"] == "y"
