-- 此文件由数据库管理员通过 init/001_roles.sql 调用。
-- 新迁移表默认只授予 SELECT、INSERT；UPDATE 必须经审查后显式授予。

REVOKE ALL PRIVILEGES ON SCHEMA public FROM PUBLIC;
REVOKE ALL PRIVILEGES ON SCHEMA public FROM sentinelops_app;
ALTER SCHEMA public OWNER TO sentinelops_migrator;
GRANT USAGE ON SCHEMA public TO sentinelops_app;

DO $$
DECLARE
  membership record;
BEGIN
  FOR membership IN
    SELECT parent_role.rolname AS granted_role, member_role.rolname AS member_role
    FROM pg_auth_members AS memberships
    JOIN pg_roles AS parent_role ON parent_role.oid = memberships.roleid
    JOIN pg_roles AS member_role ON member_role.oid = memberships.member
    WHERE member_role.rolname IN ('sentinelops_app', 'sentinelops_migrator')
  LOOP
    EXECUTE format('REVOKE %I FROM %I', membership.granted_role, membership.member_role);
  END LOOP;
END;
$$;

-- 将旧共享所有者创建的现有对象转交给迁移用户。
-- 扩展成员仍由扩展所有者持有。
DO $$
DECLARE
  object record;
BEGIN
  FOR object IN
    SELECT
      namespace.nspname AS schema_name,
      relation.relname AS object_name,
      CASE relation.relkind
        WHEN 'S' THEN 'SEQUENCE'
        WHEN 'v' THEN 'VIEW'
        WHEN 'm' THEN 'MATERIALIZED VIEW'
        WHEN 'f' THEN 'FOREIGN TABLE'
        ELSE 'TABLE'
      END AS object_type
    FROM pg_class AS relation
    JOIN pg_namespace AS namespace ON namespace.oid = relation.relnamespace
    WHERE namespace.nspname = 'public'
      AND relation.relkind IN ('r', 'p', 'S', 'v', 'm', 'f')
      AND NOT EXISTS (
        SELECT 1
        FROM pg_depend AS dependency
        WHERE dependency.classid = 'pg_class'::regclass
          AND dependency.objid = relation.oid
          AND dependency.deptype = 'e'
      )
  LOOP
    EXECUTE format(
      'ALTER %s %I.%I OWNER TO sentinelops_migrator',
      object.object_type,
      object.schema_name,
      object.object_name);
  END LOOP;
END;
$$;

DO $$
DECLARE
  routine record;
BEGIN
  FOR routine IN
    SELECT
      namespace.nspname AS schema_name,
      procedure.proname AS routine_name,
      procedure.prokind AS routine_kind,
      pg_get_function_identity_arguments(procedure.oid) AS identity_arguments
    FROM pg_proc AS procedure
    JOIN pg_namespace AS namespace ON namespace.oid = procedure.pronamespace
    WHERE namespace.nspname = 'public'
      AND procedure.prokind IN ('f', 'p')
      AND NOT EXISTS (
        SELECT 1
        FROM pg_depend AS dependency
        WHERE dependency.classid = 'pg_proc'::regclass
          AND dependency.objid = procedure.oid
          AND dependency.deptype = 'e'
      )
  LOOP
    EXECUTE format(
      'ALTER %s %I.%I(%s) OWNER TO sentinelops_migrator',
      CASE routine.routine_kind WHEN 'p' THEN 'PROCEDURE' ELSE 'FUNCTION' END,
      routine.schema_name,
      routine.routine_name,
      routine.identity_arguments);
    EXECUTE format(
      'REVOKE EXECUTE ON %s %I.%I(%s) FROM PUBLIC, sentinelops_app',
      CASE routine.routine_kind WHEN 'p' THEN 'PROCEDURE' ELSE 'FUNCTION' END,
      routine.schema_name,
      routine.routine_name,
      routine.identity_arguments);
  END LOOP;
END;
$$;

DO $$
DECLARE
  type_object record;
