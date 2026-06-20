-- Resolve E2EE sync schema.
--
-- Security invariant:
--   Cloud tables never store Todo titles, notes, strategy text, calendar titles,
--   locations, descriptions, attendees, or Feishu raw payloads in plaintext.
--   Sensitive data must be JSON-serialized and encrypted on device before insert.

create extension if not exists "pgcrypto";

create table if not exists public.resolve_devices (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  public_key text,
  encrypted_name text,
  name_nonce text,
  platform text not null check (platform in ('mac', 'android', 'unknown')),
  created_at timestamptz not null default now(),
  last_seen_at timestamptz not null default now(),
  unique (user_id, id)
);

create table if not exists public.resolve_items (
  user_id uuid not null references auth.users(id) on delete cascade,
  id text not null,
  type text not null,
  status text not null,
  route text,
  source text not null,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  due_at timestamptz,
  review_at timestamptz,
  strategy_thread_id text,
  parent_item_id text,
  source_item_id text,
  deleted_at timestamptz,
  encrypted_payload text not null,
  payload_nonce text not null,
  payload_version integer not null default 1 check (payload_version = 1),
  primary key (user_id, id)
);

create table if not exists public.resolve_strategy_threads (
  user_id uuid not null references auth.users(id) on delete cascade,
  id text not null,
  status text not null check (status in ('active', 'quiet', 'archived')),
  created_at timestamptz not null,
  updated_at timestamptz not null,
  next_review_at timestamptz,
  encrypted_payload text not null,
  payload_nonce text not null,
  payload_version integer not null default 1 check (payload_version = 1),
  primary key (user_id, id)
);

create table if not exists public.resolve_calendar_events (
  user_id uuid not null references auth.users(id) on delete cascade,
  id text not null,
  provider text not null check (provider in ('feishu', 'local')),
  external_calendar_id text,
  external_event_id text,
  status text not null,
  starts_at timestamptz not null,
  ends_at timestamptz,
  is_all_day boolean not null default false,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  remote_updated_at timestamptz,
  last_synced_at timestamptz,
  source_item_id text,
  strategy_thread_id text,
  can_edit boolean,
  can_delete boolean,
  encrypted_payload text not null,
  payload_nonce text not null,
  payload_version integer not null default 1 check (payload_version = 1),
  primary key (user_id, id)
);

create table if not exists public.resolve_sync_states (
  user_id uuid not null references auth.users(id) on delete cascade,
  provider text not null check (provider in ('feishu')),
  status text not null check (status in ('ok', 'needs_auth', 'error', 'disabled')),
  last_full_sync_at timestamptz,
  last_incremental_sync_at timestamptz,
  encrypted_sync_token text,
  sync_token_nonce text,
  encrypted_error text,
  error_nonce text,
  updated_at timestamptz not null,
  primary key (user_id, provider)
);

create table if not exists public.resolve_device_messages (
  user_id uuid not null references auth.users(id) on delete cascade,
  id uuid primary key default gen_random_uuid(),
  target_device_id uuid not null,
  kind text not null,
  created_at timestamptz not null default now(),
  consumed_at timestamptz,
  encrypted_payload text not null,
  payload_nonce text not null,
  payload_version integer not null default 1 check (payload_version = 1)
);

create table if not exists public.resolve_feishu_connections (
  user_id uuid primary key references auth.users(id) on delete cascade,
  mode text not null default 'client_e2ee' check (mode in ('client_e2ee', 'server_connector_opt_in')),
  status text not null default 'not_connected' check (
    status in ('not_connected', 'connected', 'needs_auth', 'permission_error', 'error')
  ),
  -- Encrypted on device. In strict E2EE mode the backend cannot decrypt this.
  encrypted_config text,
  config_nonce text,
  encrypted_token_vault text,
  token_vault_nonce text,
  updated_at timestamptz not null default now()
);

create index if not exists resolve_items_user_updated_idx
  on public.resolve_items (user_id, updated_at desc);

create index if not exists resolve_items_user_due_idx
  on public.resolve_items (user_id, due_at)
  where due_at is not null and deleted_at is null;

create index if not exists resolve_strategy_threads_user_updated_idx
  on public.resolve_strategy_threads (user_id, updated_at desc);

create index if not exists resolve_calendar_events_user_starts_idx
  on public.resolve_calendar_events (user_id, starts_at, ends_at);

create index if not exists resolve_device_messages_target_idx
  on public.resolve_device_messages (user_id, target_device_id, consumed_at, created_at);

alter table public.resolve_devices enable row level security;
alter table public.resolve_items enable row level security;
alter table public.resolve_strategy_threads enable row level security;
alter table public.resolve_calendar_events enable row level security;
alter table public.resolve_sync_states enable row level security;
alter table public.resolve_device_messages enable row level security;
alter table public.resolve_feishu_connections enable row level security;

drop policy if exists "devices are user scoped" on public.resolve_devices;
create policy "devices are user scoped"
  on public.resolve_devices
  to authenticated
  using ((select auth.uid()) = user_id)
  with check ((select auth.uid()) = user_id);

drop policy if exists "items are user scoped" on public.resolve_items;
create policy "items are user scoped"
  on public.resolve_items
  to authenticated
  using ((select auth.uid()) = user_id)
  with check ((select auth.uid()) = user_id);

drop policy if exists "strategy threads are user scoped" on public.resolve_strategy_threads;
create policy "strategy threads are user scoped"
  on public.resolve_strategy_threads
  to authenticated
  using ((select auth.uid()) = user_id)
  with check ((select auth.uid()) = user_id);

drop policy if exists "calendar events are user scoped" on public.resolve_calendar_events;
create policy "calendar events are user scoped"
  on public.resolve_calendar_events
  to authenticated
  using ((select auth.uid()) = user_id)
  with check ((select auth.uid()) = user_id);

drop policy if exists "sync states are user scoped" on public.resolve_sync_states;
create policy "sync states are user scoped"
  on public.resolve_sync_states
  to authenticated
  using ((select auth.uid()) = user_id)
  with check ((select auth.uid()) = user_id);

drop policy if exists "device messages are user scoped" on public.resolve_device_messages;
create policy "device messages are user scoped"
  on public.resolve_device_messages
  to authenticated
  using ((select auth.uid()) = user_id)
  with check ((select auth.uid()) = user_id);

drop policy if exists "feishu connections are user scoped" on public.resolve_feishu_connections;
create policy "feishu connections are user scoped"
  on public.resolve_feishu_connections
  to authenticated
  using ((select auth.uid()) = user_id)
  with check ((select auth.uid()) = user_id);

alter publication supabase_realtime add table public.resolve_items;
alter publication supabase_realtime add table public.resolve_strategy_threads;
alter publication supabase_realtime add table public.resolve_calendar_events;
alter publication supabase_realtime add table public.resolve_sync_states;
alter publication supabase_realtime add table public.resolve_device_messages;
