"""Disposable PostgreSQL 16 databases for matching integration tests (testcontainers-python).

The schema comes from the same Flyway migration files Common Core applies; tests never read the
developer's UDDP_POSTGRES_* settings.  Integration tests are skipped only when Docker is unreachable.
"""
import json
import re
import uuid
from pathlib import Path

import psycopg
import pytest

REPOSITORY_ROOT = Path(__file__).resolve().parent.parent
MIGRATIONS = REPOSITORY_ROOT / "services" / "api-stewardship-service" / "src" / "main" / "resources" / "db" / "migration"
FIXTURES = REPOSITORY_ROOT / "tests" / "fixtures"
CONNECTION_VARIABLES = ("UDDP_POSTGRES_URL", "UDDP_POSTGRES_HOST", "UDDP_POSTGRES_PORT", "UDDP_POSTGRES_DB",
                        "UDDP_POSTGRES_USER", "UDDP_POSTGRES_PASSWORD")


def pytest_configure(config):
    config.addinivalue_line("markers", "integration: needs Docker for a disposable PostgreSQL container")


def migration_files():
    """V<version>__*.sql in numeric version order, as Flyway applies them."""
    files = [(int(re.match(r"V(\d+)__", path.name).group(1)), path) for path in MIGRATIONS.glob("V*__*.sql")]
    return [path for _, path in sorted(files)]


@pytest.fixture(scope="session")
def postgres_container():
    try:
        try:
            from testcontainers.community.postgres import PostgresContainer
        except ImportError:  # testcontainers < 4.10
            from testcontainers.postgres import PostgresContainer
        container = PostgresContainer("postgres:16-alpine", driver=None)
        container.start()
    except Exception as error:  # Docker is not installed or not running
        pytest.skip(f"Docker is not reachable for Testcontainers: {error}")
    yield container
    container.stop()


@pytest.fixture
def migrated_database(postgres_container, monkeypatch):
    """A fresh, migrated database; UDDP_POSTGRES_* point at it for this test only."""
    name = "uddp_test_" + uuid.uuid4().hex
    admin = {
        "host": postgres_container.get_container_host_ip(),
        "port": postgres_container.get_exposed_port(5432),
        "user": postgres_container.username,
        "password": postgres_container.password,
    }
    with psycopg.connect(dbname=postgres_container.dbname, autocommit=True, **admin) as conn:
        conn.execute(f"create database {name}")
    settings = dict(admin, dbname=name)
    with psycopg.connect(**settings) as conn:
        for path in migration_files():
            conn.execute(path.read_text(encoding="utf-8"))
    for variable in CONNECTION_VARIABLES:
        monkeypatch.delenv(variable, raising=False)
    monkeypatch.setenv("UDDP_POSTGRES_HOST", str(settings["host"]))
    monkeypatch.setenv("UDDP_POSTGRES_PORT", str(settings["port"]))
    monkeypatch.setenv("UDDP_POSTGRES_DB", name)
    monkeypatch.setenv("UDDP_POSTGRES_USER", settings["user"])
    monkeypatch.setenv("UDDP_POSTGRES_PASSWORD", settings["password"])
    return settings


def seed_canonical_residents(settings, residents):
    """Insert canonical Residents with the Raw/batch lineage rows they reference; returns their IDs in order."""
    ids = []
    with psycopg.connect(**settings) as conn:
        batches = {}
        for entry in residents:
            key = (entry["stateCode"], entry["sourceSystem"])
            if key not in batches:
                batches[key] = uuid.uuid4()
                conn.execute("insert into ingestion_batch(batch_id,state_code,source_system,source_type,state_pack_version,"
                             "pipeline_template,status) values(%s,%s,%s,'file','fixture','generic-file-ingest.hpl','COMPLETED')",
                             (batches[key], *key))
            raw_id = conn.execute(
                "insert into raw_record(batch_id,state_code,source_system,raw_payload,source_metadata,state_pack_version,"
                "pipeline_template) values(%s,%s,%s,'{}','{}','fixture','generic-file-ingest.hpl') returning raw_record_id",
                (batches[key], *key)).fetchone()[0]
            canonical_id = conn.execute(
                "insert into canonical_resident(raw_record_id,batch_id,state_code,source_system,source_entity,"
                "state_pack_version,canonical_payload) values(%s,%s,%s,%s,%s,'fixture',%s::jsonb)"
                " returning canonical_resident_id",
                (raw_id, batches[key], *key, entry["sourceSystem"], json.dumps(entry["resident"]))).fetchone()[0]
            ids.append(str(canonical_id))
    return ids


def load_fixture(relative):
    return json.loads((FIXTURES / relative).read_text(encoding="utf-8"))
