"""Resident v1.1 matching: comparisons over the V101 view columns, large-run blocking, identity guards, evaluation."""
import json

import pandas as pd

from matching import identity, v11
from matching.config import Thresholds, load_blocking
from matching.evaluate import evaluate
from matching.run import AUTO_MATCH, NO_MATCH, POSSIBLE_MATCH, initialise_linker, normalise_records, pair_rows, predict

BANDS = Thresholds(auto_match=0.95, possible_match=0.75)


def resident(sid, name, father=None, year=None, last4=None, village=None, mobile=None, family=None, relations=()):
    payload = {"name": name, "nameNormalised": name.lower(), "mobile": mobile, "address": {},
               "metadata": {"sourceRecordId": sid}, "extensions": {}, "relations": list(relations)}
    if family:
        payload["extensions"]["familyReference"] = family
    view = {"name_normalised": name.lower(), "father_name_normalised": father.lower() if father else None,
            "birth_year": year, "dob_precision": "YEAR" if year else None, "village_lgd": village,
            "aadhaar_last4": last4, "mobile_masked": None}
    return (sid, payload, "ST1", "src", view)


def decide(*rows):
    """Splink + guards over synthetic rows -> {(a, b): (decision, identity rule)}."""
    records = normalise_records(rows)
    predictions = predict(initialise_linker(records))
    pairs = identity.apply_guards(pair_rows("run", predictions, BANDS), records)
    return {(p[1], p[2]): (p[5], json.loads(p[6]).get("identity", {}).get("rule")) for p in pairs}


def test_tc04_different_fathers_never_match():
    result = decide(
        resident("a", "Mohd Ali", father="Ghulam Nabi", year=1980, village="V1"),
        resident("b", "Mohd Ali", father="Abdul Karim", year=1980, village="V1", last4="1234"),
        resident("c", "Sita Devi", father="Hari", year=1990))
    assert result[("a", "b")] == (NO_MATCH, "FATHER_DIFFERS")


def test_tc05_son_and_father_with_same_name_are_related_not_same():
    result = decide(
        resident("a", "Jigmet Angchuk", father="Tsering Angchuk", year=1990, village="V1"),
        resident("b", "Tsering Angchuk", year=1957, village="V1"),
        resident("c", "Sita Devi", year=1970))
    assert result[("a", "b")] == (NO_MATCH, "RELATED_NOT_SAME")


def test_tc01_partial_aadhaar_and_name_auto_match():
    result = decide(
        resident("a", "Tashi Dorje", year=1980, last4="4321"),
        resident("b", "Dorje Tashi", year=1980, last4="4321"),
        resident("c", "Sita Devi", year=1970, last4="9999"))
    assert result[("a", "b")][0] == AUTO_MATCH


def test_tc08_name_father_village_without_identifier_is_only_possible():
    result = decide(
        resident("a", "Tashi Dorje", father="Sonam Dorje", year=1980, village="V1"),
        resident("b", "Tashi Dorje", father="Sonam Dorje", year=1980, village="V1"),
        resident("c", "Sita Devi", year=1970, village="V2"))
    assert result[("a", "b")] == (POSSIBLE_MATCH, "NO_STRONG_ID")


def test_same_household_same_source_is_no_match():
    result = decide(
        resident("a", "Tashi Dorje", year=1980, last4="4321", family="HH1"),
        resident("b", "Tashi Dorje", year=1980, last4="4321", family="HH1"),
        resident("c", "Sita Devi", year=1970))
    assert result[("a", "b")] == (NO_MATCH, "SAME_HOUSEHOLD")


def test_comparisons_token_set_names_and_initial_fathers():
    records = normalise_records([resident("a", "Dorje Tashi", father="S Dorje", year=1980),
                                 resident("b", "Tashi Dorje", father="Sonam Dorje", year=1981)])
    assert v11.tokset("Dorje  Tashi") == v11.tokset("tashi dorje")
    assert v11.initials_key("S DORJE") == v11.initials_key("SONAM DORJE") == "s dorje"
    predictions = predict(initialise_linker(records))
    row = predictions.iloc[0]
    assert row["gamma_name_tokset"] == 3 and row["gamma_father_name"] in (2, 3)
    assert row["gamma_birth_year"] == 1  # within two years (levels: exact 2, within two 1)


