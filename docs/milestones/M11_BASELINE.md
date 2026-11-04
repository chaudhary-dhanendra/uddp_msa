# M11 baseline (Part 0)

Recorded on 2026-10-25 on `feature/m11-golden-lifecycle` at `218f19a` (M10 complete; `master` fast-forwarded to it),
before any M11 change.

| Suite | Result |
| --- | --- |
| Java `mvn test` (common-core) | 61 run / 61 passed / 0 failed / 0 skipped |
| Python `python -m pytest matching` | 13 passed |

3-state e2e, `scripts/reset-and-run-e2e.ps1`:

```
E2E counts
  raw_record          7
  canonical_resident  7   (processing errors: 0)
  AUTO_MATCH pairs    3   (POSSIBLE_MATCH: 0)
  golden_resident     5   (run 60f4d1cc-6fa9-4d9c-b54d-46b38bb97b41, policy resident-survivorship-v3)
E2E PASSED: raw=7 canonical=7 golden=5 AUTO_MATCH=3
```

State 4 e2e, `scripts/reset-and-run-m10-e2e.ps1`:

```
E2E counts
  raw_record          9
  canonical_resident  9   (processing errors: 0)
  AUTO_MATCH pairs    6   (POSSIBLE_MATCH: 0)
  golden_resident     6   (run 0d92c47d-14c9-40be-b7bb-2ad3d49e78b7, policy resident-survivorship-v3)
E2E PASSED: raw=9 canonical=9 golden=6 AUTO_MATCH=6
```

Local `uddp` DB (read-only count): Flyway V9; 7 raw, 7 canonical, 1 matching run, 5 golden Residents (one
build, `resident-survivorship-v2`).
