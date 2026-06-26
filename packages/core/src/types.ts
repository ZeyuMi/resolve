export type ItemType =
  | "capture"
  | "task"
  | "tracker"
  | "strategy_note"
  | "calendar_draft";

export type ItemStatus =
  | "inbox"
  | "triaged"
  | "active"
  | "waiting"
  | "watching"
  | "discuss"
  | "review_later"
  | "done"
  | "archived"
  | "deleted"
  | "killed";

export type ItemRoute =
  | "task"
  | "calendar"
  | "tracker"
  | "strategy"
  | "archive"
  | "delete";

export type ItemSource = "mac" | "android" | "share" | "voice" | "manual";

export interface EncryptedPayloadFields {
  encryptedPayload: string;
  payloadNonce: string;
  payloadVersion: number;
}

export interface ItemMetadata extends EncryptedPayloadFields {
  id: string;
  type: ItemType;
  status: ItemStatus;
  route?: ItemRoute;
  source: ItemSource;
  createdAt: string;
  updatedAt: string;
  dueAt?: string;
  reviewAt?: string;
  strategyThreadId?: string;
  parentItemId?: string;
  sourceItemId?: string;
  noteId?: string;
  deletedAt?: string;
}

export interface ItemPayload {
  title: string;
  content?: string;
  notes?: string;
  noteId?: string;
  sortOrder?: number;
  statusChangedAt?: string;
  attachments?: Array<{
    id: string;
    name: string;
    size?: number;
    addedAt: string;
  }>;
}

export type TrackerStatus =
  | "waiting"
  | "watching"
  | "discuss"
  | "review_later"
  | "active"
  | "done"
  | "killed";

export interface TrackerItemPayload {
  title: string;
  notes?: string;
  owner?: string;
  relatedPerson?: string;
  status: TrackerStatus;
  reviewAt?: string;
  strategyThreadId?: string;
  sourceItemId?: string;
}

export interface StrategyThreadMetadata extends EncryptedPayloadFields {
  id: string;
  status: "active" | "quiet" | "archived";
  createdAt: string;
  updatedAt: string;
  nextReviewAt?: string;
}

export interface StrategyThreadPayload {
  title: string;
  description?: string;
  currentHypothesis?: string;
  sortOrder?: number;
}

export type StrategyNoteKind =
  | "observation"
  | "hypothesis"
  | "decision"
  | "question"
  | "meeting_signal";

export interface StrategyNotePayload {
  title: string;
  content?: string;
  kind: StrategyNoteKind;
}

export type CalendarProvider = "feishu" | "local";

export type CalendarEventSyncState =
  | "synced"
  | "local_pending_create"
  | "local_pending_update"
  | "local_pending_delete"
  | "remote_deleted"
  | "conflict"
  | "readonly"
  | "error"
  | "archived_locally";

export interface CalendarEventMetadata extends EncryptedPayloadFields {
  id: string;
  provider: CalendarProvider;
  externalCalendarId?: string;
  externalEventId?: string;
  status: CalendarEventSyncState;
  startsAt: string;
  endsAt?: string;
  isAllDay?: boolean;
  createdAt: string;
  updatedAt: string;
  remoteUpdatedAt?: string;
  lastSyncedAt?: string;
  sourceItemId?: string;
  strategyThreadId?: string;
  canEdit?: boolean;
  canDelete?: boolean;
}

export interface CalendarEventPayload {
  title: string;
  description?: string;
  location?: string;
  meetingUrl?: string;
  attendees?: Array<{
    name?: string;
    email?: string;
    userId?: string;
    status?: string;
  }>;
  recurrence?: unknown;
  reminders?: unknown;
  feishuRaw?: unknown;
}

export type NoteStatus = "active" | "archived" | "conflict";

export interface NoteMetadata extends EncryptedPayloadFields {
  id: string;
  canonicalPath: string;
  title: string;
  status: NoteStatus;
  createdAt: string;
  updatedAt: string;
  lastOpenedAt?: string;
  taskId?: string;
  strategyThreadId?: string;
  parentNoteId?: string;
  contentHash?: string;
  frontmatterHash?: string;
  deviceUpdatedAt?: string;
  remoteUpdatedAt?: string;
}

export interface NotePayload {
  title: string;
  markdown?: string;
  excerpt?: string;
}

export interface SyncState {
  provider: "feishu";
  userId: string;
  lastFullSyncAt?: string;
  lastIncrementalSyncAt?: string;
  encryptedSyncToken?: string;
  syncTokenNonce?: string;
  status: "ok" | "needs_auth" | "error";
  lastError?: string;
  updatedAt: string;
}

export interface DecryptedItem {
  meta: ItemMetadata;
  payload: ItemPayload | TrackerItemPayload | StrategyNotePayload;
}

export interface DecryptedStrategyThread {
  meta: StrategyThreadMetadata;
  payload: StrategyThreadPayload;
}

export interface DecryptedCalendarEvent {
  meta: CalendarEventMetadata;
  payload: CalendarEventPayload;
}

export interface DecryptedNote {
  meta: NoteMetadata;
  payload: NotePayload;
}

export interface TodaySummary {
  calendar: DecryptedCalendarEvent[];
  focus: DecryptedItem[];
  followUps: DecryptedItem[];
  strategySignals: DecryptedItem[];
  overflow: {
    calendar: number;
    focus: number;
    followUps: number;
    strategySignals: number;
  };
}
