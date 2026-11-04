-- The four UDDP service databases (docs/msa-design.md, section 6). Idempotent.
-- Run by the postgres container on first start (docker compose, /docker-entrypoint-initdb.d), or against an existing
-- server as a superuser:  psql -X -h <host> -p <port> -U <superuser> -d postgres -f deploy/postgres/01-msa-databases.sql
\set ON_ERROR_STOP on

SELECT format('CREATE DATABASE %I', d)
FROM unnest(ARRAY['uddp_raw', 'uddp_identity', 'uddp_registry', 'uddp_platform']) AS d
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = d)
\gexec
