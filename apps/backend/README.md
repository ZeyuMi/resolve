# Resolve Backend

Supabase backend for Resolve.

## Security Model

Default mode is mixed privacy:

- Mac and Android keep a local plaintext cache.
- A user vault key lives only in secure device storage or recovery material.
- Todo, Strategy, comments, and attachment metadata are encrypted before network
  upload with the user vault key.
- Calendar can be server-managed for convenience. In that mode, Feishu event
  plaintext is visible to the Edge Function at runtime, then encrypted at rest
  with `RESOLVE_SERVER_SECRET`.
- Supabase stores metadata plus `encrypted_payload`, `payload_nonce`, and
  `payload_version`.
- Supabase RLS ensures authenticated users can only access their own rows.

RLS is not the privacy boundary. Client-side encryption is.

## Feishu Server Connector

The backend can pull Feishu events server-side when explicitly enabled:

```bash
RESOLVE_ALLOW_FEISHU_SERVER_CONNECTOR=true
RESOLVE_SERVER_SECRET=<random 32+ character secret>
RESOLVE_FEISHU_APP_ID=<feishu app id>
RESOLVE_FEISHU_APP_SECRET=<feishu app secret>
```

Supported actions on `feishu-connector`:

- `start_oauth`: return a Feishu authorization URL using the server-side Feishu
  app config.
- `configure`: optional legacy path to store a per-user Feishu App ID / App
  Secret encrypted with `RESOLVE_SERVER_SECRET`.
- `sync_now`: pull Feishu events and write `server_calendar_v1` rows.
- `list_events`: return decrypted server-managed calendar events to an
  authenticated app.
- `disconnect`: remove the server token set.

In production, Feishu App ID / App Secret should live in Supabase secrets only.
Apps should call `start_oauth` for one-time user authorization, then read
calendar rows with `list_events`; routine refresh is handled by the scheduled
`feishu-sync-cron` job.

OAuth callback:

```text
https://<project-ref>.supabase.co/functions/v1/feishu-oauth-callback
```

Register that URL in the Feishu custom app redirect URI list.

Todo and Strategy data never use the Feishu server connector.

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
supabase functions deploy feishu-oauth-callback
supabase functions deploy feishu-sync-cron
supabase secrets set RESOLVE_ALLOW_FEISHU_SERVER_CONNECTOR=true
supabase secrets set RESOLVE_SERVER_SECRET=<random 32+ character secret>
supabase secrets set RESOLVE_FEISHU_APP_ID=<feishu app id>
supabase secrets set RESOLVE_FEISHU_APP_SECRET=<feishu app secret>
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
