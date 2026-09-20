create table idempotency_record (
  id uuid primary key,
  principal_key text not null,
  route_key text not null,
  idempotency_key text not null,
  request_hash text not null,
  response_status integer,
  response_body jsonb,
  state text not null,
  created_at timestamptz not null,
  expires_at timestamptz not null,
  constraint idempotency_state_allowed check (state in ('started','completed','failed')),
  constraint idempotency_expiry_order check (expires_at > created_at),
  unique (principal_key, route_key, idempotency_key)
);

create index idempotency_expiry_idx on idempotency_record(expires_at);
