"""Matching profile training on 10k synthetic residents (B2)."""
import json
import uuid

import psycopg
import pytest
from psycopg.types.json import Jsonb

from matching.profile import DEFAULT_CONFIG, add_features, predict_with, train, validate_config
from matching.run import load_approved_profile
from matching.synth import generate, load_records
from matching.train import run as train_run


@pytest.fixture(scope="module")
def synthetic(tmp_path_factory):
    out = tmp_path_factory.mktemp("synth")
    generate(10000, 42, out)
    return load_records(out)


@pytest.fixture(scope="module")
def trained(synthetic):
    frame = add_features(synthetic.drop(columns=["true_person_id"]))
    parameters, weights = train(frame, DEFAULT_CONFIG)
    return frame, parameters, weights


def test_training_completes_and_stores_weights_per_field(trained):
    _, parameters, weights = trained
    fields = {w["field"] for w in weights}
    assert {"name", "date_of_birth", "mobile", "district_lgd", "gender"} <= fields
    assert "relation_name" not in fields  # no data, so the field is skipped rather than guessed
    exact = next(w for w in weights if w["field"] == "date_of_birth" and w["level"] == "Exact date of birth")
    different = next(w for w in weights if w["field"] == "date_of_birth" and w["level"] == "Different")
    assert exact["matchWeight"] > 5 > 0 > different["matchWeight"]
    assert all(w["plainLanguage"] for w in weights)
    assert 0 < parameters["probabilityTwoRandomRecordsMatch"] < 0.01


def test_trained_profile_separates_true_pairs(synthetic, trained):
    frame, parameters, _ = trained
    _, predictions = predict_with(frame, DEFAULT_CONFIG, parameters)
    truth = dict(zip(synthetic["canonical_record_id"], synthetic["true_person_id"]))
    same = predictions["canonical_record_id_l"].map(truth) == predictions["canonical_record_id_r"].map(truth)
    assert same.sum() > 1000
    assert predictions.loc[same, "match_probability"].median() > 0.9
    assert predictions.loc[~same, "match_probability"].median() < 0.1


def test_profile_validation_rejects_bad_configuration():
    bad = {"thresholds": {"autoMatch": 0.5, "possibleMatch": 0.9}}
    with pytest.raises(ValueError):
        validate_config(bad)
    with pytest.raises(ValueError):
        validate_config({"fields": [{"field": "name", "comparison": "SOUNDS_NICE"}]})


@pytest.mark.integration
def test_training_job_stores_parameters_and_approved_profile_is_found(migrated_database, synthetic):
    profile_id, job_id = uuid.uuid4(), uuid.uuid4()
    with psycopg.connect(**migrated_database, autocommit=True) as conn:
        conn.execute("insert into matching_profile(profile_id,name,version,status,config,created_by)"
                     " values(%s,'resident',1,'DRAFT',%s,'tester')", (profile_id, Jsonb(DEFAULT_CONFIG)))
        conn.execute("insert into matching_training_job(job_id,profile_id,status,requested_by)"
                     " values(%s,%s,'QUEUED','tester')", (job_id, profile_id))
        assert load_approved_profile(conn) is None  # resident-match-v1 stays the default

    result = train_run(str(profile_id), str(job_id), records=synthetic.drop(columns=["true_person_id"]))

    with psycopg.connect(**migrated_database, autocommit=True) as conn:
        parameters, weights, status = conn.execute(
            "select p.parameters, p.weights, j.status from matching_profile p join matching_training_job j"
            " on j.profile_id = p.profile_id where p.profile_id = %s", (profile_id,)).fetchone()
        assert status == "COMPLETED" and result["weights"] == len(weights) > 10
        assert "name" in parameters["comparisons"]
        conn.execute("update matching_profile set status = 'APPROVED' where profile_id = %s", (profile_id,))
        approved = load_approved_profile(conn)
        assert approved["profile_id"] == profile_id and approved["model_version"] == "resident-v1"
        with pytest.raises(psycopg.errors.UniqueViolation):  # only one APPROVED profile at a time
            conn.execute("insert into matching_profile(profile_id,name,version,status,config,created_by)"
                         " values(%s,'resident',2,'APPROVED',%s,'tester')", (uuid.uuid4(), json.dumps(DEFAULT_CONFIG)))
