# State Pack Specification

## Package layout

```text
state-packs/<state-pack-id>/
  state.yaml
  sources/*.yaml
  mappings/*.yaml
  crosswalks/*.yaml
  validation/*.yaml
  matching/*.yaml
  survivorship/*.yaml
  extensions/*.yaml
```

## Manifest (`state.yaml`)

Required: `packId`, `stateCode`, `version`, `commonModelVersion`, and `status`. It lists source configuration and semantic assets. Versions use semantic versioning; a released version is immutable. A content digest and publication metadata will be stored by the future registry/catalog.

## Source contract

Source YAML identifies a `sourceId`, `sourceType`, `hopTemplate`, logical `connectionRef` (never a secret), extraction configuration, record identifier field, and schema version. Only generic template IDs are allowed.

Optional `recordKeyFields` (M11) lists the source fields that identify one source record across re-deliveries; without it the key is `recordIdField`. Use it when `recordIdField` is shared by several residents (for example a family or ration number): state-1 sets `recordKeyFields: [ration_no, beneficiary_nm]`. The source record key `stateCode|sourceSystem|sourceEntity|<values>` decides whether a re-delivered row is the same record (an unchanged re-delivery is skipped, a changed one becomes the next canonical version) and is what Golden carry-forward counts. See `docs/golden-lifecycle.md`.

## Mapping DSL

Mappings are declarative rules with `sourceField`, canonical `target`, optional `conversion`, optional `crosswalk`, and required/invalid-record policy. Targets must resolve to Common Model fields or a declared extension. The mapping engine rejects unknown target paths and incompatible types at pack validation time. A source field deliberately targeted at `extensions.<key>` is retained under that key; any source field with no mapping rule is retained automatically under its original key in the canonical entity's `extensions`.

## Crosswalk and validation

Crosswalk YAML maps local scalar values to canonical/reference values. Validation YAML defines type, requiredness, allowed values, patterns, and severity. It must be data/configuration, never embedded local code.

Canonical processing executes the Resident validation rules on each mapped canonical Resident. A rule names a canonical `field` path (for example `name` or `address.districtLGD`) and may set `required: true` and/or `allowedValues`; `severity` must be `error` or `warning`. An `error` failure is recorded in `canonical_processing_error` and the record is skipped. A `warning` failure keeps the record and is stored in `canonical_validation_warning`. Mapping guards (a blank `required` source field, an unknown crosswalk value, an unparseable date) still reject a record before validation runs.

## Matching and survivorship

Matching YAML configures comparison fields and threshold bands. The matching adapter reads `thresholds.autoMatch` and `thresholds.possibleMatch`: pairs at or above `autoMatch` are `AUTO_MATCH` and form clusters, pairs from `possibleMatch` up to `autoMatch` are `POSSIBLE_MATCH` (held for review), and the rest are `NO_MATCH`. Because one matching run compares residents across states, all packs that configure Resident matching must agree on the thresholds.

Survivorship YAML ranks trusted sources per field (`fieldPriority: { <field>: [<sourceId>, ...] }`), defines fallback behavior, and never deletes source values.

## Pack lifecycle

Draft -> validate -> review -> publish immutable version -> activate for a source. A processing run pins the exact pack version and digest. Corrections create a new version and use replay.

## Runtime loader

The Java/Spring Boot Common Core uses Jackson YAML to deserialize the manifest and referenced assets into typed State Pack configuration models. Loading validates required fields, referenced files, generic Hop template naming, source-to-mapping references, declared crosswalk references, and matching thresholds. It fails fast with a precise path and reason. This generic loader contains no state-code-specific conditionals.

