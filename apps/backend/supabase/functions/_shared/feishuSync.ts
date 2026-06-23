import {
  FeishuAuthorizationRequiredError,
  FeishuServerClient,
  type CreateFeishuServerEventInput,
  type FeishuServerCalendar,
  type FeishuServerConfig,
  type FeishuServerEvent,
  type FeishuTokenSet,
  type UpdateFeishuServerEventInput,
  isFeishuAuthorizationRequiredError
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

interface SyncStateRow {
  status: string;
  last_full_sync_at?: string | null;
  last_incremental_sync_at?: string | null;
  encrypted_sync_token?: string | null;
  sync_token_nonce?: string | null;
}

interface FeishuSyncTokenVault {
  version: 1;
  defaultCalendarId?: string;
  calendarIds?: string[];
  calendarTokens: Record<string, string>;
  lastFullSyncAt?: string | null;
  lastIncrementalSyncAt?: string | null;
}

interface CalendarFetchResult {
  events: FeishuServerEvent[];
  nextSyncToken?: string;
  mode: "full" | "incremental";
}

export interface SyncResult {
  userId: string;
  calendarId: string;
  calendarIds: string[];
  mode: "full" | "incremental" | "mixed";
  upserted: number;
  fetched: number;
  stored: number;
  deduped: number;
  remoteDeleted: number;
  syncedAt: string;
  fullSyncedCalendars?: string[];
  incrementalSyncedCalendars?: string[];
  minStartsAt?: string;
  maxStartsAt?: string;
  skippedCalendars?: Array<{ calendarId: string; error: string }>;
}

const fullSyncPastDays = 3650 * 2;
const fullSyncFutureDays = 3650;

export async function syncFeishuForUser(userId: string): Promise<SyncResult> {
  return withFeishuAuthRecovery(userId, () => syncFeishuForUserUnchecked(userId));
}

async function syncFeishuForUserUnchecked(userId: string): Promise<SyncResult> {
  const connection = await loadConnection(userId);
  const config = await decryptConfig(connection);
  let tokenSet = await decryptToken(connection);
  let client = new FeishuServerClient(config, tokenSet);

  if (isTokenExpired(tokenSet)) {
    tokenSet = await refreshTokenForUser(userId, client, tokenSet);
    await saveToken(userId, tokenSet);
    client = new FeishuServerClient(config, tokenSet);
  }

  const primaryCalendar = await client.getPrimaryCalendar();
  const defaultCalendarId = connection.default_calendar_id ?? primaryCalendar.calendarId;
  const calendars = await readableFeishuCalendars(client, primaryCalendar, defaultCalendarId);
  const syncedAt = new Date().toISOString();
  const startsAt = new Date(Date.now() - fullSyncPastDays * 24 * 60 * 60 * 1000).toISOString();
  const endsAt = new Date(Date.now() + fullSyncFutureDays * 24 * 60 * 60 * 1000).toISOString();
  const syncTokenVault = await loadSyncTokenVault(userId);
  const nextCalendarTokens = {
    ...syncTokenVault.calendarTokens
  };

  const candidates: Array<{ calendarId: string; event: FeishuServerEvent }> = [];
  const successfulCalendarIds = new Set<string>();
  const fullSyncedCalendarIds = new Set<string>();
  const incrementalSyncedCalendarIds = new Set<string>();
  const skippedCalendars: Array<{ calendarId: string; error: string }> = [];

  for (const calendar of calendars) {
    try {
      const fetchResult = await fetchEventsForCalendar(
        client,
        calendar.calendarId,
        startsAt,
        endsAt,
        syncTokenVault.calendarTokens[calendar.calendarId]
      );
      successfulCalendarIds.add(calendar.calendarId);
      if (fetchResult.mode === "full") {
        fullSyncedCalendarIds.add(calendar.calendarId);
      } else {
        incrementalSyncedCalendarIds.add(calendar.calendarId);
      }
      if (fetchResult.nextSyncToken) {
        nextCalendarTokens[calendar.calendarId] = fetchResult.nextSyncToken;
      }
      for (const event of fetchResult.events.filter((item) => item.eventId)) {
        candidates.push({
          calendarId: event.calendarId || calendar.calendarId,
          event
        });
      }
    } catch (error) {
      if (isFeishuAuthorizationRequiredError(error)) {
        throw error;
      }
      skippedCalendars.push({
        calendarId: calendar.calendarId,
        error: error instanceof Error ? error.message : String(error)
      });
    }
  }

  if (!successfulCalendarIds.size) {
    throw new Error(skippedCalendars[0]?.error ?? "No Feishu calendars could be synced.");
  }

  const uniqueCandidates = dedupeFeishuEventCandidates(candidates, defaultCalendarId);
  const rows = await Promise.all(
    uniqueCandidates.map(({ calendarId, event }) =>
      toCalendarEventRow(userId, calendarId, event, syncedAt)
    )
  );
  const rowStarts = rows.map((row) => row.starts_at).sort();

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
  for (const calendarId of fullSyncedCalendarIds) {
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

  await saveSyncTokenVault(userId, {
    version: 1,
    defaultCalendarId,
    calendarIds: Array.from(successfulCalendarIds),
    calendarTokens: nextCalendarTokens,
    lastFullSyncAt: fullSyncedCalendarIds.size ? syncedAt : syncTokenVault.lastFullSyncAt ?? null,
    lastIncrementalSyncAt: incrementalSyncedCalendarIds.size ? syncedAt : syncTokenVault.lastIncrementalSyncAt ?? null
  });

  const syncMode =
    fullSyncedCalendarIds.size && incrementalSyncedCalendarIds.size
      ? "mixed"
      : fullSyncedCalendarIds.size
        ? "full"
        : "incremental";
  await restUpsert(
    "resolve_sync_states",
    {
      user_id: userId,
      provider: "feishu",
      status: "ok",
      last_full_sync_at: fullSyncedCalendarIds.size ? syncedAt : syncTokenVault.lastFullSyncAt ?? null,
      last_incremental_sync_at: incrementalSyncedCalendarIds.size ? syncedAt : syncTokenVault.lastIncrementalSyncAt ?? null,
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
    mode: syncMode,
    upserted: rows.length,
    fetched: candidates.length,
    stored: rows.length,
    deduped: candidates.length - rows.length,
    remoteDeleted,
    syncedAt,
    fullSyncedCalendars: fullSyncedCalendarIds.size ? Array.from(fullSyncedCalendarIds) : undefined,
    incrementalSyncedCalendars: incrementalSyncedCalendarIds.size ? Array.from(incrementalSyncedCalendarIds) : undefined,
    minStartsAt: rowStarts[0],
    maxStartsAt: rowStarts.at(-1),
    skippedCalendars: skippedCalendars.length ? skippedCalendars : undefined
  };
}

async function fetchEventsForCalendar(
  client: FeishuServerClient,
  calendarId: string,
  startsAt: string,
  endsAt: string,
  syncToken?: string
): Promise<CalendarFetchResult> {
  if (syncToken) {
    try {
      return await fetchIncrementalEventsForCalendar(client, calendarId, syncToken);
    } catch (error) {
      if (isFeishuAuthorizationRequiredError(error)) {
        throw error;
      }
      if (!isSyncTokenError(error)) {
        throw error;
      }
    }
  }

  return fetchFullEventsForCalendar(client, calendarId, startsAt, endsAt);
}

async function fetchFullEventsForCalendar(
  client: FeishuServerClient,
  calendarId: string,
  startsAt: string,
  endsAt: string
): Promise<CalendarFetchResult> {
  const eventsByKey = new Map<string, FeishuServerEvent>();
  let nextSyncToken: string | undefined;
  const modes: Array<{ startsAt?: string; endsAt?: string; anchorTime?: string; capturesSyncToken?: boolean }> = [
    { startsAt, endsAt, capturesSyncToken: true },
    { anchorTime: startsAt, capturesSyncToken: false }
  ];

  for (const mode of modes) {
    let pageToken: string | undefined;
    do {
      const page = await client.listEvents({
        calendarId,
        startsAt: mode.startsAt,
        endsAt: mode.endsAt,
        anchorTime: mode.anchorTime,
        pageToken
      });
      for (const event of page.events) {
        if (!event.eventId || !eventOverlapsWindow(event, startsAt, endsAt)) continue;
        eventsByKey.set(feishuCandidateKey(calendarId, event), event);
      }
      if (mode.capturesSyncToken && page.nextSyncToken) {
        nextSyncToken = page.nextSyncToken;
      }
      pageToken = page.nextPageToken;
    } while (pageToken);
  }

  return {
    events: Array.from(eventsByKey.values()),
    nextSyncToken,
    mode: "full"
  };
}

async function fetchIncrementalEventsForCalendar(
  client: FeishuServerClient,
  calendarId: string,
  syncToken: string
): Promise<CalendarFetchResult> {
  const eventsByKey = new Map<string, FeishuServerEvent>();
  let pageToken: string | undefined;
  let nextSyncToken: string | undefined;

  do {
    const page = await client.listEvents({
      calendarId,
      syncToken,
      pageToken
    });
    for (const event of page.events) {
      if (!event.eventId) continue;
      eventsByKey.set(feishuCandidateKey(calendarId, event), event);
    }
    if (page.nextSyncToken) {
      nextSyncToken = page.nextSyncToken;
    }
    pageToken = page.nextPageToken;
  } while (pageToken);

  return {
    events: Array.from(eventsByKey.values()),
    nextSyncToken: nextSyncToken ?? syncToken,
    mode: "incremental"
  };
}

function dedupeFeishuEventCandidates(
  candidates: Array<{ calendarId: string; event: FeishuServerEvent }>,
  defaultCalendarId: string
) {
  const byRemoteIdentity = new Map<string, { calendarId: string; event: FeishuServerEvent }>();
  for (const candidate of candidates) {
    const key = feishuCanonicalKey(candidate.event);
    const existing = byRemoteIdentity.get(key);
    if (!existing || preferFeishuCandidate(candidate, existing, defaultCalendarId)) {
      byRemoteIdentity.set(key, candidate);
    }
  }
  return Array.from(byRemoteIdentity.values());
}

function feishuCanonicalKey(event: FeishuServerEvent) {
  return [
    event.eventId,
    event.startsAt,
    event.endsAt ?? "",
    event.isAllDay ? "all-day" : "timed"
  ].join("|");
}

function feishuCandidateKey(calendarId: string, event: FeishuServerEvent) {
  return `${calendarId}|${feishuCanonicalKey(event)}`;
}

function preferFeishuCandidate(
  candidate: { calendarId: string; event: FeishuServerEvent },
  existing: { calendarId: string; event: FeishuServerEvent },
  defaultCalendarId: string
) {
  const score = (item: { calendarId: string; event: FeishuServerEvent }) => {
    let value = 0;
    if (item.calendarId === defaultCalendarId) value += 8;
    if (item.calendarId === "primary") value += 4;
    if (item.event.canEdit) value += 2;
    if (item.event.canDelete) value += 1;
    return value;
  };
  const scoreDelta = score(candidate) - score(existing);
  if (scoreDelta !== 0) return scoreDelta > 0;
  return (candidate.event.updatedAt ?? "").localeCompare(existing.event.updatedAt ?? "") >= 0;
}

function eventOverlapsWindow(event: FeishuServerEvent, startsAt: string, endsAt: string) {
  const eventStart = Date.parse(event.startsAt);
  const eventEnd = Date.parse(event.endsAt ?? event.startsAt);
  return eventEnd >= Date.parse(startsAt) && eventStart < Date.parse(endsAt);
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
  return withFeishuAuthRecovery(userId, () => createFeishuEventForUserUnchecked(userId, input));
}

async function createFeishuEventForUserUnchecked(
  userId: string,
  input: CreateFeishuServerEventInput & { calendarId?: string }
) {
  const connection = await loadConnection(userId);
  const config = await decryptConfig(connection);
  let tokenSet = await decryptToken(connection);
  let client = new FeishuServerClient(config, tokenSet);

  if (isTokenExpired(tokenSet)) {
    tokenSet = await refreshTokenForUser(userId, client, tokenSet);
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

export async function updateFeishuEventForUser(
  userId: string,
  input: UpdateFeishuServerEventInput & { calendarId: string; eventId: string }
) {
  return withFeishuAuthRecovery(userId, () => updateFeishuEventForUserUnchecked(userId, input));
}

async function updateFeishuEventForUserUnchecked(
  userId: string,
  input: UpdateFeishuServerEventInput & { calendarId: string; eventId: string }
) {
  const connection = await loadConnection(userId);
  const config = await decryptConfig(connection);
  let tokenSet = await decryptToken(connection);
  let client = new FeishuServerClient(config, tokenSet);

  if (isTokenExpired(tokenSet)) {
    tokenSet = await refreshTokenForUser(userId, client, tokenSet);
    await saveToken(userId, tokenSet);
    client = new FeishuServerClient(config, tokenSet);
  }

  const event = await client.updateEvent(input.calendarId, input.eventId, input);
  const syncedAt = new Date().toISOString();
  const row = await toCalendarEventRow(userId, input.calendarId, event, syncedAt);
  await restUpsert("resolve_calendar_events", row, "user_id,id");
  await restPatch(
    "resolve_feishu_connections",
    `user_id=eq.${encodeFilter(userId)}`,
    {
      status: "connected",
      default_calendar_id: connection.default_calendar_id ?? input.calendarId,
      last_server_sync_at: syncedAt,
      updated_at: syncedAt
    }
  );

  return {
    event: await calendarEventEnvelope(row),
    syncedAt
  };
}

export async function deleteFeishuEventForUser(
  userId: string,
  input: { calendarId: string; eventId: string }
) {
  return withFeishuAuthRecovery(userId, () => deleteFeishuEventForUserUnchecked(userId, input));
}

async function deleteFeishuEventForUserUnchecked(
  userId: string,
  input: { calendarId: string; eventId: string }
) {
  const connection = await loadConnection(userId);
  const config = await decryptConfig(connection);
  let tokenSet = await decryptToken(connection);
  let client = new FeishuServerClient(config, tokenSet);

  if (isTokenExpired(tokenSet)) {
    tokenSet = await refreshTokenForUser(userId, client, tokenSet);
    await saveToken(userId, tokenSet);
    client = new FeishuServerClient(config, tokenSet);
  }

  await client.deleteEvent(input.calendarId, input.eventId);
  const syncedAt = new Date().toISOString();
  await restPatch(
    "resolve_calendar_events",
    `user_id=eq.${encodeFilter(userId)}&id=eq.${encodeFilter(feishuEventRowId(input.calendarId, input.eventId))}`,
    {
      status: "remote_deleted",
      last_synced_at: syncedAt,
      updated_at: syncedAt
    }
  );
  await restPatch(
    "resolve_feishu_connections",
    `user_id=eq.${encodeFilter(userId)}`,
    {
      status: "connected",
      last_server_sync_at: syncedAt,
      updated_at: syncedAt
    }
  );

  return {
    status: "remote_deleted",
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

async function loadSyncTokenVault(userId: string): Promise<FeishuSyncTokenVault> {
  const rows = await restSelect<SyncStateRow>(
    "resolve_sync_states",
    `select=status,last_full_sync_at,last_incremental_sync_at,encrypted_sync_token,sync_token_nonce&user_id=eq.${encodeFilter(userId)}&provider=eq.feishu&limit=1`
  );
  const row = rows[0];
  if (!row?.encrypted_sync_token || !row.sync_token_nonce) {
    return {
      version: 1,
      calendarTokens: {},
      lastFullSyncAt: row?.last_full_sync_at ?? null,
      lastIncrementalSyncAt: row?.last_incremental_sync_at ?? null
    };
  }

  try {
    const decrypted = await serverDecryptJson<Partial<FeishuSyncTokenVault>>(row.encrypted_sync_token, row.sync_token_nonce);
    return {
      version: 1,
      defaultCalendarId: decrypted.defaultCalendarId,
      calendarIds: decrypted.calendarIds,
      calendarTokens: decrypted.calendarTokens ?? {},
      lastFullSyncAt: row.last_full_sync_at ?? decrypted.lastFullSyncAt ?? null,
      lastIncrementalSyncAt: row.last_incremental_sync_at ?? decrypted.lastIncrementalSyncAt ?? null
    };
  } catch {
    return {
      version: 1,
      calendarTokens: {},
      lastFullSyncAt: row.last_full_sync_at ?? null,
      lastIncrementalSyncAt: row.last_incremental_sync_at ?? null
    };
  }
}

async function saveSyncTokenVault(userId: string, vault: FeishuSyncTokenVault) {
  const encrypted = await serverEncryptJson(vault);
  await restUpsert(
    "resolve_sync_states",
    {
      user_id: userId,
      provider: "feishu",
      status: "ok",
      encrypted_sync_token: encrypted.encrypted,
      sync_token_nonce: encrypted.nonce,
      updated_at: new Date().toISOString()
    },
    "user_id,provider"
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
    throw new FeishuAuthorizationRequiredError();
  }
  return serverDecryptJson<FeishuTokenSet>(connection.server_encrypted_token_set, connection.server_token_nonce);
}

async function refreshTokenForUser(userId: string, client: FeishuServerClient, staleTokenSet: FeishuTokenSet) {
  try {
    return await client.refreshAccessToken();
  } catch (error) {
    if (isFeishuAuthorizationRequiredError(error) || isFeishuTokenError(error)) {
      const latestTokenSet = await loadLatestTokenIfAnotherSyncRefreshed(userId, staleTokenSet);
      if (latestTokenSet) return latestTokenSet;
      await markFeishuConnectionNeedsAuth(userId);
      throw new FeishuAuthorizationRequiredError();
    }
    throw error;
  }
}

async function withFeishuAuthRecovery<T>(userId: string, operation: () => Promise<T>): Promise<T> {
  try {
    return await operation();
  } catch (error) {
    if (!isFeishuAuthorizationRequiredError(error)) {
      throw error;
    }
    await refreshStoredTokenForUser(userId);
    try {
      return await operation();
    } catch (retryError) {
      if (isFeishuAuthorizationRequiredError(retryError)) {
        await markFeishuConnectionNeedsAuth(userId);
        throw new FeishuAuthorizationRequiredError();
      }
      throw retryError;
    }
  }
}

async function refreshStoredTokenForUser(userId: string) {
  try {
    const connection = await loadConnection(userId);
    const config = await decryptConfig(connection);
    const tokenSet = await decryptToken(connection);
    const client = new FeishuServerClient(config, tokenSet);
    const refreshed = await refreshTokenForUser(userId, client, tokenSet);
    await saveToken(userId, refreshed);
    await restPatch(
      "resolve_feishu_connections",
      `user_id=eq.${encodeFilter(userId)}`,
      {
        status: "connected",
        updated_at: new Date().toISOString()
      }
    );
    return refreshed;
  } catch (refreshError) {
    await markFeishuConnectionNeedsAuth(userId);
    throw new FeishuAuthorizationRequiredError();
  }
}

async function loadLatestTokenIfAnotherSyncRefreshed(userId: string, staleTokenSet: FeishuTokenSet) {
  try {
    const latestConnection = await loadConnection(userId);
    const latestTokenSet = await decryptToken(latestConnection);
    const tokenChanged =
      Boolean(latestTokenSet.accessToken && latestTokenSet.accessToken !== staleTokenSet.accessToken) ||
      Boolean(latestTokenSet.refreshToken && latestTokenSet.refreshToken !== staleTokenSet.refreshToken);
    return tokenChanged && !isTokenExpired(latestTokenSet) ? latestTokenSet : null;
  } catch {
    return null;
  }
}

function isFeishuTokenError(error: unknown) {
  const message = error instanceof Error ? error.message.toLowerCase() : String(error).toLowerCase();
  return message.includes("refresh token") || (message.includes("token") && message.includes("expired"));
}

function isSyncTokenError(error: unknown) {
  const message = error instanceof Error ? error.message.toLowerCase() : String(error).toLowerCase();
  return message.includes("sync_token") ||
    message.includes("sync token") ||
    (message.includes("token") && message.includes("expired") && message.includes("sync"));
}

export async function markFeishuConnectionNeedsAuth(userId: string) {
  const updatedAt = new Date().toISOString();
  await restPatch(
    "resolve_feishu_connections",
    `user_id=eq.${encodeFilter(userId)}`,
    {
      status: "needs_auth",
      updated_at: updatedAt
    }
  );
  await restUpsert(
    "resolve_sync_states",
    {
      user_id: userId,
      provider: "feishu",
      status: "needs_auth",
      updated_at: updatedAt
    },
    "user_id,provider"
  );
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
    meetingUrl: event.meetingUrl,
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
    status: ["cancelled", "canceled", "deleted"].includes(String(event.status ?? "").toLowerCase())
      ? "remote_deleted"
      : event.canEdit === false
        ? "readonly"
        : "synced",
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
