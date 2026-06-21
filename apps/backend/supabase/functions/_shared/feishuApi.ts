export interface FeishuServerConfig {
  appId: string;
  appSecret: string;
  redirectUri: string;
  apiBaseUrl?: string;
}

export interface FeishuTokenSet {
  accessToken: string;
  refreshToken?: string;
  expiresAt?: string;
}

export class FeishuAuthorizationRequiredError extends Error {
  readonly code = "feishu_needs_auth";

  constructor(message = "Calendar needs authorization. Connect Calendar again.") {
    super(message);
    this.name = "FeishuAuthorizationRequiredError";
  }
}

export function isFeishuAuthorizationRequiredError(error: unknown) {
  return error instanceof FeishuAuthorizationRequiredError;
}

export interface FeishuServerEvent {
  calendarId: string;
  eventId: string;
  title?: string;
  description?: string;
  location?: string;
  meetingUrl?: string;
  startsAt: string;
  endsAt?: string;
  isAllDay?: boolean;
  updatedAt?: string;
  status?: string;
  recurrence?: string;
  canEdit?: boolean;
  canDelete?: boolean;
  raw?: unknown;
}

export interface FeishuServerCalendar {
  calendarId: string;
  summary?: string;
  isPrimary?: boolean;
  type?: string;
  accessRole?: string;
  raw?: unknown;
}

export interface CreateFeishuServerEventInput {
  title: string;
  description?: string;
  location?: string;
  startsAt: string;
  endsAt?: string;
  timezone?: string;
}

export type UpdateFeishuServerEventInput = Partial<CreateFeishuServerEventInput>;

const defaultApiBaseUrl = "https://open.feishu.cn/open-apis";

export function buildFeishuAuthorizeUrl(config: Pick<FeishuServerConfig, "appId" | "redirectUri">, state: string) {
  const scope = [
    "calendar:calendar",
    "calendar:calendar:readonly",
    "calendar:calendar.event:read",
    "calendar:calendar.event:create",
    "calendar:calendar.event:update",
    "calendar:calendar.event:delete"
  ].join(" ");
  const params = new URLSearchParams({
    app_id: config.appId,
    redirect_uri: config.redirectUri,
    state,
    scope
  });
  return `${defaultApiBaseUrl}/authen/v1/index?${params.toString()}`;
}

export class FeishuServerClient {
  private accessToken: string;
  private refreshToken?: string;
  private readonly apiBaseUrl: string;

  constructor(
    private readonly config: FeishuServerConfig,
    tokenSet: FeishuTokenSet
  ) {
    this.accessToken = tokenSet.accessToken;
    this.refreshToken = tokenSet.refreshToken;
    this.apiBaseUrl = config.apiBaseUrl ?? defaultApiBaseUrl;
  }

  static async exchangeCode(config: FeishuServerConfig, code: string): Promise<FeishuTokenSet> {
    const response = await fetch(`${config.apiBaseUrl ?? defaultApiBaseUrl}/authen/v2/oauth/token`, {
      method: "POST",
      headers: {
        "content-type": "application/json"
      },
      body: JSON.stringify({
        grant_type: "authorization_code",
        client_id: config.appId,
        client_secret: config.appSecret,
        code,
        redirect_uri: config.redirectUri
      })
    });
    return parseTokenResponse(response);
  }

  async refreshAccessToken(): Promise<FeishuTokenSet> {
    if (!this.refreshToken) throw new FeishuAuthorizationRequiredError();
    const response = await fetch(`${this.apiBaseUrl}/authen/v2/oauth/token`, {
      method: "POST",
      headers: {
        "content-type": "application/json"
      },
      body: JSON.stringify({
        grant_type: "refresh_token",
        client_id: this.config.appId,
        client_secret: this.config.appSecret,
        refresh_token: this.refreshToken
      })
    });
    const tokenSet = await parseTokenResponse(response);
    const nextTokenSet = {
      ...tokenSet,
      refreshToken: tokenSet.refreshToken ?? this.refreshToken
    };
    this.accessToken = tokenSet.accessToken;
    this.refreshToken = nextTokenSet.refreshToken;
    return nextTokenSet;
  }