def test_masked_mobile_is_not_compared_and_masked_value_is_weak_evidence():
    masked = resident("a", "Tashi Dorje", year=1980, mobile="XXXXXX3210")
    assert normalise_records([masked]).loc[0, "mobile"] is None
    assert normalise_records([resident("b", "X Y", mobile="9876543210")]).loc[0, "mobile"] == "9876543210"
    frame = pd.DataFrame({"name_normalised": ["a", "b"], "mobile_masked": ["XXXXXX3210", "XXXXXX3210"], "mobile": [None, None]})
    assert any(c.create_output_column_name() == "mobile_masked" for c in v11.comparisons(frame, "district_lgd", {}))


def test_v10_records_keep_the_original_model():
    plain = normalise_records([("a", {"name": "Ram Singh", "address": {}}), ("b", {"name": "Ram Singh", "address": {}})])
    assert not v11.enabled(plain)
    assert "gamma_name" in predict(initialise_linker(plain)).columns


def test_blocking_only_for_large_runs_and_configurable():
    records = normalise_records([resident(str(i), "Tashi Dorje", village="V1", year=1980, last4="1") for i in range(4)])
    blocking = load_blocking()
    assert blocking["minRecords"] == 5000 and v11.blocking_rules(records, blocking, False) is None
    rules = v11.blocking_rules(records, dict(blocking, minRecords=2), True)
    assert len(rules) == 1  # village+phonetic; no district or mobile in these rows
    assert "is_new_record" in rules[0].sql_condition


def test_guards_leave_deterministic_pairs_and_v10_data_alone():
    records = normalise_records([resident("a", "Tashi Dorje"), resident("b", "Tashi Dorje")])
    pair = ("run", "a", "b", 0.99, 9.0, AUTO_MATCH, json.dumps({"identity": {"rule": "DETERMINISTIC"}}))
    assert identity.apply_guards([pair], records)[0][5] == AUTO_MATCH
    assert identity.jaro_winkler("martha", "marhta") > 0.96 and identity.jaro_winkler("abc", "xyz") == 0.0


def test_evaluate_precision_recall_and_cases():
    labels = [{"true_person_id": "P1", "source_record_id": "a", "test_case_id": "TC01"},
              {"true_person_id": "P1", "source_record_id": "b", "test_case_id": "TC01"},
              {"true_person_id": "P2", "source_record_id": "c", "test_case_id": "TC04"},
              {"true_person_id": "P3", "source_record_id": "d", "test_case_id": "TC04"}]
    good = evaluate(labels, {"a": "1", "b": "1", "c": "2", "d": "3"})
    assert (good["precision"], good["recall"], good["cases"]) == (1.0, 1.0, {"TC01": "PASS", "TC04": "PASS"})
    bad = evaluate(labels, {"a": "1", "b": "2", "c": "3", "d": "3"})
    assert bad["recall"] == 0.0 and bad["precision"] == 0.0 and bad["cases"] == {"TC01": "FAIL", "TC04": "FAIL"}


def test_blocking_threshold_override_estimate_and_duckdb_settings(tmp_path):
    from matching.config import load_duckdb_settings
    records = normalise_records([resident(str(i), "Tashi Dorje", village="V1", year=1980, last4="1") for i in range(4)])
    blocking = load_blocking(environ={"UDDP_MATCHING_BLOCKING_THRESHOLD": "2"})
    assert blocking["minRecords"] == 2 and blocking["rules"]
    assert load_blocking(environ={})["minRecords"] == 5000
    assert v11.blocking_estimate(records, load_blocking(environ={})) is None
    estimate = v11.blocking_estimate(records, blocking)
    assert estimate["allPairs"] == 6 and estimate["totalPairs"] == 6
    assert load_duckdb_settings()["memoryLimit"] and load_duckdb_settings()["threads"]
    assert load_duckdb_settings(tmp_path / "missing.yaml") == {}
