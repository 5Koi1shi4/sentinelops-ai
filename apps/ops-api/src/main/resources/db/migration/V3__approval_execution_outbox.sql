create table approval_request (
  id uuid primary key,
  incident_id uuid not null references incident(id),
  proposal_id uuid not null references diagnosis_proposal(id),
  proposal_hash text not null,
  requester_principal_id uuid not null references principal(id),
  policy_version text not null,
  required_approvals integer not null,
  independent_approver_required boolean not null,
  status text not null,
  resource_version bigint not null default 0,
  created_at timestamptz not null,
  expires_at timestamptz not null,
  decided_at timestamptz,
  constraint approval_quorum_allowed check (required_approvals in (1,2)),
  constraint approval_status_allowed check
    (status in ('pending','approved','rejected','expired','invalidated')),
  constraint approval_resource_version_nonnegative check (resource_version >= 0),
  constraint approval_expiry_order check (expires_at > created_at),
  constraint approval_decided_time_consistent check
    ((status = 'pending' and decided_at is null) or
     (status <> 'pending' and decided_at is not null))
);
create index approval_request_incident_idx on approval_request(incident_id, created_at desc, id desc);
create index approval_request_proposal_idx on approval_request(proposal_id);
create index approval_request_requester_idx on approval_request(requester_principal_id);
create unique index approval_request_active_proposal_uk on approval_request(proposal_id)
  where status in ('pending','approved');

create table approval_decision (
  id uuid primary key,
  approval_request_id uuid not null references approval_request(id),
  reviewer_principal_id uuid not null references principal(id),
  decision text not null,
  comment text not null default '',
  proposal_hash text not null,
  decided_at timestamptz not null,
  constraint approval_decision_allowed check (decision in ('approve','reject'))
);
create unique index approval_decision_reviewer_uk
  on approval_decision(approval_request_id, reviewer_principal_id);
create index approval_decision_reviewer_idx on approval_decision(reviewer_principal_id);

create table execution (
  id uuid primary key,
  incident_id uuid not null references incident(id),
  proposal_id uuid not null references diagnosis_proposal(id),
  approval_request_id uuid not null references approval_request(id),
  status text not null,
  idempotency_key text not null,
  ticket_jti text,
  claimed_by text,
  lease_until timestamptz,
  fencing_token bigint not null default 0,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  started_at timestamptz,
  completed_at timestamptz,
  constraint execution_status_allowed check
    (status in ('pending','running','verifying','succeeded','failed','unknown','escalated')),
  constraint execution_fencing_nonnegative check (fencing_token >= 0),
  constraint execution_lease_claim_consistent check
    ((claimed_by is null and lease_until is null) or
     (claimed_by is not null and lease_until is not null))
);
create index execution_incident_idx on execution(incident_id, created_at desc, id desc);
create index execution_proposal_idx on execution(proposal_id);
create index execution_approval_request_idx on execution(approval_request_id);
create unique index execution_idempotency_uk on execution(idempotency_key);
create unique index execution_ticket_jti_uk on execution(ticket_jti)
  where ticket_jti is not null;

create table execution_attempt (
  id uuid primary key,
  execution_id uuid not null references execution(id),
  step_id text not null,
  attempt_no integer not null,
  fencing_token bigint not null,
  adapter_id text not null,
  adapter_version text not null,
  request_hash text not null,
  outcome text not null,
  sanitized_result jsonb not null default '{}'::jsonb,
  started_at timestamptz not null,
  completed_at timestamptz not null,
  constraint execution_attempt_number_positive check (attempt_no > 0),
  constraint execution_attempt_fencing_positive check (fencing_token > 0),
  constraint execution_attempt_outcome_allowed check
    (outcome in ('succeeded','failed','unknown')),
  constraint execution_attempt_time_order check (completed_at >= started_at),
  unique (execution_id, step_id, attempt_no)
);

create table outbox_event (
  id uuid primary key,
  aggregate_type text not null,
  aggregate_id uuid not null,
  event_type text not null,
  payload jsonb not null,
  created_at timestamptz not null,
  claimed_by text,
  claim_until timestamptz,
  publish_attempts integer not null default 0,
  last_error text,
  published_at timestamptz,
  constraint outbox_payload_object check (jsonb_typeof(payload) = 'object'),
  constraint outbox_attempts_nonnegative check (publish_attempts >= 0),
  constraint outbox_claim_consistent check
    ((claimed_by is null and claim_until is null) or
     (claimed_by is not null and claim_until is not null))
);
create index outbox_pending_claim_idx on outbox_event(created_at, id)
  where published_at is null;
create index outbox_aggregate_idx on outbox_event(aggregate_type, aggregate_id, created_at);
