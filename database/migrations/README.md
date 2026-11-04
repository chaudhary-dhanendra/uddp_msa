# Migrations moved

Since M8 all migrations (V1 onwards) live on the single Flyway path
`common-core/src/main/resources/db/migration/`. Common Core applies them at startup, tests apply them to
disposable containers, and `scripts/db-migrate.ps1` runs them against the `UDDP_POSTGRES_*` database.
