import {
  FeishuServerClient,
  type CreateFeishuServerEventInput,
  type FeishuServerCalendar,
  type FeishuServerConfig,
  type FeishuServerEvent,
  type FeishuTokenSet
} from "./feishuApi.ts";
import { serverDecryptJson, serverEncryptJson } from "./serverCrypto.ts";
import { encodeFilter, restPatch, restSelect, restUpsert } from "./supabaseRest.ts";

interface ConnectionRow {
  user_id: string;
  mode: "client_e2ee" | "server_connector_opt_in";
  status: string;
  server_encrypted_config?: string | null;
  server_config_nonce?: string | null;
  server_encrypted_token_set?: string | null;
  server_token_nonce?: string | null;
  default_calendar_id?: string | null;
}

interface CalendarEventRow {
  user_id: string;
  id: string;
  encryption_scheme: "server_calendar_v1";
  provider: "feishu";
  external_calendar_id: string;
  external_event_id: string;
  status: string;
  starts_at: string;
  ends_at?: string | null;
  is_all_day: boolean;
  created_at: string;
  updated_at: string;
  remote_updated_at?: string | null;
  last_synced_at: string;
  can_edit?: boolean | null;
  can_delete?: boolean | null;
  encrypted_payload: string;
  payload_nonce: string;
  payload_version: 1;
}

export interface SyncResult {
  userId: string;
  calendarId: string;
  calendarIds: string[];
  upserted: number;
  remoteDeleted: number;
  syncedAt: string;
  skippedCalendars?: Array<{ calendarId: string; error: string }>;
}

const fullSyncPastDays = 3650 * 2;
const fullSyncFutureDays = 3650;

export async function syncFeishuForUser(userId: string): Promise<SyncResult> {
  const connection = await loadConnection(userId);
  const config = await decryptConfig(connection);
  let tokenSet = await decryptToken(connection);
  let client = new FeishuServerClient(config, tokenSet);

  if (isTokenExpired(tokenSet)) {
    tokenSet = await client.refreshAccessToken();
    await saveToken(userId, tokenSet);
    client = new FeishuServerClient(config, tokenSet);
  }

  const primaryCalendar = await client.getPrimaryCalendar();
  const defaultCalendarId = connection.default_calendar_id ?? primaryCalendar.calendarId;
  const calendars = await readableFeishuCalendars(client, primaryCalendar, defaultCalendarId);
  const syncedAt = new Date().toISOString();
  const startsAt = new Date(Date.now() - fullSyncPastDays * 24 * 60 * 60 * 1000).toISOString();
  const endsAt = new Date(Date.now() + fullSyncFutureDays * 24 * 60 * 60 * 1000).toISOString();

  const rows: CalendarEventRow[] = [];
  const successfulCalendarIds = new Set<string>();
  const skippedCalendars: Array<{ calendarId: string; error: string }> = [];

  for (const calendar of calendars) {
    const events: FeishuServerEvent[] = [];
    let pageToken: string | undefined;
    try {
      do {
        const page = await client.listEvents({
          calendarId: calendar.calendarId,
          startsAt,
          endsAt,
          pageToken
        });
        events.push(...page.events);
        pageToken = page.nextPageToken;
      } while (pageToken);

      successfulCalendarIds.add(calendar.calendarId);
      for (const event of events.filter((item) => item.eventId)) {
        rows.push(await toCalendarEventRow(userId, event.calendarId || calendar.calendarId, event, syncedAt));
      }
    } catch (error) {
      skippedCalendars.push({
        calendarId: calendar.calendarId,
        error: error instanceof Error ? error.message : String(error)
      });
    }
  }

  if (!successfulCalendarIds.size) {
    throw new Error(skippedCalendars[0]?.error ?? "No Feishu calendars could be synced.");
  }

  if (rows.length) {
    await restUpsert("resolve_calendar_events", rows, "user_id,id");
  }

  const remoteIdsByCalendar = new Map<string, Set<string>>();
  for (const row of rows) {
    const remoteIds = remoteIdsByCalendar.get(row.external_calendar_id) ?? new Set<string>();
    remoteIds.add(row.id);
    remoteIdsByCalendar.set(row.external_calendar_id, remoteIds);
  }

  let remoteDeleted = 0;
  for (const calendarId of successfulCalendarIds) {
    const remoteIds = remoteIdsByCalendar.get(calendarId) ?? new Set<string>();
    const existingRows = await restSelect<{ id: string }>(
      "resolve_calendar_events",
      [
        "select=id",
        `user_id=eq.${encodeFilter(userId)}`,
        "encryption_scheme=eq.server_calendar_v1",
        `external_calendar_id=eq.${encodeFilter(calendarId)}`,
        `starts_at=gte.${encodeFilter(startsAt)}`,
        `starts_at=lte.${encodeFilter(endsAt)}`
      ].join("&")
    );
    const missingIds = existingRows.map((row) => row.id).filter((id) => !remoteIds.has(id));
    remoteDeleted += missingIds.length;
    if (missingIds.length) {
      await Promise.all(
        missingIds.map((id) =>
          restPatch(
            "resolve_calendar_events",
            `user_id=eq.${encodeFilter(userId)}&id=eq.${encodeFilter(id)}`,
            {
              status: "remote_deleted",
              last_synced_at: syncedAt,
              updated_at: syncedAt
            }
          )
        )
      );
    }
  }

  await restUpsert(
    "resolve_sync_states",
    {
      user_id: userId,
      provider: "feishu",
      status: "ok",
      last_full_sync_at: syncedAt,
      updated_at: syncedAt
    },
    "user_id,provider"
  );
  await restPatch(
    "resolve_feishu_connections",
    `user_id=eq.${encodeFilter(userId)}`,
    {
      status: "connected",
      default_calendar_id: defaultCalendarId,
      last_server_sync_at: syncedAt,
      updated_at: syncedAt
    }
  );

  return {
    userId,
    calendarId: defaultCalendarId,
    calendarIds: Array.from(successfulCalendarIds),
    upserted: rows.length,
    remoteDeleted,
    syncedAt,
    skippedCalendars: skippedCalendars.length ? skippedCalendars : undefined
  };
}