  async getPrimaryCalendar(): Promise<FeishuServerCalendar> {
    const data = await this.request<{ calendar: unknown }>("/calendar/v4/calendars/primary");
    return mapCalendar(data.calendar);
  }

  async listCalendars(): Promise<FeishuServerCalendar[]> {
    const calendars: FeishuServerCalendar[] = [];
    let pageToken: string | undefined;
    do {
      const query = new URLSearchParams({
        page_size: "100"
      });
      if (pageToken) query.set("page_token", pageToken);
      const data = await this.request<{
        calendar_list?: unknown[];
        calendars?: unknown[];
        items?: unknown[];
        has_more?: boolean;
        page_token?: string;
      }>(`/calendar/v4/calendars?${query.toString()}`);
      calendars.push(...(data.calendar_list ?? data.calendars ?? data.items ?? []).map(mapCalendar));
      pageToken = data.has_more ? data.page_token : undefined;
    } while (pageToken);
    return calendars;
  }

  async listEvents(params: {
    calendarId: string;
    anchorTime?: string;
    startsAt?: string;
    endsAt?: string;
    pageToken?: string;
    syncToken?: string;
    pageSize?: number;
  }) {
    const query = new URLSearchParams({
      page_size: String(params.pageSize ?? 1000)
    });
    if (params.anchorTime) query.set("anchor_time", toUnixSeconds(params.anchorTime));
    if (params.startsAt) query.set("start_time", toUnixSeconds(params.startsAt));
    if (params.endsAt) query.set("end_time", toUnixSeconds(params.endsAt));
    if (params.pageToken) query.set("page_token", params.pageToken);
    if (params.syncToken) query.set("sync_token", params.syncToken);

    const data = await this.request<{
      items?: unknown[];
      page_token?: string;
      sync_token?: string;
    }>(`/calendar/v4/calendars/${encodeURIComponent(params.calendarId)}/events?${query.toString()}`);

    return {
      events: (data.items ?? []).map((event) => mapEvent(params.calendarId, event)),
      nextPageToken: data.page_token,
      nextSyncToken: data.sync_token
    };
  }

  async createEvent(calendarId: string, input: CreateFeishuServerEventInput): Promise<FeishuServerEvent> {
    const startsAt = input.startsAt;
    const endsAt = input.endsAt ?? new Date(new Date(startsAt).getTime() + 60 * 60 * 1000).toISOString();
    const data = await this.request<{ event?: unknown }>(
      `/calendar/v4/calendars/${encodeURIComponent(calendarId)}/events`,
      {
        method: "POST",
        body: JSON.stringify({
          summary: input.title,
          description: input.description,
          location: input.location ? { name: input.location } : undefined,
          start_time: toFeishuTime(startsAt, input.timezone),
          end_time: toFeishuTime(endsAt, input.timezone)
        })
      }
    );
    return mapEvent(calendarId, data.event);
  }

  async updateEvent(calendarId: string, eventId: string, input: UpdateFeishuServerEventInput): Promise<FeishuServerEvent> {
    const body: Record<string, unknown> = {};
    if (input.title != null) body.summary = input.title;
    if (input.description != null) body.description = input.description;
    if (input.location != null) body.location = input.location ? { name: input.location } : undefined;
    if (input.startsAt != null) body.start_time = toFeishuTime(input.startsAt, input.timezone);
    if (input.endsAt != null) body.end_time = toFeishuTime(input.endsAt, input.timezone);
    const data = await this.request<{ event?: unknown }>(
      `/calendar/v4/calendars/${encodeURIComponent(calendarId)}/events/${encodeURIComponent(eventId)}`,
      {
        method: "PATCH",
        body: JSON.stringify(body)
      }
    );
    return mapEvent(calendarId, data.event);
  }

