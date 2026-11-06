"""Per-run matching metrics, stored after every run (Matching Studio monitoring). Reads and inserts metrics only."""
import json

BANDS = 20


def score_histogram(probabilities, bins=10):
    counts = [0] * bins
    for p in probabilities:
        counts[min(bins - 1, int(float(p) * bins))] += 1
    return counts


def acceptance_by_band(conn):
    """{band lower edge: {accepted, rejected}} over the steward decisions on review tasks so far."""
    rows = conn.execute(
        "select least(%s, floor(t.match_probability * %s)::int) band, d.action, count(*) from stewardship_decision d"
        " join stewardship_review_task t on t.review_task_id = d.review_task_id"
        " where d.action in ('ACCEPT_MATCH','REJECT_MATCH') group by 1, 2", (BANDS - 1, BANDS)).fetchall()
    out = {}
    for band, action, count in rows:
        entry = out.setdefault(f"{band / BANDS:.2f}", {"accepted": 0, "rejected": 0})
        entry["accepted" if action == "ACCEPT_MATCH" else "rejected"] += count
    return out


def store_run_metrics(conn, run_id, considered, decisions, probabilities, clusters, new_ids=None):
    """``decisions`` counts per decision, ``clusters`` the (run, cluster, record) rows of the run. An incremental run
    (``new_ids``) counts only its new records as matched, so the match rate stays a share of the records considered."""
    members = {}
    for _, cluster, record in clusters:
        members.setdefault(cluster, set()).add(record)
    counted = None if new_ids is None else {str(i) for i in new_ids}
    matched = sum(len(m) if counted is None else len({str(r) for r in m} & counted)
                  for m in members.values() if len(m) > 1)
    denominator = max(considered, 1)
    conn.execute(
        "insert into matching_run_metrics(run_id, records_considered, auto_pairs, possible_pairs, no_match_pairs,"
        " matched_records, match_rate, review_rate, score_histogram, acceptance_by_band)"
        " values (%s,%s,%s,%s,%s,%s,%s,%s,%s::jsonb,%s::jsonb)",
        (run_id, considered, decisions["AUTO_MATCH"], decisions["POSSIBLE_MATCH"], decisions["NO_MATCH"], matched,
         matched / denominator, decisions["POSSIBLE_MATCH"] / denominator,
         json.dumps(score_histogram(probabilities)), json.dumps(acceptance_by_band(conn))))
