"""The deliberately configured (not statistically calibrated) PoC model.

Decision thresholds are not code: they come from each State Pack's
matching/<entity>.yaml ``thresholds`` (autoMatch, possibleMatch).
"""
import os
from dataclasses import dataclass
from pathlib import Path

import yaml
import re
from splink import SettingsCreator
from splink.blocking_rule_library import CustomRule
from splink.comparison_library import ExactMatch, JaroWinklerAtThresholds

MODEL_VERSION = "resident-match-v1"
# 1 for records an incremental run must compare (new or changed), 0 for existing ones.
NEW_RECORD_COLUMN = "is_new_record"
STATE_PACK_DIRECTORY_VARIABLE = "UDDP_STATE_PACK_DIRECTORY"
DEFAULT_STATE_PACK_DIRECTORY = Path(__file__).resolve().parent.parent / "services" / "statepack-service" / "seed" / "state-packs"


@dataclass(frozen=True)
class Thresholds:
    auto_match: float
    possible_match: float

    def __post_init__(self):
        if not 0 <= self.possible_match <= self.auto_match <= 1:
            raise ValueError(f"Matching thresholds must satisfy 0 <= possibleMatch <= autoMatch <= 1, got {self}")


def state_pack_directory(environ=os.environ):
    return Path(environ.get(STATE_PACK_DIRECTORY_VARIABLE) or DEFAULT_STATE_PACK_DIRECTORY)


def load_thresholds(directory, entity="Resident"):
    """Read the entity's thresholds from every State Pack; all packs that configure it must agree.

    One matching run compares residents across states, so a single threshold
    band applies.  Disagreeing packs stop the run rather than silently picking one.
    """
    found = {}
    for manifest_path in sorted(Path(directory).glob("*/state.yaml")):
        manifest = yaml.safe_load(manifest_path.read_text(encoding="utf-8"))
        for relative in manifest.get("matching") or []:
            definition = yaml.safe_load((manifest_path.parent / relative).read_text(encoding="utf-8"))
            if definition.get("entity") != entity:
                continue
            configured = definition.get("thresholds") or {}
            if configured.get("autoMatch") is None or configured.get("possibleMatch") is None:
                raise ValueError(f"{manifest['packId']} {relative} must set thresholds.autoMatch and thresholds.possibleMatch")
            found[manifest["packId"]] = Thresholds(float(configured["autoMatch"]), float(configured["possibleMatch"]))
    if not found:
        raise ValueError(f"No State Pack in {directory} configures {entity} matching thresholds")
    distinct = set(found.values())
    if len(distinct) > 1:
        detail = ", ".join(f"{pack}: autoMatch={t.auto_match} possibleMatch={t.possible_match}" for pack, t in found.items())
        raise ValueError(f"State Packs disagree on {entity} matching thresholds ({detail})")
    return distinct.pop()


def load_geography_level(directory, entity="Resident"):
    """Configured canonical geography level, defaulting to the historic district LGD comparison.

    A pack opts in simply by listing ``geography.<level>.lgd`` in its existing matching fields.  All participating
    packs must agree because a run spans packs.
    """
    levels = {}
    for manifest_path in sorted(Path(directory).glob("*/state.yaml")):
        manifest = yaml.safe_load(manifest_path.read_text(encoding="utf-8"))
        for relative in manifest.get("matching") or []:
            definition = yaml.safe_load((manifest_path.parent / relative).read_text(encoding="utf-8"))
            if definition.get("entity") != entity:
                continue
            selected = [re.fullmatch(r"geography\.([A-Za-z][A-Za-z0-9]*)\.lgd", field or "")
                        for field in (definition.get("fields") or [])]
            selected = [match.group(1) for match in selected if match]
            if len(selected) > 1:
                raise ValueError(f"{manifest['packId']} {relative} configures more than one geography level")
            levels[manifest["packId"]] = selected[0] if selected else "district"
    distinct = set(levels.values())
    if len(distinct) > 1:
        raise ValueError("State Packs disagree on Resident geography matching level")
    return distinct.pop() if distinct else "district"


