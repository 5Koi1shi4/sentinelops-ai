insert into service_catalog(
  id, service_key, display_name, owner_team, slo_config, data_source_refs,
  execution_target_aliases, created_at, updated_at
) values (
  '0199a000-0000-7000-8000-000000000001',
  'checkout-api',
  'Demo Checkout API',
  'demo-sre',
  '{"availabilityTarget": 0.999}'::jsonb,
  '{"metrics": "demo-prometheus", "logs": "demo-loki"}'::jsonb,
  '{"primary": "demo-checkout"}'::jsonb,
  '2026-09-20T00:00:00Z',
  '2026-09-20T00:00:00Z'
);

insert into principal(id, issuer, subject, display_name, created_at) values
  (
    '0199a000-0000-7000-8000-000000000002',
    'urn:sentinelops:demo',
    'demo-author',
    'Demo Runbook Author',
    '2026-09-20T00:00:00Z'
  ),
  (
    '0199a000-0000-7000-8000-000000000003',
    'urn:sentinelops:demo',
    'demo-reviewer',
    'Demo Runbook Reviewer',
    '2026-09-20T00:00:00Z'
  );

insert into runbook(
  id, runbook_key, service_id, display_name, owner_team, created_at, updated_at
) values (
  '0199a000-0000-7000-8000-000000000004',
  'RB-DB-POOL-03',
  '0199a000-0000-7000-8000-000000000001',
  'Recover a saturated checkout database pool',
  'demo-sre',
  '2026-09-20T00:00:00Z',
  '2026-09-20T00:00:00Z'
);

insert into runbook_version(
  id, runbook_id, version_number, lifecycle, risk_level, adapter_id,
  definition, definition_checksum, author_principal_id, reviewer_principal_id,
  created_at, published_at
) values (
  '0199a000-0000-7000-8000-000000000005',
  '0199a000-0000-7000-8000-000000000004',
  1,
  'published',
  'r1',
  'demo-http',
  '{
    "runbookKey": "RB-DB-POOL-03",
    "risk": "R1",
    "adapterId": "demo-http",
    "parameters": {
      "type": "object",
      "properties": {
        "replicas": {"type": "integer", "minimum": 1, "maximum": 1}
      },
      "required": ["replicas"],
      "additionalProperties": false
    },
    "steps": [
      {"stepId": "recover-one", "operation": "recover_connection_pool"}
    ],
    "verification": {
      "probe": "demo_checkout_health",
      "successThreshold": 1.0,
      "attempts": 6,
      "intervalSeconds": 5
    },
    "rollback": null
  }'::jsonb,
  'CT3z4ykAZ7DMf648N-qnBUeGWkKZcqYulbkAOQ0st2g',
  '0199a000-0000-7000-8000-000000000002',
  '0199a000-0000-7000-8000-000000000003',
  '2026-09-20T00:00:00Z',
  '2026-09-20T00:00:00Z'
);
