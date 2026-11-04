# Common Data Model

## Contract principles

Canonical entities are stable, source-neutral contracts. Every transformed entity carries `metadata`, `sourceReferences`, and optional namespaced `extensions`. Canonical dates use ISO-8601 (`YYYY-MM-DD`), gender uses `MALE`, `FEMALE`, `OTHER`, or `UNKNOWN`, and mobile remains a string.

## Resident

`residentId` is a UDDP registry identifier (assigned after registry processing); a pre-registry canonical record may omit it. Resident includes `name`, `dateOfBirth`, `gender`, `mobile`, `address`, `sourceReferences`, `extensions`, and metadata. Address includes state, district, districtLGD, block, village, and pincode.

## Family

Family has `familyId`, `sourceFamilyIds`, members (resident references), address, extensions, and metadata. Source family identifiers are preserved; no source identifier is assumed globally unique.

## Scheme and Benefit

Since M15 (docs/benefit-domain.md, ADR-0011) a Scheme is a platform catalogue entry (`platform/schemes/catalogue.yaml`): `schemeId`, `name`, department, benefit type (`ENTITLEMENT` or `ASSISTANCE`), form (`IN_KIND` or `CASH`), beneficiary level (`FAMILY` or `RESIDENT`), default unit and frequency, exclusivity group and optional declarative eligibility. Schemes carry no state code; State Packs crosswalk their own scheme codes onto `schemeId`. A Benefit is one benefit as one source names it: a platform `benefitId`, the `schemeId` and source scheme code, the source benefit ID, status (`ACTIVE`, `SUSPENDED`, `CLOSED`; `ENDED` when no current delivery names it), start date, entitlement (amount or quantity, unit, frequency), disbursements, the household for FAMILY-level schemes, extensions and metadata. Benefits link to Golden Residents and Golden Families when read. A full eligibility engine and payment reconciliation are deferred.

## Metadata required on all canonical entities

`stateCode`, `sourceSystem`, `sourceRecordId`, `ingestionBatchId`, `ingestionTimestamp`, `statePackVersion`, `mappingVersion`, and `lineageId`.

The initial machine-readable schemas are in `common-model/`; they define the contract shape, not database storage. The Java Common Core mirrors core fields with strongly typed Java records. Extension data is held in a safe `Map<String, Object>` boundary so a State Pack can add namespaced fields without changing the Java Resident class.

For canonical persistence, the complete serialized `Resident` is stored as PostgreSQL JSONB. Dates remain ISO-8601 strings and extensions remain part of the payload, so unmapped source values such as State 3's `blood_group` and `disability_pct` survive the Raw-to-canonical boundary.

