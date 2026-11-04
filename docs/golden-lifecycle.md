# Golden Resident lifecycle and incremental processing (M11)

Up to M10 every Golden build wrote a new, separate set of Golden Residents for one `(matching run, policy)`, so a
person's Golden ID changed on every rebuild. M11 makes the Golden ID a **stable platform identity**: a build now
updates the one current Golden set, carrying IDs forward, versioning values and recording every change as an
event. Canonical records gain **versions** so a corrected source record replaces, rather than duplicates, its
earlier version. Everything here is generic: no rule depends on a state, source or pack.

Decisions are recorded as ADRs in `docs/decisions/` (0001 to 0006).

## 1. Data model

```text
canonical_resident (V3, unchanged, append-only)
  └─ canonical_resident_version      one row per canonical: source_record_key, version_number, supersedes
       └─ canonical_resident_current (view) latest version per source record key: the only rows that participate

golden_resident (V6 identity table; V10 adds status, retired_at, merged_into_id, split_from_id)
  ├─ golden_resident_version          version_number, payload, policy, matching run, build, effective_from/to
  │    └─ golden_resident_version_provenance   per-field winner, rule, policy, contributors (per version)
  ├─ golden_resident_membership       canonical member + source_record_key, effective_from/to, added/removed version
  └─ golden_resident_event            CREATED, MEMBER_ADDED, MEMBER_REMOVED, MEMBER_VERSION_CHANGED,
                                      VALUE_CHANGED, MERGED, SPLIT, RETIRED
golden_build                          one row per build: matching run, policy, mode, counts
```

- **Stable identity** (`golden_resident`): `golden_resident_id` is the platform Golden ID, never a Splink
  cluster ID, never reused and never deleted. `status` is `ACTIVE` or `RETIRED`. A retired ID keeps
  `merged_into_id`; an ID created by a split keeps `split_from_id`. The V6 columns (`matching_run_id`,
  `survivorship_policy_version`, `member_set_key`, `golden_payload`) and the V6 tables `golden_resident_member`
  and `golden_resident_field_provenance` now describe the identity's **founding** build (its version 1) and are
  never rewritten; current values live in versions (ADR-0002).
- **Versions** (`golden_resident_version`): version 1 at creation, then a new version whenever the members, the
  survived values, their provenance or the survivorship policy change. Exactly one version per identity has
  `effective_to IS NULL` (the current one); a retired identity has none. `change_reasons` lists why the version
  exists (`CREATED`, `MEMBERSHIP_CHANGED`, `MEMBER_VERSION_CHANGED`, `VALUES_CHANGED`, `POLICY_CHANGED`,
  `MERGED`, `SPLIT`). Provenance is stored per version, so each value stays traceable to the canonical record,
  rule and policy that chose it at that time.
- **Membership** (`golden_resident_membership`): which canonical record (and source record key) belongs to which
  identity, from when to when. A source record key is a current member of at most one identity (unique partial
  index).
- **Events** (`golden_resident_event`): an append-only history per identity, with the build, the version number,
  the related identity (merge/split partner), the member or field concerned and old/new values.
- **Canonical versions** (`canonical_resident_version`): the canonical row itself is never updated. A new
  version row names the canonical it supersedes; the latest version of a source record is the one nothing
  supersedes. Only latest versions participate in matching and survivorship.

The only updates M11 makes to history rows are the one-time closing of `effective_to` on a version or membership
and the one-time retirement of an identity (`status`, `retired_at`, `merged_into_id`). Canonical rows, Raw rows
and matching evidence are never updated or deleted (ADR-0006).

## 2. Source record key and canonical versioning

A source record is identified by `stateCode|sourceSystem|sourceEntity|<record key values>`. The record key values
come from the source definition's optional `recordKeyFields` (a list of source fields); without it the key is the
existing `recordIdField`. A pack whose `recordIdField` is shared by several residents (a family or ration number)
configures `recordKeyFields` in YAML (ADR-0003).

When canonical processing maps a Raw record:

1. It computes the source record key and reads the latest canonical version for that key.
2. If there is none, it inserts the canonical row and version 1.
3. If the latest version has the same State Pack version and the same canonical content (the payload without
   per-ingestion `metadata` and `uddp.*` lineage extensions), the Raw record is an unchanged re-delivery: Raw keeps
   it (append-only) and no canonical version is created (counted as skipped).
