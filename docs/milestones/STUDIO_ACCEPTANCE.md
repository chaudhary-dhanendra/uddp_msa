# Onboarding Studio acceptance report

Branch `feature/onboarding-studio` (from the M12 tip, which is `master` = `m15-scheme-benefit` plus the M12 UI commits).
Design choices: ADR-0013 (no-echo settings, redaction), ADR-0014 (setup mode, encrypted store), ADR-0015 (Studio),
ADR-0016 (database-table sources). No migration was added: V1-V13 are unchanged.

## Results (2026-10-26)

| Check | Result |
| --- | --- |
| Java `mvn test` (Testcontainers) | 164 tests, 0 failures, 0 errors, 0 skipped |
| Python `pytest matching` | 16 passed |
| UI `npm test` / `typecheck` / `lint` / `build` | 9 passed / clean / clean / built |
| `reset-and-run-e2e.ps1` | raw 7, canonical 7, Golden 5, AUTO_MATCH 3, families 6, benefits and stewardship passed |
| `reset-and-run-m10-e2e.ps1` | raw 9, canonical 9, Golden 6, AUTO_MATCH 6, families 8 |
| `reset-and-run-incremental-e2e.ps1` | raw 9, canonical 9, Golden 6, families 8, Ram Singh Golden ID unchanged |
| `reset-and-run-onboarding-api-e2e.ps1` (real Hop + Splink via `/api/onboarding`) | raw 7, canonical 7, Golden 5, no password stored |
| `run-studio-acceptance.ps1` (Playwright, Edge, real Hop + Splink, UI only) | raw 9, canonical 9, AUTO_MATCH 6, Golden 6, families 8, benefits 5, Ram Singh 4 sources / 1 Golden ID, no password stored |
| `StudioEquivalenceTest` (Studio + setup APIs only) | after 1-3: raw 7, canonical 7, AUTO_MATCH 3, Golden 5, families 6, benefits 5; after State 4: raw 9, canonical 9, Golden 6, families 8, Ram Singh 4 sources, same Golden ID |
| Platform code changes per state (Portability evidence, from git) | 0 for each of State 1, 2, 3, 4 |
| `M10PortabilityGuardTest` | 3 tests passed |

The browser journey's screenshots (44, at 1366px) are in `docs/demo/screens/`.

## Corrections a user makes (everything else is accepted as suggested)

- State 1: district `101` -> `100001`, `102` -> `100002`; scheme `PHH`, `AAY` -> `PDS-RATION` (numeric district codes and
  local card codes carry no generic signal).
- State 2: gender `1` -> `MALE`, `2` -> `FEMALE`; status `1` -> `ACTIVE`; scheme `VRIDHA` -> `OLD-AGE-PENSION`.
- State 3: none (`OAP` = initials of Old Age Pension; districts by name; gender by abbreviation).
- State 4: none (gender codes learned from State 2's published crosswalk; `North-Patna`/`South-Gaya` fuzzy-match LGD).

## Studio packs compared with the hand-written packs

Apart from the source `location` (the landed upload instead of `sample-data/...`):

- All four: the Studio adds the platform-default warning rules `mobile` PATTERN and `address.districtLGD`
  REFERENCE_LIST (State 4's hand-written pack has both as errors and no gender rule).
- State 1: no `card_type -> extensions.cardCategory` Benefit extension; `benefit-status` crosswalk lists only `A`
  (the values in the file; the hand-written one also lists `S`, `C`).
- State 2: `benefit-status` lists only `1` (hand-written also `2`, `3`).
- State 3: `scheme` lists only `OAP` (hand-written also `DIVYANG`).
- State 4: `enrollment_channel` is kept as an extension under its own name rather than `enrollmentChannel`; gender
  crosswalk lacks `9 -> UNKNOWN` (no such value in the file); matching names its fields; survivorship ranks the
  source for name, mobile and district (hand-written: empty).

None of these changes the onboarding results above. `StudioEquivalenceTest` regenerates this comparison in
`common-core/target/studio-equivalence.md`.

## Generic engine changes (listed in `STUDIO_GENERIC_CHANGES`)

- `statepack/StatePackLoader.java`: `reload()` and `directory()` so a published pack is loaded without a restart; a
  missing directory is an empty registry; database sources must name a connection and a table or query.
- `statepack/StatePackAssets.java`: optional `table` and `query` on a source.
- `canonical/PostgresConnectionFactory.java`: settings looked up per use (setup mode), process variables for Hop and
  Python, secrets for redaction.
- `hop/pipelines/generic-db-ingest.hpl`: the second generic template (any state).

## Known limitations

- Onboarding runs one at a time in the backend process; a crash mid-stage still leaves the stage RUNNING (as in M10).
- Raw payloads are built by plain concatenation in both Hop templates, so a source value containing a double quote is
  not supported yet.
- The settings key file sits beside the settings file in the user's profile (ADR-0014); production uses environment
  variables.
- Older M12 views (`src/features/simple`) still use untyped `any`; the new Setup, Settings and Studio screens use only
  generated API types.
