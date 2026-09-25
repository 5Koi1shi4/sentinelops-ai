\set ON_ERROR_STOP on
\getenv sentinelops_migrator_password SENTINELOPS_DB_MIGRATOR_PASSWORD
\getenv sentinelops_app_password SENTINELOPS_DB_APP_PASSWORD

SELECT length(:'sentinelops_migrator_password') > 0 AS has_migrator_password \gset
\if :has_migrator_password
\else
  \echo SENTINELOPS_DB_MIGRATOR_PASSWORD must contain a non-empty secret
  \quit 2
\endif

SELECT length(:'sentinelops_app_password') > 0 AS has_app_password \gset
\if :has_app_password
\else
  \echo SENTINELOPS_DB_APP_PASSWORD must contain a non-empty secret
  \quit 2
\endif

SELECT format(
  'CREATE ROLE sentinelops_migrator LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS NOINHERIT',
  :'sentinelops_migrator_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sentinelops_migrator')
\gexec

ALTER ROLE sentinelops_migrator
  WITH LOGIN PASSWORD :'sentinelops_migrator_password'
  NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS NOINHERIT;

SELECT format(
  'CREATE ROLE sentinelops_app LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS NOINHERIT',
  :'sentinelops_app_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sentinelops_app')
\gexec

ALTER ROLE sentinelops_app
  WITH LOGIN PASSWORD :'sentinelops_app_password'
  NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS NOINHERIT;

-- 由数据库管理员在 Flyway 迁移前安装扩展。
CREATE EXTENSION IF NOT EXISTS vector;

SELECT current_database() AS sentinelops_database_name \gset
REVOKE ALL PRIVILEGES ON DATABASE :"sentinelops_database_name" FROM PUBLIC;
REVOKE ALL PRIVILEGES ON DATABASE :"sentinelops_database_name" FROM sentinelops_migrator, sentinelops_app;
GRANT CONNECT ON DATABASE :"sentinelops_database_name" TO sentinelops_migrator, sentinelops_app;

\ir ../grants/runtime-grants.sql
