import csv
import hashlib
from collections import Counter

from matching.synth import FILES, STATES, generate


def _digest(out):
    h = hashlib.sha256()
    for name in [f"{s}/{FILES[s]}" for s in STATES] + ["ground_truth.csv"]:
        h.update((out / name).read_bytes())
    return h.hexdigest()


def test_deterministic_and_ground_truth_consistent(tmp_path):
    a, b, c = tmp_path / "a", tmp_path / "b", tmp_path / "c"
    stats = generate(10000, 42, a)
    generate(10000, 42, b)
    generate(10000, 43, c)
    assert _digest(a) == _digest(b)
    assert _digest(a) != _digest(c)
    assert stats["rows"] == 10000
    with open(a / "ground_truth.csv", encoding="utf-8") as f:
        truth = list(csv.DictReader(f))
    assert len(truth) == 10000
    for s in STATES:
        with open(a / s / FILES[s], encoding="utf-8") as f:
            n = sum(1 for _ in csv.reader(f)) - 1
        assert n == stats[s] == sum(1 for t in truth if t["state"] == s)
    per_person = Counter(t["true_person_id"] for t in truth)
    assert len(per_person) == stats["persons"]
    assert any(v > 1 for v in per_person.values())
    assert len({t["household_id"] for t in truth}) < len(per_person)
    assert len({(t["state"], t["true_person_id"]) for t in truth}) == len(truth)
