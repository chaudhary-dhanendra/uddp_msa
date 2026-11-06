from .metrics import store_run_metrics


class FakeConnection:
    def __init__(self):
        self.inserted = None

    def execute(self, sql, params=()):
        if sql.startswith("insert"):
            self.inserted = params
        return self

    def fetchall(self):
        return []


DECISIONS = {"AUTO_MATCH": 2, "POSSIBLE_MATCH": 0, "NO_MATCH": 0}
# Cluster c1: two existing records and one new; cluster c2: one existing record alone.
CLUSTERS = [("r", "c1", "old-1"), ("r", "c1", "old-2"), ("r", "c1", "new-1"), ("r", "c2", "old-3")]


def test_full_run_counts_every_clustered_record():
    conn = FakeConnection()
    store_run_metrics(conn, "r", 4, DECISIONS, [0.99, 0.98], CLUSTERS)
    assert conn.inserted[5:7] == (3, 3 / 4)


def test_incremental_run_counts_only_its_new_records():
    conn = FakeConnection()
    store_run_metrics(conn, "r", 1, DECISIONS, [0.99, 0.98], CLUSTERS, new_ids=["new-1"])
    assert conn.inserted[5:7] == (1, 1.0)
