# Shaping operations catalogue

Every layout operation (Tier 1) and field parser (Tier 2) the platform ships, with its configuration and a State Pack
example. Design and approval rules: `docs/decisions/0020-shaping-layer.md`.

A source declares the steps in its `sources/*.yaml`:

```yaml
sourceId: social-welfare
sourceType: file
format: xlsx                          # csv (default) or xlsx
hopTemplate: generic-excel-ingest.hpl
location: landing/social-welfare/delivery.xlsx
sheets: { 01_Social_Welfare: {} }     # one sheet per source
recordIdField: BEN_ID
layout:                               # run in order over the rows of the sheet
  - { op: header-detect@1.0 }
parse:                                # run in order over each tidy row
  - { sourceField: NAME_PARENTAGE, parser: name-relation@1.0, minConfidence: 0.5,
      outputs: { name: person_name, relation.type: relation_type, relation.name: relation_name } }
```

* **Column addressing.** `header-detect` records the column names; later operations address a column by name
  (case and spaces ignored) or by zero-based index. Without header-detect, columns are `col1`, `col2`, ...
* **`parse` step keys.** `sourceField` (a tidy field), `parser` (`name@version`), `outputs` (parser output key to the new
  tidy field), optional `minConfidence` (0-1), plus the parser's own keys. A blank source value is skipped. Mapping rules
  then read the output fields like any other column.
* **Provenance.** Every tidy field remembers sheet, row, producing operation/parser, version and parser confidence.

## Layout operations (Tier 1)

| Operation | Purpose | Config |
|---|---|---|
| `header-detect@1.0` | Find the header row(s), drop everything above and including them, name the columns. A merged group header over sub-headers becomes "Group Sub". Removes "(Dropdown)/(Required)/(Optional)" and newlines. | `maxScanRows` (20), `multiRow: auto` or `false` |
| `column-aliases@1.0` | Rename detected columns to logical names (after header-detect). | `aliases: {LOGICAL: [variant, ...]}` |
| `skip-rows@1.0` | Drop banners, totals and blank lines. | `rules`: list of `{firstColumn: regex}`, `{anyColumn: regex}`, `{onlyFilled: [cols]}`, `{allBlank: true}` |
| `section-rows@1.0` | A title row ("Block: Lamkhai") becomes a value carried by the rows below it; the title row is removed. | `onlyFilled: [cols]`, `carryAs: NAME` |
| `carry-down@1.0` | Fill blank cells with the value above (merged cells, grouped listings). Resets per sheet. | `columns: [..]` |
| `ditto@1.0` | Replace ditto marks with the value above. | `columns: [..]`, `tokens` (default `-do-`, `_do-`, `do`, `"`) |
| `numbers@1.0` | Remove digit grouping ("25,23,809" becomes "2523809"). | `columns: [..]`, `grouping: indian` or `western` |
| `whole-numbers@1.0` | Excel numeric cells arrive as "67.0"; this makes them "67" (only integral values, only the named columns). | `columns: [..]` |
| `sheet-name@1.0` | Expose the sheet name as a column. | `as: COLUMN` |
| `wide-to-long@1.0` | Repeating column groups ("Tranche 1 Amount", "Tranche 2 Amount") become one row per group. Needs header-detect. | `pattern: "Tranche {n} *"`, `n: [1, 2]`, `into: NAME` |

```yaml
layout:
  - { op: header-detect@1.0 }
  - { op: skip-rows@1.0, rules: [{ firstColumn: "^Total" }, { allBlank: true }] }
  - { op: carry-down@1.0, columns: [RATION_CARD] }     # members listed under a household
  - { op: whole-numbers@1.0, columns: [AGE, SL_NO] }     # Excel "67.0" becomes "67"
  - { op: numbers@1.0, columns: [AMOUNT], grouping: indian }
```

Family inheritance: a food-members sheet carries the ration card down so every member row names its
household, and the pack's `Family` rules map it (`sourceFamilyIds[]`, `membership.relationshipType`).

## Field parsers (Tier 2)

