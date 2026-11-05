"""Matching profiles: versioned, configurable Splink models (Matching Studio, Track B).

A profile is plain JSON configuration (``DEFAULT_CONFIG`` shows every key). Training estimates m and u
probabilities and stores them as a model version beside the configuration; ``build_settings`` turns a
configuration plus parameters back into Splink settings, so prediction never depends on a pickled model.
Nothing here knows about a state or a department.
"""
import copy
import json
import math
import re
import tempfile
from pathlib import Path

import pandas as pd
import splink.comparison_level_library as cll
import splink.comparison_library as cl
from splink import DuckDBAPI, Linker, SettingsCreator

from .config import NEW_RECORD_COLUMN

PROBABILITY_FLOOR = 0.001
NAME, DOB, EXACT = "NAME", "DOB", "EXACT"
COMPARISONS = (NAME, DOB, EXACT)

DEFAULT_CONFIG = {
    "fields": [
        {"field": "name", "comparison": NAME, "tfAdjust": True},
        {"field": "date_of_birth", "comparison": DOB},
        {"field": "mobile", "comparison": EXACT},
        {"field": "district_lgd", "comparison": EXACT},
        {"field": "gender", "comparison": EXACT},
        {"field": "relation_name", "comparison": NAME},
        {"field": "national_id", "comparison": EXACT},
    ],
    "blocking": [["name_phonetic", "district_lgd"], ["date_of_birth", "gender"], ["mobile"]],
    "priorRules": [["mobile", "date_of_birth"]],
    "thresholds": {"autoMatch": 0.95, "possibleMatch": 0.70},
    "training": {"uMaxPairs": 1_000_000, "seed": 1, "emBlocking": [["name_phonetic"], ["date_of_birth"]]},
}
HONORIFICS = {"shri", "sri", "smt", "mr", "mrs", "ms", "dr", "late", "sh", "kumari", "km"}
ABBREVIATIONS = {"md": "mohammad", "mohd": "mohammad", "muhammad": "mohammad"}
# Spellings that differ only in transliteration collapse to one key.
TRANSLITERATION = [("aa", "a"), ("ee", "i"), ("ii", "i"), ("oo", "u"), ("uu", "u"), ("ph", "f"), ("kh", "k"),
                   ("gh", "g"), ("th", "t"), ("dh", "d"), ("sh", "s"), ("ch", "c"), ("ck", "k"), ("w", "v"),
                   ("z", "j"), ("q", "k"), ("y", "i"), ("x", "ks")]


def _tokens(name):
    words = re.sub(r"[^a-z\s]", " ", (name or "").lower()).split()
    return [ABBREVIATIONS.get(w, w) for w in words if w not in HONORIFICS]


def _translit(token):
    for source, target in TRANSLITERATION:
        token = token.replace(source, target)
    return re.sub(r"(.)\1+", r"\1", token)


def _skeleton(token):
    return token[:1] + re.sub(r"[aeiou]", "", token[1:])


def name_features(name):
    """name_key (transliteration-normalised, order-free), name_phonetic (consonant skeleton), name_init."""
    tokens = sorted(_translit(t) for t in _tokens(name))
    if not tokens:
        return None, None, None
    longest = max(tokens, key=len)
    initials = "".join(sorted(t[0] for t in tokens))
    return " ".join(tokens), " ".join(sorted(_skeleton(t) for t in tokens)), f"{initials}|{_skeleton(longest)}"


def dob_swapped(value):
    """ISO date with day and month exchanged, or None when the swap is not a valid date."""
    m = re.fullmatch(r"(\d{4})-(\d{2})-(\d{2})", value or "")
    if not m or int(m.group(3)) > 12 or int(m.group(2)) > 12:
        return None
    return f"{m.group(1)}-{m.group(3)}-{m.group(2)}"


def add_features(records):
    """Derived comparison columns; the source columns stay so match evidence keeps showing real values."""
    df = records.copy()
    for column in ("relation_name", "national_id", "mobile", "district_lgd", "gender", "date_of_birth"):
        if column not in df:
            df[column] = None
    keys = df["name"].map(name_features)
    df["name_key"], df["name_phonetic"], df["name_init"] = (keys.map(lambda k: k[i]) for i in range(3))
    keys = df["relation_name"].map(name_features)
    df["relation_key"], df["relation_phonetic"], df["relation_init"] = (keys.map(lambda k: k[i]) for i in range(3))
    df["dob_swapped"] = df["date_of_birth"].map(dob_swapped)
    for column in ("mobile", "national_id", "district_lgd", "gender", "date_of_birth"):
        df[column] = df[column].where(df[column].astype(str).str.len() > 0, None)
    return df


