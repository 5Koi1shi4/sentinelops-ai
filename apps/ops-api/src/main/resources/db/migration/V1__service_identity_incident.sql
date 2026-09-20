create extension if not exists vector;

create table service_catalog (
  id uuid primary key,
  service_key text not null unique,
  display_name text not null,
  owner_team text not null,
  slo_config jsonb not null default '{}'::jsonb,
  data_source_refs jsonb not null default '{}'::jsonb,
  execution_target_aliases jsonb not null default '{}'::jsonb,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  constraint service_key_nonblank check (btrim(service_key) <> ''),
  constraint service_slo_config_object check (jsonb_typeof(slo_config) = 'object'),
  constraint service_data_source_refs_object check (jsonb_typeof(data_source_refs) = 'object'),
  constraint service_execution_targets_object check (jsonb_typeof(execution_target_aliases) = 'object')
);

create table principal (
  id uuid primary key,
  issuer text not null,
  subject text not null,
  display_name text not null,
  created_at timestamptz not null,
  unique (issuer, subject)
);

create table role_grant (
  id uuid primary key,
  principal_id uuid not null references principal(id),
  role_name text not null,
  service_id uuid references service_catalog(id),
  granted_at timestamptz not null,
  constraint role_name_allowed check (role_name in
    ('observer','on_call_operator','sre_approver','runbook_admin','platform_admin')),
  unique nulls not distinct (principal_id, role_name, service_id)
);
create index role_grant_principal_idx on role_grant(principal_id);
create index role_grant_service_idx on role_grant(service_id) where service_id is not null;

create table incident (
  id uuid primary key,
  service_id uuid not null references service_catalog(id),
  fingerprint text not null,
  title text not null,
  severity text not null,
  status text not null,
  version bigint not null default 0,
  next_event_seq bigint not null default 1,
  occurrence_count bigint not null default 1,
  opened_at timestamptz not null,
  updated_at timestamptz not null,
  resolved_at timestamptz,
  constraint incident_severity_allowed check (severity in ('sev1','sev2','sev3','sev4')),
  constraint incident_status_allowed check (status in
    ('detected','triaging','diagnosed','awaiting_approval','executing','verifying','resolved','suppressed','escalated')),
  constraint incident_version_nonnegative check (version >= 0),
  constraint incident_event_seq_positive check (next_event_seq > 0)
);
create unique index incident_active_fingerprint_uk
  on incident(service_id, fingerprint)
  where status not in ('resolved','suppressed');
create index incident_status_opened_cursor_idx on incident(status, opened_at desc, id desc);
create index incident_service_opened_idx on incident(service_id, opened_at desc);

create table incident_projection (
  incident_id uuid primary key references incident(id),
  service_id uuid not null references service_catalog(id),
  title text not null,
  severity text not null,
  status text not null,
  resource_version bigint not null,
  occurrence_count bigint not null,
  opened_at timestamptz not null,
  updated_at timestamptz not null,
  resolved_at timestamptz
);
create index incident_projection_status_cursor_idx
  on incident_projection(status, opened_at desc, incident_id desc);
create index incident_projection_service_cursor_idx
  on incident_projection(service_id, opened_at desc, incident_id desc);

create function refresh_incident_projection() returns trigger language plpgsql as $$
begin
  insert into incident_projection(
    incident_id, service_id, title, severity, status, resource_version,
    occurrence_count, opened_at, updated_at, resolved_at
  ) values (
    NEW.id, NEW.service_id, NEW.title, NEW.severity, NEW.status, NEW.version,
    NEW.occurrence_count, NEW.opened_at, NEW.updated_at, NEW.resolved_at
  )
  on conflict (incident_id) do update set
    service_id = excluded.service_id,
    title = excluded.title,
    severity = excluded.severity,
    status = excluded.status,
    resource_version = excluded.resource_version,
    occurrence_count = excluded.occurrence_count,
    updated_at = excluded.updated_at,
    resolved_at = excluded.resolved_at;
  return NEW;
end;
$$;
create trigger incident_projection_refresh
after insert or update on incident
for each row execute function refresh_incident_projection();

create table incident_event (
  id uuid primary key,
  incident_id uuid not null references incident(id),
  seq_no bigint not null,
  event_type text not null,
  actor_type text not null,
  actor_id text not null,
  source text,
  source_event_id text,
  payload jsonb not null default '{}'::jsonb,
  occurred_at timestamptz not null,
  constraint incident_event_seq_positive check (seq_no > 0),
  constraint incident_event_incident_seq_uk unique (incident_id, seq_no)
);
create unique index incident_event_source_event_uk
  on incident_event(source, source_event_id)
  where source_event_id is not null;
create index incident_event_incident_time_idx on incident_event(incident_id, occurred_at, id);

create table evidence_snapshot (
  id uuid primary key,
  incident_id uuid not null references incident(id),
  diagnosis_run_id uuid,
  source_type text not null,
  source_ref text not null,
  query_spec jsonb not null,
  redacted_payload jsonb not null,
  content_hash text not null,
  captured_at timestamptz not null,
  truncated boolean not null default false,
  unique (incident_id, content_hash)
);
create index evidence_snapshot_incident_idx on evidence_snapshot(incident_id, captured_at);
