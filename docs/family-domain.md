# Family domain (M14)

Family is a first-class, source-observed household entity, not a Resident attribute. It follows the Resident's
layered pattern (Raw → canonical observation → stable Golden identity → read API) and, like the Resident, is driven
only by State Pack YAML: a pack that declares `Family:` mapping rules gets Families, a pack without them gets none.
Nothing in the Family code names a state, source or pack. Decisions are in ADR-0009.

## 1. Configuration

The `Family` entity of a mapping file lists rules with the same shape as Resident rules
(`sourceField`, `target`, optional `crosswalk`, `required`):

| Target | Meaning |
| --- | --- |
| `sourceFamilyIds[]` | A household identifier as the source names it (ration card, family reference). Repeatable: a row may name several households. At least one is needed for the row to belong to a family. |
| `membership.relationshipType` | The resident's role in the household, after an optional crosswalk. Must be one of `HEAD`, `SPOUSE`, `CHILD`, `PARENT`, `SIBLING`, `OTHER`, `UNKNOWN`. Absent → `UNKNOWN`. |
| `extensions.<name>` | Kept on the observation's `family_payload.extensions`. |

```yaml
entities:
  Resident: [ ... ]
  Family:
    - { sourceField: ration_no, target: "sourceFamilyIds[]" }
    - { sourceField: relation_cd, target: membership.relationshipType, crosswalk: relationship }
```

A blank `sourceFamilyIds[]` value means "this delivery names no household for the resident" (it is not an error
unless the rule is `required`). An unknown target, a missing crosswalk value or a relationship outside the list is
a Family processing error: it is recorded in `canonical_family_processing_error` and never rejects the Resident.

## 2. Canonical layer (append-only)

```text
raw_record ──> canonical_resident (+ canonical_resident_version)            unchanged Resident flow
          └──> canonical_family_observation   one per raw record processed with Family rules
                 resident_record_key, canonical_resident_id, state_pack_version, mapping_version
                 └─ canonical_family_membership   one per household named by that delivery
                      family_record_key = stateCode|sourceSystem|sourceEntity|sourceFamilyId
                      source_family_id, relationship_type, family_payload
canonical_family_membership_current (view)   memberships of the LATEST observation per resident record
```

- Family processing runs inside canonical processing, right after a Resident row is stored or found unchanged,
  so every path (REST, onboarding `MAPPED`, e2e scripts) observes Families without extra calls.
- The observation points at the resident's source record key (ADR-0003) and at the canonical Resident that
  represents this delivery (the new version, or the unchanged latest one).
- Re-processing the same raw record under the same pack version writes nothing (unique per raw record and pack
  version); a new delivery or a new pack version writes a new observation. Nothing is ever updated.
- The latest observation per resident record is what the source currently says. If a re-delivery names a different
  household, or none, the resident leaves the old household; an observation with no membership rows records that.

## 3. Golden Families

```text
golden_family              stable platform UUID per family_record_key; ACTIVE or RETIRED; never deleted or reused
golden_family_version      payload (members, relationship types, member count), change reasons, effective_from/to
golden_family_membership   resident_record_key -> golden_resident_id, relationship, lineage to the canonical
                           membership, added/removed version, effective_from/to
golden_family_event        CREATED, MEMBER_ADDED, MEMBER_REMOVED, MEMBER_CHANGED, RETIRED
```

A Golden Family build runs at the end of every Golden Resident build, in the same transaction, so Family members
always point at the current Golden Resident IDs:

1. Read `canonical_family_membership_current` and group it by `family_record_key`.
2. Resolve each member's resident record key to its current Golden Resident.
3. For each household: no identity yet → `CREATED` (version 1, `MEMBER_ADDED` per member). Otherwise compare the
   current members `(resident record key → Golden Resident, relationship)`: new key → `MEMBER_ADDED`, missing key →
   `MEMBER_REMOVED`, same key with a different Golden Resident (a Resident merge or split) or relationship →
   `MEMBER_CHANGED`. Any of those writes a new version; otherwise nothing is written.
4. An active household with no current member is `RETIRED` (its last version is closed); it comes back as a new
   version of the same ID if a later delivery names it again.

**Identity rule.** A Golden Family is one source household. Two households from different sources (or states) are
never merged automatically, even when they share members: a person can legitimately belong to a ration-card
household in one scheme and a pension household in another. Households that share a Golden Resident are shown as
*related families* by the read API. Cross-source household matching and manual household merge are deferred
(they need a matching model and the stewardship flow).

Canonical rows and matching evidence are never changed by the Family build; history rows are closed once
(`effective_to`) as in ADR-0006.

## 4. API

| Endpoint | Returns |
| --- | --- |
| `GET /api/v1/families` | Paged Golden Families; filters `state`, `status`, `q` (source family ID), `goldenResidentId` |
| `GET /api/v1/families/{id}` | Current version, members (Golden Resident ID and name, relationship, canonical and raw lineage), related families, version summary |
| `GET /api/v1/families/{id}/history` | Every version with its members, and every event |
| `GET /api/v1/golden-residents/{id}/families` | The Golden Resident's current households |

## 5. Sample data

| Pack | Family rule | Households (members) |
| --- | --- | --- |
| state-1 `pds` | `ration_no` | R1001 (Ram Singh, Sita Singh), R1002 (Ramesh Kumar) |
| state-2 `pension` | `family_ref` | F5001 (Ram Singh), F5002 (Anita Devi) |
| state-3 `beneficiaries` | `family_id` | F9001 (Ram Singh), F9002 (Sunita Devi) |
| state-4 `citizen-register` | `household_token` | H4-RAM (Ram Singh), H4-NEHA (Neha Verma) |

Three states give 6 Golden Families with 7 memberships; adding State 4 gives 8 with 9. Ram Singh's one Golden
Resident belongs to 3 (then 4) households, which are related families of each other.

## 6. Limitations

- Household-level attributes (address, ration category) are observed in `family_payload.extensions` but not
  survived; there is no Family survivorship policy yet.
- Golden Families are rebuilt from all current observations on each Golden build (fine at MVP size).
- No cross-source household matching or manual household merge/split.
