create table audit_record (
  id uuid primary key,
  service_id uuid references service_catalog(id),
  actor_type text not null,
  actor_id text not null,
  action text not null,
  resource_type text not null,
  resource_id text not null,
  before_hash text,
  after_hash text,
  metadata jsonb not null default '{}'::jsonb,
  trace_id text,
  occurred_at timestamptz not null,
  constraint audit_actor_type_allowed check
    (actor_type in ('user','service','system','model')),
  constraint audit_metadata_object check (jsonb_typeof(metadata) = 'object')
);
create index audit_record_service_idx on audit_record(service_id, occurred_at, id)
  where service_id is not null;
create index audit_record_resource_cursor_idx
  on audit_record(resource_type, resource_id, occurred_at, id);

create trigger incident_event_immutable
before update or delete on incident_event
for each row execute function reject_row_mutation();
create trigger execution_attempt_immutable
before update or delete on execution_attempt
for each row execute function reject_row_mutation();
create trigger audit_record_immutable
before update or delete on audit_record
for each row execute function reject_row_mutation();