  private async request<T>(path: string, init: RequestInit = {}) {
    const response = await fetch(`${this.apiBaseUrl}${path}`, {
      ...init,
      headers: {
        "content-type": "application/json",
        authorization: `Bearer ${this.accessToken}`,
        ...init.headers
      }
    });
    return parseFeishuResponse<T>(response);
  }
}

async function parseFeishuResponse<T>(response: Response) {
  const body = await response.json() as { code: number; msg?: string; data?: T };
  if (!response.ok || body.code !== 0) {
    const message = body.msg ?? `Feishu API failed with HTTP ${response.status}`;
    if (response.status === 401 || isTokenFailure(message)) {
      throw new FeishuAuthorizationRequiredError();
    }
    throw new Error(message);
  }
  return (body.data ?? {}) as T;
}

function isTokenFailure(message: string) {
  const normalized = message.toLowerCase();
  return normalized.includes("token") &&
    (normalized.includes("expired") || normalized.includes("invalid") || normalized.includes("unauthorized"));
}

async function parseTokenResponse(response: Response) {
  const body = await response.json() as
    | {
        code?: number;
        msg?: string;
        data?: {
          access_token: string;
          refresh_token?: string;
          expires_in?: number;
        };
      }
    | {
        access_token?: string;
        refresh_token?: string;
        expires_in?: number;
        msg?: string;
      };

  if (!response.ok) {
    throw new Error(("msg" in body && body.msg) || `Feishu API failed with HTTP ${response.status}`);
  }

  if ("access_token" in body && body.access_token) {
    return toTokenSet({
      access_token: body.access_token,
      refresh_token: body.refresh_token,
      expires_in: body.expires_in
    });
  }
  if ("data" in body && body.data?.access_token) {
    return toTokenSet(body.data);
  }
  throw new Error(("msg" in body && body.msg) || "Feishu token exchange failed.");
}

function toTokenSet(data: { access_token: string; refresh_token?: string; expires_in?: number }) {
  return {
    accessToken: data.access_token,
    refreshToken: data.refresh_token,
    expiresAt: data.expires_in ? new Date(Date.now() + data.expires_in * 1000).toISOString() : undefined
  };
}

function asRecord(value: unknown) {
  return (value ?? {}) as Record<string, unknown>;
}

function mapCalendar(value: unknown): FeishuServerCalendar {
  const record = asRecord(value);
  return {
    calendarId: String(record.calendar_id ?? record.calendarId ?? "primary"),
    summary: typeof record.summary === "string" ? record.summary : undefined,
    isPrimary: record.is_primary === true,
    type: typeof record.type === "string" ? record.type : undefined,
    accessRole: typeof record.access_role === "string" ? record.access_role : undefined,
    raw: value
  };
}

function mapEvent(calendarId: string, value: unknown): FeishuServerEvent {
  const record = asRecord(value);
  const start = asRecord(record.start_time);
  const end = asRecord(record.end_time);
  const permissions = asRecord(record.permissions);

  return {
    calendarId,
    eventId: String(record.event_id ?? record.id ?? ""),
    title: typeof record.summary === "string" ? record.summary : undefined,
    description: typeof record.description === "string" ? record.description : undefined,
    location: typeof record.location === "string" ? record.location : readLocation(record.location),
    meetingUrl: readMeetingUrl(record),
    startsAt: fromFeishuTime(start) ?? new Date().toISOString(),
    endsAt: fromFeishuTime(end),
    isAllDay: start.date != null,
    updatedAt: typeof record.update_time === "string" ? new Date(Number(record.update_time) * 1000).toISOString() : undefined,
    status: typeof record.status === "string" ? record.status : undefined,
    recurrence: typeof record.recurrence === "string" ? record.recurrence : undefined,
    canEdit: readPermission(permissions, "editable", "can_edit"),
    canDelete: readPermission(permissions, "deletable", "can_delete"),
    raw: value
  };
}

