from pathlib import Path

import pandas as pd
import pytest

from matching.config import DEFAULT_STATE_PACK_DIRECTORY, Thresholds, load_thresholds
from matching.run import (AUTO_MATCH, NO_MATCH, POSSIBLE_MATCH, cluster_rows, connection_settings, decision,
                          initialise_linker, normalise_mobile, normalise_name, normalise_records, ordered_pair,
                          pair_rows, predict)

BANDS = Thresholds(auto_match=0.95, possible_match=0.75)
ID_1 = "00000000-0000-0000-0000-000000000001"
ID_2 = "00000000-0000-0000-0000-000000000002"
ID_3 = "00000000-0000-0000-0000-000000000003"


def fixture_records():
    return normalise_records([
        (ID_1, {"name": "Ram  Singh", "dateOfBirth": "1980-03-12", "mobile": "+91 99999 11111", "gender": "MALE", "address": {"districtLGD": "100001"}}),
        (ID_2, {"name": "Ram Singh", "dateOfBirth": "1980-03-12", "mobile": "9999911111", "gender": "MALE", "address": {"districtLGD": "100002"}}),
        (ID_3, {"name": "Sita Devi", "dateOfBirth": "1999-01-01", "mobile": "8888800000", "gender": "FEMALE", "address": {"districtLGD": "999999"}}),
    ])


def write_pack(root, pack_id, auto_match, possible_match):
    pack = root / pack_id
    (pack / "matching").mkdir(parents=True)
    (pack / "state.yaml").write_text(f"packId: {pack_id}\nmatching: [matching/resident.yaml]\n", encoding="utf-8")
    (pack / "matching" / "resident.yaml").write_text(
        f"entity: Resident\nthresholds: {{ autoMatch: {auto_match}, possibleMatch: {possible_match} }}\n", encoding="utf-8")


def test_normalisation():
    assert normalise_name(" Ram   Singh ") == "ram singh"
    assert normalise_mobile("+91 99999-11111") == "919999911111"
    assert len(normalise_records([("id", {"name": " X ", "address": {}})])) == 1


def test_actual_splink_prediction_has_native_evidence_and_ranks_duplicate_higher():
    linker = initialise_linker(fixture_records())
    predictions = predict(linker)
    assert linker is not None
    assert {"match_weight", "match_probability"}.issubset(predictions.columns)
    duplicate = predictions[(predictions.canonical_record_id_l == ID_1) & (predictions.canonical_record_id_r == ID_2)].iloc[0]
    different = predictions[(predictions.canonical_record_id_l == ID_1) & (predictions.canonical_record_id_r == ID_3)].iloc[0]
    assert duplicate.match_probability > different.match_probability


def test_three_decisions_follow_configured_bands():
    assert decision(0.95, BANDS) == AUTO_MATCH
    assert decision(0.9499, BANDS) == POSSIBLE_MATCH
    assert decision(0.80, BANDS) == POSSIBLE_MATCH
    assert decision(0.75, BANDS) == POSSIBLE_MATCH
    assert decision(0.7499, BANDS) == NO_MATCH
    # Different configuration, same code: the bands move with the State Pack values.
    assert decision(0.80, Thresholds(auto_match=0.80, possible_match=0.50)) == AUTO_MATCH


def test_possible_match_is_persisted_but_never_clusters():
    records = fixture_records()
    linker = initialise_linker(records)
    predictions = pd.DataFrame([
        {"canonical_record_id_l": ID_1, "canonical_record_id_r": ID_2, "match_probability": 0.85, "match_weight": 2.0},
        {"canonical_record_id_l": ID_1, "canonical_record_id_r": ID_3, "match_probability": 0.01, "match_weight": -9.0},
        {"canonical_record_id_l": ID_2, "canonical_record_id_r": ID_3, "match_probability": 0.01, "match_weight": -9.0},
    ])
    assert [row[5] for row in pair_rows("run", predictions, BANDS)] == [POSSIBLE_MATCH, NO_MATCH, NO_MATCH]
    clusters = {}
    for _, cluster_id, record_id in cluster_rows("run", linker, records, predictions, BANDS):
        clusters.setdefault(cluster_id, []).append(record_id)
    assert sorted(len(members) for members in clusters.values()) == [1, 1, 1]

    predictions.loc[0, "match_probability"] = 0.99
    assert pair_rows("run", predictions, BANDS)[0][5] == AUTO_MATCH
    linked = {cid for _, cid, rid in cluster_rows("run", linker, records, predictions, BANDS) if rid in (ID_1, ID_2)}
    assert len(linked) == 1


def test_thresholds_are_read_from_the_real_state_packs():
    assert load_thresholds(DEFAULT_STATE_PACK_DIRECTORY) == BANDS


def test_thresholds_come_from_configuration_and_disagreement_fails(tmp_path: Path):
    write_pack(tmp_path, "a", 0.9, 0.6)
    write_pack(tmp_path, "b", 0.9, 0.6)
    assert load_thresholds(tmp_path) == Thresholds(auto_match=0.9, possible_match=0.6)
    write_pack(tmp_path, "c", 0.8, 0.6)
    with pytest.raises(ValueError, match="disagree"):
        load_thresholds(tmp_path)


def test_invalid_threshold_band_is_rejected(tmp_path: Path):
    write_pack(tmp_path, "a", 0.6, 0.9)
    with pytest.raises(ValueError, match="possibleMatch <= autoMatch"):
        load_thresholds(tmp_path)


def test_connection_settings_come_only_from_environment():
    env = {"UDDP_POSTGRES_HOST": "h", "UDDP_POSTGRES_PORT": "1", "UDDP_POSTGRES_DB": "d", "UDDP_POSTGRES_USER": "u", "UDDP_POSTGRES_PASSWORD": "p"}
    assert connection_settings(env) == {"host": "h", "port": "1", "dbname": "d", "user": "u", "password": "p"}
    del env["UDDP_POSTGRES_USER"]
    with pytest.raises(RuntimeError, match="UDDP_POSTGRES_USER"):
        connection_settings(env)


def test_unordered_pair_ordering():
    assert ordered_pair("b", "a") == ("a", "b")


def test_relation_names_come_from_the_relations_list():
    payload = {"name": "Asha Test", "relations": [{"type": "HUSBAND", "name": "Dev Test"},
                                                 {"type": "FATHER", "name": "Ravi Test"}]}
    flat = {"name": "Meena Test", "relationName": "Old Field"}
    frame = normalise_records([(ID_1, payload), (ID_2, flat), (ID_3, {"name": "No Relation"})])
    assert list(frame["relation_name"]) == [normalise_name("Ravi Test"), normalise_name("Old Field"), None]
