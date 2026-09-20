alter table approval_request
  add column target_alias text;

update approval_request ar
set target_alias = coalesce(
  nullif(btrim(s.execution_target_aliases ->> 'primary'), ''),
  '__unavailable__'
)
from incident i
join service_catalog s on s.id = i.service_id
where i.id = ar.incident_id;

alter table approval_request
  alter column target_alias set not null,
  add constraint approval_target_alias_nonblank
    check (btrim(target_alias) <> '');

alter table execution
  add column target_alias text,
  add column ticket_issued_at timestamptz;

update execution e
set target_alias = ar.target_alias
from approval_request ar
where ar.id = e.approval_request_id;

alter table execution
  alter column target_alias set not null,
  add constraint execution_target_alias_nonblank
    check (btrim(target_alias) <> ''),
  add constraint execution_ticket_material_consistent
    check ((ticket_jti is null and ticket_issued_at is null)
        or (ticket_jti is not null and ticket_issued_at is not null));