function readPermission(record: Record<string, unknown>, ...keys: string[]) {
  for (const key of keys) {
    if (record[key] === true) return true;
    if (record[key] === false) return false;
  }
  return undefined;
}

function readLocation(value: unknown) {
  const record = asRecord(value);
  return typeof record.name === "string" ? record.name : undefined;
}

function readMeetingUrl(record: Record<string, unknown>) {
  const preferredKeys = [
    "meeting_url",
    "meetingUrl",
    "online_meeting_url",
    "onlineMeetingUrl",
    "vc_url",
    "vcUrl",
    "join_url",
    "joinUrl",
    "conference_url",
    "conferenceUrl",
    "url"
  ];
  const nestedKeys = [
    "vchat",
    "video_meeting",
    "videoMeeting",
    "online_meeting",
    "onlineMeeting",
    "video_conference",
    "videoConference",
    "meeting",
    "conference",
    "conference_info",
    "conferenceInfo",
    "conference_data",
    "conferenceData"
  ];

  for (const key of preferredKeys) {
    const value = record[key];
    if (typeof value === "string") {
      const url = firstUrl(value) ?? value;
      if (looksLikeMeetingUrl(url) || meetingKeyPattern.test(key)) return cleanUrl(url);
    }
  }
  for (const key of nestedKeys) {
    const value = findMeetingUrl(record[key], 0, true);
    if (value) return value;
  }
  const fromDescription = typeof record.description === "string" ? firstUrl(record.description) : undefined;
  return fromDescription && looksLikeMeetingUrl(fromDescription) ? cleanUrl(fromDescription) : findMeetingUrl(record, 0, false);
}

function findMeetingUrl(value: unknown, depth: number, trustedContext: boolean): string | undefined {
  if (depth > 5 || value == null) return undefined;
  if (typeof value === "string") {
    const url = firstUrl(value);
    return url && (trustedContext || looksLikeMeetingUrl(url)) ? cleanUrl(url) : undefined;
  }
  if (Array.isArray(value)) {
    for (const item of value) {
      const found = findMeetingUrl(item, depth + 1, trustedContext);
      if (found) return found;
    }
    return undefined;
  }
  if (typeof value === "object") {
    const record = value as Record<string, unknown>;
    for (const key of Object.keys(record).sort(meetingKeySort)) {
      const found = findMeetingUrl(record[key], depth + 1, trustedContext || meetingKeyPattern.test(key));
      if (found) return found;
    }
  }
  return undefined;
}

const meetingKeyPattern = /meeting|vchat|vc|conference|url|link|join|video|online/i;

function meetingKeySort(left: string, right: string) {
  const score = (key: string) => meetingKeyPattern.test(key) ? 0 : 1;
  return score(left) - score(right);
}

function firstUrl(value: string) {
  return value.match(/https?:\/\/[^\s<>"')，。；、]+/i)?.[0];
}

function looksLikeMeetingUrl(value: string) {
  return /https?:\/\//i.test(value) && /(feishu|larksuite|vc|videochat|meeting|meet|zoom|teams|tencent|voov|google)/i.test(value);
}

function cleanUrl(value: string) {
  return value.replace(/[.,;:]+$/, "");
}

function fromFeishuTime(value: Record<string, unknown>) {
  if (typeof value.timestamp === "string") {
    return new Date(Number(value.timestamp) * 1000).toISOString();
  }
  if (typeof value.date === "string") {
    return new Date(`${value.date}T00:00:00`).toISOString();
  }
  return undefined;
}

function toUnixSeconds(iso: string) {
  return Math.floor(new Date(iso).getTime() / 1000).toString();
}

function toFeishuTime(iso: string, timezone = "Asia/Shanghai") {
  return {
    timestamp: toUnixSeconds(iso),
    timezone
  };
}
