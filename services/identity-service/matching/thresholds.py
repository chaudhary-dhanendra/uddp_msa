"""Threshold suggestion from steward labels: ``python -m matching.thresholds --run-id <uuid> --target 0.99``.

Labels are sampled per score band, so they are not a random sample of all pairs. The suggestion therefore estimates the
share of true matches *per probability band* from the labels and applies it to the number of pairs the run actually
has in each band. Read-only.
"""
import argparse
import bisect
import json

# Bands are fine near 1, where most pairs and the precision decision are.
EDGES = [i / 20 for i in range(19)] + [0.92, 0.94, 0.96, 0.97, 0.98, 0.99, 0.995, 0.999, 1.0]
BANDS = len(EDGES) - 1


def _band(probability):
    return min(BANDS - 1, max(0, bisect.bisect_right(EDGES, float(probability)) - 1))


def suggest_thresholds(population, labels, target_precision=0.99, review_recall=0.95):
    """Suggest AUTO_MATCH and review-band thresholds.

    ``population`` is every scored pair's match probability; ``labels`` is (probability, is_same) for labelled pairs
    (UNSURE labels are left out by the caller). Returns the thresholds, the expected review-queue size, the estimated
    number of true matches left below the review band and the precision/recall curve for charting.
    """
    counts = [0] * BANDS
    for p in population:
        counts[_band(p)] += 1
    same = [0] * BANDS
    labelled = [0] * BANDS
    for p, is_same in labels:
        labelled[_band(p)] += 1
        same[_band(p)] += 1 if is_same else 0
    # Smoothed same-person rate per band; an unlabelled band borrows the nearest labelled band above it.
    rate = [None] * BANDS
    for b in range(BANDS):
        if labelled[b]:
            rate[b] = (same[b] + 0.5) / (labelled[b] + 1.0)
    last = None
    for b in range(BANDS - 1, -1, -1):
        if rate[b] is None:
            rate[b] = last if last is not None else (b + 0.5) / BANDS
        else:
            last = rate[b]
    expected_true = [counts[b] * rate[b] for b in range(BANDS)]
    total_true = sum(expected_true) or 1.0
    curve = []
    for cut in range(BANDS + 1):
        pairs = sum(counts[cut:])
        true = sum(expected_true[cut:])
        curve.append({"threshold": EDGES[cut], "pairs": pairs,
                      "precision": (true / pairs) if pairs else 1.0, "recall": true / total_true})
    auto_cut = next((c for c in range(BANDS + 1)
                     if curve[c]["precision"] >= target_precision and curve[c]["pairs"] > 0), BANDS)
    # The review band starts at the highest cut below auto whose recall reaches the goal.
    candidates = [c for c in range(auto_cut + 1) if curve[c]["recall"] >= review_recall]
    review_cut = max(candidates) if candidates else 0
    auto = EDGES[auto_cut]
    review = min(EDGES[review_cut], auto)
    return {
        "targetPrecision": target_precision,
        "autoMatch": auto,
        "possibleMatch": review,
        "expectedAutoMatchPairs": sum(counts[auto_cut:]),
        "expectedReviewQueue": sum(counts[review_cut:auto_cut]),
        "estimatedMissedMatches": round(sum(expected_true[:review_cut]), 1),
        "precisionAtAutoMatch": curve[auto_cut]["precision"],
        "labelsUsed": len(labels),
        "curve": curve,
    }


def run(run_id, target_precision=0.99, review_recall=0.95):
    import psycopg
    from .run import connection_settings
    with psycopg.connect(**connection_settings()) as conn:
        population = [float(r[0]) for r in conn.execute(
            "select match_probability from resident_match_pair where run_id = %s", (run_id,))]
        labels = [(float(p), label == "SAME") for p, label in conn.execute(
            "select match_probability, label from matching_label where label in ('SAME','DIFFERENT')")]
    if not labels:
        raise ValueError("No SAME/DIFFERENT labels yet: label some pairs first")
    return suggest_thresholds(population, labels, target_precision, review_recall)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--run-id", required=True)
    ap.add_argument("--target", type=float, default=0.99)
    ap.add_argument("--recall", type=float, default=0.95)
    a = ap.parse_args()
    print(json.dumps(run(a.run_id, a.target, a.recall)))
