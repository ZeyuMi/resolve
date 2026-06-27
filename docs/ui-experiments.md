# UI Experiment Guide

Resolve should support fast visual experiments without touching sync, Feishu, or
local persistence.

## What To Change

For a Mac UI style experiment, prefer changing:

```text
apps/mac/src/App.tsx
apps/mac/src/styles.css
packages/ui/src/tokens.ts
```

For Android UI style experiments, prefer changing:

```text
apps/android/app/src/main/java/ai/tiiny/resolve/MainActivity.kt
packages/ui/src/tokens.ts as the cross-platform visual source of truth
```

## What Not To Change For UI Experiments

Avoid editing these unless the product behavior itself changes:

```text
packages/core/src/types.ts
packages/core/src/state.ts
packages/core/src/notes.ts
packages/core/src/ports.ts
packages/sync/src/*
packages/feishu/src/*
apps/backend/supabase/*
```

## Fakeable Boundaries

UI prototypes should run against ports from `packages/core/src/ports.ts`:

```text
ResolveStateRepository
ResolveSyncPort
NoteFilePort
SecureSecretPort
CalendarProviderPort
```

This makes it possible to test:

- a new Todo layout with in-memory tasks
- a new Calendar skin without Feishu
- a Vault editor without touching local files
- sync status UI without Supabase

## Current Refactor Status

Done:

- Core Todo/Strategy/Calendar constructors live in `packages/core/src/state.ts`.
- Note/Markdown identity helpers live in `packages/core/src/notes.ts`.
- Replaceable app ports live in `packages/core/src/ports.ts`.
- Supabase sync implements the standard sync port.
- Browser local repository implements the standard repository port.

Still intentionally pending:

- Split Mac `App.tsx` into per-tab view files.
- Split Android `MainActivity.kt` into per-feature Compose screens.
- Add fake repository/sync fixtures for visual regression tests.
- Add screenshot-driven UI preview routes.
