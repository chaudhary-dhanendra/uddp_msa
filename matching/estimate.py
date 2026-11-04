"""Estimated candidate pairs per blocking rule: ``python -m matching.estimate --profile-id <uuid>``. Read-only."""
import argparse
import json

import psycopg

from .profile import estimate_pairs
from .run import connection_settings, load_records


def run(profile_id=None, config=None, records=None):
    with psycopg.connect(**connection_settings()) as conn:
        if config is None:
            config = conn.execute("select config from matching_profile where profile_id = %s", (profile_id,)).fetchone()[0]
        return estimate_pairs(config, records if records is not None else load_records(conn))


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--profile-id", required=True)
    print(json.dumps(run(ap.parse_args().profile_id)))
