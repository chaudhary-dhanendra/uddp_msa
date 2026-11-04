# ADR-0009: Families are observed by the canonical layer; a Golden Family is one source household

Status: accepted (M14).

## Context
State Packs have declared `Family:` mapping rules since M1, but only Residents were mapped. Sources in the MVP are
resident-level files that carry a household identifier on each row (ration number, family reference). A Family
must become a first-class entity with lineage and a stable platform ID, without state-specific code and without
changing Resident matching or survivorship.

## Decision
1. Canonical processing applies a pack's Family rules to every raw record whose Resident is stored or found
   unchanged, writing an append-only *observation* (the delivery) and one *membership* per household it names.
   Family errors are recorded separately and never reject a Resident.
2. The latest observation per resident source record is the current statement of the source; older ones stay
   as history.
3. A Golden Family is one household as one source names it (`stateCode|sourceSystem|sourceEntity|sourceFamilyId`).
   It is never merged with another source's household automatically; shared members are reported as related
   families.
4. The Golden Family build runs inside the Golden Resident build transaction, after Residents, so members always
   reference current Golden Resident IDs. Versions, memberships with effective dates, and events follow the M11
   pattern (ADR-0006).
5. Relationship types are a closed platform list; packs map source codes onto it with crosswalks.

## Consequences
Families need no new onboarding stage, endpoint call or script step. Cross-source household matching, household
survivorship and manual household stewardship remain open and can be added on top of the same tables.
