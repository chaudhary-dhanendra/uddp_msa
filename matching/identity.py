"""Identity Policy for matching (Track E3): per-type identifier columns and a deterministic pre-pass before Splink.

The policy is the APPROVED row of ``identity_policy`` (maker-checker in Common Core); without one, matching is exactly
as before. Identifier values are the protected forms Common Core writes into the ``uddp.identifiers`` extension
(keyed hashes or vault references), never clear values. The ladder mirrors ``IdentityPolicyEvaluator``:

1. the same value of a deterministic type is an AUTO_MATCH, unless the dates of birth differ by more than the
   tolerance or two known genders differ, which makes it a POSSIBLE_MATCH (stewardship review);
2. different values of a never-merge type make any pair NO_MATCH;
3. evidence types are extra exact comparisons in the probabilistic model;
5. a pair where neither record has a policy identifier auto-matches only at ``noIdentifierAutoMatch``;
and on a first load with a ``baseSource``, a probabilistic AUTO_MATCH that involves no base-source record is reviewed.
"""
import json
import re
from datetime import date

IDENTIFIERS_EXTENSION = "uddp.identifiers"
LEGACY_EXTENSION = "uddp.nationalIds"
DEFAULT_DOB_TOLERANCE_DAYS = 366
AUTO_MATCH = "AUTO_MATCH"
POSSIBLE_MATCH = "POSSIBLE_MATCH"
NO_MATCH = "NO_MATCH"
DETERMINISTIC_WEIGHT = 30.0


def identifier_column(code):
    return "id_" + code.lower()


def identifier_value(payload, code):
    """The protected value of one identifier type in a canonical payload, or None."""
    extensions = (payload or {}).get("extensions") or {}
    entry = (extensions.get(IDENTIFIERS_EXTENSION) or {}).get(code)
    if isinstance(entry, dict) and entry.get("value"):
        return entry["value"]
    legacy = (extensions.get(LEGACY_EXTENSION) or {}).get(code)
    if isinstance(legacy, dict) and legacy.get("keyedHash"):
        return legacy["keyedHash"]
    return None


def normalise_policy(config):
    """Policy JSON as Common Core stores it, with its defaults filled in."""
    if config is None:
        return None
    if isinstance(config, str):
        config = json.loads(config)
    upper = lambda codes: [c.strip().upper() for c in (codes or []) if c and c.strip()]
    return {
        "deterministicTypes": upper(config.get("deterministicTypes")),
        "neverMergeTypes": upper(config.get("neverMergeTypes")),
        "evidenceTypes": upper(config.get("evidenceTypes")),
        "dobToleranceDays": config.get("dobToleranceDays", DEFAULT_DOB_TOLERANCE_DAYS)
        if config.get("dobToleranceDays") is not None else DEFAULT_DOB_TOLERANCE_DAYS,
        "genderMustAgree": config.get("genderMustAgree") is not False,
        "noIdentifierAutoMatch": config.get("noIdentifierAutoMatch"),
        "baseSource": (config.get("baseSource") or "").strip() or None,
    }


def load_identity_policy(conn):
    """The approved Identity Policy, or None."""
    row = conn.execute("select config from identity_policy where status = 'APPROVED'").fetchone()
    return normalise_policy(row[0]) if row else None


def policy_types(policy):
    """Every person identifier type the policy names, in ladder order, without repeats."""
    out = []
    for key in ("deterministicTypes", "neverMergeTypes", "evidenceTypes"):
        for code in policy[key]:
            if code not in out:
                out.append(code)
    return out


def identifier_columns(policy):
    return [identifier_column(code) for code in policy_types(policy)]


def evidence_columns(policy):
    return [identifier_column(code) for code in policy["evidenceTypes"]]


def is_base_source(policy, state_code, source_system):
    base = policy.get("baseSource")
    if not base:
        return False
    if ":" in base:
        state, source = base.split(":", 1)
        return state == state_code and source == source_system
    return base == source_system


def _present(value):
    """A non-empty string value, else None (pandas fills missing cells with NaN)."""
    return value if isinstance(value, str) and value else None


