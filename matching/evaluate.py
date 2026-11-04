"""Score a matching run against known truth: ``python -m matching.evaluate --labels labels.csv --run-id <uuid>``.

The labels CSV has ``true_person_id`` and ``source_record_id`` columns and optionally ``test_case_id``. Records are
judged by the run's clusters (what a Golden build would merge): pairwise precision and recall over all labelled
records, and, per test case, pass when its own records merge exactly as the labels say. Read-only.
"""
import argparse
import csv
import json
from itertools import combinations

import psycopg

from .run import connection_settings


def read_labels(path):
    with open(path, newline="", encoding="utf-8") as handle:
        return list(csv.DictReader(handle))


def _pairs(groups):
    return {frozenset(pair) for members in groups.values() for pair in combinations(sorted(members), 2)}


def _group(records, key):
    groups = {}
    for record in records:
        groups.setdefault(key(record), []).append(record)
    return groups


def _scores(records, truth, cluster_of):
    actual = _pairs(_group(records, lambda r: truth[r]))
    found = _pairs(_group(records, lambda r: cluster_of[r]))
    hits = len(actual & found)
    return (hits / len(found) if found else 1.0), (hits / len(actual) if actual else 1.0)


def evaluate(labels, cluster_of):
    """{precision, recall, cases: {id: PASS|FAIL}}; ``cluster_of`` maps source_record_id to its cluster.

    A labelled record the run did not see counts as its own cluster (a missed match, never a false one)."""
    truth = {row["source_record_id"]: row["true_person_id"] for row in labels}
    cluster_of = {r: cluster_of.get(r, "unseen:" + r) for r in truth}
    precision, recall = _scores(sorted(truth), truth, cluster_of)
    cases = {}
    for case, rows in _group([r for r in labels if r.get("test_case_id")], lambda r: r["test_case_id"]).items():
        p, r = _scores(sorted(row["source_record_id"] for row in rows), truth, cluster_of)
        cases[case] = "PASS" if p == 1.0 and r == 1.0 else "FAIL"
    return {"precision": round(precision, 4), "recall": round(recall, 4), "cases": cases}


def load_clusters(conn, run_id):
    rows = conn.execute(
        "select c.canonical_payload #>> '{metadata,sourceRecordId}', m.cluster_id from resident_match_cluster_member m"
        " join canonical_resident c on c.canonical_resident_id = m.canonical_record_id"
        " where m.run_id = %s", (run_id,)).fetchall()
    return {source_record_id: str(cluster) for source_record_id, cluster in rows if source_record_id}


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--labels", required=True)
    ap.add_argument("--run-id", required=True)
    args = ap.parse_args(argv)
    with psycopg.connect(**connection_settings(), autocommit=True, options="-c default_transaction_read_only=on") as conn:
        result = evaluate(read_labels(args.labels), load_clusters(conn, args.run_id))
    print(f"precision {result['precision']}  recall {result['recall']}")
    for case, outcome in sorted(result["cases"].items()):
        print(f"{case}: {outcome}")
    print(json.dumps(result))


if __name__ == "__main__":
    main()
