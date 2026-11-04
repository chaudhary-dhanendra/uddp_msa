"""matching/run.py end to end against a disposable PostgreSQL 16 with the Flyway schema (see conftest.py)."""
import psycopg
import pytest

from matching.conftest import load_fixture, seed_canonical_residents
from matching.run import run

pytestmark = pytest.mark.integration


def canonical_snapshot(conn):
    return conn.execute("select canonical_resident_id, canonical_payload::text, mapped_at from canonical_resident"
                        " order by canonical_resident_id").fetchall()


def test_sample_residents_match_three_ram_singh_records_into_one_cluster(migrated_database):
    residents = load_fixture("canonical/sample-residents.json")
    ids = seed_canonical_residents(migrated_database, residents)
    ram = {ids[i] for i, entry in enumerate(residents) if entry["resident"]["name"] == "Ram Singh"}
    with psycopg.connect(**migrated_database) as conn:
        before = canonical_snapshot(conn)

    summary = run()

    assert summary["records_considered"] == 7
    assert summary["decisions"]["AUTO_MATCH"] == 3
    assert summary["auto_match_threshold"] == 0.95
    assert summary["possible_match_threshold"] == 0.75
    with psycopg.connect(**migrated_database) as conn:
        status, auto_threshold, possible_threshold, considered, completed_at = conn.execute(
            "select status, threshold, possible_match_threshold, records_considered, completed_at"
            " from matching_run where run_id=%s", (summary["run_id"],)).fetchone()
        assert status == "COMPLETED"
        assert (float(auto_threshold), float(possible_threshold), considered) == (0.95, 0.75, 7)
        assert completed_at is not None

        auto = conn.execute("select canonical_record_a::text, canonical_record_b::text from resident_match_pair"
                            " where run_id=%s and decision='AUTO_MATCH'", (summary["run_id"],)).fetchall()
        assert {frozenset(pair) for pair in auto} == {frozenset((a, b)) for a in ram for b in ram if a < b}
        evidence = conn.execute("select evidence from resident_match_pair where run_id=%s limit 1",
                                (summary["run_id"],)).fetchone()[0]
        assert "native_match_probability" in evidence and "native_match_weight" in evidence

        clusters = dict(conn.execute("select canonical_record_id::text, cluster_id from resident_match_cluster_member"
                                     " where run_id=%s", (summary["run_id"],)).fetchall())
        assert set(clusters) == set(ids)
        assert len({clusters[i] for i in ram}) == 1
        assert len(set(clusters.values())) == 5

        assert canonical_snapshot(conn) == before, "matching must never UPDATE canonical rows"


def test_borderline_pair_is_persisted_as_possible_match_and_never_clusters(migrated_database):
    # Same name, DOB and gender; different mobile and district. resident-match-v1 scores this about 0.91,
    # between the configured possibleMatch (0.75) and autoMatch (0.95) thresholds.
    residents = load_fixture("canonical/borderline-residents.json")
    meera_a, meera_b, other = seed_canonical_residents(migrated_database, residents)

    summary = run()

    assert summary["decisions"] == {"AUTO_MATCH": 0, "POSSIBLE_MATCH": 1, "NO_MATCH": 2}
    with psycopg.connect(**migrated_database) as conn:
        a, b = sorted((meera_a, meera_b))
        probability, decision, evidence = conn.execute(
            "select match_probability, decision, evidence from resident_match_pair"
            " where run_id=%s and canonical_record_a=%s and canonical_record_b=%s",
            (summary["run_id"], a, b)).fetchone()
        assert decision == "POSSIBLE_MATCH"
        assert 0.75 <= float(probability) < 0.95
        assert sorted(evidence["mobile"]) == ["9300000001", "9300000002"]

        clusters = dict(conn.execute("select canonical_record_id::text, cluster_id from resident_match_cluster_member"
                                     " where run_id=%s", (summary["run_id"],)).fetchall())
        assert clusters[meera_a] != clusters[meera_b]
        assert len(set(clusters.values())) == 3
        assert other in clusters


def test_each_run_stores_its_monitoring_metrics(migrated_database):
    seed_canonical_residents(migrated_database, load_fixture("canonical/sample-residents.json"))

    summary = run()

    with psycopg.connect(**migrated_database) as conn:
        considered, auto, possible, matched, rate, histogram = conn.execute(
            "select records_considered, auto_pairs, possible_pairs, matched_records, match_rate, score_histogram"
            " from matching_run_metrics where run_id = %s", (summary["run_id"],)).fetchone()
    assert (considered, auto, matched) == (7, 3, 3)
    assert possible == summary["decisions"]["POSSIBLE_MATCH"]
    assert float(rate) == pytest.approx(3 / 7)
    assert sum(histogram) == summary["candidate_pairs"]
