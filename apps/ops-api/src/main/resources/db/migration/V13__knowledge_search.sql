alter table runbook_version
  add column markdown text not null default '',
  add column revision bigint not null default 0 check (revision >= 0),
  add column last_editor_principal_id uuid references principal(id),
  add column reviewed_at timestamptz,
  add constraint runbook_markdown_bounded check (char_length(markdown) <= 120000),
  add constraint runbook_review_distinct check (
    reviewer_principal_id is null or
    (reviewer_principal_id is distinct from author_principal_id and
     reviewer_principal_id is distinct from last_editor_principal_id));
create index runbook_version_editor_idx on runbook_version(last_editor_principal_id)
  where last_editor_principal_id is not null;

create function guard_runbook_identity() returns trigger language plpgsql as $$
begin
  if NEW.id is distinct from OLD.id or NEW.service_id is distinct from OLD.service_id
     or NEW.runbook_key is distinct from OLD.runbook_key then
    raise exception using errcode = '55000', message = 'Runbook identity is immutable';
  end if;
  return NEW;
end;
$$;
create trigger runbook_identity_immutable before update on runbook
for each row execute function guard_runbook_identity();

create table knowledge_chunk (
  id uuid primary key,
  runbook_version_id uuid not null references runbook_version(id),
  service_id uuid not null references service_catalog(id),
  chunk_no integer not null check (chunk_no >= 0 and chunk_no < 200),
  content text not null check (char_length(content) between 1 and 1200),
  metadata jsonb not null default '{}'::jsonb check (jsonb_typeof(metadata) = 'object'),
  embedding vector(1536) not null check (vector_norm(embedding) > 0),
  embedding_model text not null check (embedding_model ~ '^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$'),
  search_vector tsvector generated always as (to_tsvector('simple', content)) stored,
  content_hash text not null check (content_hash ~ '^[0-9a-f]{64}$'),
  created_at timestamptz not null,
  unique (runbook_version_id, chunk_no),
  unique (runbook_version_id, content_hash)
);
-- The unique (runbook_version_id, chunk_no) index also covers the version FK.
create index knowledge_chunk_service_idx on knowledge_chunk(service_id, embedding_model);
create index knowledge_chunk_fts_idx on knowledge_chunk using gin(search_vector);
create index knowledge_chunk_embedding_hnsw_idx on knowledge_chunk using hnsw (embedding vector_cosine_ops);

create function guard_knowledge_service() returns trigger language plpgsql as $$
begin
  if not exists (
    select 1 from runbook_version rv join runbook r on r.id = rv.runbook_id
    where rv.id = NEW.runbook_version_id and r.service_id = NEW.service_id
  ) then
    raise exception using errcode = '23514', message = 'Knowledge must belong to its Runbook service';
  end if;
  return NEW;
end;
$$;
create trigger knowledge_chunk_service_guard before insert on knowledge_chunk
for each row execute function guard_knowledge_service();
create trigger knowledge_chunk_immutable before update or delete on knowledge_chunk
for each row execute function reject_row_mutation();

create function guard_indexed_runbook_identity() returns trigger language plpgsql as $$
begin
  if NEW.id is distinct from OLD.id or NEW.runbook_id is distinct from OLD.runbook_id
     or NEW.version_number is distinct from OLD.version_number then
    raise exception using errcode = '55000', message = 'Runbook version identity is immutable';
  end if;
  return NEW;
end;
$$;
create trigger runbook_version_identity_immutable before update on runbook_version
for each row execute function guard_indexed_runbook_identity();
