-- 迁移完成后，只为审核过的可变表授予 UPDATE。
-- 新对象默认仅授予 SELECT、INSERT，不默认开放 UPDATE 或 DELETE。
DO $$
DECLARE
  table_name text;
  mutable_tables text[] := ARRAY[
    'service_catalog', 'principal', 'incident', 'incident_projection',
    'runbook', 'runbook_version', 'diagnosis_run', 'approval_request',
    'execution', 'outbox_event', 'idempotency_record', 'verification_cycle', 'eval_run'
  ];
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sentinelops_app') THEN
    FOREACH table_name IN ARRAY mutable_tables LOOP
      IF to_regclass(format('public.%I', table_name)) IS NOT NULL THEN
        EXECUTE format('GRANT UPDATE ON TABLE public.%I TO sentinelops_app', table_name);
      END IF;
    END LOOP;

    IF to_regclass('public.flyway_schema_history') IS NOT NULL THEN
      EXECUTE 'REVOKE ALL PRIVILEGES ON TABLE public.flyway_schema_history FROM PUBLIC, sentinelops_app';
    END IF;

    FOREACH table_name IN ARRAY ARRAY['role_grant', 'webhook_replay_nonce'] LOOP
      IF to_regclass(format('public.%I', table_name)) IS NOT NULL THEN
        EXECUTE format('GRANT DELETE ON TABLE public.%I TO sentinelops_app', table_name);
        EXECUTE format('REVOKE UPDATE ON TABLE public.%I FROM sentinelops_app', table_name);
      END IF;
    END LOOP;
  END IF;
END;
$$;