async function readableFeishuCalendars(
  client: FeishuServerClient,
  primary: FeishuServerCalendar,
  defaultCalendarId?: string
) {
  try {
    const calendars = await client.listCalendars();
    const defaultCalendar =
      defaultCalendarId && defaultCalendarId !== "primary"
        ? { calendarId: defaultCalendarId, type: "primary" } satisfies FeishuServerCalendar
        : undefined;
    const all = [primary, ...(defaultCalendar ? [defaultCalendar] : []), ...calendars];
    const unique = new Map(
      all
        .filter((calendar) => calendar.calendarId)
        .map((calendar) => [calendar.calendarId, calendar])
    );
    return Array.from(unique.values());
  } catch {
    return defaultCalendarId && defaultCalendarId !== "primary"
      ? [primary, { calendarId: defaultCalendarId, type: "primary" }]
      : [primary];
  }
}

export async function createFeishuEventForUser(
  userId: string,
  input: CreateFeishuServerEventInput & { calendarId?: string }
) {
  const connection = await loadConnection(userId);
  const config = await decryptConfig(connection);
  let tokenSet = await decryptToken(connection);
  let client = new FeishuServerClient(config, tokenSet);

  if (isTokenExpired(tokenSet)) {
    tokenSet = await client.refreshAccessToken();
    await saveToken(userId, tokenSet);
    client = new FeishuServerClient(config, tokenSet);
  }

  const primaryCalendar = await client.getPrimaryCalendar();
  const calendarId = input.calendarId ?? connection.default_calendar_id ?? primaryCalendar.calendarId;
  const event = await client.createEvent(calendarId, input);
  const syncedAt = new Date().toISOString();
  const row = await toCalendarEventRow(userId, calendarId, event, syncedAt);
  await restUpsert("resolve_calendar_events", row, "user_id,id");
  await restPatch(
    "resolve_feishu_connections",
    `user_id=eq.${encodeFilter(userId)}`,
    {
      status: "connected",
      default_calendar_id: calendarId,
      last_server_sync_at: syncedAt,
      updated_at: syncedAt
    }
  );

  return {
    event: await calendarEventEnvelope(row),
    syncedAt
  };
}