def _name_columns(field):
    prefix = "name" if field == "name" else "relation"
    return f"{prefix}_key", f"{prefix}_phonetic", f"{prefix}_init"


def _levels(entry):
    """(label, level creator) pairs, most specific first, for one configured field."""
    field, kind = entry["field"], entry["comparison"]
    if kind == NAME:
        key, phonetic, initial = _name_columns(field)
        tf = bool(entry.get("tfAdjust"))
        exact = cll.ExactMatchLevel(key, term_frequency_adjustments=tf)
        return [
            ("Missing", cll.NullLevel(key)),
            ("Same name (spelling-normalised)", exact),
            ("Same sound, any word order", cll.CustomLevel(f"{phonetic}_l = {phonetic}_r", "Same sound")),
            ("Same surname and first initial", cll.CustomLevel(f"{initial}_l = {initial}_r", "Same initials")),
            ("Very similar spelling", cll.JaroWinklerLevel(key, 0.92)),
            ("Somewhat similar spelling", cll.JaroWinklerLevel(key, 0.85)),
            ("Different", cll.ElseLevel()),
        ]
    if kind == DOB:
        return [
            ("Missing", cll.NullLevel(field)),
            ("Exact date of birth", cll.ExactMatchLevel(field)),
            ("Day and month swapped", cll.CustomLevel("date_of_birth_l = dob_swapped_r OR dob_swapped_l = date_of_birth_r",
                                                       "Day-month swap")),
            ("Same year", cll.CustomLevel("substr(date_of_birth_l,1,4) = substr(date_of_birth_r,1,4)", "Same year")),
            ("Within a year", cll.CustomLevel(
                "abs(cast(substr(date_of_birth_l,1,4) as integer) - cast(substr(date_of_birth_r,1,4) as integer)) <= 1",
                "Within a year")),
            ("Different", cll.ElseLevel()),
        ]
    if kind == EXACT:
        return [("Missing", cll.NullLevel(field)),
                ("Exact match", cll.ExactMatchLevel(field, term_frequency_adjustments=bool(entry.get("tfAdjust")))),
                ("Different", cll.ElseLevel())]
    raise ValueError(f"Unknown comparison {kind}; use one of {COMPARISONS}")


def validate_config(config):
    config = merge_defaults(config)
    names = [f["field"] for f in config["fields"]]
    if len(set(names)) != len(names):
        raise ValueError("A field may appear only once in a matching profile")
    for entry in config["fields"]:
        if entry["comparison"] not in COMPARISONS:
            raise ValueError(f"Unknown comparison {entry['comparison']} for {entry['field']}")
    if not config["blocking"]:
        raise ValueError("A profile needs at least one blocking rule")
    t = config["thresholds"]
    if not 0 <= t["possibleMatch"] <= t["autoMatch"] <= 1:
        raise ValueError("Thresholds must satisfy 0 <= possibleMatch <= autoMatch <= 1")
    return config


def merge_defaults(config):
    merged = copy.deepcopy(DEFAULT_CONFIG)
    merged.update({k: v for k, v in (config or {}).items() if k in DEFAULT_CONFIG})
    return merged


def active_fields(config, records):
    """Configured fields that have data: a field that is empty everywhere cannot be learned and is skipped."""
    out = []
    for entry in config["fields"]:
        column = _name_columns(entry["field"])[0] if entry["comparison"] == NAME else entry["field"]
        if column in records and records[column].notna().any():
            out.append(entry)
    return out


def blocking_sql(columns):
    return " AND ".join(f"l.{c} = r.{c} AND l.{c} IS NOT NULL" for c in columns)


def build_settings(config, records, parameters=None, incremental=False):
    """Splink settings for a profile; with ``parameters`` the m/u probabilities are fixed to the trained values."""
    config = validate_config(config)
    comparisons = []
    for entry in active_fields(config, records):
        learned = ((parameters or {}).get("comparisons") or {}).get(entry["field"], {})
        levels = []
        for label, level in _levels(entry):
            if label in learned and label != "Missing":
                level = level.configure(m_probability=learned[label]["m"], u_probability=learned[label]["u"])
            levels.append(level)
        comparisons.append(cl.CustomComparison(levels, output_column_name=entry["field"],
                                               comparison_description=entry["field"]))
    guard = f" AND (l.{NEW_RECORD_COLUMN} = 1 OR r.{NEW_RECORD_COLUMN} = 1)" if incremental else ""
    rules = [blocking_sql(rule) + guard for rule in config["blocking"]]
    settings = SettingsCreator(
        link_type="dedupe_only",
        comparisons=comparisons,
        blocking_rules_to_generate_predictions=rules,
        probability_two_random_records_match=(parameters or {}).get("probabilityTwoRandomRecordsMatch", 0.01),
        unique_id_column_name="canonical_record_id",
        retain_matching_columns=True,
        retain_intermediate_calculation_columns=True,
        additional_columns_to_retain=["name", "date_of_birth", "mobile", "gender", "district_lgd"],
    )
    return settings


