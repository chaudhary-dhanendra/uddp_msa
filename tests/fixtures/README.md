# Test fixtures

Fixtures used by automated tests only. `sample-data/` stays the 7-resident demo (7 canonical -> 5 golden).

- `canonical/sample-residents.json` - the matching-relevant fields of the 7 canonical Residents that
  Common Core produces from `sample-data/`. The Python matching integration tests seed a disposable
  database from it; `CanonicalFixtureContractTest` (Java) fails if Common Core's output drifts from it.
- `canonical/borderline-residents.json` - one pair agreeing on name, DOB and gender but not mobile or
  district; `resident-match-v1` scores it about 0.91, so it must be stored as POSSIBLE_MATCH and not cluster.
- `state-packs/fx-alpha`, `state-packs/fx-beta` with `source-files/` - fixture State Packs (normal pack layout):
  - conflict: "Ram Singh" (FXA) and "Ram Singh Yadav" (FXB) differ on name, mobile and district.
    fx-alpha's fieldPriority claims mobile, fx-beta's claims district, both claim name, so the name tie
    falls back to canonical-ID order (`ConflictFixtureTest`).
  - bad data in `fx-alpha/registry.csv`: blank name, unknown gender code `Q`, invalid date `45-13-1985`,
    gender `X` -> OTHER rejected by an error-severity allowedValues rule (all recorded and skipped), and a
    blank mobile breaking only a warning-severity rule (kept, warning stored) (`BadDataFixtureTest`).
- `benefit-packs/fx-welfare` with `source-files/fx-welfare/` (M15) - a welfare register with one benefit per row:
  Asha Rao holds an old age pension (W1) and a disability pension (W2) in one exclusivity group; Esha Nair's PDS
  card is suspended; Farid Khan names no benefit; and four rows are Benefit errors that never reject the Resident:
  an unmapped scheme code (W3), a scheme outside the catalogue (W4), a household benefit without a household (W5)
  and a non-numeric amount (W8) (`CanonicalBenefitProcessingTest`, `BenefitReportTest`).
- `lgd/lgd-snapshot-v1.csv` (Track F) - a small fictional LGD snapshot (one state, two districts, sub-districts, blocks, villages, a local body and ward) for the geography tests; never real geography.
