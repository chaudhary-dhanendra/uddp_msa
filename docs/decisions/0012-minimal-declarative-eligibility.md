# ADR-0012: Eligibility is minimal, declarative and evaluated only for the eligible-but-not-enrolled report

Status: accepted (M15).

## Context
The roadmap asks whether eligibility rules are in scope for M15. A real eligibility engine (income, caste, residence,
documents, household composition, state-specific thresholds) is a milestone of its own. The eligible-but-not-enrolled
report needs some notion of eligibility, and it must stay configuration, not code.

## Decision
1. A RESIDENT-level catalogue scheme may declare `eligibility` with `minAgeYears`, `maxAgeYears` and `genders`.
   Nothing else is accepted; the catalogue fails to load on an unknown criterion, on eligibility for a FAMILY-level
   scheme, or on an inverted age range.
2. Criteria are evaluated on request, on each active Golden Resident's current survived values, with age in whole
   years on the report's `asOf` date (default: today, UTC). A resident whose needed value is unknown is counted as
   undetermined and not listed.
3. "Enrolled" means holding a current ACTIVE benefit in the scheme's exclusivity group, directly or through a
   household, so a person on a disability pension is not reported as missing the old age pension.
4. Eligibility is never used to accept, reject or change benefits.
5. The sample catalogue's old age pension threshold is 45, because the synthetic residents are 32 to 51; this is
   sample data, not a statement about any real scheme.

## Consequences
The report is small, explainable and state-neutral. A full eligibility engine, per-state thresholds and FAMILY-level
eligibility are deferred and can replace the evaluator behind the same report.
