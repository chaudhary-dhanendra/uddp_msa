# ADR-0016: Database-table sources use one generic Hop template and named connections

Status: accepted (Onboarding Studio, Part 5).

## Context
Besides file uploads, a state may deliver its records as a table in its own PostgreSQL database. The source must
stay generic (no per-state pipeline) and its credentials must never appear in a pack, in YAML or in Git.

## Decision
- A source with `sourceType: database` names a logical connection (`connectionRef`) and a `table` (optionally
  `schema.table`) or one read-only `query` (a single SELECT without `; " < > | & ^ %` or backtick characters).
  `StatePackLoader` requires the connection and one of the two. `StatePackAssets.SourceDefinition` gained the two
  optional fields; file sources are unchanged.
- Named connections are saved from the Studio (`PUT /api/studio/connections/{name}`), tested on save, and stored in
  the local settings store under `connection.<name>.*` with the password AES-GCM encrypted (ADR-0014). The API never
  returns a password. A demo reset removes them.
- `hop/pipelines/generic-db-ingest.hpl` is the one database template for every state: a Table input on the Hop
  connection `uddp-source` (metadata `metadata/rdbms/uddp-source.json`, resolved from `UDDP_SOURCE_*` properties) runs
  `${UDDP_SOURCE_SQL}` and lands each row through the same steps as `generic-file-ingest.hpl`, so `raw_record` holds
  the identical `{csvHeader, rawLine}` payload and mapping is unchanged.
- Common Core generates `UDDP_SOURCE_SQL` generically (`DatabaseSources.rawLineSelect`): every row of the table or
  query becomes one CSV line in column order via `row_to_json`/`json_each_text`, quoting values that contain a comma,
  quote or line break; the header is the source's column names. The SQL and the source credentials go into the same
  temporary JVM argument file as the platform settings (ADR-0013), deleted after the run, so nothing reaches a
  command line or Hop's echoed output.
- `SOURCE_TESTED` runs the source's SELECT once through the named connection; the Studio's Preview reads the first
  rows the same way.

## Consequences
`M10PortabilityGuardTest` allows exactly the two generic templates. `StudioDatabaseSourceTest` ingests a table from a
separate Testcontainers database with the real Hop template (skipped without `HOP_HOME`). As with file sources, the
landed payload is plain concatenation, so a value containing a double quote is not supported yet.
