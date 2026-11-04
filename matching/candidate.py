"""Dry-run scoring of a trained profile: ``python -m matching.candidate --profile-id <uuid>``.

Prints the pairs the candidate profile scores at or above its review threshold as JSON. Writes nothing: the connection
is read-only, and the caller (impact preview) compares the result with the stored run and the stewardship constraints.
"""
import argparse
import json

import psycopg

from .profile import add_features, predict_with
from .run import connection_settings, load_records, ordered_pair


def run(profile_id):
    with psycopg.connect(**connection_settings(), autocommit=True, options="-c default_transaction_read_only=on") as conn:
        config, parameters = conn.execute("select config, parameters from matching_profile where profile_id = %s",
                                          (profile_id,)).fetchone()
        if parameters is None:
            raise ValueError("The candidate profile is not trained yet")
        records = add_features(load_records(conn))
    thresholds = config.get("thresholds") or {}
    floor = float(thresholds.get("possibleMatch", 0.7))
    if len(records) < 2:
        predictions = []
    else:
        _, predictions = predict_with(records, config, parameters)
        predictions = predictions[predictions["match_probability"] >= floor]
    pairs = []
    for p in (predictions.to_dict("records") if len(predictions) else []):
        a, b = ordered_pair(p["canonical_record_id_l"], p["canonical_record_id_r"])
        pairs.append([a, b, float(p["match_probability"])])
    return {"autoMatch": float(thresholds.get("autoMatch", 0.95)), "possibleMatch": floor,
            "records": len(records), "pairs": pairs}


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--profile-id", required=True)
    print(json.dumps(run(ap.parse_args().profile_id)))
