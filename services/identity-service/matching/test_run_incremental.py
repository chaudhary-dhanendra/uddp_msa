"""M11: latest canonical versions only, and incremental matching (ADR-0004), against a disposable PostgreSQL 16."""
import uuid

import psycopg
import pytest

from matching.conftest import load_fixture, seed_canonical_residents
from matching.run import run

pytestmark = pytest.mark.integration

STATE_4_RAM = {"stateCode": "ST4", "sourceSystem": "citizen-register", "resident": {
    "name": "Ram Singh", "dateOfBirth": "1980-03-12", "gender": "MALE", "mobile": "9000000001",
    "address": {"districtLGD": "100001"}}}


def make_golden_members(settings, canonical_ids):
    """One current Golden identity per canonical Resident, as a Golden build would leave them."""
    with psycopg.connect(**settings) as conn:
        run_id = uuid.uuid4()
        conn.execute("insert into matching_run(run_id,model_version,threshold,status) values(%s,'seed',0.95,'COMPLETED')",
                     (run_id,))
        for canonical_id in canonical_ids:
            golden_id = uuid.uuid4()
            conn.execute("insert into golden_resident(golden_resident_id,matching_run_id,survivorship_policy_version,"
                         "member_set_key,golden_payload) values(%s,%s,'seed',%s,'{}')", (golden_id, run_id, canonical_id))
            conn.execute("insert into golden_resident_membership(golden_resident_id,canonical_resident_id,"
                         "source_record_key,added_in_version) values(%s,%s,%s,1)",
                         (golden_id, canonical_id, "canonical:" + canonical_id))


def canonical_snapshot(conn):
    return conn.execute("select canonical_resident_id, canonical_payload::text, mapped_at from canonical_resident"
                        " order by canonical_resident_id").fetchall()


def test_incremental_run_scores_only_pairs_touching_the_new_record(migrated_database):
    residents = load_fixture("canonical/sample-residents.json")
    existing = seed_canonical_residents(migrated_database, residents)
    ram = {existing[i] for i, entry in enumerate(residents) if entry["resident"]["name"] == "Ram Singh"}
    make_golden_members(migrated_database, existing)
    (new_ram,) = seed_canonical_residents(migrated_database, [STATE_4_RAM])
    with psycopg.connect(**migrated_database) as conn:
        before = canonical_snapshot(conn)

    summary = run(incremental=True)

    assert summary["mode"] == "INCREMENTAL"
    assert summary["records_considered"] == 1
    assert summary["candidate_pairs"] == 7
    assert summary["decisions"]["AUTO_MATCH"] == 3
    with psycopg.connect(**migrated_database) as conn:
        mode, considered = conn.execute("select mode, records_considered from matching_run where run_id=%s",
                                        (summary["run_id"],)).fetchone()
        assert (mode, considered) == ("INCREMENTAL", 1)
        pairs = conn.execute("select canonical_record_a::text, canonical_record_b::text, decision from resident_match_pair"
                             " where run_id=%s", (summary["run_id"],)).fetchall()
        assert all(new_ram in (a, b) for a, b, _ in pairs), "every scored pair touches the new record"
        assert {a if b == new_ram else b for a, b, decision in pairs if decision == "AUTO_MATCH"} == ram
        clusters = dict(conn.execute("select canonical_record_id::text, cluster_id from resident_match_cluster_member"
                                     " where run_id=%s", (summary["run_id"],)).fetchall())
        assert set(clusters) == ram | {new_ram}, "only the component that contains the new record"
        assert len(set(clusters.values())) == 1
        assert canonical_snapshot(conn) == before, "matching must never UPDATE canonical rows"


def test_incremental_run_with_nothing_new_is_an_empty_completed_run(migrated_database):
    existing = seed_canonical_residents(migrated_database, load_fixture("canonical/sample-residents.json"))
    make_golden_members(migrated_database, existing)

    summary = run(incremental=True)

    assert summary["records_considered"] == 0
    assert summary["candidate_pairs"] == 0
    with psycopg.connect(**migrated_database) as conn:
        assert conn.execute("select status from matching_run where run_id=%s", (summary["run_id"],)).fetchone()[0] == "COMPLETED"
        assert conn.execute("select count(*) from resident_match_cluster_member where run_id=%s",
                            (summary["run_id"],)).fetchone()[0] == 0


def test_a_superseded_canonical_version_never_takes_part(migrated_database):
    residents = load_fixture("canonical/sample-residents.json")
    ids = seed_canonical_residents(migrated_database, residents + [residents[0]])
    old, new = ids[0], ids[-1]
    with psycopg.connect(**migrated_database) as conn:
        conn.execute("insert into canonical_resident_version(canonical_resident_id,source_record_key,version_number)"
                     " values(%s,'ST1|pds|pds|R1001|Ram Singh',1)", (old,))
        conn.execute("insert into canonical_resident_version(canonical_resident_id,source_record_key,version_number,"
                     "supersedes_canonical_id) values(%s,'ST1|pds|pds|R1001|Ram Singh',2,%s)", (new, old))

    summary = run()

    assert summary["mode"] == "FULL"
    assert summary["records_considered"] == 7
    with psycopg.connect(**migrated_database) as conn:
        involved = conn.execute("select count(*) from resident_match_pair where run_id=%s"
                                " and %s::uuid in (canonical_record_a, canonical_record_b)", (summary["run_id"], old)).fetchone()[0]
        assert involved == 0
        clustered = {row[0] for row in conn.execute("select canonical_record_id::text from resident_match_cluster_member"
                                                    " where run_id=%s", (summary["run_id"],)).fetchall()}
        assert old not in clustered and new in clustered
