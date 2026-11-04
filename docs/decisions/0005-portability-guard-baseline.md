# ADR-0005: Portability guard re-baselined on M10 with an explicit M11 engine-change list

Status: accepted (M11).

## Context
`M10PortabilityGuardTest` fails when a generic engine differs from the M9 tag. M11 is an engine milestone and has
to change survivorship, canonical processing, State Pack loading and matching. The guard's purpose, that
onboarding a state needs configuration only, still holds.

## Decision
Tag the M10 completion commit `m10-onboarding-portability` and compare against it. The engine files M11 changes
are listed by path in the guard (`M11_GENERIC_CHANGES`), and each is justified in
`docs/milestones/M11_ACCEPTANCE.md`. Any other protected change or new protected file still fails, as do a new Hop
pipeline and a state name in production code; those two checks are unchanged.

## Consequences
The list is reviewable in one place. M12 re-baselines on an M11 tag and empties the list.

## M12 update
The guard now compares against the tag `m11-golden-lifecycle` (`51fbb08`) and its list is `M12_GENERIC_CHANGES`,
which is empty: M12 adds read APIs and a UI and changes no generic engine.

## M14 update
M12 has no accepted tag, so the guard keeps comparing against `m11-golden-lifecycle`. M14's generic Family engine
files are listed in `M14_GENERIC_CHANGES` and justified in `docs/milestones/M14_ACCEPTANCE.md`.

## M13 update
M13 was done after M14 and still compares against `m11-golden-lifecycle`. Its engine changes (stewardship constraints
in `GoldenTargetGroups` and the `StewardshipConstraints` interface) are listed in `M13_GENERIC_CHANGES` and justified
in `docs/milestones/M13_ACCEPTANCE.md`. The next accepted milestone should re-baseline on a tag and empty all lists.

## M15 update
M13 is accepted and tagged (`m13-stewardship`, which includes M14), so M15 re-baselines the guard on that tag and
removes the M12, M14 and M13 lists. M15's generic Benefit engine files are listed in `M15_GENERIC_CHANGES` and
justified in `docs/milestones/M15_ACCEPTANCE.md`.

## Onboarding Studio update
M15 is accepted and tagged (`m15-scheme-benefit`), so the Studio re-baselines the guard on that tag and removes the
M15 list. The Studio's generic engine changes (a reloadable State Pack registry, process settings from the connection
factory) are listed in `STUDIO_GENERIC_CHANGES` and justified in `docs/milestones/STUDIO_ACCEPTANCE.md`. The guard
also reads pack IDs from `studio-packs/` when that directory exists.