BEGIN
  FOR type_object IN
    SELECT namespace.nspname AS schema_name, type.typname AS type_name
    FROM pg_type AS type
    JOIN pg_namespace AS namespace ON namespace.oid = type.typnamespace
    WHERE namespace.nspname = 'public'
      AND type.typtype IN ('d', 'e', 'r', 'm')
      AND type.typrelid = 0
      AND NOT EXISTS (
        SELECT 1
        FROM pg_depend AS dependency
        WHERE dependency.classid = 'pg_type'::regclass
          AND dependency.objid = type.oid
          AND dependency.deptype = 'e'
      )
  LOOP
    EXECUTE format(
      'ALTER TYPE %I.%I OWNER TO sentinelops_migrator',
      type_object.schema_name,
      type_object.type_name);
  END LOOP;
END;
$$;

REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA public FROM PUBLIC;
REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA public FROM sentinelops_app;
GRANT SELECT, INSERT ON ALL TABLES IN SCHEMA public TO sentinelops_app;

DO $$
DECLARE
  table_name text;
  mutable_tables text[] := ARRAY[
    'service_catalog', 'principal', 'incident', 'incident_projection',
    'runbook', 'runbook_version', 'diagnosis_run', 'approval_request',
    'execution', 'outbox_event', 'idempotency_record', 'verification_cycle', 'eval_run'
  ];
  append_only_tables text[] := ARRAY[
    'audit_record', 'incident_event', 'execution_attempt', 'execution_attempt_event',
    'evidence_snapshot', 'diagnosis_proposal', 'diagnosis_proposal_evidence',
    'diagnosis_run_evidence', 'verification_attempt', 'approval_decision',
    'knowledge_chunk', 'eval_dataset', 'eval_case', 'eval_case_result'
  ];
BEGIN
  FOREACH table_name IN ARRAY mutable_tables LOOP
    IF to_regclass(format('public.%I', table_name)) IS NOT NULL THEN
      EXECUTE format('GRANT UPDATE ON TABLE public.%I TO sentinelops_app', table_name);
    END IF;
  END LOOP;

  FOREACH table_name IN ARRAY append_only_tables LOOP
    IF to_regclass(format('public.%I', table_name)) IS NOT NULL THEN
      EXECUTE format('REVOKE UPDATE, DELETE ON TABLE public.%I FROM sentinelops_app', table_name);
    END IF;
  END LOOP;

  -- 运行时 API 仅在这两张表上有经审核的 DELETE 路径。
  FOREACH table_name IN ARRAY ARRAY['role_grant', 'webhook_replay_nonce'] LOOP
    IF to_regclass(format('public.%I', table_name)) IS NOT NULL THEN
      EXECUTE format('GRANT DELETE ON TABLE public.%I TO sentinelops_app', table_name);
      EXECUTE format('REVOKE UPDATE ON TABLE public.%I FROM sentinelops_app', table_name);
    END IF;
  END LOOP;
END;
$$;

REVOKE ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public FROM PUBLIC;
REVOKE ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public FROM sentinelops_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO sentinelops_app;

-- Flyway 历史表由迁移用户持有，不属于运行时应用状态。
DO $$
BEGIN
  IF to_regclass('public.flyway_schema_history') IS NOT NULL THEN
    EXECUTE 'REVOKE ALL PRIVILEGES ON TABLE public.flyway_schema_history FROM PUBLIC, sentinelops_app';
  END IF;
END;
$$;

ALTER DEFAULT PRIVILEGES FOR ROLE sentinelops_migrator IN SCHEMA public
  REVOKE ALL PRIVILEGES ON TABLES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE sentinelops_migrator IN SCHEMA public
  GRANT SELECT, INSERT ON TABLES TO sentinelops_app;
ALTER DEFAULT PRIVILEGES FOR ROLE sentinelops_migrator IN SCHEMA public
  REVOKE ALL PRIVILEGES ON SEQUENCES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE sentinelops_migrator IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO sentinelops_app;
ALTER DEFAULT PRIVILEGES FOR ROLE sentinelops_migrator
  REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE sentinelops_migrator
  REVOKE EXECUTE ON FUNCTIONS FROM sentinelops_app;
