# M14 baseline (before any M14 change)

Recorded on 2026-10-25 on `feature/m14-family-domain`.

- **Base commit:** `7a2cad1` (`feature/m12-operational-ui`, M12 Part 3 work in progress), after the M11 tag
  `m11-golden-lifecycle` (`51fbb08`). M14 builds on M12's committed `/api/v1` read APIs (Parts 0–2). M12 itself is
  not accepted and is not continued here.
- **Working tree:** uncommitted M12 work (UI feature folders, i18n/vitest config, `ProcessHopRunner` JVM-property
  fix and its test, a roadmap renumbering) is left in place, unstaged and untouched by M14 commits.
- **Early M14 drafts found in the working tree** (a V11 migration and a `FamilyMappingEngine` that did not compile)
  were set aside before measuring and replaced by the M14 design (`docs/family-domain.md`).
- **Local uddp DB:** Flyway at V10; the draft V11 had never been applied.

| Check | Command | Result |
| --- | --- | --- |
| Java tests | `cd common-core && mvn clean test` | 102 tests, 0 failures, 0 errors, 0 skipped |
| Python tests | `python -m pytest matching` | 16 passed, 0 skipped |
| E2E (recorded at M12 Part 0, engines unchanged since) | 3-state / State 4 / incremental | 7/7/3/5; 9/9/6/6; 9/9/3 AUTO_MATCH/6 with Ram Singh's Golden ID unchanged |

The M12 baseline's 80 Java tests plus M12 Parts 1–2 (20) and the working tree's `ProcessHopRunnerTest` (2) give 102.
