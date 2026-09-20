create table diagnosis_run_evidence (
  diagnosis_run_id uuid not null references diagnosis_run(id),
  evidence_snapshot_id uuid not null references evidence_snapshot(id),
  primary key (diagnosis_run_id, evidence_snapshot_id)
);

create index diagnosis_run_evidence_snapshot_idx
  on diagnosis_run_evidence(evidence_snapshot_id);

create trigger diagnosis_run_evidence_immutable
before update or delete on diagnosis_run_evidence
for each row execute function reject_row_mutation();
