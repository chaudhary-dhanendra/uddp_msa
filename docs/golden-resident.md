# Golden Resident and survivorship

Matching determines which canonical Residents are an accepted identity group;
survivorship selects authoritative core values for a separate Golden Resident.
M9 introduces the platform-owned `resident-survivorship-v3` policy in
`platform/survivorship/resident.yaml`. It chooses a per-field `SOURCE_TRUST`
winner only when governance has configured one; otherwise it uses the final,
stable canonical-ID tie-break. The normal demo deliberately does not invent
authority between PDS, Pension and Beneficiaries. `MOST_RECENT` is intentionally
not supported because the model has no trusted business-observation timestamp.

Every Golden field persists the selected canonical ID, the applied decision
(`CONFIGURED_SOURCE_PRECEDENCE` for `SOURCE_TRUST` or
`DETERMINISTIC_CANONICAL_ID_FALLBACK`), policy version, selected value, and all
contributing IDs. The isolated cross-state integration fixture configures
`welfare` ahead of `registry` for mobile and proves the governed winner is used
regardless of canonical-ID ordering. The normal platform policy uses only
`DETERMINISTIC_FALLBACK`.

`resident-survivorship-v2` is built from every State Pack's
`survivorship/resident.yaml` `fieldPriority`: for each core field, each pack
ranks its own sources (first listed = most trusted). It ignores blank values,
selects agreement (`ALL_VALUES_AGREE`), then the single highest-ranked source
for that field (`CONFIGURED_SOURCE_PRECEDENCE`). Unranked sources, or equal
ranks from different packs that disagree, fall back to stable canonical-ID
ordering (`DETERMINISTIC_CANONICAL_ID_FALLBACK`). Each selected core field
stores its winning canonical ID, rule, policy version and contributing IDs.
`resident-survivorship-v1` (no priorities) results remain in place.

Builds require an explicit completed matching run. Cluster members form groups;
unclustered canonicals form singletons. Only the latest canonical version of each
source record takes part. Extensions stay on canonical member records. Golden IDs
are platform UUIDs, not Splink cluster IDs.

Since M11 the Golden ID is a stable identity: every build updates the one current
Golden set, carries IDs forward by source-record overlap, and records versions,
membership history and lifecycle events. A later run or policy version adds
versions to the same IDs instead of a separate result. See
[golden-lifecycle.md](golden-lifecycle.md). Manual review, merge and split are in [stewardship.md](stewardship.md) (M13).