def _dob(value):
    try:
        return date.fromisoformat(str(value)[:10]) if value else None
    except ValueError:
        return None


def gross_conflict(policy, left, right):
    a, b = _dob(_present(left.get("date_of_birth"))), _dob(_present(right.get("date_of_birth")))
    if a and b and abs((a - b).days) > policy["dobToleranceDays"]:
        return True
    ga, gb = _present(left.get("gender")), _present(right.get("gender"))
    known = lambda g: g not in (None, "UNKNOWN")
    return bool(policy["genderMustAgree"] and known(ga) and known(gb) and ga != gb)


def _never_merge_type(policy, left, right):
    for code in policy["neverMergeTypes"]:
        column = identifier_column(code)
        a, b = _present(left.get(column)), _present(right.get(column))
        if a and b and a != b:
            return code
    return None


def deterministic_pairs(records, policy, new_ids=None):
    """(left id, right id, decision, type) for records sharing a deterministic value; each pairs with the first
    holder (by record id). With ``new_ids`` (incremental), only pairs touching a new or changed record."""
    rows = {r["canonical_record_id"]: r for r in records.to_dict("records")}
    out = []
    seen = set()
    for code in policy["deterministicTypes"]:
        column = identifier_column(code)
        groups = {}
        for record_id in sorted(rows):
            value = _present(rows[record_id].get(column))
            if value:
                groups.setdefault(value, []).append(record_id)
        for members in groups.values():
            first = members[0]
            for other in members[1:]:
                pair = tuple(sorted((first, other)))
                if pair in seen or (new_ids is not None and not (set(pair) & new_ids)):
                    continue
                seen.add(pair)
                left, right = rows[pair[0]], rows[pair[1]]
                blocked = _never_merge_type(policy, left, right)
                if blocked:
                    out.append((pair[0], pair[1], NO_MATCH, blocked))
                elif gross_conflict(policy, left, right):
                    out.append((pair[0], pair[1], POSSIBLE_MATCH, code))
                else:
                    out.append((pair[0], pair[1], AUTO_MATCH, code))
    return out


def apply_policy(pairs, records, policy, thresholds, first_load=False, new_ids=None):
    """The deterministic pre-pass merged with Splink's pairs.

    ``pairs`` are ``pair_rows`` tuples (run, a, b, probability, weight, decision, evidence json). Deterministic pairs
    replace Splink's row for the same pair; never-merge, the stricter no-identifier threshold and the base-source
    rule then adjust Splink's own decisions.
    """
    rows = {r["canonical_record_id"]: r for r in records.to_dict("records")}
    run_id = pairs[0][0] if pairs else None
    by_pair = {(p[1], p[2]): p for p in pairs}
    out = {}
    for a, b, decision, code in deterministic_pairs(records, policy, new_ids):
        existing = by_pair.get((a, b))
        probability = 1.0 if decision == AUTO_MATCH else (existing[3] if existing else thresholds.possible_match)
        if decision == NO_MATCH:
            probability = 0.0
        evidence = json.loads(existing[6]) if existing else {}
        evidence["identity"] = {"rule": {AUTO_MATCH: "DETERMINISTIC", POSSIBLE_MATCH: "CONFLICT",
                                         NO_MATCH: "NEVER_MERGE"}[decision], "type": code}
        weight = DETERMINISTIC_WEIGHT if decision == AUTO_MATCH else (existing[4] if existing else 0.0)
        out[(a, b)] = (run_id, a, b, float(probability), float(weight), decision, json.dumps(evidence))
    columns = identifier_columns(policy)
    for key, p in by_pair.items():
        if key in out:
            continue
        left, right = rows.get(key[0], {}), rows.get(key[1], {})
        decision, evidence = p[5], json.loads(p[6])
        blocked = _never_merge_type(policy, left, right)
        if blocked:
            decision, evidence["identity"] = NO_MATCH, {"rule": "NEVER_MERGE", "type": blocked}
        elif decision == AUTO_MATCH:
            no_ids = (not any(_present(left.get(c)) for c in columns)
                      and not any(_present(right.get(c)) for c in columns))
            stricter = policy.get("noIdentifierAutoMatch")
            if no_ids and stricter is not None and p[3] < float(stricter):
                decision, evidence["identity"] = POSSIBLE_MATCH, {"rule": "NO_IDENTIFIER_THRESHOLD"}
            elif (first_load and policy.get("baseSource")
                  and not is_base_source(policy, _present(left.get("state_code")), _present(left.get("source_system")))
                  and not is_base_source(policy, _present(right.get("state_code")),
                                         _present(right.get("source_system")))):
                decision, evidence["identity"] = POSSIBLE_MATCH, {"rule": "OUTSIDE_BASE_SOURCE"}
        out[key] = (p[0], p[1], p[2], p[3], p[4], decision, json.dumps(evidence))
    return [out[key] for key in sorted(out)]


