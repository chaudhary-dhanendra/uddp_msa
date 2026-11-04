# ADR-0015: The Onboarding Studio generates ordinary State Packs from drafts, with generic suggestions

Status: superseded in part by V14 database-backed State Packs.

## Context
The demo onboards States 1-4 from the UI only, starting with zero states, with zero platform code per state. The
platform already has a generic loader, mapping/validation engines, a lifecycle and an onboarding orchestrator.

## Decision
- **Drafts are database rows.** A draft (pack ID, state code, display name, source, mappings, crosswalks, rules and
  publication) is JSONB in `studio_draft`. Uploaded CSV bytes remain in the gitignored Studio workspace's `uploads/`
  landing folder; they are staging data rather than State Pack configuration.
- **Output is a versioned State Pack document.** Publishing still generates and validates the existing YAML-shaped
  model in a temporary directory, then persists the complete reconstructed `LoadedStatePack` JSON document in
  `state_pack_document`. It registers DRAFT -> VALIDATED -> ACTIVE and is available without a restart. A changed
  republish gets the next patch version; the previous ACTIVE version is retired. Publishing is refused while the
  preview of all rows has blocking errors, while a run is in progress, or when matching thresholds disagree with
  another loaded pack.
- **Operational resolution is database-only.** Repository YAML packs are explicit fixture/bootstrap inputs through
  `StatePackLoader.load`/`loadAll`; startup and runtime resolution never discover them automatically.
- **Suggestions are generic.** Targets, keyword groups, value kinds and code sets are platform metadata in
  `platform/studio/mapping-targets.yaml`; default rules are `platform/studio/rule-defaults.yaml`. Signals: column-name
  words, profiled value patterns (dates and their format, mobile numbers, numbers), mappings and crosswalks of
  previously published packs, LGD district names (exact, contained or Levenshtein >= 0.8), scheme catalogue names and
  initials, and abbreviations of platform codes. Benefit detail columns are tied to the scheme column by their shared
  name prefix; a benefit without its own ID column is identified by its household. Values no signal can know (for
  example numeric district codes) are left blank for the user to choose.
- **Preview** runs the existing Resident, Family and Benefit mapping engines and the Resident validator (with platform
  reference data) on rows built exactly like Hop's raw payload, and writes nothing.
- **Onboarding** starts the existing orchestrator (FULL for the first state, INCREMENTAL once active Golden data
  exists) and runs the stages in one background thread so the UI can poll the stage timeline.
- **Portability evidence is computed from git.** A fingerprint of the platform code (`common-core/src/main`,
  `matching/` without tests, `hop/`, `platform/`: the commit plus a SHA-256 of every uncommitted file) is recorded at
  startup and at each publication; a state's platform code changes are the files that differ between the fingerprint
  before its publication and the one at it. The evidence also lists the files that differ from the baseline ref
  (`uddp.studio.baseline-ref`, default `master`) and the latest `M10PortabilityGuardTest` surefire result.

## Consequences
The Studio is a generator and a client of the existing engines; it adds no state-specific branch anywhere. The only
generic engine changes are the reloadable registry and the connection factory's process settings (listed in the
portability guard). Generated packs can differ from the hand-written ones in non-semantic ways (file location,
default warning rules, crosswalk values that do not occur in the data); `StudioEquivalenceTest` reports each
difference and proves the onboarding results are identical.