export async function readServerCalendarEvents(userId: string, startsAt?: string, endsAt?: string) {
  const filters = [
    "select=*",
    `user_id=eq.${encodeFilter(userId)}`,
    "encryption_scheme=eq.server_calendar_v1",
    "status=neq.remote_deleted"
  ];
  if (startsAt) filters.push(`starts_at=gte.${encodeFilter(startsAt)}`);
  if (endsAt) filters.push(`starts_at=lte.${encodeFilter(endsAt)}`);
  filters.push("order=starts_at.asc");

  const rows = await restSelect<CalendarEventRow>("resolve_calendar_events", filters.join("&"));
  return Promise.all(
    rows.map(calendarEventEnvelope)
  );
}

export async function loadConnection(userId: string) {
  const rows = await restSelect<ConnectionRow>(
    "resolve_feishu_connections",
    `select=*&user_id=eq.${encodeFilter(userId)}&mode=eq.server_connector_opt_in&limit=1`
  );
  const connection = rows[0];
  if (!connection) throw new Error("Feishu server connector is not configured.");
  return connection;
}

async function decryptConfig(connection: ConnectionRow) {
  if (!connection.server_encrypted_config || !connection.server_config_nonce) {
    throw new Error("Missing encrypted Feishu config.");
  }
  return serverDecryptJson<FeishuServerConfig>(connection.server_encrypted_config, connection.server_config_nonce);
}

async function decryptToken(connection: ConnectionRow) {
  if (!connection.server_encrypted_token_set || !connection.server_token_nonce) {
    throw new Error("Missing Feishu token set. Reconnect Feishu.");
  }
  return serverDecryptJson<FeishuTokenSet>(connection.server_encrypted_token_set, connection.server_token_nonce);
}

async function saveToken(userId: string, tokenSet: FeishuTokenSet) {
  const encrypted = await serverEncryptJson(tokenSet);
  await restPatch(
    "resolve_feishu_connections",
    `user_id=eq.${encodeFilter(userId)}`,
    {
      server_encrypted_token_set: encrypted.encrypted,
      server_token_nonce: encrypted.nonce,
      updated_at: new Date().toISOString()
    }
  );
}

function isTokenExpired(tokenSet: FeishuTokenSet) {
  if (!tokenSet.expiresAt) return false;
  return new Date(tokenSet.expiresAt).getTime() < Date.now() + 60_000;
}

function feishuEventRowId(calendarId: string, eventId: string) {
  return `feishu_${calendarId}_${eventId}`.replace(/[^a-zA-Z0-9_-]/g, "_");
}

async function toCalendarEventRow(
  userId: string,
  calendarId: string,
  event: FeishuServerEvent,
  syncedAt: string
): Promise<CalendarEventRow> {
  const encrypted = await serverEncryptJson({
    title: event.title ?? "Untitled Feishu event",
    description: event.description,
    location: event.location,
    recurrence: event.recurrence,
    feishuRaw: event.raw
  });
  return {
    user_id: userId,
    id: feishuEventRowId(calendarId, event.eventId),
    encryption_scheme: "server_calendar_v1",
    provider: "feishu",
    external_calendar_id: calendarId,
    external_event_id: event.eventId,
    status: event.status === "cancelled" ? "remote_deleted" : event.canEdit === false ? "readonly" : "synced",
    starts_at: event.startsAt,
    ends_at: event.endsAt ?? null,
    is_all_day: Boolean(event.isAllDay),
    created_at: syncedAt,
    updated_at: event.updatedAt ?? syncedAt,
    remote_updated_at: event.updatedAt ?? null,
    last_synced_at: syncedAt,
    can_edit: event.canEdit ?? null,
    can_delete: event.canDelete ?? null,
    encrypted_payload: encrypted.encrypted,
    payload_nonce: encrypted.nonce,
    payload_version: 1
  };
}

async function calendarEventEnvelope(row: CalendarEventRow) {
  return {
    meta: {
      id: row.id,
      provider: row.provider,
      externalCalendarId: row.external_calendar_id,
      externalEventId: row.external_event_id,
      status: row.status,
      startsAt: row.starts_at,
      endsAt: row.ends_at,
      isAllDay: row.is_all_day,
      createdAt: row.created_at,
      updatedAt: row.updated_at,
      remoteUpdatedAt: row.remote_updated_at,
      lastSyncedAt: row.last_synced_at,
      canEdit: row.can_edit,
      canDelete: row.can_delete,
      encryptionScheme: row.encryption_scheme
    },
    payload: await serverDecryptJson(row.encrypted_payload, row.payload_nonce)
  };
}
