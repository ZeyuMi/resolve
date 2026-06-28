import {
  emptyEncryptedFields,
  nowIso,
  type CalendarEventPayload,
  type CalendarEventSyncState,
  type DecryptedCalendarEvent
} from "@resolve/core";
import { isNativeSecureStoreAvailable, secureStoreDelete, secureStoreGet, secureStoreSet } from "../platform/secureStore";

export const resolveSupabaseUrl = (import.meta.env.VITE_RESOLVE_SUPABASE_URL ?? "").trim();
export const resolveSupabasePublishableKey = (import.meta.env.VITE_RESOLVE_SUPABASE_PUBLISHABLE_KEY ?? "").trim();

export interface BackendSettingsState {
  supabaseUrl: string;
  publishableKey: string;
  email: string;
  status: "not_configured" | "signed_out" | "connected" | "error";
  feishuConnected: boolean;
  lastSyncedAt?: string;
  lastError?: string;
}

export interface BackendSession {
  accessToken: string;
  refreshToken?: string;
  expiresAt?: number;
}

export interface BackendCalendarDraft {
  title: string;
  description?: string;
  startsAt: string;
  endsAt?: string;
}

export interface BackendCalendarUpdateDraft {
  calendarId: string;
  eventId: string;
  title?: string;
  description?: string;
  location?: string;
  startsAt?: string;
  endsAt?: string;
}

export class ResolveBackendError extends Error {
  constructor(
    message: string,
    readonly code?: string,
    readonly status?: number
  ) {
    super(message);
    this.name = "ResolveBackendError";
  }
}

export function needsCalendarAuthorization(error: unknown) {
  const message = error instanceof Error ? error.message.toLowerCase() : String(error).toLowerCase();
  return (error instanceof ResolveBackendError && error.code === "feishu_needs_auth") ||
    message.includes("calendar needs authorization") ||
    message.includes("connect calendar again") ||
    message.includes("missing feishu refresh token") ||
    message.includes("missing feishu token set");
}

export function isBackendJwtExpired(error: unknown) {
  const message = error instanceof Error ? error.message.toLowerCase() : String(error).toLowerCase();
  const code = error instanceof ResolveBackendError ? error.code?.toLowerCase() ?? "" : "";
  return (error instanceof ResolveBackendError && error.status === 401 && (message.includes("jwt") || message.includes("expired") || code.includes("jwt"))) ||
    message.includes("jwt expired") ||
    message.includes("token is expired") ||
    message.includes("invalid jwt") ||
    message.includes("expired jwt");
}

const backendSettingsKey = "resolve:backend-settings:v1";
const backendSessionKey = "resolve:backend-session:v1";
const secureBackendSessionKey = {
  service: "ai.tiiny.resolve.backend",
  account: "backend_session"
};

let backendSessionCache: BackendSession | null | undefined;

export function defaultBackendSettings(): BackendSettingsState {
  return {
    supabaseUrl: resolveSupabaseUrl,
    publishableKey: resolveSupabasePublishableKey,
    email: "",
    status: "not_configured",
    feishuConnected: false
  };
}

export function loadBackendSettings(): BackendSettingsState {
  const defaults = defaultBackendSettings();
  const raw = localStorage.getItem(backendSettingsKey);
  if (!raw) return defaults;
  const parsed = JSON.parse(raw) as Partial<BackendSettingsState>;
  return {
    ...defaults,
    ...parsed,
    supabaseUrl: parsed.supabaseUrl || defaults.supabaseUrl,
    publishableKey: parsed.publishableKey || defaults.publishableKey
  };
}

export function saveBackendSettings(settings: BackendSettingsState) {
  localStorage.setItem(backendSettingsKey, JSON.stringify(settings));
}

export function loadBackendSession(): BackendSession | null {
  if (backendSessionCache !== undefined) return backendSessionCache;
  const raw = localStorage.getItem(backendSessionKey);
  if (!raw) return null;
  try {
    backendSessionCache = JSON.parse(raw) as BackendSession;
    return backendSessionCache;
  } catch {
    backendSessionCache = null;
    return null;
  }
}

export function saveBackendSession(session: BackendSession) {
  backendSessionCache = session;
  const serialized = JSON.stringify(session);
  if (!isNativeSecureStoreAvailable()) {
    localStorage.setItem(backendSessionKey, serialized);
    return;
  }
  localStorage.removeItem(backendSessionKey);
  void secureStoreSet(secureBackendSessionKey, serialized).catch((error) => {
    console.warn("Could not save backend session to Keychain", error);
  });
}

