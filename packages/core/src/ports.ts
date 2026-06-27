import type {
  DecryptedCalendarEvent,
  DecryptedItem,
  DecryptedNote,
  DecryptedStrategyThread
} from "./types";

export interface ResolveStateSnapshot {
  items: DecryptedItem[];
  strategyThreads: DecryptedStrategyThread[];
  calendarEvents: DecryptedCalendarEvent[];
  notes: DecryptedNote[];
}

export interface ResolveStateRepository {
  load(): ResolveStateSnapshot;
  save(state: ResolveStateSnapshot): void | Promise<void>;
}

export interface ResolveSyncPort {
  pull(options?: { includeCalendarEvents?: boolean; changedSince?: string }): Promise<ResolveStateSnapshot>;
  push(state: ResolveStateSnapshot, options?: { changedSince?: string }): Promise<void>;
  deleteItems?(itemIds: string[]): Promise<void>;
  subscribe?(onChange: (kind: string) => void): (() => void) | Promise<() => void>;
}

export interface NoteFilePort {
  ensureRoot?(): Promise<string>;
  read(path: string): Promise<{ path: string; content: string }>;
  write(path: string, content: string): Promise<{ path: string; content: string }>;
  delete(path: string): Promise<void>;
}

export interface SecureSecretPort {
  load(key: string): Promise<string | undefined>;
  save(key: string, value: string): Promise<void>;
  clear(key: string): Promise<void>;
}

export interface CalendarProviderPort<Event, Draft> {
  syncNow(): Promise<void>;
  create(input: Draft): Promise<Event>;
  update(event: Event, input: Draft): Promise<Event>;
  delete(event: Event): Promise<void>;
}
