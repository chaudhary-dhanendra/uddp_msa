# ADR-0001: Golden ID carry-forward, merge and split

Status: accepted (M11). The rules were decided by the product owner; the tie-break details were chosen in M11.

## Context
Each M6-M10 build created new Golden IDs, so a person's ID changed on every rebuild.

## Decision
On every build, each target group inherits the Golden ID of the active identity with the largest overlap of
source record keys. Pairs are accepted greedily by overlap (descending), then the oldest identity (`created_at`,
then UUID), then the group holding that identity's longest-standing member (earliest membership `effective_from`,
then canonical UUID); one identity per group and one group per identity.

- A losing identity is `RETIRED` with `merged_into_id` = the identity now holding most of its source records
  (ties: oldest). It is never reused or deleted.
- A group that gets no ID but overlaps an identity is a split side: a new UUID with `split_from_id` = the identity
  it overlaps most (ties: oldest).
- A survivorship policy change gives a new version with the same ID. A matching model change is a new matching run
  re-evaluated by this rule.
- Overlap counts source record keys, not canonical IDs, so a corrected record (a new canonical version) still
  counts as the same member.

## Consequences
Rebuilding an equivalent run is idempotent. The rule is deterministic, generic and implemented once
(`golden/CarryForward`).
