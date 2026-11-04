# M12 baseline (before any M12 change)

Recorded on 2026-10-25 on `feature/m12-operational-ui`, branched from `master` at `51fbb08` (the M11 tip).
`master` was a strict ancestor of `feature/m11-golden-lifecycle`, so it was fast-forwarded to the M11 tip first
(the same choice as at M11, where `master` was fast-forwarded to M10).

| Check | Command | Result |
| --- | --- | --- |
| Java tests | `cd common-core && mvn test` | 80 tests, 0 failures, 0 errors, 0 skipped |
| Python tests | `python -m pytest matching` | 16 passed |
| 3-state e2e | `scripts/reset-and-run-e2e.ps1` | PASSED: raw=7 canonical=7 AUTO_MATCH=3 golden=5 |
| State 4 e2e | `scripts/reset-and-run-m10-e2e.ps1` | PASSED: raw=9 canonical=9 AUTO_MATCH=6 golden=6 |
| Incremental e2e | `scripts/reset-and-run-incremental-e2e.ps1` | PASSED: baseline 7/7/3/5, then raw=9 canonical=9 golden=6 AUTO_MATCH=3 (incremental run), Ram Singh Golden ID unchanged |

Tooling: Node.js v22.21.1, npm 10.9.4, Java 21.0.12 (Temurin), Maven 3.9.16, Python 3.12.7, Docker 28.3.2.

Portability guard: re-baselined on the tag `m11-golden-lifecycle` (`51fbb08`) with an empty engine-change list, as
ADR-0005 planned. `web/onboarding-ui/` contained only `.gitkeep`.
