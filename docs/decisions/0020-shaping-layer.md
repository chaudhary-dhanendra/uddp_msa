# ADR-0020: The shaping layer between Raw and mapping

Status: accepted (2026-10-28)

## Context
Real department files are not tidy: banners above the header, merged cells, "Name S/O Father, Age 67" in one cell,
free-text addresses, masked identifiers. Putting that logic into the mapping engines or into Hop would break two
rules: semantics live in Common Core, and a state never gets its own code.

## Decision
A **shaping layer** sits between Raw and the mapping engines. It turns each raw record into a `TidyRecord` and nothing
else; mapping, matching and survivorship are unchanged.

```
Hop (technical ingest) -> Raw (append-only, source-faithful) -> SHAPING -> mapping engines -> Canonical -> ...
```

* **Layers.** Raw keeps every physical row exactly as read (CSV: `csvHeader` + `rawLine`; Excel: `format: xlsx`,
  `file`, `sheet`, `row`, `cells`). Shaping is a pure function of the raw rows and the source's `layout` and `parse`
  steps in the State Pack. It never writes Raw or Canonical; its tidy record is stored beside the canonical version it
  produced (`shaped_record`, V100) for replay and audit.
* **TidyRecord contract** (`gov.uddp.shaping.api`): `fields` (name to value, in column order) and `provenance`
  (per field: file, sheet, row, source cell/field, operation, operation version, parser confidence). A source without
  `layout`/`parse` passes its CSV fields through unchanged, so existing packs behave as before. Mapping rules read
  tidy field names; values a parser produced carry their confidence into `uddp.fieldProvenance`.
* **Two steps, in order.** `layout` steps (`name@version`) reshape rows: they see the whole sheet of a delivery
  (header-detect, carry-down and skip-rows need neighbouring rows). `parse` steps (`parser@version`) split one cell
  into typed fields (`outputs`), with a confidence; below `minConfidence` the raw value is kept and a
  `SHAPING_LOW_CONFIDENCE` warning is recorded. A row that layout consumes (the header row itself, a banner, a total)
  is skipped by canonical processing, not failed (`RowDroppedException`).
* **Excel.** `format: xlsx` sources are ingested by the one generic `generic-excel-ingest.hpl` (every physical row of
  the named `sheets`, cells as displayed text). One source is one sheet.
* **Versioned and digested.** Operations and parsers are addressed as `name@version`; the digest of a source's steps is
  stored with each tidy record, so a changed step is a new shaped record, never a silent rewrite.

## Tiers
1. **Tier 1, layout operations** (built in, declarative YAML): header-detect, skip-rows, carry-down, ditto, ...
2. **Tier 2, field parsers** (built in, declarative YAML): name-relation, free-address, masked-id, ...
   Their word lists and aliases are platform reference data (`platform/reference-data/shaping/`); a pack can add
   aliases or markers in the step's config.
3. **Tier 3, plugins** (code, only when Tiers 1-2 cannot express the source): a jar that implements
   `LayoutOperation` or `FieldParser` from the `shaping-api` jar and registers them with `ServiceLoader`.

### Plugin approval
A plugin is loaded only when all of these hold; otherwise it is logged and ignored (the platform starts anyway):
1. it sits in the plugins directory (`uddp.shaping.plugins-dir`, default `plugins`);
2. it carries a `plugin.yaml` with `name`, `version` and `apiVersion` 1.x;
3. its SHA-256 is listed in `platform/shaping/approved-plugins.yaml` (a reviewed change to that file is the approval);
4. it uses only `gov.uddp.shaping.api` (ArchUnit-enforced for core; shaping code may not touch matching, golden or
   canonical packages).

A pack that names an operation nobody registered fails at processing time with "Unknown layout operation".

## Consequences
* Messy-source knowledge is data (State Pack YAML), versioned and reviewable; no state-specific code.
* The Studio can propose steps (`SuggestionEngine`) from raw rows and show the evidence.
* Sheet-wide layout needs the delivery's raw rows: `ShapingEngine` reads them once per delivery (small cache), so a
  very large single-sheet delivery is held in memory while its batch is processed.
* Delivery-change hashing and raw-payload read views for xlsx sources (`DeliveryChangeService`, `CanonicalReadService`)
  still use the CSV reader and do not yet understand xlsx payloads.
