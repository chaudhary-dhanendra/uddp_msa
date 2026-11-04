# ADR-0002: `golden_resident` is the stable identity; its V6 columns are the founding snapshot

Status: accepted (M11).

## Context
V6 `golden_resident` holds one row per `(matching run, policy, member set)` under a unique constraint, and
`golden_resident_member` / `golden_resident_field_provenance` reference it. Clients already hold its IDs.

## Decision
Keep `golden_resident` as the identity table (`golden_resident_id` is the Golden ID) and add `status`,
`retired_at`, `merged_into_id` and `split_from_id` in V10. Its V6 columns, `golden_resident_member` and
`golden_resident_field_provenance` keep describing the identity's founding build (version 1) and are written only
when an identity is created. Current and historical values live in `golden_resident_version`,
`golden_resident_version_provenance` and `golden_resident_membership`. V10 drops the per-run unique constraint,
because carry-forward, not `(run, policy, member set)`, is now the idempotency rule.

## Consequences
Existing IDs and foreign keys stay valid and no V1-V9 file changes. Readers of current values must use the version
tables, as the API does.
