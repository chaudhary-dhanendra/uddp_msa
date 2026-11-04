# ADR-0004: Incremental matching and Golden build

Status: accepted (M11).

## Decision
- "New or changed" means latest canonical versions that are not a current Golden member.
- `python -m matching.run --incremental` scores only pairs that touch such a record (a Splink blocking rule over
  the same fixed model), stores `matching_run.mode = INCREMENTAL`, and writes cluster rows for the connected
  components that contain such a record. With no Golden members yet it is equivalent to a full run.
- An incremental Golden build starts from the current memberships, detaches members that have a newer canonical
  version, joins nodes by the run's clusters, then applies ADR-0001.
- Onboarding runs get a `processingMode` (`FULL` by default, or `INCREMENTAL`); full runs are unchanged.

## Consequences
Existing pairs are not re-scored incrementally; re-evaluating everyone, for example after a model change, is a
full run.
