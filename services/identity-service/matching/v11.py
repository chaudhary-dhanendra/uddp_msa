"""Resident canonical v1.1 matching: features read from the ``canonical_resident_match_v`` view (V101), the extra
Splink comparisons and the large-run blocking rules.

All of it switches on only when the canonical data carries v1.1 values (``name_normalised``); a run over v1.0
payloads keeps the original model and all-pairs comparison unchanged.
"""
import re

import splink.comparison_level_library as cll
import splink.comparison_library as cl
from splink.blocking_rule_library import CustomRule

from .config import NEW_RECORD_COLUMN

try:  # jellyfish ships no double metaphone; plain metaphone is the phonetic key, with a consonant skeleton fallback
    import jellyfish
except ImportError:  # pragma: no cover
    jellyfish = None

VIEW = "canonical_resident_match_v"
VIEW_COLUMNS = ("name_normalised", "father_name_normalised", "birth_year", "dob_precision", "village_lgd",
                "aadhaar_last4", "mobile_masked")
MASK_CHARACTERS = re.compile(r"[xX*•]")


def tokens(name):
    return re.sub(r"[^a-z0-9\s]", " ", (name or "").lower()).split()


def tokset(name):
    """Words sorted, so word order never matters; None when there is nothing to compare."""
    return " ".join(sorted(tokens(name))) or None


def initials_key(name):
    """Every word but the last reduced to its initial: 'S DORJE' and 'SONAM DORJE' both give 's dorje'."""
    words = tokens(name)
    if len(words) < 2:
        return None
    return " ".join([w[0] for w in words[:-1]] + [words[-1]])


def phonetic(token):
    if not token:
        return None
    if jellyfish is not None:
        return jellyfish.metaphone(token) or None
    return token[:1] + re.sub(r"[aeiou]", "", token[1:])


def is_masked(value):
    return bool(value) and bool(MASK_CHARACTERS.search(value))


def first_text(value):
    return value if isinstance(value, str) and value.strip() else None


def family_reference(payload):
    entry = ((payload.get("extensions") or {}).get("familyReference"))
    if isinstance(entry, dict):
        entry = entry.get("value")
    return first_text(str(entry)) if entry is not None else None


def relation_name(payload, relation_type):
    for relation in payload.get("relations") or []:
        if relation.get("type") == relation_type and first_text(relation.get("nameNormalised")):
            return relation["nameNormalised"]
    return None


def any_relation_name(payload):
    """The parentage or spouse name of a canonical record (``relations[]``), the father's first."""
    relations = [r for r in payload.get("relations") or [] if isinstance(r, dict) and first_text(r.get("name"))]
    relations.sort(key=lambda r: r.get("type") != "FATHER")
    return relations[0]["name"] if relations else None


def record_features(payload, view, plain_name):
    """Columns one record adds in v1.1 mode: the view's values plus the derived comparison keys."""
    view = view or {}
    name = view.get("name_normalised")
    father = view.get("father_name_normalised")
    first = tokens(name or plain_name)
    dob_precision = view.get("dob_precision")
    metadata = payload.get("metadata") or {}
    return {
        "name_normalised": name,
        "father_name_normalised": father,
        "husband_name_normalised": relation_name(payload, "HUSBAND"),
        "birth_year": view.get("birth_year"),
        "dob_precision": dob_precision,
        "village_lgd": view.get("village_lgd"),
        "aadhaar_last4": view.get("aadhaar_last4"),
        "mobile_masked": view.get("mobile_masked"),
        "name_tokset": tokset(name or plain_name),
        "father_tokset": tokset(father),
        "father_init": initials_key(father),
        "first_name_phonetic": phonetic(first[0]) if first else None,
        "dob_day": payload.get("dateOfBirth") if dob_precision in (None, "DAY") else None,
        "family_ref": family_reference(payload),
        "source_record_id": first_text(metadata.get("sourceRecordId")),
    }


def enabled(records):
    return "name_normalised" in records and records["name_normalised"].notna().any()


def has(records, column):
    return column in records and records[column].notna().any()


