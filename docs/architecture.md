# Resolve Architecture

## Shape

Resolve is a personal command system, not a generic team workspace.
The active MVP information architecture is Todo, Calendar, and Strategy. Capture
goes straight into Todo; there is no user-facing Inbox or Today page.

The current backend direction is Supabase-first. Todo and Strategy remain strict
end-to-end encrypted. Calendar has an optional server-managed Feishu connector
for convenience; in that mode, the backend can see Feishu event plaintext while
calling Feishu APIs, then encrypts calendar payloads at rest.

```text
Mac Tauri / Android
  local SQLite or Room plaintext cache
  secure device key store
  AES-GCM vault key
  AES-GCM encrypted payloads before network
Supabase Auth / Postgres / Realtime
  metadata needed for ordering and sync
  encryptedPayload only for sensitive content
Feishu OpenAPI
  strict E2EE mode: client-side sync
  server connector mode: runtime calendar plaintext, encrypted-at-rest storage
```

## Supabase Tables

```text
resolve_items
resolve_strategy_threads
resolve_calendar_events
resolve_sync_states
resolve_devices
resolve_device_messages
resolve_feishu_connections
```

Metadata can contain IDs, timestamps, status, provider, and sync state. Titles,
notes, descriptions, locations, attendees, strategy content, and calendar raw
payloads must stay inside `encrypted_payload`.

All Supabase tables have RLS enabled and are scoped by `user_id = auth.uid()`.
RLS is access control, not privacy. The privacy boundary is client-side
encryption.

## Zero Plaintext Rule

Cloud storage must not contain plaintext personal content. Calendar is allowed
to be visible to Edge Function runtime only in server connector mode.

Forbidden cloud columns include:

```text
title
content
notes
description
location
attendees
recurrence
reminders
feishuRaw
currentHypothesis
```

The TypeScript Supabase adapter has a runtime guard that refuses to upsert rows
containing these keys into client-vault sync tables. Server-managed calendar rows
use `encryption_scheme = 'server_calendar_v1'` and `RESOLVE_SERVER_SECRET`
encryption.

## Sync

Sync triggers are app open, local mutations, Supabase Realtime, and periodic
background polling as a fallback.

Calendar is a full month view backed by Feishu events. Creating an event from a
day cell or from a Todo creates a local `local_pending_create` event that is
queued for Feishu sync. Linking a Todo to Strategy does not remove it from Todo;
Strategy subtasks are represented as Todo items with `strategyThreadId`.

Feishu handling:

- Strict mode: Mac/Android call Feishu directly, then upload vault-key encrypted
  calendar payloads.
- Server connector mode: Supabase Edge Functions store Feishu App Secret and
  tokens encrypted with `RESOLVE_SERVER_SECRET`, pull Feishu events, write
  `server_calendar_v1` calendar rows, and expose decrypted calendar events only
  to authenticated apps.

Server connector mode is guarded behind:

```text
RESOLVE_ALLOW_FEISHU_SERVER_CONNECTOR=true
RESOLVE_SERVER_SECRET=<random secret>
```

## Conflict Policy

Calendar writes use visible sync states:

- `local_pending_create`
- `local_pending_update`
- `local_pending_delete`
- `readonly`
- `conflict`
- `error`

The first MVP conflict policy is remote-wins, but local changes are retained as
a conflict note instead of being silently overwritten.
