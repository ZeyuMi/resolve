export interface FirestoreEncryptedItem {
  id: string;
  userId: string;
  type: string;
  status: string;
  source: string;
  createdAt: string;
  updatedAt: string;
  dueAt?: string;
  reviewAt?: string;
  strategyThreadId?: string;
  parentItemId?: string;
  sourceItemId?: string;
  encryptedPayload: string;
  payloadNonce: string;
  payloadVersion: number;
  deletedAt?: string;
}

export interface FirestoreEncryptedStrategyThread {
  id: string;
  userId: string;
  status: "active" | "quiet" | "archived";
  createdAt: string;
  updatedAt: string;
  nextReviewAt?: string;
  encryptedPayload: string;
  payloadNonce: string;
  payloadVersion: number;
}

export interface FirestoreEncryptedCalendarEvent {
  id: string;
  userId: string;
  provider: "feishu" | "local";
  externalCalendarId?: string;
  externalEventId?: string;
  status: string;
  startsAt: string;
  endsAt?: string;
  isAllDay?: boolean;
  createdAt: string;
  updatedAt: string;
  remoteUpdatedAt?: string;
  lastSyncedAt?: string;
  sourceItemId?: string;
  strategyThreadId?: string;
  encryptedPayload: string;
  payloadNonce: string;
  payloadVersion: number;
}

export interface FirestoreSyncState {
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