def estimate_pairs(config, records):
    """Candidate pairs each blocking rule would generate (sum over groups of n*(n-1)/2), plus the union bound."""
    df = add_features(records)
    out = []
    for rule in validate_config(config)["blocking"]:
        sizes = df.dropna(subset=list(rule)).groupby(list(rule)).size()
        out.append({"rule": list(rule), "pairs": int((sizes * (sizes - 1) // 2).sum())})
    return {"rules": out, "totalPairs": sum(r["pairs"] for r in out), "records": len(df),
            "allPairs": len(df) * (len(df) - 1) // 2}


def plain_language(field, label, m, u):
    factor = m / u
    word = {"Missing": "is missing"}.get(label, label.lower())
    if factor >= 1:
        return f"{field}: when {word}, a pair is {factor:,.0f} times more likely to be the same person"
    return f"{field}: when {word}, a pair is {1 / factor:,.0f} times less likely to be the same person"


def level_labels(config, records):
    """{field: plain labels of its non-null levels, in model order}."""
    return {e["field"]: [label for label, _ in _levels(e) if label != "Missing"] for e in active_fields(config, records)}


def extract_parameters(model, labels):
    """Parameters and per-level match weights from a saved Splink model (dict)."""
    parameters = {"probabilityTwoRandomRecordsMatch": float(model["probability_two_random_records_match"]),
                  "comparisons": {}}
    weights = []
    for comparison in model["comparisons"]:
        field = comparison["output_column_name"]
        parameters["comparisons"][field] = {}
        levels = [lv for lv in comparison["comparison_levels"] if not lv.get("is_null_level")]
        for label, level in zip(labels[field], levels):
            m, u = level.get("m_probability"), level.get("u_probability")
            if not m or not u:
                continue
            m, u = max(m, PROBABILITY_FLOOR), max(u, PROBABILITY_FLOOR)  # one unseen level must not decide a pair alone
            parameters["comparisons"][field][label] = {"m": float(m), "u": float(u)}
            weights.append({"field": field, "level": label, "m": float(m), "u": float(u),
                            "matchWeight": math.log2(m / u), "plainLanguage": plain_language(field, label, m, u)})
    return parameters, weights


def train(records, config, progress=None):
    """Estimate u by random sampling and m by EM on ``records`` (already feature-enriched). Returns (parameters, weights)."""
    config = validate_config(config)
    training = config["training"]
    linker = Linker(records, build_settings(config, records), DuckDBAPI())
    say = progress or (lambda _message: None)
    prior = [blocking_sql(r) for r in config["priorRules"] if all(c in records and records[c].notna().any() for c in r)]
    say("Estimating the overall match rate")
    linker.training.estimate_probability_two_random_records_match(prior or [blocking_sql(config["blocking"][0])],
                                                                    recall=0.8)
    say("Estimating u probabilities by random sampling")
    linker.training.estimate_u_using_random_sampling(max_pairs=int(training["uMaxPairs"]), seed=int(training["seed"]))
    for rule in training["emBlocking"]:
        if all(c in records and records[c].notna().any() for c in rule):
            say(f"Estimating m probabilities (EM, blocked on {', '.join(rule)})")
            linker.training.estimate_parameters_using_expectation_maximisation(blocking_sql(rule))
    with tempfile.TemporaryDirectory() as folder:
        path = Path(folder) / "model.json"
        linker.misc.save_model_to_json(str(path), overwrite=True)
        model = json.loads(path.read_text(encoding="utf-8"))
    return extract_parameters(model, level_labels(config, records))


def predict_with(records, config, parameters, incremental=False):
    """Pair predictions for ``records`` (feature-enriched) under a trained profile."""
    linker = Linker(records, build_settings(config, records, parameters, incremental), DuckDBAPI())
    return linker, linker.inference.predict().as_pandas_dataframe()


def comparison_weights(row):
    """Per-comparison log2 Bayes factors of one prediction row (with term-frequency adjustment where present)."""
    out = {}
    for column, value in row.items():
        if column.startswith("bf_tf_adj_") and value is not None and not pd.isna(value) and value > 0:
            out.setdefault(column[len("bf_tf_adj_"):], 0.0)
            out[column[len("bf_tf_adj_"):]] += math.log2(value)
        elif column.startswith("bf_") and not column.startswith("bf_tf_adj_") and value is not None \
                and not pd.isna(value) and value > 0:
            out[column[3:]] = out.get(column[3:], 0.0) + math.log2(value)
    return out
