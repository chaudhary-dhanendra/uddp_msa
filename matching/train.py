"""Train a matching profile: ``python -m matching.train --profile-id <uuid> --job-id <uuid>``.

Reads the profile configuration and the latest canonical Residents from the UDDP_POSTGRES_* database, estimates u
(random sampling) and m (EM) with Splink and stores the parameters and per-field match weights on the profile as
a model version. Canonical data and matching evidence are never changed.
"""
import argparse
import json
import sys

import psycopg
from psycopg.types.json import Jsonb

from .profile import add_features, train
from .run import connection_settings, load_records


def store_training(conn, profile_id, parameters, weights, record_count):
    conn.execute("update matching_profile set parameters = %s, weights = %s, training_records = %s, trained_at = now()"
                 " where profile_id = %s", (Jsonb(parameters), Jsonb(weights), record_count, profile_id))


def set_job(conn, job_id, status, message=None):
    if job_id:
        conn.execute("update matching_training_job set status = %s, message = %s,"
                     " finished_at = case when %s in ('COMPLETED','FAILED') then now() else finished_at end"
                     " where job_id = %s", (status, message, status, job_id))


def run(profile_id, job_id=None, records=None):
    with psycopg.connect(**connection_settings(), autocommit=True) as conn:
        try:
            set_job(conn, job_id, "RUNNING", "Loading canonical residents")
            config = conn.execute("select config from matching_profile where profile_id = %s", (profile_id,)).fetchone()[0]
            frame = add_features(records if records is not None else load_records(conn))
            if len(frame) < 50:
                raise ValueError(f"Training needs at least 50 canonical residents, found {len(frame)}")
            parameters, weights = train(frame, config, lambda m: set_job(conn, job_id, "RUNNING", m))
            store_training(conn, profile_id, parameters, weights, len(frame))
            set_job(conn, job_id, "COMPLETED", f"Trained on {len(frame)} records; {len(weights)} level weights stored")
            return {"profile_id": profile_id, "records": len(frame), "weights": len(weights)}
        except Exception as error:  # reported on the job, then re-raised for the exit code
            set_job(conn, job_id, "FAILED", str(error)[:500])
            raise


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--profile-id", required=True)
    ap.add_argument("--job-id")
    a = ap.parse_args()
    print(json.dumps(run(a.profile_id, a.job_id)))
    sys.exit(0)
