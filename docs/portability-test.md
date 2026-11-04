# Portability Acceptance Test

## Purpose

Prove that State 2 and State 3 onboarding are configuration, not forks of Common Core or Apache Hop.

## Acceptance scenario

1. Start one unchanged Common Core build and the same Common Data Model version.
2. Activate State 1 Pack and execute `generic-file-ingest.hpl` against `sample-data/state-1/pds.csv`.
3. Transform State 1 records and assert canonical name, ISO date, gender, district LGD, family reference, mobile, and lineage.
4. Activate State 2 Pack and execute the **same** `generic-file-ingest.hpl` against `sample-data/state-2/pension.csv`.
5. Transform State 2 records and assert equivalent canonical fields despite distinct names, dates, gender codes, and district representation.
6. Assert configuration validation succeeds, no Common Core tracked source file changed between runs, and the selected Hop template ID is unchanged.
7. Assert the State 2 result is persisted through the same Raw, canonical, matching, and registry interfaces.
8. Activate State 3 Pack and map its landed CSV records with that same engine. Unmapped source fields are retained under their original keys in `Resident.extensions`.

## Milestone 3 mapping proof

`ResidentMappingEngineTest` exercises the one declarative Java engine across all three packs. It proves the configured date conversions `DD-MM-YYYY`, `YYYYMMDD`, and `YYYY-MM-DD` all produce ISO dates; State 2's configured gender crosswalk maps `1` to `MALE` and `2` to `FEMALE`; and State 3 retains `blood_group` and `disability_pct` automatically in `Resident.extensions`.

`PostgresRawMappingProofTest` maps the first raw record from each state's batch with the same engine. Since M8 every PostgreSQL proof runs on a disposable PostgreSQL 16 container (Testcontainers): the test creates an empty database, applies the Flyway migrations, and lands `sample-data/` in Raw in exactly the shape `generic-file-ingest.hpl` writes (`testsupport/HopShapedRawLoader`). Tests never read the developer's `UDDP_POSTGRES_*` settings (surefire excludes them) and need only Docker.

`PostgresCanonicalProcessingProofTest` processes the 3 + 2 + 2 batches through the same generic engine and canonical persistence service, confirms seven canonical rows with direct Raw/batch lineage, verifies State 3 extension persistence, and repeats the batches to prove the total remains seven.

## Required visible report

| Check | Expected |
| --- | --- |
| Common Core change required | 0 |
| New state-specific ETL pipeline required | NO |
| New State Pack required | YES |
| Same mapping engine | YES |
| Same Common Data Model | YES |
| Same Registry | YES |
| State-specific Java mapper | NO |
| Unmapped source values | Preserved in `Resident.extensions` |

The future automated test should fail if State 2 requires a conditional in Common Core or a `state-2-*` Hop pipeline.

