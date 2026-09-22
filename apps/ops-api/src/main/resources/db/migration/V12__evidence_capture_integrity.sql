-- Preserve the first capture owner while validating every non-null historical owner.
alter table diagnosis_run
  add constraint diagnosis_run_incident_identity_uk unique (id, incident_id);

alter table evidence_snapshot
  add constraint evidence_snapshot_run_incident_fk
  foreign key (diagnosis_run_id, incident_id) references diagnosis_run(id, incident_id);

alter table evidence_snapshot drop constraint evidence_snapshot_diagnosis_run_fk;

-- Do not repair immutable history implicitly: inconsistent legacy links stop migration.
do $$
begin
  if exists (
    select 1 from diagnosis_run_evidence l
    join diagnosis_run r on r.id = l.diagnosis_run_id
    join evidence_snapshot e on e.id = l.evidence_snapshot_id
    where r.incident_id <> e.incident_id
  ) then
    raise exception 'evidence run link incident mismatch' using errcode = '23514';
  end if;
end;
$$;

create function enforce_evidence_run_incident() returns trigger language plpgsql as $$
declare
  run_incident uuid;
  evidence_incident uuid;
begin
  select incident_id into run_incident from diagnosis_run where id = new.diagnosis_run_id;
  select incident_id into evidence_incident from evidence_snapshot where id = new.evidence_snapshot_id;
  if run_incident is null or evidence_incident is null or run_incident <> evidence_incident then
    raise exception 'evidence run link incident mismatch' using errcode = '23514';
  end if;
  return new;
end;
$$;

create trigger diagnosis_run_evidence_incident_guard
before insert on diagnosis_run_evidence
for each row execute function enforce_evidence_run_incident();

-- Identity changes would invalidate already checked append-only run/evidence links.
create function preserve_diagnosis_run_identity() returns trigger language plpgsql as $$
begin
  if new.id is distinct from old.id or new.incident_id is distinct from old.incident_id then
    raise exception 'diagnosis run identity is immutable' using errcode = '23514';
  end if;
  return new;
end;
$$;

create trigger diagnosis_run_identity_immutable
before update of id, incident_id on diagnosis_run
for each row execute function preserve_diagnosis_run_identity();
