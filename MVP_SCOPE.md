# MVP Scope

## In scope for the eventual MVP

- Generic Apache Hop ingestion by source type
- Immutable Raw/Landing storage and replay
- State Pack validation/loading
- Mapping, conversion, crosswalk, validation, and canonical contracts
- Resident/Family-focused registry, lineage, configurable survivorship
- Explainable Splink-based resident matching
- REST API, simple onboarding/demo UI, State 1 and State 2 samples
- Automated portability test

## Milestone 1

Java 21/Spring Boot foundation, health and State Pack APIs, canonical Java domain models, and generic State Pack validation/loading are in scope. Raw persistence, mapping execution, replay, matching, registry, Hop execution, UI, and production operations remain out of scope.

## Excluded from initial MVP

Production security hardening, real Aadhaar/Aadhaar Vault, complete DPDP compliance, HA, Kubernetes, Kafka, CDC, OpenMetadata, Keycloak, OpenG2P/OpenSPP, complex AI mapping, production monitoring, and scale testing.

