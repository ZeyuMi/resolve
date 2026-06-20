import type {
  CreateFeishuEventInput,
  FeishuCalendar,
  FeishuCalendarClient,
  FeishuConfig,
  FeishuEvent,
  ListEventsParams,
  ListEventsResult,
  TokenSet,
  UpdateFeishuEventInput
} from "./types";

const defaultApiBaseUrl = "https://open.feishu.cn/open-apis";

interface FeishuApiResponse<T> {
  code: number;
  msg?: string;
  data?: T;
}

export class FeishuOpenApiClient implements FeishuCalendarClient {
  private accessToken: string;
  private refreshToken?: string;
  private readonly apiBaseUrl: string;

  constructor(
    private readonly config: FeishuConfig,
    tokenSet: TokenSet
  ) {
    this.accessToken = tokenSet.accessToken;
    this.refreshToken = tokenSet.refreshToken;
    this.apiBaseUrl = config.apiBaseUrl ?? defaultApiBaseUrl;
  }

  static buildAuthorizeUrl(
    config: Pick<FeishuConfig, "appId" | "redirectUri">,
    state: string,
    scopes: string[] = []
  ) {
    const params = new URLSearchParams({
      app_id: config.appId,
      redirect_uri: config.redirectUri,
      state
    });
    if (scopes.length > 0) params.set("scope", scopes.join(" "));
    return `${defaultApiBaseUrl}/authen/v1/index?${params.toString()}`;
  }

  static async exchangeCode(config: FeishuConfig, code: string): Promise<TokenSet> {
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
    return parseFeishuTokenResponse(response);
  }

  async refreshAccessToken(): Promise<TokenSet> {
    if (!this.refreshToken) {
      throw new Error("Missing Feishu refresh token. Reconnect Feishu.");
    }

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
    const tokenSet = await parseFeishuTokenResponse(response);
    this.accessToken = tokenSet.accessToken;
    this.refreshToken = tokenSet.refreshToken ?? this.refreshToken;
    return tokenSet;
  }

  async getPrimaryCalendar(): Promise<FeishuCalendar> {
    const data = await this.request<{ calendar: unknown }>("/calendar/v4/calendars/primary");
    return mapCalendar(data.calendar);
  }