| Parser | Input cell | Output keys (for `outputs`) | Config |
|---|---|---|---|
| `name-relation@1.0` | "Name S/O Father" (S/O, D/O, W/O, SO, ...; "Late" marks a deceased relation) | `name`, `nameNormalised`, `relation.type`, `relation.name`, `relation.nameNormalised`, `relation.deceased`, `inferredGender` | `extraMarkers` (marker to relation type) |
| `name-relation-age@1.0` | the same, plus an age token ("Age 67", "(63 Yrs)", "58Y") | as above plus `age.years` | `extraMarkers` |
| `free-address@1.0` | "Phunal / Lamkhai / Ngairang", "Vill Wangkhem, Senkhai" | `geography.village.name`, `geography.block.name`, `geography.district.name`, `geography.suggestions` | `aliases` (LGD spelling to variants) |
| `masked-id@1.0` | "XXXXXXXX4821", "4821" or a full valid number | identifier codes, e.g. `AADHAAR_LAST4`, `AADHAAR` | `type` (a registry type with `partialOf`) |
| `id-classify@1.0` | a column holding one of several identifier kinds | `type` (code or `INVALID`) and the value under its code | `types: [..]` |
| `unit-quantity@1.0` | "1.5 Kanal", "1 Unit" | `quantity`, `unit` | none |

Confidence: `free-address` is 1.0 when every level is named, 0.8 when a level is implied by the master, 0.5 when several
chains tie (names are then left out and `geography.suggestions` lists them). A parse warning never contains the value.

```yaml
parse:
  - { sourceField: BENEFICIARY_DETAILS, parser: name-relation-age@1.0,
      outputs: { name: person_name, relation.type: relation_type, relation.name: relation_name, age.years: person_age } }
  - { sourceField: ADDRESS, parser: free-address@1.0, aliases: { Wangkhem: [Wangkhom] },
      outputs: { geography.village.name: addr_village, geography.block.name: addr_block, geography.district.name: addr_district } }
  - { sourceField: AADHAAR_MASKED, parser: masked-id@1.0, outputs: { AADHAAR_LAST4: aadhaar_last4 } }
  - { sourceField: UNIT_SIZE, parser: unit-quantity@1.0, outputs: { quantity: unit_quantity, unit: unit_name } }
```

Mapping then uses the output fields:

```yaml
Resident:
  - { sourceField: person_name, target: name, required: true }
  - { sourceField: relation_type, target: "relations[].type" }
  - { sourceField: aadhaar_last4, target: identifiers.AADHAAR_LAST4 }
  - { sourceField: addr_village, target: geography.village.name }
```

A parser output that is a date (`date-text@1.0`, a calendar plug-in) is ISO: `yyyy-MM-dd`, `yyyy-MM` or `yyyy`. A
Resident `dateOfBirth` rule with no `date:` conversion reads it with its precision (a month or a year alone becomes the
first day of it, with `dateOfBirthPrecision` MONTH or YEAR and the birth year kept); any other value is UNREADABLE_DATE.

In the Onboarding Studio, the Shape step proposes the parsers it can detect (`name-relation`, `masked-id`,
`free-address`); **Add a field parser** runs any other built-in parser or loaded plug-in on a chosen column, learning
its output keys from the first sample value and naming the new columns `<prefix>_<output>`. Configure → Shaping
plug-ins has a form to test any parser on one value.

## Tier 3, plugins
Implement `gov.uddp.shaping.api.LayoutOperation` or `FieldParser`, list the class in
`META-INF/services/...`, add `plugin.yaml` (`name`, `version`, `apiVersion: 1.0`), and have the platform owner add the
jar's SHA-256 to `platform/shaping/approved-plugins.yaml`. See ADR-0020.

## Workbooks in the Studio

Uploading an `.xlsx` lists its sheets with their row counts on the Upload step; every sheet is loaded until the user
unticks some, and the ticked sheets become the source's `sheets:`. Profile, Shape and Preview read only those sheets.
A workbook's layout always starts with `header-detect@1.0` (the Studio adds it when the accepted steps do not name
one), so its columns carry the header names rather than `col1`, `col2`. The Shape step shows the sheet of each
before/after row, names the sheet a proposal came from, and proposes `sheet-name` (`as: sheet`) when rows come from
more than one sheet. See `docs/perf.md` for when to export CSV instead.
See also [Held and rejected records](held-and-rejected-records.md): a low-confidence parse can hold a row (fix target SHAPING).