export function clearBackendSession() {
  backendSessionCache = null;
  localStorage.removeItem(backendSessionKey);
  if (isNativeSecureStoreAvailable()) {
    void secureStoreDelete(secureBackendSessionKey).catch((error) => {
      console.warn("Could not clear backend session from Keychain", error);
    });
  }
}

export async function hydrateBackendSessionFromSecureStore() {
  if (!isNativeSecureStoreAvailable()) {
    backendSessionCache = loadBackendSession();
    return backendSessionCache;
  }

  const legacyRaw = localStorage.getItem(backendSessionKey);
  const secureRaw = await secureStoreGet(secureBackendSessionKey);
  const raw = secureRaw || legacyRaw;
  if (!raw) {
    backendSessionCache = null;
    return null;
  }

  try {
    const session = JSON.parse(raw) as BackendSession;
    backendSessionCache = session;
    if (!secureRaw) {
      await secureStoreSet(secureBackendSessionKey, JSON.stringify(session));
    }
    localStorage.removeItem(backendSessionKey);
    return session;
  } catch {
    backendSessionCache = null;
    localStorage.removeItem(backendSessionKey);
    await secureStoreDelete(secureBackendSessionKey);
    return null;
  }
}

export function shouldRefreshBackendSession(session: BackendSession) {
  return Boolean(session.expiresAt && session.expiresAt < Date.now() + 60_000);
}

export class ResolveBackendClient {
  private readonly baseUrl: string;
  private readonly publishableKey: string;

  constructor(
    private readonly settings: BackendSettingsState,
    private readonly session?: BackendSession | null
  ) {
    this.baseUrl = (settings.supabaseUrl || resolveSupabaseUrl).replace(/\/$/, "");
    this.publishableKey = settings.publishableKey || resolveSupabasePublishableKey;
  }

  async signInWithPassword(password: string) {
    const response = await this.post(
      `${this.baseUrl}/auth/v1/token?grant_type=password`,
      {
        email: this.settings.email.trim(),
        password
      },
      false
    );
    return parseSession(response);
  }

  async refreshSession() {
    if (!this.session?.refreshToken) throw new Error("Sign in again.");
    const response = await this.post(
      `${this.baseUrl}/auth/v1/token?grant_type=refresh_token`,
      {
        refresh_token: this.session.refreshToken
      },
      false
    );
    return parseSession(response);
  }

  async status() {
    return this.connector({
      action: "status"
    }) as Promise<{
      configured: boolean;
      connected: boolean;
      needsAuthorization?: boolean;
      status: string;
      defaultCalendarId?: string;
      lastServerSyncAt?: string;
    }>;
  }

  async startFeishuOAuth() {
    return this.connector({
      action: "start_oauth"
    }) as Promise<{
      authorizeUrl: string;
      redirectUri?: string;
      expiresAt?: string;
    }>;
  }

  async syncNow() {
    return this.connector({
      action: "sync_now"
    }) as Promise<{ syncedAt?: string }>;
  }

  async listEvents(startsAt: string, endsAt: string) {
    const response = await this.connector({
      action: "list_events",
      startsAt,
      endsAt
    }) as { events?: unknown[] };
    return (response.events ?? []).map(serverEventToCalendarEvent);
  }

  async createEvent(draft: BackendCalendarDraft) {
    const response = await this.connector({
      action: "create_event",
      title: draft.title,
      description: draft.description,
      startsAt: draft.startsAt,
      endsAt: draft.endsAt
    }) as { event?: unknown };
    return serverEventToCalendarEvent(response.event);
  }

  async updateEvent(draft: BackendCalendarUpdateDraft) {
    const response = await this.connector({
      action: "update_event",
      calendarId: draft.calendarId,
      eventId: draft.eventId,
      title: draft.title,
      description: draft.description,
      location: draft.location,
      startsAt: draft.startsAt,
      endsAt: draft.endsAt
    }) as { event?: unknown; syncedAt?: string };
    return {
      event: serverEventToCalendarEvent(response.event),
      syncedAt: typeof response.syncedAt === "string" ? response.syncedAt : undefined
    };
  }

  async deleteEvent(calendarId: string, eventId: string) {
    return this.connector({
      action: "delete_event",
      calendarId,
      eventId
    }) as Promise<{ status?: string; syncedAt?: string }>;
  }

  private async connector(body: Record<string, unknown>) {
    if (!this.session?.accessToken) throw new Error("Sign in first.");
    return this.post(`${this.baseUrl}/functions/v1/feishu-connector`, body, true);
  }

