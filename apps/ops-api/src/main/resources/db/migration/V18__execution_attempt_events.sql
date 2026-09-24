create table execution_attempt_event (
  id uuid primary key,
  execution_id uuid not null references execution(id),
  step_id text not null,
  attempt_no integer not null,
  fencing_token bigint not null,
  phase text not null,
  metadata jsonb not null default '{}'::jsonb,
  occurred_at timestamptz not null,
  constraint execution_attempt_event_attempt_positive check (attempt_no > 0),
  constraint execution_attempt_event_fencing_positive check (fencing_token > 0),
  constraint execution_attempt_event_phase_allowed check
    (phase in ('prepared','dispatched','acknowledged','failed_before_dispatch','unknown_after_dispatch')),
  constraint execution_attempt_event_metadata_object check (jsonb_typeof(metadata) = 'object'),
  unique (execution_id, step_id, attempt_no, phase)
);

create index execution_attempt_event_execution_time_idx
  on execution_attempt_event(execution_id, occurred_at, id);

create trigger execution_attempt_event_immutable
before update or delete on execution_attempt_event
for each row execute function reject_row_mutation();
