# ADR-0010: Stewardship decisions are append-only link constraints applied at Golden build time

Status: accepted (M13).

## Context
POSSIBLE_MATCH pairs need human review, and stewards need to merge and split Golden Residents. Matching evidence
and canonical rows must never be updated (architecture rule 3), Golden IDs must stay stable and never be reused
(ADR-0001), and a decision must survive later matching runs, corrections and pack version changes.

## Decision
1. Every decision (accept or reject a review task, merge, split) is stored append-only with its reviewer and reason,
   and expands into `MUST_LINK` / `CANNOT_LINK` constraints on **source record key** pairs.
2. The effective constraint of a pair is the one from its latest decision.
3. `GoldenTargetGroups` applies constraints after forming the automatic groups: separate `CANNOT_LINK` pairs
   (deterministic first-fit in key order, preferring `MUST_LINK` partners), then join `MUST_LINK` pairs in decision
   order unless that would violate a `CANNOT_LINK`.
4. Carry-forward assigns IDs exactly as for automatic changes, so manual merges and splits produce the same
   `MERGED_INTO` / `SPLIT_FROM` history.
5. Review tasks are created by the Golden build for POSSIBLE_MATCH pairs that have none; their status is derived from
   decisions, so tasks are never updated.
6. A decision endpoint rebuilds Golden against the latest completed matching run so its effect is immediate.

## Consequences
Stewardship needs no change to matching or to the matching tables, and every Golden change it causes is in the
existing history. A conflicting set of decisions cannot corrupt a build: the conflicting link is skipped and
counted. Roles and maker-checker approval are deferred to M17.
