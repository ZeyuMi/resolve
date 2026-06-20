# Resolve Backend

Supabase backend for Resolve.

## Security Model

Default mode is strict E2EE:

- Mac and Android keep a local plaintext cache.
- A user vault key lives only in secure device storage or recovery material.
- Todo, Strategy, Calendar, comments, attachments metadata, Feishu raw event
  payloads, and sync tokens are encrypted before network upload.
- Supabase stores metadata plus `encrypted_payload`, `payload_nonce`, and
  `payload_version`.
- Supabase RLS ensures authenticated users can only access their own rows.

RLS is not the privacy boundary. Client-side encryption is.

## Feishu

The default backend does not pull Feishu events server-side.

Reason: strict E2EE means the cloud cannot see personal data. A server-side
Feishu connector would receive plaintext calendar events from Feishu before it
could encrypt them, so it is disabled by default.

The guarded Edge Functions return a clear error unless this environment variable
is explicitly set:

```bash
RESOLVE_ALLOW_FEISHU_SERVER_CONNECTOR=true
```

Even then, the connector is intentionally a stub until the runtime plaintext
tradeoff is explicitly accepted.

## Local Development

```bash
npm --workspace @resolve/backend run supabase:start
npm --workspace @resolve/backend run supabase:reset
npm --workspace @resolve/backend run supabase:functions
```

## Deploy Sketch

```bash
cd apps/backend
supabase link --project-ref <project-ref>
supabase db push
supabase functions deploy health
supabase functions deploy feishu-connector
supabase functions deploy feishu-sync-cron
```

Do not commit `.env` files. Store production secrets in Supabase secrets.

## Tables

- `resolve_items`
- `resolve_strategy_threads`
- `resolve_calendar_events`
- `resolve_sync_states`
- `resolve_devices`
- `resolve_device_messages`
- `resolve_feishu_connections`

All tables are scoped by `user_id` and RLS policies using `auth.uid()`.
