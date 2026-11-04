# ADR-0011: A platform scheme catalogue; benefits are source facts linked to Golden identities when read

Status: accepted (M15).

## Context
M15 adds schemes and benefits. States and departments name the same scheme differently (a ration card category, a
pension type code), and the sample sources are resident-level files that already carry each person's ration card or
pension order. The roadmap leaves two choices open: which schemes and departments to model first, and whether
eligibility is in scope (ADR-0012). Benefits must link to Golden Residents and Golden Families, which change with
every Golden build, merge, split and stewardship decision.

## Decision
1. **First schemes.** Model the Public Distribution System ration (Food and Civil Supplies, held by a household) and
   the old age pension (Social Welfare, held by a person), plus a disability pension in the same exclusivity group
   so that duplicates across schemes can be proven. These are the benefits the sample sources already describe
   (state 1 is a PDS register, state 2 a pension register) and the ones the acceptance criteria name.
2. **Catalogue.** One platform catalogue, `platform/schemes/catalogue.yaml`, versioned and validated at startup:
   departments and schemes (`schemeId`, department, benefit type ENTITLEMENT/ASSISTANCE, form IN_KIND/CASH,
   beneficiary level FAMILY/RESIDENT, default unit and frequency, exclusivity group, optional eligibility). It holds
   no state code: a scheme is the same in every state.
3. **Crosswalks in State Packs.** Each pack maps its own scheme codes onto catalogue IDs with an ordinary crosswalk,
   through a declarative `Benefit` entity in its mapping. A code that maps to no catalogue scheme is a Benefit
   processing error, never a Resident rejection.
4. **Canonical benefit observations.** Canonical processing records, per stored or unchanged Resident delivery, the
   benefit that delivery names (append-only, idempotent per raw record and pack version) and any disbursement it
   reports. A benefit gets a stable platform UUID per `stateCode|sourceSystem|sourceEntity|schemeId|sourceBenefitId`
   the first time it is observed; it is never deleted or reused. The latest observation per resident record is the
   source's current statement.
5. **No benefit survivorship or matching.** Two sources paying one person are two benefits; that is what the
   duplicate report needs to see.
6. **Links resolved on read.** Resident beneficiaries resolve through the current Golden Resident membership of their
   source record key; FAMILY-level benefits through the Golden Family of their household key. No benefit table stores
   a Golden ID, so links are never stale and the Golden build is unchanged.
7. **Duplicates** are two or more current ACTIVE benefits of one Golden Resident (directly or through a household) in
   one exclusivity group.

## Consequences
Benefits need no new onboarding stage, build step or script call, and Golden Resident and Family behaviour is
untouched. Benefit history is the observation log (no versions or events). Sources with several benefits per row,
benefit stewardship and benefit-level matching are deferred.
