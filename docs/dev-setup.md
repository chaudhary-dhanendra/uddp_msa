# Developer setup

## Prerequisites

| Tool | Used for |
| --- | --- |
| Java 21, Maven 3.9+ | Common Core build and tests |
| Docker Desktop (running) | Testcontainers in `mvn test` and pytest, plus the e2e throwaway database |
| Python 3.12 + `pip install -r services/identity-service/matching/requirements-dev.txt` | Splink matching and its tests |
| Apache Hop 2.19 with `HOP_HOME` set | Real ingestion in the e2e scripts only |

PostgreSQL settings come only from the `UDDP_POSTGRES_*` variables (see `CLAUDE.md`). Tests never read
them: Java and Python tests start their own PostgreSQL 16 containers.

## `mvn spring-boot:run` fails with "PKIX path building failed"

**Symptom.** `mvn spring-boot:run` (or any goal that needs a new artifact) fails with
`Plugin org.springframework.boot:spring-boot-maven-plugin:3.5.6 or one of its dependencies could not be
resolved ... (certificate_unknown) PKIX path building failed`. After a failure Maven caches it
("was cached in the local repository"), so retries keep failing without contacting the server.

**Cause.** This isn't a proxy or `settings.xml` problem: on the affected machine there is no
`~/.m2/settings.xml` and no proxy variables. Something on the PC (typically antivirus HTTPS scanning)
re-signs HTTPS traffic with a local root certificate. Windows trusts that certificate, but Java's own
`cacerts` truststore does not, so Maven, which runs on Java, rejects `repo.maven.apache.org`.

**Fix.** Make Maven's JVM use the Windows certificate store. Set this once as a user variable, then open a
new terminal:

```powershell
[Environment]::SetEnvironmentVariable('MAVEN_OPTS', '-Djavax.net.ssl.trustStoreType=Windows-ROOT', 'User')
```

Run the next build once with `-U` to drop the cached failures:

```powershell
cd common-core
mvn -U spring-boot:run
```

Verified on 2026-10-24: with that option the missing `spring-boot-maven-plugin` dependencies
downloaded, and `mvn spring-boot:run` started the app (`/health` returned `UP`). The option is
Windows-only, which is why it isn't committed to `.mvn/jvm.config`: that would break Linux CI.

On startup the app applies Flyway migrations to the `UDDP_POSTGRES_*` database. Add
`-Dspring-boot.run.arguments=--uddp.database.migrate-on-startup=false` to start without migrating.

## One-command end to end

```powershell
$env:HOP_HOME = 'D:\tools\apache-hop-2.19.0'   # or set it as a user variable
scripts\reset-and-run-e2e.ps1
```

```bash
HOP_HOME=/opt/hop scripts/reset-and-run-e2e.sh
```

Both scripts start a throwaway `postgres:16-alpine` container on a random localhost port with a random
password, run Flyway, ingest every State Pack file source with the same generic Hop pipeline
(`hop/pipelines/generic-file-ingest.hpl`), run canonical processing through the Common Core API, Splink
matching and the Golden build, then print counts and assert raw=7, canonical=7, golden=5 and AUTO_MATCH=3. The
container is removed afterwards (`-KeepDatabase` / `KEEP_DATABASE=1` keeps it). Your `UDDP_POSTGRES_*`
database is never touched.

The repository must be registered as a Hop project. The scripts find it in `hop-config.json`, or you can
name it with `UDDP_HOP_PROJECT`. Hop reads the connection settings from Java system properties, which
the scripts pass in a temporary JVM argument file (`JDK_JAVA_OPTIONS=@file`), so they never appear on
the command line that `hop-run` echoes.

Hop 2.19 logs a non-fatal `rowsBinaryGzipBase64Encoded` execution-audit error after each pipeline. This is
a known Hop issue, described in `docs/etl-design.md`. The pipeline still exits 0.

## M10 State 4 end to end

```powershell
scripts
eset-and-run-m10-e2e.ps1
```

Runs the same script with the `m10-state-packs/state-4` pack added to the M9 packs in a temporary directory,
and asserts raw=9, canonical=9, AUTO_MATCH=6 and golden=6. See `docs/m10-portability.md`.

## M9 baseline

M1–M9 are complete. The reproducible-build baseline is the current Resident-domain implementation;
M9 adds data-quality and platform survivorship. A successful e2e run reports
seven canonical Residents, five Golden Residents, and three `AUTO_MATCH` pairs.
