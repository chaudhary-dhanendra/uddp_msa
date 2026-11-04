# ADR-0013: Database settings reach Hop through a deleted temporary argument file; stored errors are redacted

Status: accepted (Onboarding Studio, Part 0).

## Context
`ProcessHopRunner` passed the database settings to Hop as `-DUDDP_POSTGRES_*=...` in `JDK_JAVA_OPTIONS`. The JVM
prints `JDK_JAVA_OPTIONS` when it starts ("Picked up JDK_JAVA_OPTIONS: ..."), Hop's output becomes the INGESTED
stage's error, and that error is stored in `onboarding_run.failure_message`, `onboarding_stage_execution.error_message`
and `ingestion_batch.error_message` and returned by the API. A failed run therefore stored and returned the password.
The source and pipeline paths were also relative to the backend's working directory while Hop runs from `HOP_HOME`.

## Decision
- `database/ChildProcessDatabaseSettings` writes the settings to a temporary JVM argument file (owner-only where the
  file system supports it) and sets `JDK_JAVA_OPTIONS=@<file>`, so the JVM prints only the file name. The file is
  deleted as soon as Hop exits (try-with-resources). This is the mechanism the e2e scripts already used.
- Python matching receives `UDDP_POSTGRES_*` and `UDDP_STATE_PACK_DIRECTORY` in its environment; nothing is put on a
  command line.
- `security/SecretRedactor` removes every known secret value (the active password) and any `password=...` /
  `password: ...` / `-D..._PASSWORD=...` assignment from Hop and Python output and from every failure message
  `OnboardingService` stores or returns.
- `ProcessHopRunner` passes absolute source and pipeline paths, resolved against `uddp.repository-root` (default
  `..`, the repository root when the backend runs from `common-core/`), and uses `hop-run.bat` on Windows and
  `hop-run.sh` elsewhere.

## Consequences
`ProcessHopRunnerTest` runs the real process boundary against a stand-in `hop-run` that prints the argument file
and a `password=` line, and `OnboardingSecretRedactionTest` proves no stored or returned failure text of a failed
run contains the password. `scripts/reset-and-run-onboarding-api-e2e.ps1` proves `/api/onboarding` with the real
Hop and Splink. A short password is still redacted wherever it occurs, which can mask unrelated text that happens
to contain it; that is the safe direction.
