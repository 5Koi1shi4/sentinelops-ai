create table verification_cycle (
  id uuid primary key,
  incident_id uuid not null references incident(id),
  incident_version bigint not null,
  execution_id uuid references execution(id),
  runbook_version_id uuid not null references runbook_version(id),
  cycle_no integer not null,
  probe text not null,
  target_alias text not null,
  success_threshold numeric(8,5) not null,
  max_attempts integer not null,
  interval_seconds integer not null,
  status text not null,
  claimed_by text,
  claim_token uuid,
  claim_until timestamptz,
  started_at timestamptz not null,
  completed_at timestamptz,
  constraint verification_cycle_incident_version_nonnegative
    check (incident_version >= 0),
  constraint verification_cycle_number_positive check (cycle_no > 0),
  constraint verification_cycle_attempts_positive check (max_attempts > 0),
  constraint verification_cycle_interval_positive check (interval_seconds > 0),
  constraint verification_cycle_threshold_range
    check (success_threshold >= 0 and success_threshold <= 1),
  constraint verification_cycle_probe_nonblank check (btrim(probe) <> ''),
  constraint verification_cycle_target_nonblank check (btrim(target_alias) <> ''),
  constraint verification_cycle_status_allowed
    check (status in ('running','succeeded','failed','superseded')),
  constraint verification_cycle_claim_consistent
    check ((claimed_by is null and claim_token is null and claim_until is null)
        or (claimed_by is not null and claim_token is not null
            and claim_until is not null)),
  constraint verification_cycle_completion_consistent
    check ((status = 'running' and completed_at is null)
        or (status <> 'running' and completed_at is not null)),
  unique (incident_id, incident_version),
  unique (incident_id, cycle_no)
);

create index verification_cycle_reclaim_idx
  on verification_cycle(status, claim_until, started_at, id)
  where status = 'running';
create unique index verification_cycle_incident_running_uk
  on verification_cycle(incident_id)
  where status = 'running';
create index verification_cycle_execution_idx
  on verification_cycle(execution_id)
  where execution_id is not null;

create table verification_attempt (
  id uuid primary key,
  cycle_id uuid not null references verification_cycle(id),
  attempt_no integer not null,
  successful boolean not null,
  sanitized_result jsonb not null,
  observed_at timestamptz not null,
  constraint verification_attempt_number_positive check (attempt_no > 0),
  constraint verification_attempt_result_object
    check (jsonb_typeof(sanitized_result) = 'object'),
  unique (cycle_id, attempt_no)
);

create index verification_attempt_cycle_time_idx
  on verification_attempt(cycle_id, observed_at, id);

create trigger verification_attempt_immutable
before update or delete on verification_attempt
for each row execute function reject_row_mutation();
