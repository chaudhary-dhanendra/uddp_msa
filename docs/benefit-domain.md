# Scheme and Benefit domains (M15)

M15 extends UDDP from "who is this person" to "what does this person receive, from whom". A platform **scheme
catalogue** names every scheme once; State Packs translate their own scheme codes onto it with crosswalks; canonical
processing records each source's **benefits** (entitlements and assistance, with disbursements); and the read API
joins them to Golden Residents and Golden Families, so one Resident 360 view shows benefits across departments and
states. Two reports use the same joins: duplicate benefits and eligible-but-not-enrolled. Decisions are in ADR-0011
(catalogue and benefit model) and ADR-0012 (minimal declarative eligibility). Nothing in the code names a state,
source, pack or department.

## 1. Platform scheme catalogue

`platform/schemes/catalogue.yaml` is platform configuration, like `platform/survivorship/resident.yaml`. It is loaded
and validated at startup (`SchemeCatalogue`); an invalid catalogue stops the application.

```yaml
catalogueVersion: 1.0.0
departments:
  - { code: FOOD_CIVIL_SUPPLIES, name: Food and Civil Supplies }
schemes:
  - schemeId: PDS-RATION
    name: Public Distribution System ration
    department: FOOD_CIVIL_SUPPLIES
    benefitType: ENTITLEMENT        # ENTITLEMENT (a standing right) | ASSISTANCE (one-off support)
    benefitForm: IN_KIND            # IN_KIND | CASH
    beneficiaryLevel: FAMILY        # FAMILY: held by a household | RESIDENT: held by a person
    unit: KG                        # default unit of quantities (IN_KIND) or currency (CASH)
    frequency: MONTHLY              # default entitlement frequency
    exclusivityGroup: PDS-RATION    # optional; default = schemeId (see section 5)
    eligibility: { ... }            # optional, RESIDENT-level schemes only (section 6)
```

| Sample scheme | Department | Level | Group | Eligibility |
| --- | --- | --- | --- | --- |
| `PDS-RATION` Public Distribution System ration | Food and Civil Supplies | FAMILY | `PDS-RATION` | none |
| `OLD-AGE-PENSION` Old age pension | Social Welfare | RESIDENT | `SOCIAL-PENSION` | `minAgeYears: 45` (sample) |
| `DISABILITY-PENSION` Disability pension | Social Welfare | RESIDENT | `SOCIAL-PENSION` | none |

The sample residents are 32–51 years old, so the sample catalogue uses 45 as the old age pension threshold to give
the demo both eligible and ineligible people; a real catalogue sets its own value. A scheme is identified by
`schemeId` alone: it is the same scheme in every state that crosswalks onto it.

## 2. State Pack configuration

A source's mapping may declare a `Benefit` entity next to `Resident` and `Family`. Each row of that source names at
most one benefit of the resident it describes. Rules have the usual shape (`sourceField`, `target`, optional
`crosswalk`, `conversion`, `required`):

| Target | Meaning |
| --- | --- |
| `schemeCode` | The source's scheme code, crosswalked to a catalogue `schemeId` (typically crosswalk `scheme`). Blank → the row names no benefit. |
| `sourceBenefitId` | The source's benefit identifier (ration card number, pension order number). Required when a scheme is named. |
| `sourceFamilyId` | The household holding the benefit; required for FAMILY-level schemes. Same ID space as the Family rules. |
| `status` | `ACTIVE`, `SUSPENDED` or `CLOSED` after an optional crosswalk; absent or blank → `ACTIVE`. |
| `startDate` | Date the benefit started (`conversion: date:<format>`). |
| `entitlement.amount`, `entitlement.quantity` | Decimal entitlement per period. |
| `entitlement.unit`, `entitlement.frequency` | Override the catalogue defaults; frequency is `MONTHLY`, `QUARTERLY`, `HALF_YEARLY`, `ANNUAL` or `ONE_TIME`. |
| `disbursement.date` | Date of the disbursement (transfer, ration lifting) the row reports; blank → none. |
| `disbursement.amount`, `disbursement.quantity`, `disbursement.reference` | Its value and the source's reference; an amount or quantity without a date is an error. |
| `extensions.<name>` | Kept on the observation's `benefit_payload.extensions`. |

```yaml
  Benefit:
    - { sourceField: pension_type, target: schemeCode, crosswalk: scheme }
    - { sourceField: pension_order_no, target: sourceBenefitId }
    - { sourceField: pension_status, target: status, crosswalk: benefit-status }
    - { sourceField: monthly_amount, target: entitlement.amount }
    - { sourceField: last_paid_on, target: disbursement.date, conversion: date:YYYYMMDD }
```

An unknown target, a missing crosswalk value, a scheme that is not in the catalogue, a missing benefit ID or a
malformed number or date is a Benefit processing error: it is recorded in `canonical_benefit_processing_error` and
never rejects the Resident (as Family errors, ADR-0009).

## 3. Canonical layer (append-only, V13)

```text
raw_record ──> canonical_resident (+ version)                    unchanged Resident flow
          ├──> canonical_family_observation (+ membership)        unchanged Family flow (M14)
          └──> canonical_benefit_observation   one per raw record processed with Benefit rules
                 resident_record_key, canonical_resident_id, pack/mapping/catalogue versions
                 benefit_record_key (null = the delivery names no benefit), scheme_id, source scheme code,
                 source benefit ID, beneficiary level, family_record_key, status, start date, entitlement
benefit                          stable platform UUID per benefit_record_key; never deleted or reused
canonical_benefit_disbursement   one per (benefit, date, reference); lineage to the observation that reported it
canonical_benefit_current (view) the latest observation per resident record, when it names a benefit
```

