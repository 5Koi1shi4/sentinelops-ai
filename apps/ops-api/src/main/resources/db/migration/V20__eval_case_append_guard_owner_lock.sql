-- The runtime role may append Eval cases, but cannot UPDATE immutable datasets.
-- This trigger alone needs the owner privilege for its row lock; retain the lock
-- so a concurrent Eval run cannot race with a case append.
alter function public.protect_eval_case_append() security definer;
alter function public.protect_eval_case_append()
  set search_path to pg_catalog, public, pg_temp;
revoke execute on function public.protect_eval_case_append() from public;
