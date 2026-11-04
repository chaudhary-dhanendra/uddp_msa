# Resident matching PoC

The operational path is: canonical PostgreSQL Residents -> pandas -> Splink
4.0.17 `DuckDBAPI()` -> `Linker` -> `inference.predict()` -> native
`match_weight`/`match_probability` -> application threshold -> clusters ->
PostgreSQL persistence.  It reads only `canonical_resident`; it does not read
Raw records or CSV files.  From the State Packs it reads only the decision
thresholds in `matching/resident.yaml` (`autoMatch`, `possibleMatch`), from
`UDDP_STATE_PACK_DIRECTORY` or the repository's `state-packs/`.  Every pack
that configures Resident matching must agree, because one run compares
residents across states; disagreement stops the run.

Each pair gets one of three decisions: `AUTO_MATCH` at or above `autoMatch`,
`POSSIBLE_MATCH` from `possibleMatch` up to `autoMatch` (held for review), and
`NO_MATCH` below `possibleMatch`.  Only `AUTO_MATCH` pairs form clusters.  The
run records both thresholds (`matching_run.threshold`,
`matching_run.possible_match_threshold`).

`resident-match-v1` compares generic canonical name, date of birth, mobile,
gender, and district LGD.  It intentionally runs all pairs: with seven PoC
records that is at most 21.  The model parameters and the 0.95 / 0.75 pack
thresholds are configured MVP assumptions, not estimated or production-calibrated values.
Representative data and outcome review are needed before choosing a production
threshold; false positives and false negatives remain possible.

Matching is not survivorship: it creates neither a golden Resident nor a master
ID, and does not alter canonical Resident records.  Each execution persists a
new run, native pair evidence/decision, and AUTO_MATCH-only cluster membership.

Connection settings come only from `UDDP_POSTGRES_HOST`, `UDDP_POSTGRES_PORT`,
`UDDP_POSTGRES_DB`, `UDDP_POSTGRES_USER` and `UDDP_POSTGRES_PASSWORD`.  There
are no defaults: a missing variable stops the run with an error naming it.