def comparisons(records, geography_column, name_m_u):
    """The v1.1 comparison list, in place of the v1.0 name / date_of_birth / mobile ones; absent data adds none."""
    out = []

    def add(column, comparison):
        if has(records, column):
            out.append(comparison)

    add("name_tokset", cl.JaroWinklerAtThresholds("name_tokset", [0.95, 0.85]).configure(**name_m_u))
    add("dob_day", cl.ExactMatch("dob_day").configure(m_probabilities=[0.98, 0.02], u_probabilities=[0.003, 0.997]))
    add("mobile", cl.ExactMatch("mobile").configure(m_probabilities=[0.99, 0.01], u_probabilities=[0.001, 0.999]))
    add(geography_column, cl.ExactMatch(geography_column).configure(
        m_probabilities=[0.85, 0.15], u_probabilities=[0.15, 0.85]))
    add("gender", cl.ExactMatch("gender").configure(m_probabilities=[0.95, 0.05], u_probabilities=[0.50, 0.50]))
    if has(records, "father_tokset"):
        out.append(cl.CustomComparison([
            cll.NullLevel("father_tokset"),
            cll.JaroWinklerLevel("father_tokset", 0.92).configure(m_probability=0.70, u_probability=0.005),
            cll.CustomLevel("father_init_l IS NOT NULL AND father_init_l = father_init_r", "Initials")
            .configure(m_probability=0.15, u_probability=0.002),
            cll.JaroWinklerLevel("father_tokset", 0.80).configure(m_probability=0.10, u_probability=0.03),
            cll.ElseLevel().configure(m_probability=0.05, u_probability=0.963),
        ], output_column_name="father_name", comparison_description="father_name"))
    if has(records, "birth_year"):
        out.append(cl.CustomComparison([
            cll.NullLevel("birth_year"),
            cll.ExactMatchLevel("birth_year").configure(m_probability=0.70, u_probability=0.02),
            cll.CustomLevel("abs(birth_year_l - birth_year_r) <= 2", "Within two years")
            .configure(m_probability=0.25, u_probability=0.06),
            cll.ElseLevel().configure(m_probability=0.05, u_probability=0.92),
        ], output_column_name="birth_year", comparison_description="birth_year"))
    add("aadhaar_last4", cl.ExactMatch("aadhaar_last4").configure(
        m_probabilities=[0.9, 0.1], u_probabilities=[0.001, 0.999]))
    add("village_lgd", cl.ExactMatch("village_lgd").configure(
        m_probabilities=[0.85, 0.15], u_probabilities=[0.05, 0.95]))
    add("mobile_masked", cl.ExactMatch("mobile_masked").configure(
        m_probabilities=[0.9, 0.1], u_probabilities=[0.01, 0.99]))
    return out


def blocking_rules(records, blocking, incremental):
    """Large-run blocking rules, or None when the run is small (all pairs) or no rule has its columns."""
    if not blocking or len(records) <= int(blocking.get("minRecords", 5000)):
        return None
    return _rules(records, blocking, incremental)


def _rules(records, blocking, incremental):
    guard = f" AND (l.{NEW_RECORD_COLUMN} = 1 OR r.{NEW_RECORD_COLUMN} = 1)" if incremental else ""
    rules = []
    for columns in blocking.get("rules") or []:
        if all(has(records, c) for c in columns):
            rules.append(CustomRule(" AND ".join(f"l.{c} IS NOT NULL AND l.{c} = r.{c} AND l.{c} <> ''"
                                                 if _is_text(records, c) else f"l.{c} IS NOT NULL AND l.{c} = r.{c}"
                                                 for c in columns) + guard))
    return rules or None


def _is_text(records, column):
    return records[column].map(lambda v: isinstance(v, str)).any()


def blocking_estimate(records, blocking):
    """Candidate pairs per applicable blocking rule (sum over groups of n*(n-1)/2) against all pairs, or None when off."""
    if not blocking or len(records) <= int(blocking.get("minRecords", 5000)):
        return None
    out = []
    for columns in blocking.get("rules") or []:
        if all(has(records, c) for c in columns):
            sizes = records.dropna(subset=list(columns)).groupby(list(columns)).size()
            out.append({"rule": list(columns), "pairs": int((sizes * (sizes - 1) // 2).sum())})
    return {"records": len(records), "allPairs": len(records) * (len(records) - 1) // 2, "rules": out,
            "totalPairs": sum(r["pairs"] for r in out)}
