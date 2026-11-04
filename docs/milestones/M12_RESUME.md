# M12 resume point

Repository consolidation is complete. `master` is fast-forwarded to `m15-scheme-benefit`; the active branch is
`feature/m12-operational-ui-final`.

Completed commits:

- `c29cd29` — carried the pre-existing M12 WIP forward from the M15 tip.
- `a2223de` — extended operational navigation and basic Families, Benefits, Reports and Stewardship views.
- `f284d59` — added the Prettier dependency and formatter script.

Next step: run `npm run format` in `web/onboarding-ui`, commit the behavior-neutral formatting separately, then
replace the remaining placeholder/generic detail screens with typed API-backed 360, lineage, history, matching and
stewardship action views. Follow with the requested backend/demo work, all baseline/e2e checks, acceptance evidence,
merge and tag.

Current verified UI checks: `npm run typecheck`, `npm run lint`, `npm run build`, and `npm test` passed (4 tests).
The Java `mvn clean test` baseline was started but did not complete within this work window; do not record it as passed.

Continuation attempt on 2026-10-25: `mvn clean test` compiled main and test sources but then made no progress for more
than three minutes while Testcontainers tests were starting, and was interrupted. `npm ci` also left empty package
directories with no executable shims; as a result the formatter, TypeScript, ESLint and Vitest commands cannot now
be run until the locked dependencies are successfully installed. The UI's last known passing result remains the four
Vitest tests recorded above. Commit `9030811` only removes a duplicate Prettier manifest entry.
