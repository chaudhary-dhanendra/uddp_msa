# UDDP Architecture

## Decision summary

UDDP is a Java 21/Spring Boot modular monolith with a strict boundary between technical ingestion and semantic interoperability. Apache Hop moves records from a source to immutable Raw/Landing storage. The Java Common Core loads a versioned State Pack and turns Raw records into canonical records. PostgreSQL later holds raw records, canonical processing runs, source references, lineage, and registry/golden-record data. Python is reserved for the future Splink identity-resolution adapter.

```text
Source DB / CSV / API
  -> generic Hop template (parameters from State Pack source config)
  -> Raw/Landing (original payload + immutable ingestion envelope)
  -> Common Core (State Pack loader -> mapping -> conversion -> crosswalk -> validation)
  -> Common Data Model processing record
  -> Splink matching adapter
  -> Registry / golden record
  -> REST API and onboarding demo
```

## Architectural decisions

### A. Hop configuration

Hop executes a source-type template such as `generic-file-ingest.hpl`, `generic-db-ingest.hpl`, or `generic-api-ingest.hpl`. Before execution, the onboarding/orchestration layer resolves a State Pack source entry into an allow-listed parameter set: connection reference, query/table, source identifier, state code, batch id, source schema version, and Raw target. Secrets remain outside State Packs, referenced by logical connection names. A single template is reused when source mechanics match.

### B. Boundary

Hop owns connection, extraction, transport, batch orchestration, and Raw write. The Common Core owns field meaning, mapping, format conversion, crosswalks, canonical validation, extensions, matching, survivorship, and replay. Hop must not contain state-specific semantic transforms.

### C. Raw/Landing

Raw records preserve the original payload unchanged plus an ingestion envelope; see [docs/etl-design.md](docs/etl-design.md). Raw is append-only and is the replay authority.

### D. Replay

Replay selects Raw records by source/batch/time and processes them with an explicitly selected State Pack and mapping version. It creates a new processing run and lineage records; it never overwrites original Raw data or silently mutates past output.

### E–F. State Pack versioning and loading

Each pack has `packId`, semantic `version`, compatibility declaration, and content digest recorded at publish time. The loader validates the manifest and referenced YAML/JSON assets against contract schemas, resolves a precise immutable version, and supplies a compiled configuration object to Common Core modules.

### G. No leakage of state logic

Common Core accepts only canonical contracts plus a validated State Pack interface. It has no conditional branches keyed by state, source, or department. Enforce this with code review, a forbidden-pattern check, and the portability integration test.

### H. Portability

State 2 is portable only if it is added through State Pack data, a suitable existing generic template, and unchanged Common Core source. See [docs/portability-test.md](docs/portability-test.md).

### I. Legitimate templates

A new generic Hop template is legitimate only for a distinct reusable technical transport/protocol (for example, an API authentication/extraction mode unavailable to the API template). It is incorrect if its logic names a state/department, renames semantic fields, applies local code mappings, or exists solely for one state's schema.

### J. Extensions

Extensions are namespaced JSON objects (`state-1.pds.cardCategory`) with declared JSON Schema in the pack. Core consumers must ignore unknown extensions. Core fields remain stable and extensions cannot redefine or override them.

## Future identity-resolution flow

The Java Common Core's `MatchingService` boundary receives validated canonical Resident candidates, not raw source columns. A future Python/Splink adapter creates a Splink-compatible comparison dataset with name, date of birth, gender, mobile, and district LGD; the State Pack supplies comparison participation and decision thresholds. Splink returns a probability/score and explainable field-level evidence. UDDP converts this to `AUTO_MATCH`, `POSSIBLE_MATCH`, or `NO_MATCH`. Auto matches can attach a source reference to an existing registry resident; possible matches are held for review; no matches create a candidate resident. The adapter and its result contract are reusable, while score thresholds and source-field availability are pack configuration.

## Future registry and golden record

PostgreSQL registry storage will separate immutable source/canonical facts from chosen golden values:

- `resident` — platform `residentId`, registry status, creation/update metadata
- `resident_source_reference` — state/source/source-record identity, raw and canonical version links
- `resident_attribute_observation` — each observed canonical field value with lineage
- `resident_golden_attribute` — currently selected value, rule and evidence used
- `family` and `family_member` — stable family registry identities and membership links
- `lineage_event` — ingestion, mapping, validation, matching, survivorship, and replay events

Survivorship reads State Pack field-priority rules and selects a golden value from observations. It never deletes contradicted source values, and a golden value is always traceable to its source observation and rule version.

## Extension points deliberately deferred

Security hardening, DPDP controls, Aadhaar handling, HA, Kafka, Kubernetes, CDC, metadata platforms, Keycloak, OpenG2P/OpenSPP, and large-scale observability are outside the MVP. Interfaces should be designed so they can be added without changing State Pack semantics.

