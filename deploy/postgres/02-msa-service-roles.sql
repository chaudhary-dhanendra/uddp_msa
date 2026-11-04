-- One login role per service, each owning only its own schema (docs/msa-design.md, section 6).
-- Run after 01-msa-databases.sql, by the postgres container on first start (docker compose) or as a superuser:
--   UDDP_MSA_SERVICE_PASSWORD=... psql -X -h <host> -p <port> -U <superuser> -d postgres -f deploy/postgres/02-msa-service-roles.sql
-- Idempotent: re-running syncs the role passwords and grants.
-- The service password is read from the environment variable UDDP_MSA_SERVICE_PASSWORD; it never appears here.
\set ON_ERROR_STOP on
\getenv svc_password UDDP_MSA_SERVICE_PASSWORD
\if :{?svc_password}
\else
  \echo 'UDDP_MSA_SERVICE_PASSWORD is not set'
  \quit
\endif

SELECT format('CREATE ROLE %I LOGIN', r)
FROM unnest(ARRAY['uddp_ingestion', 'uddp_canonical', 'uddp_identity',
                  'uddp_registry', 'uddp_statepack', 'uddp_stewardship']) AS r
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r)
\gexec

SELECT format('ALTER ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD %L', r, :'svc_password')
FROM unnest(ARRAY['uddp_ingestion', 'uddp_canonical', 'uddp_identity',
                  'uddp_registry', 'uddp_statepack', 'uddp_stewardship']) AS r
\gexec

-- uddp_raw: ingestion
\connect uddp_raw
REVOKE ALL ON DATABASE uddp_raw FROM PUBLIC;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT CONNECT ON DATABASE uddp_raw TO uddp_ingestion;
CREATE SCHEMA IF NOT EXISTS ingestion AUTHORIZATION uddp_ingestion;
ALTER ROLE uddp_ingestion IN DATABASE uddp_raw SET search_path = ingestion;

-- uddp_identity: identity
\connect uddp_identity
REVOKE ALL ON DATABASE uddp_identity FROM PUBLIC;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT CONNECT ON DATABASE uddp_identity TO uddp_identity;
CREATE SCHEMA IF NOT EXISTS identity AUTHORIZATION uddp_identity;
ALTER ROLE uddp_identity IN DATABASE uddp_identity SET search_path = identity;

-- uddp_registry: registry and lineage
\connect uddp_registry
REVOKE ALL ON DATABASE uddp_registry FROM PUBLIC;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT CONNECT ON DATABASE uddp_registry TO uddp_registry;
CREATE SCHEMA IF NOT EXISTS registry AUTHORIZATION uddp_registry;
CREATE SCHEMA IF NOT EXISTS lineage AUTHORIZATION uddp_registry;
ALTER ROLE uddp_registry IN DATABASE uddp_registry SET search_path = registry, lineage;

-- uddp_platform: canonical, statepack and stewardship, each with its own role and no access to the others
\connect uddp_platform
REVOKE ALL ON DATABASE uddp_platform FROM PUBLIC;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT CONNECT ON DATABASE uddp_platform TO uddp_canonical, uddp_statepack, uddp_stewardship;
CREATE SCHEMA IF NOT EXISTS canonical AUTHORIZATION uddp_canonical;
CREATE SCHEMA IF NOT EXISTS statepack AUTHORIZATION uddp_statepack;
CREATE SCHEMA IF NOT EXISTS stewardship AUTHORIZATION uddp_stewardship;
REVOKE ALL ON SCHEMA canonical FROM PUBLIC;
REVOKE ALL ON SCHEMA statepack FROM PUBLIC;
REVOKE ALL ON SCHEMA stewardship FROM PUBLIC;
ALTER ROLE uddp_canonical IN DATABASE uddp_platform SET search_path = canonical;
ALTER ROLE uddp_statepack IN DATABASE uddp_platform SET search_path = statepack;
ALTER ROLE uddp_stewardship IN DATABASE uddp_platform SET search_path = stewardship;

\echo 'UDDP service roles and schemas are ready.'
