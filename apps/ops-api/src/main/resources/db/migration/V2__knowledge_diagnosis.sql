create table runbook (
  id uuid primary key,
  runbook_key text not null unique,
  service_id uuid not null references service_catalog(id),
  display_name text not null,
  owner_team text not null,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  constraint runbook_key_nonblank check (btrim(runbook_key) <> '')
);
create index runbook_service_idx on runbook(service_id);

create table runbook_version (
  id uuid primary key,
  runbook_id uuid not null references runbook(id),
  version_number integer not null,
  lifecycle text not null,
  risk_level text not null,
  adapter_id text not null,
  definition jsonb not null,
  definition_checksum text not null,
  author_principal_id uuid references principal(id),
  reviewer_principal_id uuid references principal(id),
  created_at timestamptz not null,
  published_at timestamptz,
  constraint runbook_version_positive check (version_number > 0),
  constraint runbook_lifecycle_allowed check (lifecycle in ('draft','published','retired')),
  constraint runbook_risk_allowed check (risk_level in ('r0','r1','r2','r3')),
  constraint runbook_publish_time_consistent check
    ((lifecycle = 'draft' and published_at is null) or
     (lifecycle in ('published','retired') and published_at is not null)),
  constraint runbook_definition_object check (jsonb_typeof(definition) = 'object'),
  constraint runbook_checksum_nonblank check (btrim(definition_checksum) <> '')
);
create unique index runbook_version_number_uk on runbook_version(runbook_id, version_number);
create index runbook_version_author_idx on runbook_version(author_principal_id)
  where author_principal_id is not null;
create index runbook_version_reviewer_idx on runbook_version(reviewer_principal_id)
  where reviewer_principal_id is not null;

create table diagnosis_run (
  id uuid primary key,
  incident_id uuid not null references incident(id),
  requested_by_principal_id uuid not null references principal(id),
  incident_version bigint not null,
  engine_type text not null,
  status text not null,
  model_provider text,
  model_name text,
  prompt_version text not null,
  input_hash text not null,
  input_tokens bigint not null default 0,
  output_tokens bigint not null default 0,
  cost_micros bigint not null default 0,
  failure_code text,
  started_at timestamptz not null,
  completed_at timestamptz,
  constraint diagnosis_incident_version_nonnegative check (incident_version >= 0),
  constraint diagnosis_engine_allowed check (engine_type in ('deterministic','model')),
  constraint diagnosis_status_allowed check (status in ('running','succeeded','failed')),
  constraint diagnosis_usage_nonnegative check
    (input_tokens >= 0 and output_tokens >= 0 and cost_micros >= 0),
  constraint diagnosis_completion_consistent check
    ((status = 'running' and completed_at is null) or
     (status in ('succeeded','failed') and completed_at is not null))
);
create index diagnosis_run_incident_idx on diagnosis_run(incident_id, started_at desc, id desc);
create index diagnosis_run_requester_idx on diagnosis_run(requested_by_principal_id);

alter table evidence_snapshot
  add constraint evidence_snapshot_diagnosis_run_fk
  foreign key (diagnosis_run_id) references diagnosis_run(id);
create index evidence_snapshot_diagnosis_run_idx on evidence_snapshot(diagnosis_run_id)
  where diagnosis_run_id is not null;

create table diagnosis_proposal (
  id uuid primary key,
  diagnosis_run_id uuid not null unique references diagnosis_run(id),
  incident_id uuid not null references incident(id),
  runbook_version_id uuid references runbook_version(id),
  summary text not null,
  proposal_payload jsonb not null,
  proposal_hash text not null,
  risk_level text not null,
  created_at timestamptz not null,
  constraint diagnosis_proposal_risk_allowed check (risk_level in ('r0','r1','r2')),
  constraint diagnosis_proposal_payload_object check (jsonb_typeof(proposal_payload) = 'object'),
  constraint diagnosis_proposal_hash_nonblank check (btrim(proposal_hash) <> '')
);
create index diagnosis_proposal_incident_idx on diagnosis_proposal(incident_id, created_at desc, id desc);
create index diagnosis_proposal_runbook_version_idx on diagnosis_proposal(runbook_version_id)
  where runbook_version_id is not null;

create table diagnosis_proposal_evidence (
  proposal_id uuid not null references diagnosis_proposal(id),
  evidence_snapshot_id uuid not null references evidence_snapshot(id),
  primary key (proposal_id, evidence_snapshot_id)
);
create index diagnosis_proposal_evidence_snapshot_idx
  on diagnosis_proposal_evidence(evidence_snapshot_id);

create function reject_row_mutation() returns trigger language plpgsql as $$
begin
  raise exception using errcode = '55000', message = TG_TABLE_NAME || ' is append-only';
end;
$$;

create trigger evidence_snapshot_immutable
before update or delete on evidence_snapshot
for each row execute function reject_row_mutation();
create trigger diagnosis_proposal_immutable
before update or delete on diagnosis_proposal
for each row execute function reject_row_mutation();

create function reject_published_runbook_mutation() returns trigger language plpgsql as $$
begin
  if TG_OP = 'DELETE' and OLD.lifecycle in ('published','retired') then
    raise exception using errcode = '55000', message = 'published Runbook versions cannot be deleted';
  end if;
  if TG_OP = 'DELETE' then
    return OLD;
  end if;
  if OLD.lifecycle = 'retired' then
    raise exception using errcode = '55000', message = 'retired Runbook versions are immutable';
  end if;
  if OLD.lifecycle = 'published' then
    if NEW.lifecycle = 'retired'
       and (to_jsonb(NEW) - 'lifecycle') = (to_jsonb(OLD) - 'lifecycle') then
      return NEW;
    end if;
    raise exception using errcode = '55000', message = 'published Runbook definitions are immutable';
  end if;
  return NEW;
end;
$$;

create trigger runbook_version_published_immutable
before update or delete on runbook_version
for each row execute function reject_published_runbook_mutation();
