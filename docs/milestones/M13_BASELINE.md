# M13 baseline (before any M13 change)

Recorded on 2026-10-25 on `feature/m13-stewardship`, branched from the M14 completion commit `5a3513d` (tag
`m14-family-domain`). M13 is done after M14 at the user's request; it builds on M14's Golden build and M12's
committed `/api/v1` read APIs.

| Check | Result |
| --- | --- |
| Java tests (`mvn test`) | 118 passed, 0 failed, 0 skipped |
| Python tests (`pytest matching`) | 16 passed |
| 3-state e2e | raw=7 canonical=7 golden=5 AUTO_MATCH=3 families=6 |
| State 4 e2e | raw=9 canonical=9 golden=6 AUTO_MATCH=6 families=8 |
| Incremental e2e | 9/9, AUTO_MATCH=3, golden=6, families=8, Ram Singh Golden ID unchanged |

These are the M14 acceptance results (`docs/milestones/M14_ACCEPTANCE.md`), measured minutes earlier on the same
tree. POSSIBLE_MATCH pairs are stored but not reviewable; merges and splits come only from matching runs.
