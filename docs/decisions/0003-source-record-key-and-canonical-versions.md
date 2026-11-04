# ADR-0003: Source record key and append-only canonical versions

Status: accepted (M11).

## Context
Only the latest canonical version per source record may participate in matching and survivorship, so a source
record needs a stable identity across re-ingestion. `recordIdField` is not always per resident: state-1's
`ration_no` is a family number shared by two residents.

## Decision
- Key = `stateCode|sourceSystem|sourceEntity|<values>`, with the values taken from the source's optional
  `recordKeyFields` (default `[recordIdField]`). state-1 configures `recordKeyFields: [ration_no, beneficiary_nm]`
  in its YAML: the most conservative choice that needs no sample-data change. A name correction in state-1
  therefore appears as a new source record, which matching links back to the same identity.
- `canonical_resident` is never updated. `canonical_resident_version` (insert-only, written in the same statement
  as the canonical row) records the key, the version number and the superseded canonical; unique constraints keep
  each key's chain linear. The view `canonical_resident_current` holds the latest versions.
- An identical re-delivery (same pack version, same content ignoring per-ingestion metadata) creates no canonical
  version; corrected content or a new State Pack version creates version n+1.
- Rows of one batch that share a key are all processing errors, never silent replacements: which row came "last" is
  not reliable, so neither may become the record's next version.
- The V10 backfill uses the default key and appends `|raw:<id>` where one batch shares a key.

## Consequences
For data migrated from before V10, state-1 keys differ from the keys new processing computes, so a re-ingest of
state-1 into a migrated database is treated as new source records; matching links them to the same Golden
identity.