  private async post(url: string, body: Record<string, unknown>, includeBearer: boolean) {
    const response = await fetch(url, {
      method: "POST",
      headers: {
        apikey: this.publishableKey,
        "content-type": "application/json",
        ...(includeBearer ? { authorization: `Bearer ${this.session?.accessToken ?? ""}` } : {})
      },
      body: JSON.stringify(body)
    });
    const text = await response.text();
    const json = text ? JSON.parse(text) as Record<string, unknown> : {};
    if (!response.ok) {
      throw new ResolveBackendError(
        readErrorMessage(json, `HTTP ${response.status}`),
        typeof json.error === "string" ? json.error : undefined,
        response.status
      );
    }
    return json;
  }
}

const calendarSyncStates = new Set<CalendarEventSyncState>([
  "synced",
  "local_pending_create",
  "local_pending_update",
  "local_pending_delete",
  "remote_deleted",
  "conflict",
  "readonly",
  "error",
  "archived_locally"
]);

function readCalendarSyncState(value: unknown): CalendarEventSyncState {
  return typeof value === "string" && calendarSyncStates.has(value as CalendarEventSyncState)
    ? value as CalendarEventSyncState
    : "synced";
}

function parseSession(json: Record<string, unknown>): BackendSession {
  const accessToken = typeof json.access_token === "string" ? json.access_token : "";
  if (!accessToken) throw new Error(readErrorMessage(json, "Sign in failed."));
  const expiresAt = typeof json.expires_at === "number"
    ? json.expires_at * 1000
    : typeof json.expires_in === "number"
      ? Date.now() + json.expires_in * 1000
      : undefined;
  return {
    accessToken,
    refreshToken: typeof json.refresh_token === "string" ? json.refresh_token : undefined,
    expiresAt
  };
}

function serverEventToCalendarEvent(value: unknown): DecryptedCalendarEvent {
  const record = (value ?? {}) as { meta?: Record<string, unknown>; payload?: Record<string, unknown> };
  const meta = record.meta ?? {};
  const payload = record.payload ?? {};
  const timestamp = nowIso();
  const startsAt = typeof meta.startsAt === "string" ? meta.startsAt : timestamp;
  const eventPayload: CalendarEventPayload = {
    title: typeof payload.title === "string" && payload.title.trim() ? payload.title : "Untitled Feishu event",
    description: typeof payload.description === "string" ? payload.description : undefined,
    location: typeof payload.location === "string" ? payload.location : undefined,
    meetingUrl: typeof payload.meetingUrl === "string" ? payload.meetingUrl : extractMeetingUrl(payload.description),
    recurrence: payload.recurrence,
    feishuRaw: payload.feishuRaw
  };
  return {
    meta: {
      id: typeof meta.id === "string" ? meta.id : `feishu_${crypto.randomUUID()}`,
      provider: "feishu",
      externalCalendarId: typeof meta.externalCalendarId === "string" ? meta.externalCalendarId : undefined,
      externalEventId: typeof meta.externalEventId === "string" ? meta.externalEventId : undefined,
      status: readCalendarSyncState(meta.status),
      startsAt,
      endsAt: typeof meta.endsAt === "string" ? meta.endsAt : undefined,
      isAllDay: meta.isAllDay === true,
      createdAt: typeof meta.createdAt === "string" ? meta.createdAt : timestamp,
      updatedAt: typeof meta.updatedAt === "string" ? meta.updatedAt : timestamp,
      remoteUpdatedAt: typeof meta.remoteUpdatedAt === "string" ? meta.remoteUpdatedAt : undefined,
      lastSyncedAt: typeof meta.lastSyncedAt === "string" ? meta.lastSyncedAt : timestamp,
      canEdit: typeof meta.canEdit === "boolean" ? meta.canEdit : undefined,
      canDelete: typeof meta.canDelete === "boolean" ? meta.canDelete : undefined,
      ...emptyEncryptedFields
    },
    payload: eventPayload
  };
}

function readErrorMessage(json: Record<string, unknown>, fallback: string) {
  for (const key of ["message", "msg", "error_description", "error"]) {
    if (typeof json[key] === "string" && json[key]) return json[key];
  }
  return fallback;
}

function extractMeetingUrl(value: unknown) {
  if (typeof value !== "string") return undefined;
  const urls = value.match(/https?:\/\/[^\s<>"')，。；、]+/gi) ?? [];
  return urls.map((url) => url.replace(/[.,;:]+$/, "")).find((url) =>
    /(feishu|larksuite|vc|videochat|meeting|meet|zoom|teams|tencent|voov|google)/i.test(url)
  );
}
