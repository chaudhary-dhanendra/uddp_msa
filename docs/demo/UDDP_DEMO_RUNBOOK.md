# UDDP stakeholder demo: Onboarding Studio

The story: **build the platform once, configure it for every source.** One deployment serves one state (its State
Profile, in Platform settings); the state's departments own the source systems. Starting from an empty platform,
everything happens in the UI: configure the platform database, then onboard four department sources one by one
(upload, map, crosswalk, rules, preview, publish, onboard, see results) with zero platform code per source.

The demo sources and the departments they stand for (demo labels only; the platform has no department-specific code):

| Source pack | Sample file | Source | Department |
| --- | --- | --- | --- |
| `state-1` (`ST1`) | `pds.csv` | PDS | Food & Civil Supplies |
| `state-2` (`ST2`) | `pension.csv` | Pension | Social Welfare |
| `state-3` (`ST3`) | `beneficiaries.csv` | Welfare beneficiaries | Social Welfare |
| `state-4` (`ST4`) | `citizen-register.csv` | Citizen register | Planning |

The pack IDs and codes keep their historical `state-N`/`STN` values so existing data and scripts stay valid.

Screenshots of every step (1366px) are in [`screens/`](screens/); the file names below refer to them.

## Before the demo (once)

- Java 21, Maven, Node 22 (`npm ci` in `web/onboarding-ui`), Python 3.12 with `matching/requirements.txt`.
- Apache Hop with `HOP_HOME` set and this repository registered as Hop project `uddp-mvp`.
- A PostgreSQL server for the demo database. Either your local server (the Setup screen creates a new database
  `uddp_demo` on it; your `uddp` database is never touched) or a throwaway container:

  ```powershell
  docker run -d --name uddp-demo-db -e POSTGRES_USER=uddp -e POSTGRES_PASSWORD=<choose one> -p 127.0.0.1:55432:5432 postgres:16-alpine
  ```

  Use a database name ending in `_demo`: only such a database can be reset from the UI.
- Sample files: `sample-data/state-1/pds.csv`, `state-2/pension.csv`, `state-3/beneficiaries.csv`,
  `state-4/citizen-register.csv`.

## Start and stop

```powershell
scripts/run-demo.ps1          # builds, starts the backend on 8081 (setup mode) and the UI, prints the URL
scripts/run-demo.ps1 -Stop    # stops both
```

Open `http://127.0.0.1:4173/`. The backend starts **without** your `UDDP_POSTGRES_*` variables (pass
`-UseEnvironment` to keep them). Source Pack configuration is database-backed, so a fresh platform database starts
with zero sources even when repository fixture packs are present. Settings are saved in `%USERPROFILE%\.uddp` with the
password encrypted; logs are in `runtime/`.

## Click by click, and what to say

### 1. Platform database (Setup screen)
- `01-setup-empty`: the platform has nothing: no database, no sources. *"This is a brand-new installation. Nothing is
  configured and there is no state-, department- or source-specific code anywhere."*
- Enter host, port, `uddp_demo`, user and password; tick **Create the database if it does not exist**; click
  **Test connection** (`02-setup-tested`), then **Apply**. *"Apply creates the database and its schema and switches the
  platform on without a restart. The password is encrypted on this machine and never shown again."*
- `03-overview-empty-platform`, `04-studio-zero-states`: the full application, with zero sources.

### 2. PDS — Food & Civil Supplies (steps 1-9 of the wizard: `05`-`13`)
Open **Onboarding Studio → New source**; the Sources list groups every source under its department.
1. **Source details**: pack ID `state-1`, source code `ST1`, name `PDS (Food & Civil Supplies)`.
2. **Upload & profile**: upload `pds.csv`. *"The Studio profiles every column: types, date formats, blanks, distinct
   values."* (Database tables work the same way: choose **Database table**, a named connection and a table.)