- `benefit_record_key = stateCode|sourceSystem|sourceEntity|schemeId|sourceBenefitId`: a benefit as one source
  names it. It uses the platform scheme ID, so a source re-categorising a ration card (two source codes, one scheme)
  keeps the same benefit.
- Benefit observation runs in canonical processing right after the Family observation, for every stored or
  unchanged Resident delivery, so REST, onboarding `MAPPED` and the e2e scripts need no extra step.
- Re-processing a raw record under the same pack version writes nothing; a new delivery writes a new observation, so
  status and amount changes are history. Disbursements accumulate across deliveries and are written once.
- The latest observation per resident record is what the source currently says. A re-delivery that names no benefit
  (or another one) ends the resident's link to the old benefit; a benefit no current observation names is **ended**.

Benefits are source facts, not matched entities: two states paying the same person are two benefits, which is
exactly what the duplicate report must see. There is no benefit survivorship; a benefit's current attributes come
from the latest current observation that names it.

## 4. Linking to Golden Residents and Golden Families

Links are resolved when read, from the current Golden state, so they follow every Golden build, merge, split and
stewardship decision without a benefit build step:

- **Resident beneficiaries** of a benefit: the residents whose current observation names it, through
  `golden_resident_membership` (current row per source record key) to their Golden Resident.
- **Household** of a FAMILY-level benefit: `golden_family` by the benefit's `family_record_key`.
- A Golden Resident's benefits: the benefits it holds directly, plus the FAMILY-level benefits of every household it
  currently belongs to. Each benefit is listed once, with `linkedVia` = `RESIDENT`, `FAMILY` or both.

## 5. Duplicate benefits report

A resident should hold at most one current, `ACTIVE` benefit per **exclusivity group** (a scheme's
`exclusivityGroup`, by default its own ID). The report lists every active Golden Resident who holds two or more
distinct such benefits in one group, directly or through a household, with the benefits (state, source, scheme,
source benefit ID, department, lineage). Same scheme from two states, or two schemes of one group (old age and
disability pension), are both found. `SUSPENDED` and `CLOSED` benefits and ended benefits are ignored.

## 6. Eligibility (minimal, declarative)

A RESIDENT-level scheme may declare `eligibility` with `minAgeYears`, `maxAgeYears` (age in whole years on the
report's `asOf` date) and `genders`. The eligible-but-not-enrolled report evaluates those criteria on every active
Golden Resident's current values and lists the eligible ones who hold no current `ACTIVE` benefit in the scheme's
exclusivity group (someone on a disability pension is not "missing" an old age pension). Residents whose
date of birth or gender the rule needs is unknown are counted as `undetermined`, not listed. Income, caste,
residence, document and household criteria, eligibility of FAMILY-level schemes and a rules engine are deferred.

## 7. API

| Endpoint | Returns |
| --- | --- |
| `GET /api/v1/schemes` | The catalogue: version, departments, schemes with defaults, group and eligibility |
| `GET /api/v1/benefits` | Paged benefits; filters `state`, `schemeId`, `department`, `status` (ACTIVE, SUSPENDED, CLOSED, ENDED, ALL), `goldenResidentId` |
| `GET /api/v1/benefits/{id}` | One benefit: scheme, current attributes, resident beneficiaries and household with lineage, observations, disbursements |
| `GET /api/v1/golden-residents/{id}/benefits` | Resident 360 benefits: every benefit across departments and states, with links and last disbursement |
| `GET /api/v1/reports/duplicate-benefits` | Golden Residents holding more than one active benefit in an exclusivity group |
| `GET /api/v1/reports/eligible-not-enrolled` | Per scheme with eligibility (`schemeId` optional, `asOf` default today): eligible, enrolled, undetermined counts and the eligible residents not enrolled |

There are no screens yet: the M12 UI is unfinished, so Resident 360 benefits and the reports are delivered as read
APIs; their screens are deferred to the UI milestone.

## 8. Sample data

| Pack | Source | Benefit columns | Benefits |
| --- | --- | --- | --- |
| state-1 | `pds` | card type (`PHH`, `AAY` → `PDS-RATION`), card status, issue date, monthly grain, last lifting | R1001 (Ram Singh, Sita Singh), R1002 (Ramesh Kumar) |
| state-2 | `pension` | pension type (`VRIDHA` → `OLD-AGE-PENSION`), order number, status, amount, last payment | PPO-5001 (Ram Singh), PPO-5002 (Anita Devi) |
| state-3 | `beneficiaries` | welfare scheme (`OAP` → `OLD-AGE-PENSION`), welfare ID, status, amount, last payment | OAP-9001 (Ram Singh); Sunita Devi has none |

Three states give 7 benefit observations (6 naming a benefit), 5 benefits and 5 disbursements (Ram and Sita report
the same R1001 lifting once). Ram Singh's one Golden Resident sees the PDS ration of household R1001 (state 1) and
old age pensions from states 2 and 3: the **seeded duplicate**, the only finding of the duplicate report. Eligible
but not enrolled for the old age pension on 2026-10-25: Ramesh Kumar (51). State 4 declares no Benefit rules, so
adding it changes no benefit count.

## 9. Limitations

- One benefit per source row; sources that list several benefits per person need one row per benefit.
- No benefit survivorship, benefit history events or benefit stewardship; history is the observation log.
- Eligibility covers age and gender only (ADR-0012); there is no means test, document check or rules engine.
- Reports are computed on request over all current data (fine at MVP size) and are not paged.
- No screens until the operational UI lands.
