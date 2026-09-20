alter table execution
  add column claim_attempt_key text;

update execution
set claim_attempt_key = 'legacy:' || id::text
where ticket_jti is not null;

alter table execution
  add constraint execution_claim_attempt_nonblank
    check (claim_attempt_key is null or btrim(claim_attempt_key) <> ''),
  add constraint execution_claim_attempt_consistent
    check ((ticket_jti is null and claim_attempt_key is null)
        or (ticket_jti is not null and claim_attempt_key is not null));
