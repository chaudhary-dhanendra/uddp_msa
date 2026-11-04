# ADR-0014: Setup mode and an encrypted local settings store; environment variables still win

Status: accepted (Onboarding Studio, Part 1).

## Context
The stakeholder demo starts from an empty platform and configures the database from the UI. Until now the backend
read its database only from `UDDP_POSTGRES_*` environment variables and could not start usefully without them.
Production must keep the environment-only path, and no password may be stored in plain text, returned by the API or
committed.

## Decision
- **Active source.** `setup/PlatformDatabaseSettings` resolves, in order: complete `UDDP_POSTGRES_*` environment
  variables (`ENVIRONMENT`, the production path), then the settings saved from the Setup screen (`LOCAL_STORE`), else
  `NONE`. The application's `PostgresConnectionFactory` looks settings up on every use, so applying new settings takes
  effect without a restart; Hop and Python receive the same settings through the ADR-0013 mechanisms.
- **Setup mode.** With no source, `SetupModeFilter` answers 503 `SETUP_REQUIRED` for every `/api/**` request except
  `/api/setup/**`; `/health` reports `mode: SETUP|READY` and the source. If saved settings cannot be migrated at
  startup the backend still starts, in setup mode, and the Setup screen shows why (environment settings fail fast).
- **Store.** `~/.uddp/uddp.properties` (`uddp.settings-directory`), outside the repository. The password is encrypted
  with AES-256-GCM under a random 256-bit key in `~/.uddp/uddp.key`, created on first use with owner-only POSIX
  permissions where supported; on Windows the per-user profile ACL protects it. We chose a key file over Windows
  DPAPI because it needs no native bindings (JNA) or PowerShell round trips, behaves the same on every OS and in
  tests, and keeps the secret out of the repository just as well for a single-user demo machine. The key and the
  properties file live together, so the protection is the OS account, not the cipher alone; that is acceptable for
  a local demo store and is why production keeps using environment variables (or a secret manager behind them).
- **API.** `/api/setup/status`, `/test-connection`, `/apply` (optionally creates the database through the
  `postgres` maintenance database, runs Flyway, saves, reloads the pack registry) and `/reset-demo`. No response
  contains a password; a blank password on apply keeps the saved one for the same host, port and user. Apply is
  refused (409) while environment settings are active or an onboarding run is in progress.
- **Reset.** Only for a database whose name ends with `_demo`, confirmed by typing that exact name: drops and
  recreates the `public` schema, migrates it again and removes Studio-generated packs, drafts, uploads and named
  source connections.

## Consequences
Tests set `uddp.settings-directory` and the Studio folders to temporary locations and never read `~/.uddp`.
`SetupApiTest` proves setup mode, apply without restart, encrypted storage, masked responses and the reset guards.
