alter table principal add column claims_issued_at timestamptz;

alter table role_grant drop constraint role_name_allowed;
alter table role_grant add constraint role_name_allowed check (role_name in
  ('observer','on_call_operator','sre_approver','runbook_admin','platform_admin','auditor'));

-- Earlier audit writers had no result field; preserve that uncertainty on upgrade.
alter table audit_record add column result text not null default 'unknown';
alter table audit_record add constraint audit_result_allowed check (result in
  ('success','failure','denied','unknown'));
create index audit_record_cursor_idx on audit_record(occurred_at desc, id desc);