def jaro_winkler(a, b):
    """Jaro-Winkler similarity (prefix scale 0.1, up to four characters) of two strings."""
    if a == b:
        return 1.0
    if not a or not b:
        return 0.0
    window = max(max(len(a), len(b)) // 2 - 1, 0)
    a_hit, b_hit = [False] * len(a), [False] * len(b)
    matches = 0
    for i, ca in enumerate(a):
        for j in range(max(0, i - window), min(len(b), i + window + 1)):
            if not b_hit[j] and b[j] == ca:
                a_hit[i] = b_hit[j] = True
                matches += 1
                break
    if not matches:
        return 0.0
    ordered_b = [b[j] for j in range(len(b)) if b_hit[j]]
    transpositions = sum(1 for ca, cb in zip((a[i] for i in range(len(a)) if a_hit[i]), ordered_b) if ca != cb) / 2
    jaro = (matches / len(a) + matches / len(b) + (matches - transpositions) / matches) / 3
    prefix = 0
    for ca, cb in zip(a[:4], b[:4]):
        if ca != cb:
            break
        prefix += 1
    return jaro + prefix * 0.1 * (1 - jaro)


FATHER_DIFFERS_BELOW = 0.70
KINSHIP_NAME_AT_LEAST = 0.90
KINSHIP_YEARS = 15


def _tokset(value):
    return " ".join(sorted(re.sub(r"[^a-z0-9\s]", " ", (value or "").lower()).split()))


def _year(value):
    try:
        return None if value is None or value != value else int(value)
    except (TypeError, ValueError):
        return None


def _related_names(record):
    return [n for n in (_present(record.get("father_name_normalised")), _present(record.get("husband_name"))) if n]


def _guard(left, right, strong_columns, no_identifier_below):
    """(decision, identity evidence) the first matching guard imposes on a pair, else None."""
    fa, fb = _present(left.get("father_name_normalised")), _present(right.get("father_name_normalised"))
    if fa and fb:
        similarity = jaro_winkler(_tokset(fa), _tokset(fb))
        if similarity < FATHER_DIFFERS_BELOW:
            return NO_MATCH, {"rule": "FATHER_DIFFERS", "similarity": round(similarity, 3)}
    ya, yb = _year(left.get("birth_year")), _year(right.get("birth_year"))
    if ya is not None and yb is not None and abs(ya - yb) >= KINSHIP_YEARS:
        for parent, child in ((left, right), (right, left)):
            name = _tokset(child.get("name_normalised") or child.get("name"))
            if any(jaro_winkler(_tokset(related), name) >= KINSHIP_NAME_AT_LEAST for related in _related_names(parent)):
                return NO_MATCH, {"rule": "RELATED_NOT_SAME", "guard": "KINSHIP", "yearsApart": abs(ya - yb)}
    family = _present(left.get("family_ref"))
    if (family and family == _present(right.get("family_ref"))
            and _present(left.get("source_record_id")) != _present(right.get("source_record_id"))
            and (_present(left.get("state_code")), _present(left.get("source_system")))
            == (_present(right.get("state_code")), _present(right.get("source_system")))):
        return NO_MATCH, {"rule": "SAME_HOUSEHOLD", "familyReference": family}
    return None


def apply_guards(pairs, records, policy=None):
    """Identity guards on Splink's pairs, after prediction and before clustering (Resident v1.1 data only).

    FATHER_DIFFERS, KINSHIP (rule RELATED_NOT_SAME) and SAME_HOUSEHOLD turn a pair into NO_MATCH; NO_STRONG_ID caps
    an AUTO_MATCH at POSSIBLE_MATCH when neither record has a full or partial identifier (at the policy's
    ``noIdentifierAutoMatch`` when it sets one). Each hit is recorded in ``evidence["identity"]``; a pair the
    deterministic identifier rule decided is left alone.
    """
    rows = {r["canonical_record_id"]: r for r in records.to_dict("records")}
    strong = [c for c in records.columns if c == "aadhaar_last4" or c.startswith("id_")]
    cap = (policy or {}).get("noIdentifierAutoMatch")
    out = []
    for p in pairs:
        evidence = json.loads(p[6])
        if (evidence.get("identity") or {}).get("rule") == "DETERMINISTIC":
            out.append(p)
            continue
        left, right = rows.get(p[1], {}), rows.get(p[2], {})
        decision, found = p[5], _guard(left, right, strong, cap)
        if found:
            decision, evidence["identity"] = found
        elif decision == AUTO_MATCH and not any(_present(r.get(c)) for r in (left, right) for c in strong):
            if cap is None or p[3] < float(cap):
                decision, evidence["identity"] = POSSIBLE_MATCH, {"rule": "NO_STRONG_ID"}
        out.append((p[0], p[1], p[2], p[3], p[4], decision, json.dumps(evidence)))
    return out


def _hard_conflicts(pairs):
    """record -> records it must never share a cluster with: NO_MATCH pairs that an identity rule or guard decided."""
    conflicts = {}
    for p in pairs:
        if p[5] != NO_MATCH:
            continue
        evidence = json.loads(p[6]) if isinstance(p[6], str) else (p[6] or {})
        if (evidence.get("identity") or {}).get("rule"):
            conflicts.setdefault(p[1], set()).add(p[2])
            conflicts.setdefault(p[2], set()).add(p[1])
    return conflicts


def cluster_rows(run_id, record_ids, pairs, new_ids=None):
    """Clusters of AUTO_MATCH pairs; a cluster's ID is its smallest record ID.

    Without hard conflicts these are the connected components. Records an identity rule or guard decided must never
    merge (different fathers, kin, same household) never share a cluster: AUTO_MATCH pairs are applied strongest first
    (ties: the endpoints with the fewest conflicts first) and a pair that would join two clusters holding a conflicting
    pair of records is left unapplied, so a record that only a weakly informed third record links to a conflicting
    group stays apart from it.
    """
    parent = {record_id: record_id for record_id in record_ids}
    members = {record_id: {record_id} for record_id in record_ids}
    conflicts = _hard_conflicts(pairs)

    def find(node):
        while parent[node] != node:
            parent[node] = parent[parent[node]]
            node = parent[node]
        return node

    def degree(record_id):
        return len(conflicts.get(record_id, ()))

    auto = [p for p in pairs if p[5] == AUTO_MATCH and p[1] in parent and p[2] in parent]
    auto.sort(key=lambda p: (-round(float(p[3]), 4), degree(p[1]) + degree(p[2]), p[1], p[2]))
    for p in auto:
        ra, rb = find(p[1]), find(p[2])
        if ra == rb:
            continue
        if any(other in conflicts.get(one, ()) for one in members[ra] for other in members[rb]):
            continue
        root, child = min(ra, rb), max(ra, rb)
        parent[child] = root
        members[root] |= members.pop(child)
    rows = [(run_id, find(record_id), record_id) for record_id in sorted(record_ids)]
    if new_ids is None:
        return rows
    touched = {cluster for _, cluster, record_id in rows if record_id in new_ids}
    return [row for row in rows if row[1] in touched]
