# Resolve Architecture

## Shape

Resolve is a personal command system, not a generic team workspace.
The active MVP information architecture is Todo, Calendar, and Strategy. Capture
goes straight into Todo; there is no user-facing Inbox or Today page. The MVP
keeps all plaintext on device and uses Firestore only as an encrypted transport.

```text
Mac Tauri / Android
  local SQLite or Room plaintext
  secure device key store
  AES-GCM encrypted payloads
Firestore
  metadata needed for ordering and sync
  encryptedPayload only for sensitive content
Feishu OpenAPI
  user-owned app credentials
  tokens stored locally only
```

## Collections

```text
users/{userId}/items/{itemId}
users/{userId}/strategyThreads/{threadId}
users/{userId}/calendarEvents/{calendarEventId}
users/{userId}/syncStates/{provider}
```

Firestore metadata can contain IDs, timestamps, status, provider, and sync
state. Titles, notes, descriptions, locations, attendees, strategy content, and
calendar raw payloads must stay inside `encryptedPayload`.

## Sync

The MVP sync triggers are app open, manual refresh, and periodic background
polling. It intentionally does not use Feishu webhooks because there is no
self-hosted backend.

Calendar is a full month view backed by Feishu events. Creating an event from a
day cell or from a Todo creates a local `local_pending_create` event that is
queued for Feishu sync. Linking a Todo to Strategy does not remove it from Todo;
Strategy subtasks are represented as Todo items with `strategyThreadId`.

Feishu token handling:

- App ID and App Secret are entered by the user.
- App Secret is stored in macOS Keychain or Android Keystore-backed storage.
- access_token and refresh_token are stored locally only.
- Tokens are never pushed to Firebase.
- A future token vault must be explicit and encrypted with the vault key.

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
