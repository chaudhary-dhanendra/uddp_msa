# M10 State 4 portability proof

State 4 is represented only by `m10-state-packs/state-4/` and `sample-data/state-4/`.
It has different column names and ordering, `YYYY/MM/DD` dates, numeric gender codes,
text district codes, different household identifiers, and extra source attributes.

`scripts/reset-and-run-m10-e2e.ps1` combines the M9 packs and the State 4 pack at runtime (in a temporary
directory) and runs the unchanged generic Hop pipeline, canonical processing, Splink and the Golden build.
It must report exactly 9 Raw, 9 Canonical, 6 `AUTO_MATCH` pairs and 6 Golden Residents: State 4 adds a
fourth "Ram Singh", so the four Ram Singh records form C(4,2) = 6 pairs and one Golden Resident, and
"Neha Verma" is a new person (5 -> 6 Golden).

## Portability guard

`M10PortabilityGuardTest` (part of `mvn test`) fails when:

- any file under the generic engines differs from the `m9-data-quality-survivorship` tag, or a new file is
  added there: `mapping/`, `canonical/`, `validation/` (DQ), `golden/` (survivorship), `statepack/`, the
  Python matching module (`matching/config.py`, `matching/run.py`) and `hop/pipelines/`;
- any Hop pipeline other than `generic-file-ingest.hpl` exists;
- production Java (`common-core/src/main`) or Python (`matching/`, excluding tests) names a configured pack
  ID or state code (read from every pack's `state.yaml`), or a production file name is state-numbered
  (for example `State4Mapper.java`).

New generic orchestration classes (the `onboarding/` package) are deliberately outside the protected
engine set. During M10 close-out the guard was shown to fail on a temporary `State4Mapper`, a temporary
`state-4-ingest.hpl` and a temporary edit to `SurvivorshipEngine`, then all three were reverted.