NATIONAL_ID_VARIABLE = "UDDP_MATCH_NATIONAL_ID"


def national_id_comparison_enabled(environ=os.environ):
    """Exact comparison of protected national ID hashes is optional configuration (P1 Part 5b), off by default."""
    return (environ.get(NATIONAL_ID_VARIABLE) or "").strip().lower() in ("1", "true", "yes")


BLOCKING_FILE = Path(__file__).resolve().parent / "resident.yaml"


BLOCKING_THRESHOLD_VARIABLE = "UDDP_MATCHING_BLOCKING_THRESHOLD"  # property uddp.matching.blocking-threshold


def _resident_yaml(path):
    path = Path(path)
    return (yaml.safe_load(path.read_text(encoding="utf-8")) or {}) if path.exists() else {}


def load_blocking(path=BLOCKING_FILE, environ=os.environ):
    """The ``blocking`` section of matching/resident.yaml: ``minRecords`` and the column-group ``rules``.

    Blocking is always on above the threshold; the environment variable overrides ``minRecords`` (default 5,000).
    """
    blocking = _resident_yaml(path).get("blocking")
    override = (environ.get(BLOCKING_THRESHOLD_VARIABLE) or "").strip()
    if override:
        blocking = dict(blocking or {}, minRecords=int(override))
    return blocking


def load_duckdb_settings(path=BLOCKING_FILE):
    """The ``duckdb`` section of matching/resident.yaml: ``memoryLimit`` (e.g. 8GB) and ``threads``; absent = DuckDB defaults."""
    return _resident_yaml(path).get("duckdb") or {}


def resident_match_settings(incremental=False, national_id=None, evidence_columns=(), geography_column="district_lgd",
                            records=None, blocking=None):
    """Return the single centralized model configuration for Residents.

    ``incremental`` keeps the same comparisons and parameters and only restricts which pairs are generated: those
    with at least one new or changed record. ``evidence_columns`` (Identity Policy evidence types, Track E3) add one
    exact comparison per identifier column.

    Seven records are insufficient to estimate reliable parameters, so these are
    explicit MVP assumptions.  They are model parameters, not an application
    replacement scorer.
    """
    from . import v11
    name_m_u = dict(m_probabilities=[0.90, 0.06, 0.03, 0.01], u_probabilities=[0.005, 0.01, 0.03, 0.955])
    use_v11 = records is not None and v11.enabled(records)
    base = v11.comparisons(records, geography_column, name_m_u) if use_v11 else [
        JaroWinklerAtThresholds("name", [0.95, 0.85]).configure(**name_m_u),
        ExactMatch("date_of_birth").configure(m_probabilities=[0.98, 0.02], u_probabilities=[0.003, 0.997]),
        ExactMatch("mobile").configure(m_probabilities=[0.99, 0.01], u_probabilities=[0.001, 0.999]),
        ExactMatch(geography_column).configure(m_probabilities=[0.85, 0.15], u_probabilities=[0.15, 0.85]),
        ExactMatch("gender").configure(m_probabilities=[0.95, 0.05], u_probabilities=[0.50, 0.50]),
    ]
    large_run_rules = v11.blocking_rules(records, blocking, incremental) if records is not None else None
    return SettingsCreator(
        link_type="dedupe_only",
        comparisons=base + ([ExactMatch("national_id").configure(m_probabilities=[0.99, 0.01], u_probabilities=[0.0001, 0.9999])]
             if (national_id_comparison_enabled() if national_id is None else national_id) else [])
        + [ExactMatch(column).configure(m_probabilities=[0.95, 0.05], u_probabilities=[0.001, 0.999])
           for column in evidence_columns],
        # Intentional all-pairs comparison: at most 21 pairs for this PoC.
        blocking_rules_to_generate_predictions=large_run_rules or [
            CustomRule(f"l.{NEW_RECORD_COLUMN} = 1 OR r.{NEW_RECORD_COLUMN} = 1") if incremental else CustomRule("1 = 1")],
        probability_two_random_records_match=0.05,
        unique_id_column_name="canonical_record_id",
        retain_matching_columns=True,
    )
