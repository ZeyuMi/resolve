-- E2EE Markdown Note sync.
-- Note titles, canonical file paths, and Markdown bodies are encrypted in payload.

create table if not exists public.resolve_notes (
  user_id uuid not null references auth.users(id) on delete cascade,
  id text not null,
  status text not null check (status in ('active', 'archived', 'conflict')),
  created_at timestamptz not null,
  updated_at timestamptz not null,
  last_opened_at timestamptz,
  task_id text,
  strategy_thread_id text,
  parent_note_id text,
  content_hash text,
  frontmatter_hash text,
  encrypted_payload text not null,
  payload_nonce text not null,
  payload_version integer not null default 1 check (payload_version = 1),
  primary key (user_id, id)
);

create index if not exists resolve_notes_user_updated_idx
  on public.resolve_notes (user_id, updated_at desc);

create index if not exists resolve_notes_task_idx
  on public.resolve_notes (user_id, task_id)
  where task_id is not null;

alter table public.resolve_notes enable row level security;

drop policy if exists "notes are user scoped" on public.resolve_notes;
create policy "notes are user scoped"
  on public.resolve_notes
  to authenticated
  using ((select auth.uid()) = user_id)
  with check ((select auth.uid()) = user_id);

do $$
begin
  alter publication supabase_realtime add table public.resolve_notes;
exception
  when duplicate_object then null;
end $$;
