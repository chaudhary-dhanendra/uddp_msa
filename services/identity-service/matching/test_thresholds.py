"""Threshold suggestion meets the target precision on synthetic ground truth (B3)."""
import random

from matching.profile import DEFAULT_CONFIG, add_features, predict_with, train
from matching.synth import generate, load_records
from matching.thresholds import suggest_thresholds


def test_suggested_threshold_meets_target_precision_on_synthetic_labels(tmp_path):
    generate(10000, 7, tmp_path)
    records = load_records(tmp_path)
    frame = add_features(records.drop(columns=["true_person_id"]))
    parameters, _ = train(frame, DEFAULT_CONFIG)
    _, pairs = predict_with(frame, DEFAULT_CONFIG, parameters)
    truth = dict(zip(records["canonical_record_id"], records["true_person_id"]))
    pairs["same"] = pairs["canonical_record_id_l"].map(truth) == pairs["canonical_record_id_r"].map(truth)

    # Stewards label up to 60 pairs from each 0.1-wide score band, not a random sample.
    rng = random.Random(1)
    labels = []
    for band in range(10):
        inside = pairs[(pairs.match_probability >= band / 10) & (pairs.match_probability < (band + 1) / 10 + (band == 9))]
        for _, row in inside.sample(min(60, len(inside)), random_state=rng.randint(0, 10 ** 6)).iterrows():
            labels.append((row.match_probability, bool(row.same)))

    target = 0.98
    suggestion = suggest_thresholds(list(pairs.match_probability), labels, target_precision=target)

    auto = pairs[pairs.match_probability >= suggestion["autoMatch"]]
    assert len(auto) > 0
    assert auto.same.mean() >= target - 0.01       # the real precision, measured on all ground truth
    assert suggestion["possibleMatch"] <= suggestion["autoMatch"]
    assert suggestion["expectedReviewQueue"] >= 0 and suggestion["labelsUsed"] == len(labels)
    queue = pairs[(pairs.match_probability >= suggestion["possibleMatch"]) & (pairs.match_probability < suggestion["autoMatch"])]
    assert suggestion["expectedReviewQueue"] == len(queue)
    missed = int(pairs[pairs.match_probability < suggestion["possibleMatch"]].same.sum())
    assert missed / 3 <= suggestion["estimatedMissedMatches"] <= 3 * missed + 50  # estimated from labels, so only roughly
