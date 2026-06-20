-- Server-side Feishu calendar connector.
--
-- This is intentionally scoped to calendar data. Todo and Strategy remain
-- strict client-side E2EE through vault-key encrypted payloads.

alter table public.resolve_calendar_events
  add column if not exists encryption_scheme text not null default 'vault_v1'
    check (encryption_scheme in ('vault_v1', 'server_calendar_v1'));

alter table public.resolve_feishu_connections
  add column if not exists server_encrypted_config text,
  add column if not exists server_config_nonce text,
  add column if not exists server_encrypted_token_set text,
  add column if not exists server_token_nonce text,
  add column if not exists default_calendar_id text,
  add column if not exists last_server_sync_at timestamptz;

create table if not exists public.resolve_feishu_oauth_states (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  state text not null unique,
  server_encrypted_config text not null,
  server_config_nonce text not null,
  redirect_uri text not null,
  created_at timestamptz not null default now(),
  expires_at timestamptz not null,
  consumed_at timestamptz
);

create index if not exists resolve_feishu_oauth_states_state_idx
  on public.resolve_feishu_oauth_states (state)
  where consumed_at is null;

create index if not exists resolve_calendar_events_server_idx
  on public.resolve_calendar_events (user_id, encryption_scheme, starts_at);

alter table public.resolve_feishu_oauth_states enable row level security;

-- No user-facing policy on oauth states. Edge Functions access this table with
-- the service role and only expose opaque status/results to clients.
