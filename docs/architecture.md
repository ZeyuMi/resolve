# Resolve Architecture

## Shape

Resolve is a personal command system, not a generic team workspace.
The active MVP information architecture is Todo, Calendar, and Strategy. Capture
goes straight into Todo; there is no user-facing Inbox or Today page.

The current backend direction is Supabase-first, with strict end-to-end
encryption for user content. Firestore remains as an older sync adapter during
the migration, but new backend work should target Supabase.

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
  strict E2EE mode: client-side sync only
  optional token broker mode: disabled until explicitly accepted
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

Cloud storage must not contain plaintext personal content.

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
containing these keys.

## Sync

Sync triggers are app open, local mutations, Supabase Realtime, and periodic
background polling as a fallback.

Calendar is a full month view backed by Feishu events. Creating an event from a
day cell or from a Todo creates a local `local_pending_create` event that is
queued for Feishu sync. Linking a Todo to Strategy does not remove it from Todo;
Strategy subtasks are represented as Todo items with `strategyThreadId`.

Feishu handling in strict E2EE mode:

- App ID and App Secret can be stored locally in macOS Keychain or Android
  Keystore-backed storage.
- access_token and refresh_token stay local or are stored in Supabase only as
  vault-key encrypted blobs.
- Supabase may store encrypted Feishu config/status but cannot decrypt it.
- Mac/Android call Feishu directly, then upload encrypted calendar payloads.

Server-side Feishu sync is not compatible with strict E2EE. If a Supabase Edge
Function calls Feishu OpenAPI, the function runtime can see event plaintext
before encryption. That mode is therefore guarded behind
`RESOLVE_ALLOW_FEISHU_SERVER_CONNECTOR=true` and is intentionally not
implemented yet.

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
