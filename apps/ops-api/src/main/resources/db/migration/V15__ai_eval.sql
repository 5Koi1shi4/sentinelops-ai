create table eval_dataset (
  id uuid primary key,
  dataset_key text not null,
  version_number integer not null,
  checksum text not null,
  created_by_principal_id uuid not null references principal(id),
  created_at timestamptz not null,
  constraint eval_dataset_version_positive check (version_number > 0),
  unique (dataset_key, version_number),
  unique (checksum)
);
create index eval_dataset_creator_idx on eval_dataset(created_by_principal_id);

create table eval_case (
  id uuid primary key,
  dataset_id uuid not null references eval_dataset(id),
  case_key text not null,
  input_fixture jsonb not null,
  expectation jsonb not null,
  tags text[] not null default '{}',
  checksum text not null,
  constraint eval_case_input_object check (jsonb_typeof(input_fixture) = 'object'),
  constraint eval_case_expectation_object check (jsonb_typeof(expectation) = 'object'),
  unique (dataset_id, case_key),
  unique (dataset_id, checksum)
);
create index eval_case_tags_idx on eval_case using gin(tags);

create table eval_run (
  id uuid primary key,
  dataset_id uuid not null references eval_dataset(id),
  baseline_run_id uuid references eval_run(id),
  status text not null,
  provider text not null,
  model_name text not null,
  prompt_version text not null,
  toolset_version text not null,
  runbook_corpus_hash text not null,
  run_config jsonb not null,
  aggregate_metrics jsonb not null default '{}'::jsonb,
  release_allowed boolean,
  started_at timestamptz not null,
  completed_at timestamptz,
  constraint eval_run_status_allowed check (status in ('running','completed','failed')),
  constraint eval_run_config_object check (jsonb_typeof(run_config) = 'object'),
  constraint eval_run_metrics_object check (jsonb_typeof(aggregate_metrics) = 'object'),
  constraint eval_run_completion_consistent check
    ((status = 'running' and completed_at is null and release_allowed is null) or
     (status in ('completed','failed') and completed_at is not null))
);
create index eval_run_dataset_idx on eval_run(dataset_id, started_at desc, id desc);
create index eval_run_baseline_idx on eval_run(baseline_run_id)
  where baseline_run_id is not null;
create index eval_run_running_idx on eval_run(started_at, id) where status = 'running';

create table eval_case_result (
  id uuid primary key,
  eval_run_id uuid not null references eval_run(id),
  eval_case_id uuid not null references eval_case(id),
  status text not null,
  proposal_hash text,
  scores jsonb not null,
  failure_code text,
  input_tokens bigint not null default 0,
  output_tokens bigint not null default 0,
  latency_ms bigint not null,
  cost_micros bigint not null default 0,
  created_at timestamptz not null,
  constraint eval_case_result_status_allowed check (status in ('passed','failed','error')),
  constraint eval_case_result_scores_object check (jsonb_typeof(scores) = 'object'),
  constraint eval_case_result_measures_nonnegative check
    (input_tokens >= 0 and output_tokens >= 0 and latency_ms >= 0 and cost_micros >= 0),
  unique (eval_run_id, eval_case_id)
);
create index eval_case_result_case_idx on eval_case_result(eval_case_id);

create trigger eval_dataset_immutable
before update or delete on eval_dataset
for each row execute function reject_row_mutation();
create trigger eval_case_immutable
before update or delete on eval_case
for each row execute function reject_row_mutation();
create trigger eval_case_result_immutable
before update or delete on eval_case_result
for each row execute function reject_row_mutation();

