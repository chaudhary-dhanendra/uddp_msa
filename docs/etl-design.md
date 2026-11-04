# ETL and Raw/Landing Design

## Generic Hop templates

Future templates are `generic-file-ingest.hpl`, `generic-db-ingest.hpl`, and `generic-api-ingest.hpl`. They receive only technical, validated parameters from the resolved State Pack source config. They read source records, construct a Raw envelope, and write it to Raw/Landing. A generic workflow creates a batch and invokes the template.

### Proven local file-ingestion implementation

`hop/pipelines/generic-file-ingest.hpl` is a single portable Apache Hop 2.19 pipeline. Its `TextFileInput2` transform reads a source file through the `SOURCE_FILE` parameter with `Header=Y`, one header line, `format=MIXED`, and exactly one `String` field: `raw_source_line`. It does not declare, parse, or interpret state-specific CSV columns.

`CSV_HEADER` is supplied at runtime alongside `SOURCE_FILE`, `BATCH_ID`, `STATE_CODE`, `SOURCE_SYSTEM`, `SOURCE_ENTITY`, `SOURCE_SCHEMA_VERSION`, and `STATE_PACK_VERSION`. Hop's `GetVariable` transform adds those runtime values as technical provenance. Constant fields provide JSON punctuation only; `ConcatFields` constructs source-faithful `raw_payload` JSON containing the configured CSV header and the opaque physical source line. A Hop `TableOutput` transform inserts those records into PostgreSQL `public.raw_record`.

The Hop PostgreSQL metadata takes host, port, database, user and password from `${UDDP_POSTGRES_HOST}`, `${UDDP_POSTGRES_PORT}`, `${UDDP_POSTGRES_DB}`, `${UDDP_POSTGRES_USER}` and `${UDDP_POSTGRES_PASSWORD}`. The values are supplied at execution time and is not stored in the repository. The metadata includes PostgreSQL JDBC `stringtype=unspecified`, allowing Hop string values to bind correctly to the database's `uuid` and `jsonb` columns. PostgreSQL may be deployed locally or with the project Docker Compose service; that is environment configuration and does not change this architecture.

The same unchanged pipeline was proven against three physical schemas:

| State | Rows | Evidence |
| --- | ---: | --- |
| State 1 | 3 | Six-column PDS CSV persisted to one batch. |
| State 2 | 2 | Different six-column pension CSV persisted without pipeline changes. |
| State 3 | 2 | Eight-column CSV persisted with `blood_group` and `disability_pct` retained in both header and raw line. |

The final acceptance batches contained 3 + 2 + 2 = 7 `raw_record` rows. No semantic mapping, type conversion, gender/DOB normalization, crosswalk, validation, or canonical/CDM transformation occurs in Hop. Those responsibilities remain deferred to Java Common Core and the State Pack configuration.

Current limitation: this implementation preserves one physical line per record and is appropriate for the demonstrated simple CSV files. Quoted CSV fields containing embedded newlines or other multiline record forms require a separately tested ingestion policy before support is claimed.

Apache Hop 2.19 emits a non-fatal execution-audit serializer warning after successful runs: `rowsBinaryGzipBase64Encoded` reports a `Long`/`String` type mismatch while storing execution data. The pipeline exits 0 and `TableOutput` succeeds; this is documented as a non-blocking runtime issue and is not part of the ingestion transform path.

## Raw envelope

Raw/Landing is append-only. A future `raw_records` table/object payload contains:

| Field | Purpose |
| --- | --- |
| `rawRecordId` | Platform-assigned immutable identifier |
| `payload` | Original record bytes/object without semantic alteration |
| `contentType` | CSV row / JSON / database row representation |
| `sourceSystem`, `sourceId`, `sourceRecordId` | Origin and source key where present |
| `stateCode`, `sourceSchemaVersion` | Context for configuration selection |
| `ingestionBatchId`, `ingestionTimestamp` | Batch and time provenance |
| `templateId`, `templateVersion` | Technical ingestion lineage |
| `sourceLocation`, `sourceRowNumber` | Recoverable source position when applicable |
| `payloadHash` | Integrity and duplicate-detection aid |

CSV ingestion stores the original header mapping and raw row values. It may additionally store a parsed object for retrieval, but the original representation remains authoritative.

## Replay

`ReplayRequest` selects a stable set of raw IDs or a batch, plus target State Pack/version. The core reads Raw payloads, maps them under that configuration, and writes a new canonical processing version linked to `rawRecordId`, pack digest, mapping version, validation result, and replay run ID. Existing raw and canonical versions are retained; downstream registry updates are traceable and idempotency is defined by run + raw record + pack digest.

## Canonical Resident persistence

The operational flow is now `Source -> generic Hop -> raw_record -> State Pack -> ResidentMappingEngine -> canonical_resident`. `canonical_resident` stores the canonical `Resident` contract as JSONB, with direct `raw_record_id` and `batch_id` foreign keys plus state, source, source entity, and State Pack version provenance. This preserves a simple lineage query from a canonical payload to its immutable Raw record and ingestion batch without prematurely normalizing Resident fields.

The database enforces one `Resident` canonical version per `(raw_record_id, state_pack_version, canonical_entity_type)`. Reprocessing the same Raw record and pack version returns a skip; mapping it under a newer pack version can retain a new version. Mapping failures are recorded in `canonical_processing_error` with Raw ID, batch, pack version, message, and timestamp, while other rows in the batch continue processing.