  async listCalendars(): Promise<FeishuCalendar[]> {
    const calendars: FeishuCalendar[] = [];
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

  async listEvents(params: ListEventsParams): Promise<ListEventsResult> {
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

  async getEvent(calendarId: string, eventId: string): Promise<FeishuEvent> {
    const data = await this.request<{ event: unknown }>(
      `/calendar/v4/calendars/${encodeURIComponent(calendarId)}/events/${encodeURIComponent(eventId)}`
    );
    return mapEvent(calendarId, data.event);
  }

  async createEvent(calendarId: string, input: CreateFeishuEventInput): Promise<FeishuEvent> {
    const data = await this.request<{ event: unknown }>(
      `/calendar/v4/calendars/${encodeURIComponent(calendarId)}/events`,
      {
        method: "POST",
        body: JSON.stringify(toFeishuEventInput(input))
      }
    );
    return mapEvent(calendarId, data.event);
  }

  async updateEvent(calendarId: string, eventId: string, input: UpdateFeishuEventInput): Promise<FeishuEvent> {
    const data = await this.request<{ event: unknown }>(
      `/calendar/v4/calendars/${encodeURIComponent(calendarId)}/events/${encodeURIComponent(eventId)}`,
      {
        method: "PATCH",
        body: JSON.stringify(toFeishuEventInput(input))
      }
    );
    return mapEvent(calendarId, data.event);
  }

  async deleteEvent(calendarId: string, eventId: string): Promise<void> {
    await this.request(
      `/calendar/v4/calendars/${encodeURIComponent(calendarId)}/events/${encodeURIComponent(eventId)}`,
      {
        method: "DELETE"
      }
    );
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
  const body = (await response.json()) as FeishuApiResponse<T>;
  if (!response.ok || body.code !== 0) {
    throw new Error(body.msg ?? `Feishu API failed with HTTP ${response.status}`);
  }
  return (body.data ?? {}) as T;
}

async function parseFeishuTokenResponse(response: Response) {
  const body = (await response.json()) as
    | FeishuApiResponse<{
        access_token: string;
        refresh_token?: string;
        expires_in?: number;
      }>
    | {
        code?: number;
        msg?: string;
        access_token?: string;
        refresh_token?: string;
        expires_in?: number;
      };

  if (!response.ok) {
    throw new Error("msg" in body && body.msg ? body.msg : `Feishu API failed with HTTP ${response.status}`);
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

  throw new Error("msg" in body && body.msg ? body.msg : `Feishu token exchange failed with HTTP ${response.status}`);
}

function toTokenSet(data: { access_token: string; refresh_token?: string; expires_in?: number }): TokenSet {
  return {
    accessToken: data.access_token,
    refreshToken: data.refresh_token,
    expiresAt: data.expires_in ? new Date(Date.now() + data.expires_in * 1000).toISOString() : undefined
  };
}

function asRecord(value: unknown) {
  return (value ?? {}) as Record<string, unknown>;
}

function mapCalendar(value: unknown): FeishuCalendar {
  const record = asRecord(value);
  return {
    calendarId: String(record.calendar_id ?? record.calendarId ?? "primary"),
    summary: typeof record.summary === "string" ? record.summary : undefined,
    description: typeof record.description === "string" ? record.description : undefined,
    isPrimary: record.is_primary === true,
    type: typeof record.type === "string" ? record.type : undefined,
    accessRole: typeof record.access_role === "string" ? record.access_role : undefined,
    canEdit: record.access_role === "writer" || record.access_role === "owner",
    raw: value
  };
}

function mapEvent(calendarId: string, value: unknown): FeishuEvent {
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
    startsAt: fromFeishuTime(start) ?? new Date().toISOString(),
    endsAt: fromFeishuTime(end),
    isAllDay: start.date != null,
    updatedAt: typeof record.update_time === "string" ? new Date(Number(record.update_time) * 1000).toISOString() : undefined,
    status: typeof record.status === "string" ? record.status : undefined,
    recurrence: typeof record.recurrence === "string" ? record.recurrence : undefined,
    isException: record.is_exception === true,
    recurringEventId: typeof record.recurring_event_id === "string" ? record.recurring_event_id : undefined,
    canEdit: readFeishuPermission(permissions, "editable", "can_edit"),
    canDelete: readFeishuPermission(permissions, "deletable", "can_delete"),
    raw: value
  };
}

function readFeishuPermission(record: Record<string, unknown>, ...keys: string[]) {
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

function fromFeishuTime(value: Record<string, unknown>) {
  if (typeof value.timestamp === "string") {
    return new Date(Number(value.timestamp) * 1000).toISOString();
  }
  if (typeof value.date === "string") {
    return new Date(`${value.date}T00:00:00`).toISOString();
  }
  return undefined;
}

function toFeishuEventInput(input: UpdateFeishuEventInput) {
  return compactObject({
    summary: input.title,
    description: input.description,
    location: input.location,
    start_time: input.startsAt ? toFeishuTime(input.startsAt, input.isAllDay) : undefined,
    end_time: input.endsAt ? toFeishuTime(input.endsAt, input.isAllDay) : undefined
  });
}

function toFeishuTime(iso: string, isAllDay?: boolean) {
  const date = new Date(iso);
  if (isAllDay) {
    return { date: iso.slice(0, 10) };
  }
  return {
    timestamp: Math.floor(date.getTime() / 1000).toString(),
    timezone: Intl.DateTimeFormat().resolvedOptions().timeZone || "Asia/Shanghai"
  };
}

function toUnixSeconds(iso: string) {
  return Math.floor(new Date(iso).getTime() / 1000).toString();
}

function compactObject<T extends Record<string, unknown>>(input: T) {
  return Object.fromEntries(Object.entries(input).filter(([, value]) => value !== undefined)) as Partial<T>;
}