create function protect_eval_run_identity() returns trigger language plpgsql as $$
begin
  if TG_OP = 'DELETE' then
    raise exception using errcode = '55000', message = 'Eval runs cannot be deleted';
  end if;
  if NEW.dataset_id is distinct from OLD.dataset_id
     or NEW.baseline_run_id is distinct from OLD.baseline_run_id
     or NEW.provider is distinct from OLD.provider
     or NEW.model_name is distinct from OLD.model_name
     or NEW.prompt_version is distinct from OLD.prompt_version
     or NEW.toolset_version is distinct from OLD.toolset_version
     or NEW.runbook_corpus_hash is distinct from OLD.runbook_corpus_hash
     or NEW.run_config is distinct from OLD.run_config
     or NEW.started_at is distinct from OLD.started_at then
    raise exception using errcode = '55000', message = 'Eval run identity/configuration is immutable';
  end if;
  return NEW;
end;
$$;
create trigger eval_run_identity_immutable
before update or delete on eval_run
for each row execute function protect_eval_run_identity();

alter table eval_run
  add column command_id uuid not null references idempotency_record(id),
  add column owner_token uuid not null,
  add column lease_expires_at timestamptz not null,
  add column deadline_at timestamptz not null,
  add constraint eval_run_command_uk unique(command_id),
  add constraint eval_run_dataset_pair unique(id,dataset_id),
  add constraint eval_run_deadline_bound check
    (deadline_at > started_at and deadline_at <= started_at + interval '20 minutes'
     and lease_expires_at <= deadline_at),
  add constraint eval_run_terminal_release check
    (status='running' or release_allowed is not null);
alter table eval_case add constraint eval_case_dataset_pair unique(id,dataset_id);
alter table eval_case_result
  add column dataset_id uuid not null,
  add column owner_token uuid not null,
  add constraint eval_result_run_dataset_fk foreign key(eval_run_id,dataset_id) references eval_run(id,dataset_id),
  add constraint eval_result_case_dataset_fk foreign key(eval_case_id,dataset_id) references eval_case(id,dataset_id);
create index eval_case_result_dataset_idx on eval_case_result(dataset_id);

create or replace function protect_eval_run_identity() returns trigger language plpgsql as $$
begin
  if TG_OP = 'DELETE' or OLD.status <> 'running' then
    raise exception using errcode='55000',message='Eval terminal runs are immutable';
  end if;
  if (to_jsonb(NEW) - array['status','aggregate_metrics','release_allowed','completed_at','lease_expires_at'])
     is distinct from
     (to_jsonb(OLD) - array['status','aggregate_metrics','release_allowed','completed_at','lease_expires_at']) then
    raise exception using errcode='55000',message='Eval run identity is immutable';
  end if;
  if NEW.status='completed' and
     (select count(*) from eval_case_result where eval_run_id=OLD.id) <>
     (select count(*) from eval_case where dataset_id=OLD.dataset_id) then
    raise exception using errcode='23514',message='Eval run has incomplete case coverage';
  end if;
  if NEW.release_allowed and exists(select 1 from eval_case_result where eval_run_id=OLD.id and status='error') then
    raise exception using errcode='23514',message='Eval errors cannot allow release';
  end if;
  return NEW;
end;
$$;

create function protect_eval_result_scope() returns trigger language plpgsql as $$
declare current_run eval_run%rowtype;
begin
  select * into current_run from eval_run where id=NEW.eval_run_id for update;
  if current_run.id is null or current_run.status <> 'running'
     or current_run.owner_token <> NEW.owner_token
     or current_run.lease_expires_at <= clock_timestamp()
     or current_run.deadline_at <= clock_timestamp() then
    raise exception using errcode='55000',message='Eval result requires a live run owner';
  end if;
  return NEW;
end;
$$;
create trigger eval_case_result_scope before insert on eval_case_result
for each row execute function protect_eval_result_scope();

create function protect_eval_case_append() returns trigger language plpgsql as $$
begin
  perform 1 from eval_dataset where id=NEW.dataset_id for update;
  if exists(select 1 from eval_run where dataset_id=NEW.dataset_id) then
    raise exception using errcode='55000',message='An executed Eval dataset cannot gain cases';
  end if;
  return NEW;
end;
$$;
create trigger eval_case_append_guard before insert on eval_case
for each row execute function protect_eval_case_append();
