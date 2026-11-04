# M15 baseline (before any M15 change)

Recorded on 2026-10-25 on `feature/m15`, branched from the M13 completion commit `90938ce` (tag `m13-stewardship`).
M13 was cut from the M14 tag `m14-family-domain` (`git merge-base --is-ancestor m14-family-domain m13-stewardship`
succeeds), so this tip carries M14 and M13 and is the latest linear state.

| Check | Result |
| --- | --- |
| Java tests (`mvn clean test`) | 132 passed, 0 failed, 0 errors, 0 skipped |
| Python tests (`pytest matching`) | 16 passed |
| 3-state e2e | raw=7 canonical=7 golden=5 AUTO_MATCH=3 families=6, stewardship split/merge smoke passed |
| State 4 e2e | raw=9 canonical=9 golden=6 AUTO_MATCH=6 families=8 |
| Incremental e2e | 9/9, AUTO_MATCH=3, golden=6, families=8, Ram Singh Golden ID unchanged |

The Java count includes the 2 tests of M12's uncommitted `ProcessHopRunnerTest`, which is in the working tree (M12 is
paused and its files are left untouched and unstaged); the M13 report's 132 was measured the same way. The e2e
results are M13's acceptance results (`docs/milestones/M13_ACCEPTANCE.md`) on the same commit. Schemes and benefits
exist only as JSON Schemas (`common-model/scheme.schema.json`, `benefit.schema.json`) and unused Java records.
