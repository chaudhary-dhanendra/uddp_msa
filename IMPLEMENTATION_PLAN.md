# Implementation Plan

## Completed baseline

1. **M1 Foundation** — Java/Spring Boot foundation, State Pack loading, canonical contracts, health and State Pack APIs, Docker Compose PostgreSQL, and Flyway foundation.
2. **M2 Generic ingestion** — Raw/Landing storage, batch metadata, generic file ingestion, and State Pack source parameter resolution.
3. **M3 Semantic mapping** — declarative mapping, conversion, crosswalks, configuration validation, and canonical processing-run storage.
4. **M4 Canonical layer** — canonical Resident persistence, source references, lineage, replay boundary, and validation reporting.
5. **M5 Entity resolution** — Splink Resident matching, explainable decisions, configurable decision bands, and match clusters.
6. **M6 Golden Resident** — Golden Resident construction, field provenance, and deterministic survivorship.
7. **M7 Config wiring** — State Pack validation rules, matching thresholds (`AUTO_MATCH` / `POSSIBLE_MATCH` / `NO_MATCH`), and survivorship `fieldPriority`, with V7 migration support.
8. **M8 Reproducible build** — one Flyway path, disposable PostgreSQL/Testcontainers proofs, reproducible Java and Python tests, and one-command end-to-end tooling.

## Remaining roadmap

9. **M9 Data Quality Framework and Cross-State Survivorship**
10. **M10 State 4 Portability Proof and Onboarding Orchestration**
11. **M11 Golden Record Lifecycle and Incremental Processing** — complete, see docs/milestones/M11_ACCEPTANCE.md
12. **M12 Read APIs and Operational UI**
13. **M13 Onboarding Studio**
14. **M14 Stewardship and Manual Review**
15. **M15 Family Domain**
16. **M16 Scheme and Benefit Domains**
17. **M17 Security and Privacy**
18. **M18 Matching Productionization and Production Readiness**

This is the agreed forward-roadmap numbering for M12 planning. Historical M1–M11 acceptance evidence retains
its original milestone labels and is not retroactively renumbered.

Every future milestone must preserve the configuration-driven architecture and finish with automated tests and an explicit review.

