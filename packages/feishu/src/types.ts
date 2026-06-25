export interface FeishuConfig {
  appId: string;
  appSecret: string;
  redirectUri: string;
  apiBaseUrl?: string;
}

export interface TokenSet {
  accessToken: string;
  refreshToken?: string;
  expiresAt?: string;
  refreshExpiresAt?: string;
}

export interface FeishuCalendar {
  calendarId: string;
  summary?: string;
  description?: string;
  isPrimary?: boolean;
  type?: string;
  accessRole?: string;
  canEdit?: boolean;
  raw?: unknown;
}

export interface FeishuEvent {
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
  isException?: boolean;
  recurringEventId?: string;
  canEdit?: boolean;
  canDelete?: boolean;
  raw?: unknown;
}

export interface ListEventsParams {
  calendarId: string;
  startsAt?: string;
  endsAt?: string;
  anchorTime?: string;
  pageToken?: string;
  syncToken?: string;
  pageSize?: number;
}

export interface ListEventsResult {
  events: FeishuEvent[];
  nextPageToken?: string;
  nextSyncToken?: string;
}

export interface CreateFeishuEventInput {
  title: string;
  startsAt: string;
  endsAt?: string;
  isAllDay?: boolean;
  description?: string;
  location?: string;
}

export interface UpdateFeishuEventInput extends Partial<CreateFeishuEventInput> {}

export interface FeishuCalendarClient {
  getPrimaryCalendar(): Promise<FeishuCalendar>;
  listCalendars(): Promise<FeishuCalendar[]>;
  listEvents(params: ListEventsParams): Promise<ListEventsResult>;
  getEvent(calendarId: string, eventId: string): Promise<FeishuEvent>;
  createEvent(calendarId: string, input: CreateFeishuEventInput): Promise<FeishuEvent>;
  updateEvent(calendarId: string, eventId: string, input: UpdateFeishuEventInput): Promise<FeishuEvent>;
  deleteEvent(calendarId: string, eventId: string): Promise<void>;
  refreshAccessToken(): Promise<TokenSet>;
}
