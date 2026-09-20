alter table outbox_event
  add column next_attempt_at timestamptz,
  add column quarantined_at timestamptz,
  add constraint outbox_quarantine_consistent check (
    quarantined_at is null or
    (published_at is null and claimed_by is null and claim_until is null and next_attempt_at is null)
  );

create index outbox_retry_ready_idx
  on outbox_event(next_attempt_at, created_at, id)
  where published_at is null and quarantined_at is null;