3. **Map columns**: every column is pre-filled with a suggested target and its confidence. *"`beneficiary_nm` is the
   name, `dob` a date in DD-MM-YYYY, `ration_no` the household. Columns nobody maps are kept as extensions, nothing is
   lost."*
4. **Crosswalks**: choose `100001`/`100002` for districts `101`/`102`, and `PDS-RATION` for `PHH` and `AAY`.
   *"Numeric district codes cannot be guessed; a person decides once, and later sources reuse what was decided."*
5. **Rules**: platform defaults (validation, matching thresholds, survivorship), editable.
6. **Preview**: a dry run of the rows through the real engines; nothing is written. Errors would be highlighted and
   would block publishing.
7. **Review & publish**: the generated Source Pack YAML, read-only. **Publish** validates, registers and activates it.
   *"This is the only artefact a source needs: configuration."*
8. **Onboard**: **Start onboarding** and watch the stages: generic Hop ingest, mapping, data quality, Splink
   matching, Golden build.
9. **Done**: links to Overview, Golden Resident 360 and the portability evidence.

### 3. Pension and Welfare beneficiaries — Social Welfare (`14`-`31`)
Same wizard (names `Pension (Social Welfare)` and `Welfare beneficiaries (Social Welfare)`). Pension (`pension.csv`): choose `MALE`/`FEMALE` for gender `1`/`2`, `ACTIVE` for status `1`,
`OLD-AGE-PENSION` for `VRIDHA`. Welfare beneficiaries (`beneficiaries.csv`): accept everything (the Studio already knows the
districts, gender codes and that `OAP` is the Old Age Pension). *"Each source needs fewer decisions than the last."*
Runs after the first one are INCREMENTAL: only the new source's records are matched.

### 4. Citizen register — Planning (`32`-`40`)
Name `Citizen register (Planning)`. Upload `citizen-register.csv`: unfamiliar column names (`resident_label`, `born_on`, `identity_marker`,
`district_text`). Accept every suggestion: the gender codes were learned from the Pension source and `North-Patna` matches the
LGD district Patna. *"A fourth source, a new schema, zero code and zero corrections."*

### 5. Results
- `41-studio-four-states`: all four sources onboarded, each under its department.
- `42-ram-singh-360`: **Golden Resident 360 → search "Ram Singh"**: one Golden ID with records from PDS, Pension, Welfare
  beneficiaries and the Citizen register, field provenance, households and benefits. *"Three departments, four sources, four schemas, one resident."*
- `43-portability-evidence`: **Portability evidence**: every source is generated files plus the generic Hop
  template, and **0 platform code changes** per source, computed from git, with the portability guard result.
- `44-settings-masked`: **Platform settings**: the connection with the password masked.

Expected numbers after the Citizen register: 9 raw, 9 canonical, 6 Golden Residents, 8 Golden Families, 5 benefits, Ram Singh with
4 sources under one Golden ID (after the first three sources: 7, 7, 5 Golden, 6 families, 5 benefits).

## Reset between rehearsals (from the UI)
**Platform settings → Reset demo database**: type the database name (for example `uddp_demo`) and confirm. It drops
and recreates every UDDP object in that database and removes the Studio's packs, drafts, uploads and source
connections, so the Studio shows zero sources again. It is refused for any database whose name does not end with
`_demo`, and while an onboarding run is in progress.

## Recovery
- **Backend unavailable**: check `runtime/demo-backend.log`; restart with `scripts/run-demo.ps1`.
- **Setup screen after a restart, with a problem message**: the saved database is not reachable (for example the
  container stopped). Start it again and click Apply; the saved password is kept if you leave it blank on Settings.
- **A stage failed**: the timeline shows the redacted reason (no password is ever shown or stored). Fix the cause
  (for example `HOP_HOME`) and start onboarding again.

## Automated rehearsal
`scripts/run-studio-acceptance.ps1` performs this whole journey in Microsoft Edge on a throwaway database (real Hop
and Splink), regenerates the screenshots and asserts the numbers above.
