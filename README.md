# Resolve

Private, cross-device command system:

- 3-second capture straight into Todo
- Todo as the only execution list
- Full Feishu-backed Calendar
- Strategy threads
- Strategy subtasks reflected in Todo
- encrypted Firestore sync
- Feishu Calendar client boundary

## Structure

```text
apps/
  mac/       Tauri + React + TypeScript shell
  android/   Kotlin + Jetpack Compose shell
packages/
  core/      shared data model, triage rules, Today aggregation
  crypto/    AES-GCM payload encryption helpers
  sync/      Firestore encrypted document contracts and local browser repo
  feishu/    Feishu Calendar OAuth/API contract
  ui/        shared design tokens
firebase/   Firestore rules and indexes
docs/        architecture notes
```

## Run Mac Web Shell

```bash
npm install
npm run dev:mac
```

The Tauri shell is in `apps/mac/src-tauri`. The current Rust toolchain on this
machine may need an update before `tauri dev` can build native macOS binaries.

## Privacy Contract

Firestore stores `encryptedPayload`, `payloadNonce`, and non-sensitive metadata.
The vault key, Feishu App Secret, and Feishu tokens stay local.

## MVP Status

Implemented in this scaffold:

- shared Item, StrategyThread, CalendarEvent, SyncState models
- local SQLite schema for Item, StrategyThread, CalendarEvent, SyncState
- rule-based route suggestions
- Capture directly creates active Todo items
- Todo items can be linked to Feishu Calendar drafts and Strategy threads while staying in Todo
- Full month Calendar view with add-event draft flow
- Strategy thread view with subtasks reflected in Todo
- AES-GCM encryption helpers
- Firestore encrypted document mapper and rules
- Feishu OAuth URL, token refresh, primary calendar, list/create/update/delete client boundary
- Android Compose project skeleton with Capture, Todo, Calendar, Strategy, Settings

Next integration work:

- wire Tauri commands to macOS Keychain and SQLite
- wire Android Room, Keystore, Firebase SDK, and WorkManager implementation
- verify Feishu scopes and endpoints against the user's own Feishu app
- add real OAuth callback handling on each platform
