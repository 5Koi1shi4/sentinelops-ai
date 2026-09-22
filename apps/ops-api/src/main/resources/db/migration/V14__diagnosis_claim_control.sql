-- Existing in-flight work cannot have a valid owner after upgrading the synchronous engine.
update diagnosis_run set status='failed', failure_code='DIAGNOSIS_INTERRUPTED',
  completed_at=clock_timestamp() where status='running';

alter table diagnosis_run
  add column owner_token uuid,
  add column lease_expires_at timestamptz,
  add column command_id uuid references idempotency_record(id),
  add column tool_call_count integer not null default 0,
  add column finish_reason text,
  add column latency_ms bigint not null default 0,
  add column response_hash text,
  add column runbook_corpus_version text,
  add constraint diagnosis_lease_bound check
    (lease_expires_at is null or
      (owner_token is not null and lease_expires_at > started_at
       and lease_expires_at <= started_at + interval '90 seconds')),
  add constraint diagnosis_running_owner check
    (status <> 'running' or (owner_token is not null and lease_expires_at is not null)),
  add constraint diagnosis_tool_budget check (tool_call_count between 0 and 6),
  add constraint diagnosis_latency_nonnegative check (latency_ms >= 0);

create unique index diagnosis_one_active_incident on diagnosis_run(incident_id) where status='running';
create unique index diagnosis_command_uk on diagnosis_run(command_id) where command_id is not null;
