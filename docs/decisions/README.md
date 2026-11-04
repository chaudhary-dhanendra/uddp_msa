# Architecture decision records

Short records of design choices. Each states the context, the decision and its consequences.

| ADR | Decision |
| --- | --- |
| [0001](0001-golden-id-carry-forward.md) | Golden ID carry-forward, merge and split rules |
| [0002](0002-golden-identity-table-and-founding-snapshot.md) | `golden_resident` is the stable identity; its V6 columns are the founding snapshot |
| [0003](0003-source-record-key-and-canonical-versions.md) | Source record key (`recordKeyFields`) and append-only canonical versions |
| [0004](0004-incremental-matching-and-build.md) | Incremental matching and Golden build |
| [0005](0005-portability-guard-baseline.md) | Portability guard re-baselined on M10 with an explicit M11 engine-change list |
| [0006](0006-history-updates-and-retired-id-api.md) | The only updates to history rows; retired IDs resolve through the API |
| [0007](0007-typed-read-api-v1.md) | A typed, paged read API under `/api/v1`, documented with springdoc OpenAPI |
| [0008](0008-state-pack-query-service.md) | State Packs are read through `StatePackQueryService` (M13 registry swaps in) |
| [0009](0009-family-domain.md) | Families are observed by the canonical layer; a Golden Family is one source household |
| [0010](0010-stewardship-constraints.md) | Stewardship decisions are append-only link constraints applied at Golden build time |
| [0011](0011-scheme-catalogue-and-benefit-model.md) | A platform scheme catalogue; benefits are source facts linked to Golden identities when read |
| [0012](0012-minimal-declarative-eligibility.md) | Eligibility is minimal, declarative and evaluated only for the eligible-but-not-enrolled report |
| [0013](0013-child-process-database-settings-and-redaction.md) | Database settings reach Hop through a deleted temporary argument file; stored errors are redacted |
| [0014](0014-setup-mode-and-local-settings-store.md) | Setup mode and an encrypted local settings store; environment variables still win |
| [0015](0015-onboarding-studio.md) | The Onboarding Studio generates ordinary State Packs from drafts, with generic suggestions |
| [0016](0016-database-table-sources.md) | Database-table sources use one generic Hop template and named connections |
