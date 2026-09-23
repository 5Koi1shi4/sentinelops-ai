create table webhook_replay_nonce (
  source_key text not null,
  nonce_hash text not null,
  observed_at timestamptz not null,
  expires_at timestamptz not null,
  primary key (source_key, nonce_hash),
  constraint webhook_replay_source_key_check check
    (source_key ~ '^[a-z][a-z0-9-]{0,63}$'),
  constraint webhook_replay_nonce_hash_check check
    (nonce_hash ~ '^[a-f0-9]{64}$'),
  constraint webhook_replay_expiry_check check (expires_at > observed_at)
);

create index webhook_replay_nonce_expiry_idx on webhook_replay_nonce(expires_at);
