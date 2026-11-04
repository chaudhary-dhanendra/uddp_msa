# ADR-0008: State Packs are read through StatePackQueryService

Status: accepted (M12).

## Context
M13 adds an Onboarding Studio with State Packs stored in a DB-backed, versioned registry (drafts, maker-checker
approval, publish). M12's State Pack viewer and every state filter must not have to change when that happens.

## Decision
- Controllers and read services depend only on the interface `gov.uddp.core.read.statepack.StatePackQueryService`
  (`list()`, `find(packIdOrStateCode)`, `stateCode(...)`) and its storage-neutral models in `StatePackModels`
  (`StatePackListItem`, `StatePackView` with sources, mapping rules, crosswalk entries, validation, matching and
  survivorship rules, and registered versions).
- The operational query service treats `state_pack_lifecycle` as the state-discovery authority. Repository packs are
  fixtures only: a directory is invisible to the UI and overview until its version is registered and `ACTIVE` in
  PostgreSQL. This keeps a newly-created database at zero configured states even when fixture packs are present.
- The API (`/api/v1/states`) and the UI see only these models, so M13 can provide a registry-backed bean (marked
  `@Primary`, or replacing this one) without changing controllers, the OpenAPI contract or the UI.

## Consequences
The viewer is read-only: lifecycle transitions stay on the existing onboarding API and are not exposed in the UI.