4. Otherwise (values corrected, or the State Pack version changed) it inserts a new canonical row and version
   n+1 that supersedes the previous latest version. A re-mapping under a new State Pack version therefore creates
   new versions, never a second participating record.
5. Raw records of one batch that share a key are ambiguous (either could replace the other), so all of them are
   rejected as processing errors (`Duplicate source record key in batch`) and the record's versions stay as they were.

Rows that existed before V10 are backfilled by the migration with the default key (`metadata.sourceRecordId`);
where several canonicals of one batch share it, each gets a `|raw:<raw_record_id>` suffix so none is hidden.

## 3. Golden build and carry-forward

`build(matchingRun)` runs in one transaction:

1. **Participants**: latest canonical versions (`canonical_resident_current`).
2. **Target groups**
   - `FULL` run: the run's clusters, restricted to participants. Participants the run did not consider are
     singletons (as before M11).
   - `INCREMENTAL` run: start from the current membership of the active identities. A member whose source record
     has a newer canonical version is detached into its own node. The run's clusters then join nodes
     (union-find over source record keys). Participants in neither are singletons.
3. **Carry-forward** (ADR-0001). For every target group G and active identity I, overlap = number of source record
   keys in both. Candidate pairs with overlap > 0 are taken in order: overlap descending, then the oldest
   identity (`created_at`, then ID), then the group holding the identity's longest-standing member (earliest
   membership `effective_from`, then canonical ID); a pair is accepted when neither G nor I is taken yet. So a
   group inherits the ID of the identity it overlaps most, ties go to the oldest ID, and of two split sides the
   larger keeps the ID (on a tie, the side with the identity's original members).
4. **New groups** (no accepted identity) get a new platform UUID. If the group overlaps an identity, the new ID
   records `split_from_id` = the identity it overlaps most (ties: oldest), and both get a `SPLIT` event; otherwise
   it is a plain `CREATED`.
5. **Losing identities** (active, not accepted) are `RETIRED` with `merged_into_id` = the identity that now holds
   most of their source records (ties: oldest); the survivor gets `MERGED`, the loser `RETIRED`.
6. **Versions**: for every accepted or new identity, survivorship runs over the group. If the member canonicals,
   payload, provenance and policy version all equal the current version, nothing is written. Otherwise the current
   version is closed and version n+1 is written with its provenance; membership rows are closed/opened for the
   difference; events record each `MEMBER_ADDED`, `MEMBER_REMOVED`, `MEMBER_VERSION_CHANGED` (same key, newer
   canonical) and `VALUE_CHANGED` (field, old value, new value).

A survivorship policy change therefore gives every identity a new version with the same ID. A matching model
change is just a new matching run: membership is re-evaluated and IDs are carried forward by the same rule.
Rebuilding the same or an equivalent run is idempotent: no new versions, no events.

`GoldenBuildSummary` keeps the M6 fields (`created` = new identities, `reused` = carried-forward identities) and
adds `unchanged`, `versioned`, `merged`, `split`, `retired`, `mode` and the `goldenBuildId`.

## 4. Incremental flow

```text
new batch -> Raw -> canonical (new keys: version 1; corrected keys: version n+1; unchanged: skipped)
          -> matching --incremental: records = latest versions that are not current Golden members
                                     pairs = only those touching such a record (Splink blocking rule)
          -> Golden build (run mode INCREMENTAL): current groups + new/changed records joined by AUTO_MATCH clusters
```

`python -m matching.run --incremental` stores `matching_run.mode = 'INCREMENTAL'`, `records_considered` = number of
new or changed records, the pairs that involve one of them, and cluster rows for the connected components that
contain one. With no current Golden members it behaves like a full run. The M10 orchestrator gets a
`processingMode` (`FULL`, the default, or `INCREMENTAL`) per onboarding run; `MATCHED` and `GOLDEN_BUILT` then
use incremental matching and build. Full runs behave exactly as in M10.

## 5. Migration of existing golden data (V10)

1. Backfill `canonical_resident_version` for every canonical row (default key, see section 2). Rows of one key from
   different batches or pack versions are chained by `created_at` as versions 1..n.
2. Each existing `golden_resident` row becomes an identity with version 1 (its payload, policy, run and copied
   provenance; `effective_from = created_at`).
3. The rows of the most recent build (latest `(matching_run_id, survivorship_policy_version)` by `created_at`) are
   the current identities: they stay `ACTIVE`, and their latest-version members get open memberships. Every Golden
   ID that clients have seen from that build keeps working unchanged.
4. Rows of older builds are `RETIRED` with `merged_into_id` = the current identity sharing most of their source
   records (ties: oldest); their version and memberships are closed. A current row left with no current member
   (all its members superseded) is retired the same way. Each gets `CREATED` and `RETIRED` events marked
   `migration`.

## 6. API

- `GET /api/golden-residents` lists active identities with their current version (`matchingRunId` and
  `survivorshipPolicyVersion` filter on the current version; `includeRetired=true` adds retired IDs).
- `GET /api/golden-residents/{id}` returns the current version (payload, members, provenance), `status`,
  `currentVersion`, `splitFromId`, and a `versions` summary. For a retired ID it returns 200 with
  `status: RETIRED`, `mergedIntoId` and `resolvedGoldenResidentId` (the active identity at the end of the
  merge chain), so old references always resolve.
- `GET /api/golden-residents/{id}/history` returns every version (with provenance) and every event, oldest first.
- `POST /api/golden-residents/build` is unchanged; the summary carries the new counters.

## 7. Worked examples (sample data)

Sample: 7 canonicals; Ram Singh appears in states 1, 2 and 3; 5 Golden identities (Ram, Sita, Ramesh, Anita,
Sunita). `G-RAM` is Ram's Golden ID.

1. **Re-run unchanged.** Matching runs again (new run, same clusters). Groups equal current memberships, every
   group accepts its own identity with overlap = its size, survivorship gives the same payload and provenance:
   0 created, 5 reused, 5 unchanged, 0 versions, 0 events.
2. **State 4 Ram Singh joins (incremental).** State 4 is ingested: 2 new canonicals (Ram, Neha). Incremental
   matching considers those 2; Ram-4 AUTO_MATCHes the three Ram members. Target groups: {Ram1,Ram2,Ram3,Ram4},
   the four untouched singletons, {Neha}. `{Ram x4}` overlaps `G-RAM` by 3, so it keeps `G-RAM`; `G-RAM` gets
   version 2 (`MEMBERSHIP_CHANGED`), `MEMBER_ADDED` for Ram-4 and `VALUE_CHANGED` only for fields whose survived
   value changed. Neha is `CREATED`. 6 active identities; the other 4 have no new version.
3. **Mobile correction.** Ram's record in one source is re-delivered with a new mobile. Canonical version 2 of
   that key supersedes version 1; matching (full or incremental) sees only version 2. The group keeps `G-RAM`
   (overlap by source record key, not canonical ID). If the survived mobile changes, version n+1 has
   `MEMBER_VERSION_CHANGED` and `VALUE_CHANGED mobile old -> new`; the old value stays in version n and its
   provenance in `/history`.
4. **Merge.** A new run clusters two identities' records together: `G-RAM` (3 records) and `G-SITA` (1 record).
   The group overlaps `G-RAM` by 3 and `G-SITA` by 1, so it keeps `G-RAM`; `G-SITA` is `RETIRED` with
   `merged_into_id = G-RAM`; `G-RAM` gets a version with `MERGED` and `MEMBER_ADDED`. `GET G-SITA` returns
   `RETIRED` and resolves to `G-RAM`. With equal overlaps the oldest ID would survive. `G-SITA` is never issued
   again.
5. **Split.** A later run separates them again into {Ram x3} and {Sita}. {Ram x3} overlaps `G-RAM` by 3 and keeps
   it; {Sita} overlaps it by 1 but `G-RAM` is taken, so Sita gets a new ID `G-NEW` with `split_from_id = G-RAM`
   (never the retired `G-SITA`); `SPLIT` events on both and `MEMBER_REMOVED` on `G-RAM`.
6. **Policy v2 -> v3.** A build under `resident-survivorship-v3` after v2 builds: same groups, so every identity
   keeps its ID; every identity gets a new version (`POLICY_CHANGED`) whose provenance names v3, plus
   `VALUE_CHANGED` where v3 selects a different value.
