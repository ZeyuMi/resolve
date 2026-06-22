import {
  useEffect,
  useMemo,
  useRef,
  useState,
  type CSSProperties,
  type KeyboardEvent as ReactKeyboardEvent,
  type PointerEvent as ReactPointerEvent,
  type ReactNode
} from "react";
import {
  Archive,
  Brain,
  CalendarDays,
  Check,
  Copy,
  ChevronLeft,
  ChevronRight,
  Clock3,
  ExternalLink,
  KeyRound,
  LayoutList,
  Lock,
  Paperclip,
  Plus,
  RefreshCw,
  Search,
  Send,
  Settings,
  ShieldCheck,
  Trash2,
  X
} from "lucide-react";
import {
  emptyEncryptedFields,
  makeId,
  nowIso,
  relativeAgeLabel,
  type CalendarEventPayload,
  type DecryptedCalendarEvent,
  type DecryptedItem,
  type DecryptedStrategyThread,
  type ItemPayload,
  type StrategyNotePayload
} from "@resolve/core";
import { type ResolveState } from "@resolve/sync";
import {
  feishuCalendarScopes,
  FeishuOpenApiClient,
  type FeishuCalendar,
  type FeishuEvent,
  type TokenSet
} from "@resolve/feishu";
import type { CalendarDraft, CalendarEventEditDraft, CalendarViewMode, StrategyDraft, Tab } from "./appTypes";
import { createAppRepository } from "./data/appRepository";
import {
  canUseFeishuConnection,
  clearFeishuToken,
  feishuConfig,
  isTauriRuntime,
  isTokenExpired,
  loadFeishuSettings,
  loadFeishuToken,
  localFeishuRedirectUri,
  nativeFeishuRedirectUri,
  saveFeishuSettings,
  saveFeishuToken,
  type FeishuSettingsState
} from "./data/feishuLocalStore";
import {
  clearBackendSession,
  isBackendJwtExpired,
  loadBackendSession,
  loadBackendSettings,
  needsCalendarAuthorization,
  resolveSupabasePublishableKey,
  resolveSupabaseUrl,
  ResolveBackendClient,
  saveBackendSession,
  saveBackendSettings,
  shouldRefreshBackendSession,
  type BackendSettingsState,
  type BackendSession
} from "./data/resolveBackend";
import { mergeEncryptedRemoteState, ResolveAppEncryptedSync } from "./data/appEncryptedSync";
import {
  activeTodoStatuses,
  createCalendarEvent,
  createStrategyThread,
  createTodoItem,
  normalizeState
} from "./data/resolveState";
import { registerMacPlatformHandlers, type MacTrayAction } from "./platform/macPlatform";
import {
  installNativeHttpBridge,
  clearSecureSyncSecret,
  loadSecureFeishuCredentials,
  loadSecureSyncSecret,
  runNativeFeishuOAuth,
  saveSecureFeishuCredentials,
  saveSecureSyncSecret
} from "./platform/nativeIntegrations";

const feishuOAuthScopes = feishuCalendarScopes
  .map((scope) => scope.key)
  .filter((scope) => scope !== "contact:user.email:readonly");
const appSyncCursorKey = "resolve:app-sync-cursor:v2";
const appSyncCursorLookbackMs = 30 * 24 * 60 * 60 * 1000;

function loadAppSyncCursor() {
  return localStorage.getItem(appSyncCursorKey) ?? undefined;
}

function appSyncCursorWithLookback(cursor?: string) {
  if (!cursor) return undefined;
  const time = new Date(cursor).getTime();
  if (!Number.isFinite(time)) return undefined;
  return new Date(Math.max(0, time - appSyncCursorLookbackMs)).toISOString();
}

function saveAppSyncCursor(cursor: string) {
  localStorage.setItem(appSyncCursorKey, cursor);
}

async function openExternalUrl(url: string) {
  if (isTauriRuntime()) {
    try {
      const { open } = await import("@tauri-apps/api/shell");
      await open(url);
      return;
    } catch (error) {
      console.warn("Could not open system browser through Tauri", error);
    }
  }
  window.open(url, "_blank", "noopener,noreferrer");
}

function calendarEventId(calendarId: string, eventId: string) {
  return `feishu_${calendarId}_${eventId}`.replace(/[^a-zA-Z0-9_-]/g, "_");
}

function calendarEventFromFeishu(
  event: FeishuEvent,
  options: {
    sourceItemId?: string;
    strategyThreadId?: string;
    existing?: DecryptedCalendarEvent;
  } = {}
): DecryptedCalendarEvent {
  const timestamp = nowIso();
  return {
    meta: {
      id: options.existing?.meta.id ?? calendarEventId(event.calendarId, event.eventId),
      provider: "feishu",
      externalCalendarId: event.calendarId,
      externalEventId: event.eventId,
      status: event.canEdit === false ? "readonly" : "synced",
      startsAt: event.startsAt,
      endsAt: event.endsAt,
      isAllDay: event.isAllDay,
      createdAt: options.existing?.meta.createdAt ?? timestamp,
      updatedAt: timestamp,
      remoteUpdatedAt: event.updatedAt,
      lastSyncedAt: timestamp,
      sourceItemId: options.sourceItemId ?? options.existing?.meta.sourceItemId,
      strategyThreadId: options.strategyThreadId ?? options.existing?.meta.strategyThreadId,
      canEdit: event.canEdit,
      canDelete: event.canDelete,
      ...emptyEncryptedFields
    },
    payload: {
      title: event.title ?? "Untitled Feishu event",
      description: event.description,
      location: event.location,
      meetingUrl: event.meetingUrl,
      recurrence: event.recurrence,
      feishuRaw: event.raw
    }
  };
}

function isCancelledFeishuEvent(event: FeishuEvent) {
  return event.status === "cancelled" || !event.eventId;
}

function calendarExternalKey(event: Pick<DecryptedCalendarEvent["meta"], "externalCalendarId" | "externalEventId">) {
  return event.externalCalendarId && event.externalEventId
    ? `${event.externalCalendarId}:${event.externalEventId}`
    : undefined;
}

function displayCalendarEventKey(event: DecryptedCalendarEvent) {
  const payload = event.payload as CalendarEventPayload;
  return [
    event.meta.startsAt,
    event.meta.endsAt ?? "",
    event.meta.isAllDay ? "all-day" : "timed",
    (payload.title ?? "").trim().toLocaleLowerCase()
  ].join("|");
}

function sameCalendarEventIdentity(left: DecryptedCalendarEvent, right: DecryptedCalendarEvent) {
  const leftKey = calendarExternalKey(left.meta);
  const rightKey = calendarExternalKey(right.meta);
  if (leftKey && rightKey) return leftKey === rightKey;
  return left.meta.id === right.meta.id;
}

function replaceCalendarEvent(events: DecryptedCalendarEvent[], nextEvent: DecryptedCalendarEvent) {
  let replaced = false;
  const nextEvents = events.map((event) => {
    if (!sameCalendarEventIdentity(event, nextEvent)) return event;
    replaced = true;
    return nextEvent;
  });
  return replaced ? nextEvents : [nextEvent, ...nextEvents];
}

function dedupeCalendarEvents(events: DecryptedCalendarEvent[]) {
  const keyed = new Map<string, DecryptedCalendarEvent>();
  const localEvents: DecryptedCalendarEvent[] = [];

  events.forEach((event) => {
    const key = calendarExternalKey(event.meta);
    if (!key) {
      localEvents.push(event);
      return;
    }

    const existing = keyed.get(key);
    if (!existing || event.meta.updatedAt.localeCompare(existing.meta.updatedAt) >= 0) {
      keyed.set(key, event);
    }
  });

  return [...Array.from(keyed.values()), ...localEvents];
}

function dedupeDisplayCalendarEvents(events: DecryptedCalendarEvent[]) {
  const seen = new Map<string, DecryptedCalendarEvent>();
  events.forEach((event) => {
    const key = displayCalendarEventKey(event);
    const existing = seen.get(key);
    if (!existing || event.meta.updatedAt.localeCompare(existing.meta.updatedAt) >= 0) {
      seen.set(key, event);
    }
  });
  return Array.from(seen.values());
}

function preferCalendarDuplicate(candidate: DecryptedCalendarEvent, existing: DecryptedCalendarEvent) {
  const candidateHasRecurrence = Boolean((candidate.payload as CalendarEventPayload).recurrence);
  const existingHasRecurrence = Boolean((existing.payload as CalendarEventPayload).recurrence);
  if (candidateHasRecurrence !== existingHasRecurrence) return candidateHasRecurrence ? candidate : existing;
  return candidate.meta.updatedAt.localeCompare(existing.meta.updatedAt) >= 0 ? candidate : existing;
}

function dedupeSyncedCalendarDuplicates(events: DecryptedCalendarEvent[]) {
  const seen = new Map<string, DecryptedCalendarEvent>();
  const passthrough: DecryptedCalendarEvent[] = [];
  const syncedDisplayKeys = new Set(
    events
      .filter((event) => event.meta.provider === "feishu" && event.meta.externalEventId)
      .map(displayCalendarEventKey)
  );

  events.forEach((event) => {
    if (
      event.meta.status === "local_pending_create" &&
      !event.meta.externalEventId &&
      syncedDisplayKeys.has(displayCalendarEventKey(event))
    ) {
      return;
    }

    const shouldDedupe =
      event.meta.provider === "feishu" &&
      !["local_pending_create", "local_pending_update", "local_pending_delete"].includes(event.meta.status);

    if (!shouldDedupe) {
      passthrough.push(event);
      return;
    }

    const key = displayCalendarEventKey(event);
    const existing = seen.get(key);
    seen.set(key, existing ? preferCalendarDuplicate(event, existing) : event);
  });

  return [...Array.from(seen.values()), ...passthrough];
}

function isStalePendingCreate(event: DecryptedCalendarEvent, now = Date.now()) {
  return (
    event.meta.provider === "feishu" &&
    event.meta.status === "local_pending_create" &&
    !event.meta.externalEventId &&
    now - new Date(event.meta.updatedAt).getTime() > 2 * 60_000
  );
}

function eventOverlapsWindow(event: DecryptedCalendarEvent, startsAt: string, endsAt: string) {
  const eventStart = new Date(event.meta.startsAt).getTime();
  const eventEnd = new Date(event.meta.endsAt ?? event.meta.startsAt).getTime();
  return eventEnd >= new Date(startsAt).getTime() && eventStart <= new Date(endsAt).getTime();
}

function calendarEventVisibleInWindow(event: DecryptedCalendarEvent, startsAt: string, endsAt: string) {
  if (eventOverlapsWindow(event, startsAt, endsAt)) return true;
  const recurrence = typeof event.payload.recurrence === "string" ? event.payload.recurrence : undefined;
  if (!recurrence) return false;
  return expandRecurringCalendarEvent(event, recurrence, new Date(startsAt), new Date(endsAt)).length > 0;
}

function mergeFeishuEvents(
  current: DecryptedCalendarEvent[],
  remoteEvents: DecryptedCalendarEvent[],
  options: {
    syncedCalendarIds: Set<string>;
    windowStartsAt: string;
    windowEndsAt: string;
    deletedRemoteKeys: Set<string>;
  } = {
    syncedCalendarIds: new Set(),
    windowStartsAt: new Date(0).toISOString(),
    windowEndsAt: new Date(8_640_000_000_000_000).toISOString(),
    deletedRemoteKeys: new Set()
  }
) {
  const currentEvents = dedupeCalendarEvents(current).filter((event) => !isStalePendingCreate(event));
  const remoteByKey = new Map(
    dedupeCalendarEvents(remoteEvents)
      .map((event) => [calendarExternalKey(event.meta), event])
      .filter((entry): entry is [string, DecryptedCalendarEvent] => Boolean(entry[0]))
  );
  const merged = currentEvents.map((event) => {
    const key = calendarExternalKey(event.meta);
    if (!key) return event;
    if (options.deletedRemoteKeys.has(key)) return undefined;
    const remote = remoteByKey.get(key);
    if (!remote) {
      const shouldPruneMissingRemote =
        event.meta.provider === "feishu" &&
        event.meta.externalCalendarId &&
        options.syncedCalendarIds.has(event.meta.externalCalendarId) &&
        calendarEventVisibleInWindow(event, options.windowStartsAt, options.windowEndsAt) &&
        !["local_pending_create", "local_pending_update", "local_pending_delete"].includes(event.meta.status);
      return shouldPruneMissingRemote ? undefined : event;
    }
    remoteByKey.delete(key);
    return calendarEventFromFeishu(remote.payload.feishuRaw ? remoteToFeishuEvent(remote) : remoteToFeishuEvent(remote), {
      existing: event
    });
  }).filter((event): event is DecryptedCalendarEvent => Boolean(event));
  return dedupeSyncedCalendarDuplicates(dedupeCalendarEvents([...Array.from(remoteByKey.values()), ...merged]));
}

function mergeBackendCalendarEvents(current: DecryptedCalendarEvent[], remoteEvents: DecryptedCalendarEvent[]) {
  return dedupeCalendarEvents([
    ...remoteEvents,
    ...current.filter((event) => event.meta.provider !== "feishu")
  ]).sort((a, b) => a.meta.startsAt.localeCompare(b.meta.startsAt));
}

function remoteToFeishuEvent(event: DecryptedCalendarEvent): FeishuEvent {
  return {
    calendarId: event.meta.externalCalendarId ?? "primary",
    eventId: event.meta.externalEventId ?? event.meta.id,
    title: event.payload.title,
    description: event.payload.description,
    location: event.payload.location,
    startsAt: event.meta.startsAt,
    endsAt: event.meta.endsAt,
    isAllDay: event.meta.isAllDay,
    updatedAt: event.meta.remoteUpdatedAt,
    status: typeof event.payload.feishuRaw === "object" && event.payload.feishuRaw && "status" in event.payload.feishuRaw
      ? String((event.payload.feishuRaw as { status?: unknown }).status)
      : undefined,
    recurrence: typeof event.payload.recurrence === "string" ? event.payload.recurrence : undefined,
    canEdit: event.meta.canEdit,
    canDelete: event.meta.canDelete,
    raw: event.payload.feishuRaw
  };
}

function addDaysToIso(base: Date, days: number) {
  const next = new Date(base);
  next.setDate(base.getDate() + days);
  return next.toISOString();
}

function feishuErrorMessage(error: unknown) {
  return error instanceof Error ? error.message : String(error);
}

async function readableFeishuCalendars(client: FeishuOpenApiClient, primary: FeishuCalendar, defaultCalendarId?: string) {
  try {
    const calendars = await client.listCalendars();
    const defaultCalendar =
      defaultCalendarId && defaultCalendarId !== "primary"
        ? { calendarId: defaultCalendarId, type: "primary" } satisfies FeishuCalendar
        : undefined;
    const all = [primary, ...(defaultCalendar ? [defaultCalendar] : []), ...calendars];
    const unique = new Map(all.map((calendar) => [calendar.calendarId, calendar]));
    const supported = Array.from(unique.values()).filter((calendar) =>
      !calendar.type || ["primary", "shared", "resource"].includes(calendar.type)
    );
    return supported.length ? supported : [primary];
  } catch {
    return defaultCalendarId && defaultCalendarId !== "primary"
      ? [primary, { calendarId: defaultCalendarId, type: "primary" }]
      : [primary];
  }
}

async function resolveWritableCalendarId(settings: FeishuSettingsState, client: FeishuOpenApiClient) {
  if (settings.defaultCalendar && settings.defaultCalendar !== "primary") return settings.defaultCalendar;
  const primary = await client.getPrimaryCalendar();
  return primary.calendarId;
}

function normalizePrimaryCalendarAliases(events: DecryptedCalendarEvent[], primaryCalendarId: string) {
  return events.map((event) =>
    event.meta.provider === "feishu" && event.meta.externalCalendarId === "primary"
      ? {
          ...event,
          meta: {
            ...event.meta,
            externalCalendarId: primaryCalendarId
          }
        }
      : event
  );
}

function formatDateInput(date: Date) {
  const year = date.getFullYear();
  const month = `${date.getMonth() + 1}`.padStart(2, "0");
  const day = `${date.getDate()}`.padStart(2, "0");
  return `${year}-${month}-${day}`;
}

function dateKey(iso: string) {
  return formatDateInput(new Date(iso));
}

function monthDays(monthCursor: Date) {
  const first = new Date(monthCursor.getFullYear(), monthCursor.getMonth(), 1);
  const start = new Date(first);
  start.setDate(first.getDate() - ((first.getDay() + 6) % 7));
  return Array.from({ length: 42 }, (_, index) => {
    const day = new Date(start);
    day.setDate(start.getDate() + index);
    return day;
  });
}

function weekDays(selectedDate: string) {
  const date = new Date(`${selectedDate}T00:00:00`);
  const start = new Date(date);
  start.setDate(date.getDate() - ((date.getDay() + 6) % 7));
  return Array.from({ length: 7 }, (_, index) => {
    const day = new Date(start);
    day.setDate(start.getDate() + index);
    return day;
  });
}

function eventTimeLabel(iso: string) {
  return new Date(iso).toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit" });
}

function timeSelectOptions() {
  return Array.from({ length: 96 }, (_, index) => {
    const totalMinutes = index * 15;
    const hour = `${Math.floor(totalMinutes / 60)}`.padStart(2, "0");
    const minute = `${totalMinutes % 60}`.padStart(2, "0");
    return `${hour}:${minute}`;
  });
}

function compareCalendarEvents(a: DecryptedCalendarEvent, b: DecryptedCalendarEvent) {
  const byStart = new Date(a.meta.startsAt).getTime() - new Date(b.meta.startsAt).getTime();
  if (byStart !== 0) return byStart;
  const byEnd = new Date(a.meta.endsAt ?? a.meta.startsAt).getTime() - new Date(b.meta.endsAt ?? b.meta.startsAt).getTime();
  if (byEnd !== 0) return byEnd;
  return a.payload.title.localeCompare(b.payload.title, "zh-CN");
}

function visibleCalendarEvent(event: DecryptedCalendarEvent) {
  const raw = event.payload.feishuRaw as Record<string, unknown> | undefined;
  return (
    !["archived_locally", "remote_deleted", "local_pending_delete"].includes(event.meta.status) &&
    raw?.status !== "cancelled"
  );
}

function expandRecurringCalendarEvents(events: DecryptedCalendarEvent[], rangeStart: Date, rangeEnd: Date) {
  return events.flatMap((event) => {
    const recurrence = typeof event.payload.recurrence === "string" ? event.payload.recurrence : undefined;
    if (!recurrence) return [event];
    return expandRecurringCalendarEvent(event, recurrence, rangeStart, rangeEnd);
  });
}

function expandRecurringCalendarEvent(
  event: DecryptedCalendarEvent,
  recurrence: string,
  rangeStart: Date,
  rangeEnd: Date
) {
  const rule = parseRRule(recurrence);
  const freq = rule.FREQ;
  if (!freq) return [event];

  const interval = Math.max(Number(rule.INTERVAL ?? "1") || 1, 1);
  const count = rule.COUNT ? Number(rule.COUNT) : undefined;
  const until = rule.UNTIL ? parseRRuleUntil(rule.UNTIL) : undefined;
  const start = new Date(event.meta.startsAt);
  const end = new Date(event.meta.endsAt ?? event.meta.startsAt);
  const duration = end.getTime() - start.getTime();
  const byDays = parseByDay(rule.BYDAY);
  const byMonthDays = parseByMonthDay(rule.BYMONTHDAY);
  const occurrences: DecryptedCalendarEvent[] = [];
  let cursor = new Date(start);
  let generated = 0;
  let guard = 0;

  while (cursor < rangeEnd && guard < 2500) {
    guard += 1;
    if (until && cursor > until) break;
    if (count && generated >= count) break;

    const occurrenceStarts = occurrenceStartsForCursor(cursor, start, freq, byDays, byMonthDays)
      .filter((date) => date >= start)
      .sort((a, b) => a.getTime() - b.getTime());

    for (const occurrenceStart of occurrenceStarts) {
      if (until && occurrenceStart > until) continue;
      if (count && generated >= count) break;
      generated += 1;
      const occurrenceEnd = new Date(occurrenceStart.getTime() + duration);
      if (occurrenceEnd < rangeStart || occurrenceStart >= rangeEnd) continue;
      occurrences.push({
        ...event,
        meta: {
          ...event.meta,
          id: `${event.meta.id}_${occurrenceStart.toISOString()}`,
          startsAt: occurrenceStart.toISOString(),
          endsAt: occurrenceEnd.toISOString()
        }
      });
    }

    cursor = advanceRecurringCursor(cursor, freq, interval);
  }

  return occurrences.length ? occurrences : [event];
}

function parseRRule(recurrence: string) {
  return Object.fromEntries(
    recurrence.split(";").map((part) => {
      const [key, value] = part.split("=");
      return [key, value];
    })
  ) as Record<string, string | undefined>;
}

function parseRRuleUntil(value: string) {
  if (/^\d{8}T\d{6}Z$/.test(value)) {
    return new Date(`${value.slice(0, 4)}-${value.slice(4, 6)}-${value.slice(6, 8)}T${value.slice(9, 11)}:${value.slice(11, 13)}:${value.slice(13, 15)}Z`);
  }
  if (/^\d{8}$/.test(value)) {
    return new Date(`${value.slice(0, 4)}-${value.slice(4, 6)}-${value.slice(6, 8)}T23:59:59`);
  }
  return new Date(value);
}

function parseByDay(value?: string) {
  if (!value) return undefined;
  const map: Record<string, number> = { SU: 0, MO: 1, TU: 2, WE: 3, TH: 4, FR: 5, SA: 6 };
  const days = value.split(",").map((day) => map[day.replace(/^-?\d+/, "")]).filter((day) => day != null);
  return days.length ? days : undefined;
}

function parseByMonthDay(value?: string) {
  if (!value) return undefined;
  const days = value.split(",").map(Number).filter((day) => Number.isFinite(day) && day > 0);
  return days.length ? days : undefined;
}

function occurrenceStartsForCursor(
  cursor: Date,
  start: Date,
  freq: string,
  byDays?: number[],
  byMonthDays?: number[]
) {
  if (freq === "WEEKLY" && byDays?.length) {
    return byDays.map((day) => {
      const date = new Date(cursor);
      date.setDate(cursor.getDate() + ((day - cursor.getDay() + 7) % 7));
      date.setHours(start.getHours(), start.getMinutes(), start.getSeconds(), start.getMilliseconds());
      return date;
    });
  }
  if (freq === "MONTHLY" && byMonthDays?.length) {
    return byMonthDays.map((day) => {
      const date = new Date(cursor.getFullYear(), cursor.getMonth(), day);
      date.setHours(start.getHours(), start.getMinutes(), start.getSeconds(), start.getMilliseconds());
      return date;
    }).filter((date) => date.getMonth() === cursor.getMonth());
  }
  return [new Date(cursor)];
}

function advanceRecurringCursor(cursor: Date, freq: string, interval: number) {
  const next = new Date(cursor);
  if (freq === "DAILY") next.setDate(next.getDate() + interval);
  else if (freq === "WEEKLY") next.setDate(next.getDate() + interval * 7);
  else if (freq === "MONTHLY") next.setMonth(next.getMonth() + interval);
  else if (freq === "YEARLY") next.setFullYear(next.getFullYear() + interval);
  else next.setDate(next.getDate() + interval);
  return next;
}

function todoDateLabel(iso: string) {
  const date = new Date(iso);
  const dateText = date.toLocaleDateString("zh-CN", { month: "short", day: "numeric", weekday: "short" });
  return `${dateText} ${eventTimeLabel(iso)}`;
}

function quickDateOptions(base = new Date()) {
  const today = new Date(base);
  const tomorrow = new Date(base);
  tomorrow.setDate(today.getDate() + 1);
  const nextWeek = new Date(base);
  nextWeek.setDate(today.getDate() + 7);
  return [
    { label: "Today", value: formatDateInput(today) },
    { label: "Tomorrow", value: formatDateInput(tomorrow) },
    { label: "Next week", value: formatDateInput(nextWeek) }
  ];
}

function inputTimeValue(iso?: string) {
  if (!iso) return "09:00";
  return new Date(iso).toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit" });
}

function todoSortOrder(item: DecryptedItem) {
  const value = (item.payload as ItemPayload).sortOrder;
  return typeof value === "number" && Number.isFinite(value) ? value : undefined;
}

function compareTodoItems(a: DecryptedItem, b: DecryptedItem) {
  const aOrder = todoSortOrder(a);
  const bOrder = todoSortOrder(b);
  if (aOrder != null && bOrder != null && aOrder !== bOrder) return aOrder - bOrder;
  if (aOrder != null && bOrder == null) return -1;
  if (aOrder == null && bOrder != null) return 1;
  if (a.meta.dueAt && !b.meta.dueAt) return -1;
  if (!a.meta.dueAt && b.meta.dueAt) return 1;
  const aDate = a.meta.dueAt ?? a.meta.createdAt;
  const bDate = b.meta.dueAt ?? b.meta.createdAt;
  return aDate.localeCompare(bDate);
}

function strategySortOrder(thread: DecryptedStrategyThread) {
  const value = thread.payload.sortOrder;
  return typeof value === "number" && Number.isFinite(value) ? value : undefined;
}

function compareStrategyThreads(a: DecryptedStrategyThread, b: DecryptedStrategyThread) {
  const aOrder = strategySortOrder(a);
  const bOrder = strategySortOrder(b);
  if (aOrder != null && bOrder != null && aOrder !== bOrder) return aOrder - bOrder;
  if (aOrder != null && bOrder == null) return -1;
  if (aOrder == null && bOrder != null) return 1;
  return a.meta.createdAt.localeCompare(b.meta.createdAt);
}

function isImeComposing(event: ReactKeyboardEvent<HTMLElement>) {
  return event.nativeEvent.isComposing || event.key === "Process" || event.keyCode === 229;
}

type ReorderPlacement = "before" | "after";

function reorderPlacementFromPoint(element: Element, clientY: number): ReorderPlacement {
  const rect = element.getBoundingClientRect();
  return clientY > rect.top + rect.height / 2 ? "after" : "before";
}

function isInteractiveDragTarget(target: EventTarget | null) {
  return target instanceof Element && Boolean(target.closest("button, input, textarea, select, a, [contenteditable='true']"));
}

function calendarDescriptionWithStrategy(description: string, strategyTitle?: string) {
  const cleanDescription = description.trim();
  const cleanStrategy = strategyTitle?.trim();
  if (!cleanStrategy) return cleanDescription;
  const strategyLine = `Strategy: ${cleanStrategy}`;
  if (cleanDescription.includes(strategyLine)) return cleanDescription;
  return [strategyLine, cleanDescription].filter(Boolean).join("\n\n");
}

export function App() {
  const repository = useRef(createAppRepository());
  const [state, setState] = useState<ResolveState>(() => {
    const next = normalizeState(repository.current.load());
    repository.current.save(next);
    return next;
  });
  const [tab, setTab] = useState<Tab>("todo");
  const [captureText, setCaptureText] = useState("");
  const [quickCaptureOpen, setQuickCaptureOpen] = useState(false);
  const [toast, setToast] = useState<string | null>(null);
  const [feishuSettings, setFeishuSettings] = useState(loadFeishuSettings);
  const [backendSettings, setBackendSettings] = useState(loadBackendSettings);
  const [feishuConnecting, setFeishuConnecting] = useState(false);
  const [manualSyncing, setManualSyncing] = useState(false);
  const [selectedThreadId, setSelectedThreadId] = useState(() => state.strategyThreads[0]?.meta.id ?? "");
  const [strategyTaskText, setStrategyTaskText] = useState("");
  const [strategyDraft, setStrategyDraft] = useState<StrategyDraft>({ title: "", currentHypothesis: "" });
  const [calendarDraft, setCalendarDraft] = useState<CalendarDraft | null>(null);
  const [calendarViewMode, setCalendarViewMode] = useState<CalendarViewMode>("month");
  const [monthCursor, setMonthCursor] = useState(() => new Date());
  const [selectedDate, setSelectedDate] = useState(() => formatDateInput(new Date()));
  const [selectedCalendarEventKey, setSelectedCalendarEventKey] = useState<string | null>(null);
  const [selectedTodoId, setSelectedTodoId] = useState<string | null>(null);
  const [selectedStrategyTodoId, setSelectedStrategyTodoId] = useState<string | null>(null);
  const [showCompleted, setShowCompleted] = useState(false);
  const [showArchived, setShowArchived] = useState(false);
  const stateRef = useRef(state);
  const feishuSettingsRef = useRef(feishuSettings);
  const backendSettingsRef = useRef(backendSettings);
  const syncInFlightRef = useRef(false);
  const syncQueuedRef = useRef(false);
  const backendStatusHydratedRef = useRef(false);
  const appSyncRef = useRef<ResolveAppEncryptedSync | null>(null);
  const localSaveTimerRef = useRef<number | null>(null);
  const applyingRemoteStateRef = useRef(false);
  const [syncSecretReady, setSyncSecretReady] = useState(false);

  const taskItems = useMemo(
    () =>
      state.items
        .filter((item) => item.meta.type === "task")
        .sort(compareTodoItems),
    [state.items]
  );
  const todoItems = useMemo(
    () =>
      taskItems
        .filter((item) => activeTodoStatuses.has(item.meta.status))
        .sort(compareTodoItems),
    [taskItems]
  );
  const completedTodos = useMemo(
    () => taskItems.filter((item) => item.meta.status === "done").sort((a, b) => b.meta.updatedAt.localeCompare(a.meta.updatedAt)),
    [taskItems]
  );
  const archivedTodos = useMemo(
    () => taskItems.filter((item) => item.meta.status === "archived").sort((a, b) => b.meta.updatedAt.localeCompare(a.meta.updatedAt)),
    [taskItems]
  );
  const strategyThreads = useMemo(
    () => state.strategyThreads.filter((thread) => thread.meta.status !== "archived").sort(compareStrategyThreads),
    [state.strategyThreads]
  );
  const selectedThread = strategyThreads.find((thread) => thread.meta.id === selectedThreadId);
  const strategyTodos = todoItems.filter((item) => item.meta.strategyThreadId === selectedThreadId);
  const strategyCompletedTodos = completedTodos.filter((item) => item.meta.strategyThreadId === selectedThreadId);
  const selectedTodo = taskItems.find((item) => item.meta.id === selectedTodoId);
  const selectedStrategyTodo = taskItems.find(
    (item) => item.meta.id === selectedStrategyTodoId && item.meta.strategyThreadId === selectedThreadId
  );
  const selectedTodoSubtasks = useMemo(
    () => directTodoSubtasks(taskItems, selectedTodo?.meta.id),
    [taskItems, selectedTodo?.meta.id]
  );
  const selectedStrategyTodoSubtasks = useMemo(
    () => directTodoSubtasks(taskItems, selectedStrategyTodo?.meta.id),
    [taskItems, selectedStrategyTodo?.meta.id]
  );
  const strategySignals = state.items
    .filter((item) => item.meta.type === "strategy_note")
    .filter((item) => item.meta.strategyThreadId === selectedThreadId)
    .sort((a, b) => b.meta.createdAt.localeCompare(a.meta.createdAt));

  useEffect(() => {
    void installNativeHttpBridge().catch((error) => {
      console.warn("Could not install native HTTP bridge", error);
    });
  }, []);

  useEffect(() => {
    stateRef.current = state;
  }, [state]);

  useEffect(() => {
    return () => {
      if (localSaveTimerRef.current) {
        window.clearTimeout(localSaveTimerRef.current);
        localSaveTimerRef.current = null;
        repository.current.save(stateRef.current);
      }
    };
  }, []);

  useEffect(() => {
    feishuSettingsRef.current = feishuSettings;
  }, [feishuSettings]);

  useEffect(() => {
    backendSettingsRef.current = backendSettings;
  }, [backendSettings]);

  useEffect(() => {
    if (!isTauriRuntime()) return;
    let cancelled = false;

    async function hydrateFeishuCredentials() {
      try {
        const credentials = await loadSecureFeishuCredentials();
        if (cancelled || (!credentials.appId && !credentials.appSecret)) return;
        setFeishuSettings((current) => {
          const next = {
            ...current,
            ...credentials,
            redirectUri: current.redirectUri || nativeFeishuRedirectUri
          };
          feishuSettingsRef.current = next;
          saveFeishuSettings(next);
          return next;
        });
      } catch (error) {
        console.warn("Could not load Feishu credentials from Keychain", error);
      }
    }

    void hydrateFeishuCredentials();
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      const target = event.target as HTMLElement | null;
      const isTyping = Boolean(target?.closest("input, textarea, select, [contenteditable='true']"));
      const wantsCapture =
        (event.altKey && event.code === "Space") ||
        ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "k") ||
        (!isTyping && event.key === "/");

      if (wantsCapture) {
        event.preventDefault();
        if (!window.matchMedia("(max-width: 760px)").matches) {
          setQuickCaptureOpen(true);
          return;
        }
        const input = Array.from(document.querySelectorAll<HTMLTextAreaElement>("[data-command-input]")).find(
          (element) => {
            const rect = element.getBoundingClientRect();
            return rect.width > 0 && rect.height > 0;
          }
        );
        input?.focus();
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, []);

  useEffect(() => {
    let cleanup = () => {};
    void registerMacPlatformHandlers({
      onQuickCapture: () => setQuickCaptureOpen(true),
      onTrayAction: (action) => handleMacTrayAction(action)
    }).then((unlisten) => {
      cleanup = unlisten;
    });
    return () => cleanup();
  }, []);

  useEffect(() => {
    let cancelled = false;
    void loadSecureSyncSecret().then((secret) => {
      if (!cancelled) setSyncSecretReady(Boolean(secret));
    });
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    let disposed = false;

    async function connectAppSync() {
      try {
        const sync = await createAppSyncClient();
        if (!sync) return;
        if (disposed) {
          await sync.dispose();
          return;
        }
        appSyncRef.current = sync;
      } catch (error) {
        console.warn("App data sync is not ready", error);
      }
    }

    void connectAppSync();
    return () => {
      disposed = true;
      const sync = appSyncRef.current;
      appSyncRef.current = null;
      void sync?.dispose();
    };
  }, [backendSettings.status, backendSettings.email, syncSecretReady]);

  useEffect(() => {
    if (backendStatusHydratedRef.current || !loadBackendSession()) return;
    backendStatusHydratedRef.current = true;
    let cancelled = false;

    async function hydrateBackendStatus() {
      try {
        const status = await withBackendClient((client) => client.status());
        if (cancelled) return;
        handleSaveBackend({
          ...backendSettingsRef.current,
          status: "connected",
          feishuConnected: status.connected,
          lastSyncedAt: status.lastServerSyncAt ?? backendSettingsRef.current.lastSyncedAt,
          lastError: status.needsAuthorization ? "Calendar needs attention" : undefined
        });
      } catch (error) {
        if (cancelled) return;
        handleSaveBackend({
          ...backendSettingsRef.current,
          status: "error",
          lastError: feishuErrorMessage(error)
        });
      }
    }

    void hydrateBackendStatus();
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const code = params.get("code");
    if (!code) return;
    const oauthCode = code;

    const returnedState = params.get("state");
    const expectedState = sessionStorage.getItem("feishu-oauth-state");
    window.history.replaceState({}, document.title, "/");

    async function finishOAuth() {
      if (!returnedState || returnedState !== expectedState) {
        const next = {
          ...feishuSettings,
          status: "permission_error" as const,
          lastError: "Calendar authorization did not complete."
        };
        setFeishuSettings(next);
        saveFeishuSettings(next);
        showToast("Feishu OAuth state mismatch");
        return;
      }

      if (!feishuSettings.appId || !feishuSettings.appSecret) {
        const next = {
          ...feishuSettings,
          status: "permission_error" as const,
          lastError: "Calendar connection needs attention."
        };
        setFeishuSettings(next);
        saveFeishuSettings(next);
        showToast("Calendar needs attention");
        return;
      }

      try {
        const tokenSet = await FeishuOpenApiClient.exchangeCode(feishuConfig(feishuSettings), oauthCode);
        saveFeishuToken(tokenSet);
        const next = {
          ...feishuSettings,
          status: "connected" as const,
          lastError: undefined
        };
        setFeishuSettings(next);
        saveFeishuSettings(next);
        setTab("calendar");
        showToast("Calendar connected");
      } catch (error) {
        const next = {
          ...feishuSettings,
          status: "permission_error" as const,
          lastError: feishuErrorMessage(error)
        };
        setFeishuSettings(next);
        saveFeishuSettings(next);
        showToast("Calendar authorization failed");
      } finally {
        sessionStorage.removeItem("feishu-oauth-state");
      }
    }

    void finishOAuth();
  }, []);

  async function pullAppStateFromCloud(
    reason: "initial" | "remote" | "manual",
    options: { includeCalendarEvents?: boolean; changedSince?: string } = {}
  ) {
    try {
      await withFreshAppSync(async (sync) => {
        const remote = await sync.pull({
          includeCalendarEvents: options.includeCalendarEvents ?? true,
          changedSince: options.changedSince
        });
        const merged = normalizeState(mergeEncryptedRemoteState(stateRef.current, remote));
        applyingRemoteStateRef.current = true;
        stateRef.current = merged;
        setState(merged);
        queueLocalStateSave(merged, 80);
        applyingRemoteStateRef.current = false;
      });
      if (reason === "manual") showToast("Synced app data");
    } catch (error) {
      applyingRemoteStateRef.current = false;
      console.warn("Could not pull app data", error);
    }
  }

  function queueLocalStateSave(next: ResolveState, delay = 250) {
    if (localSaveTimerRef.current) window.clearTimeout(localSaveTimerRef.current);
    localSaveTimerRef.current = window.setTimeout(() => {
      localSaveTimerRef.current = null;
      repository.current.save(normalizeState(stateRef.current ?? next));
    }, delay);
  }

  function persist(next: ResolveState) {
    const normalized = normalizeState(next);
    stateRef.current = normalized;
    setState(normalized);
    queueLocalStateSave(normalized);
  }

  function showToast(message: string) {
    setToast(message);
    window.setTimeout(() => setToast(null), 2200);
  }

  async function manualSyncNow() {
    if (manualSyncing) return;
    setManualSyncing(true);
    try {
      await withFreshAppSync(async (sync) => {
        const localCursor = loadAppSyncCursor();
        const changedSince = appSyncCursorWithLookback(localCursor);
        const cursorAfterSync = nowIso();
        const remote = await sync.pull({ includeCalendarEvents: false, changedSince });
        const merged = normalizeState(mergeEncryptedRemoteState(stateRef.current, remote));
        applyingRemoteStateRef.current = true;
        stateRef.current = merged;
        setState(merged);
        queueLocalStateSave(merged, 80);
        applyingRemoteStateRef.current = false;
        await sync.push(normalizeState(stateRef.current), { changedSince });
        saveAppSyncCursor(cursorAfterSync);
        showToast("Synced app data");
      });
      if (loadBackendSession()) {
        await syncBackendCalendar();
      } else {
        await syncFeishuCalendar();
      }
    } finally {
      setManualSyncing(false);
    }
  }

  function handleMacTrayAction(action: MacTrayAction) {
    if (action === "quick_capture") {
      setQuickCaptureOpen(true);
      return;
    }
    if (action === "open_calendar") {
      setTab("calendar");
      return;
    }
    if (action === "open_strategy") {
      setTab("strategy");
      return;
    }
    if (action === "settings") {
      setTab("settings");
      return;
    }
    if (action === "sync_feishu") {
      void manualSyncNow();
      return;
    }
    setTab("todo");
  }

  function handleCapture() {
    const title = captureText.trim();
    if (!title) return;
    const todo = createTodoItem({ title });
    persist({
      ...state,
      items: [todo, ...state.items]
    });
    setCaptureText("");
    setQuickCaptureOpen(false);
    setTab("todo");
    showToast("Added to Todo");
  }

  function updateTodo(itemId: string, patch: Partial<DecryptedItem["meta"]>) {
    persist({
      ...state,
      items: state.items.map((item) =>
        item.meta.id === itemId
          ? {
              ...item,
              meta: {
                ...item.meta,
                ...patch,
                updatedAt: nowIso()
              }
            }
          : item
      )
    });
  }

  function collectTodoDescendantIds(items: DecryptedItem[], parentIds: Iterable<string>) {
    const childrenByParent = new Map<string, DecryptedItem[]>();
    items.forEach((item) => {
      const parentId = item.meta.parentItemId;
      if (!parentId) return;
      const siblings = childrenByParent.get(parentId) ?? [];
      siblings.push(item);
      childrenByParent.set(parentId, siblings);
    });

    const descendants = new Set<string>();
    const visit = (id: string) => {
      childrenByParent.get(id)?.forEach((child) => {
        if (descendants.has(child.meta.id)) return;
        descendants.add(child.meta.id);
        visit(child.meta.id);
      });
    };
    Array.from(parentIds).forEach(visit);
    return descendants;
  }

  function archiveTodoWithDescendants(todo: DecryptedItem) {
    const archivedIds = collectTodoDescendantIds(state.items, [todo.meta.id]);
    archivedIds.add(todo.meta.id);
    const timestamp = nowIso();
    persist({
      ...state,
      items: state.items.map((item) =>
        archivedIds.has(item.meta.id)
          ? {
              ...item,
              meta: {
                ...item.meta,
                status: "archived",
                updatedAt: timestamp
              }
            }
          : item
      )
    });
  }

  function archiveStrategyWithTasks(thread: DecryptedStrategyThread) {
    const linkedRootIds = state.items
      .filter((item) => item.meta.type === "task" && item.meta.strategyThreadId === thread.meta.id)
      .map((item) => item.meta.id);
    const archivedIds = collectTodoDescendantIds(state.items, linkedRootIds);
    linkedRootIds.forEach((id) => archivedIds.add(id));
    const timestamp = nowIso();
    const nextThreads = state.strategyThreads.map((candidate) =>
      candidate.meta.id === thread.meta.id
        ? {
            ...candidate,
            meta: {
              ...candidate.meta,
              status: "archived" as const,
              updatedAt: timestamp
            }
          }
        : candidate
    );
    const nextActiveThreadId = nextThreads.find((candidate) => candidate.meta.status !== "archived")?.meta.id ?? "";
    persist({
      ...state,
      strategyThreads: nextThreads,
      items: state.items.map((item) =>
        archivedIds.has(item.meta.id)
          ? {
              ...item,
              meta: {
                ...item.meta,
                status: "archived",
                updatedAt: timestamp
              }
            }
          : item
      )
    });
    setSelectedThreadId(nextActiveThreadId);
    setSelectedStrategyTodoId(null);
  }

  function updateTodoPayload(itemId: string, patch: Partial<ItemPayload>) {
    persist({
      ...state,
      items: state.items.map((item) =>
        item.meta.id === itemId
          ? {
              ...item,
              meta: {
                ...item.meta,
                updatedAt: nowIso()
              },
              payload: {
                ...(item.payload as ItemPayload),
                ...patch
              }
            }
          : item
      )
    });
  }

  function updateTodoDetail(
    itemId: string,
    metaPatch: Partial<DecryptedItem["meta"]>,
    payloadPatch: Partial<ItemPayload>
  ) {
    const latest = stateRef.current;
    persist({
      ...latest,
      items: latest.items.map((item) =>
        item.meta.id === itemId
          ? {
              ...item,
              meta: {
                ...item.meta,
                ...metaPatch,
                updatedAt: nowIso()
              },
              payload: {
                ...(item.payload as ItemPayload),
                ...payloadPatch
              }
            }
          : item
      )
    });
  }

  function restoreTodo(itemId: string) {
    updateTodo(itemId, { status: "active" });
    showToast("Restored to Todo");
  }

  function deleteRemoteTodoIds(itemIds: Iterable<string>) {
    const ids = Array.from(new Set(itemIds)).filter(Boolean);
    if (!ids.length) return;
    void withFreshAppSync((sync) => sync.deleteItems(ids)).catch((error) => {
      console.warn("Could not delete archived todos remotely", error);
    });
  }

  function deleteTodosPermanently(rootIds: Iterable<string>) {
    const deletedIds = new Set<string>();
    Array.from(rootIds).forEach((id) => {
      deletedIds.add(id);
      collectTodoDescendantIds(stateRef.current.items, [id]).forEach((childId) => deletedIds.add(childId));
    });
    if (!deletedIds.size) return;
    const latest = stateRef.current;
    persist({
      ...latest,
      items: latest.items.filter((item) => !deletedIds.has(item.meta.id))
    });
    deleteRemoteTodoIds(deletedIds);
    if (selectedTodoId && deletedIds.has(selectedTodoId)) setSelectedTodoId(null);
    if (selectedStrategyTodoId && deletedIds.has(selectedStrategyTodoId)) setSelectedStrategyTodoId(null);
  }

  function clearArchivedTodos() {
    const archivedIds = stateRef.current.items
      .filter((item) => item.meta.type === "task" && item.meta.status === "archived")
      .map((item) => item.meta.id);
    deleteTodosPermanently(archivedIds);
    setShowArchived(false);
    showToast("Archive cleared");
  }

  function openCalendarDraft(todo?: DecryptedItem, date = selectedDate) {
    const strategyTitle =
      todo?.meta.strategyThreadId
        ? stateRef.current.strategyThreads.find((thread) => thread.meta.id === todo.meta.strategyThreadId)?.payload.title
        : undefined;
    const todoNotes = todo ? (todo.payload as ItemPayload).notes?.trim() : "";
    const description = [strategyTitle ? `Strategy: ${strategyTitle}` : "", todoNotes].filter(Boolean).join("\n\n");
    setCalendarDraft({
      todoId: todo?.meta.id,
      date,
      time: "09:00",
      title: todo ? (todo.payload as ItemPayload).title : "",
      description
    });
    setTab("calendar");
  }

  function patchCalendarDraft(patch: Partial<CalendarDraft> | null) {
    if (!patch) {
      setCalendarDraft(null);
      return;
    }
    setCalendarDraft((current) => (current ? { ...current, ...patch } : current));
  }

  async function saveCalendarDraft() {
    const draft = calendarDraft;
    if (!draft?.title.trim()) return;
    const startsAt = new Date(`${draft.date}T${draft.time}:00`).toISOString();
    const endsAt = new Date(new Date(startsAt).getTime() + 30 * 60_000).toISOString();
    const currentState = stateRef.current;
    const sourceTodo = currentState.items.find((item) => item.meta.id === draft.todoId);
    const sourceStrategyTitle = sourceTodo?.meta.strategyThreadId
      ? currentState.strategyThreads.find((thread) => thread.meta.id === sourceTodo.meta.strategyThreadId)?.payload.title
      : undefined;
    const draftDescription = calendarDescriptionWithStrategy(draft.description, sourceStrategyTitle);
    const optimisticEvent = createCalendarEvent({
      title: draft.title.trim(),
      startsAt,
      endsAt,
      description: draftDescription || undefined,
      sourceItemId: draft.todoId,
      strategyThreadId: sourceTodo?.meta.strategyThreadId
    });
    persist({
      ...currentState,
      items: currentState.items.map((item) =>
        item.meta.id === draft.todoId
          ? {
              ...item,
              meta: {
                ...item.meta,
                dueAt: startsAt,
                updatedAt: nowIso()
              }
            }
          : item
      ),
      calendarEvents: [optimisticEvent, ...currentState.calendarEvents]
    });
    setSelectedDate(draft.date);
    setSelectedCalendarEventKey(null);
    setCalendarDraft(null);
    showToast("Added locally; syncing Feishu");

    if (backendSettingsRef.current.feishuConnected && loadBackendSession()) {
      try {
        const remote = await withBackendClient((client) => client.createEvent({
          title: draft.title.trim(),
          startsAt,
          endsAt,
          description: draftDescription || undefined
        }));
        const syncedEvent = {
          ...remote,
          meta: {
            ...remote.meta,
            sourceItemId: draft.todoId,
            strategyThreadId: sourceTodo?.meta.strategyThreadId
          }
        };
        const latestState = stateRef.current;
        persist({
          ...latestState,
          calendarEvents: dedupeSyncedCalendarDuplicates(
            dedupeCalendarEvents(replaceCalendarEvent(latestState.calendarEvents, syncedEvent))
          )
        });
        showToast("Created in Feishu");
      } catch (error) {
        if (needsCalendarAuthorization(error)) {
          handleSaveBackend({
            ...backendSettingsRef.current,
            status: "connected",
            feishuConnected: false,
            lastError: "Calendar needs attention"
          });
          const nextFeishuSettings = {
            ...feishuSettingsRef.current,
            status: "not_connected" as const,
            lastError: "Calendar needs attention"
          };
          setFeishuSettings(nextFeishuSettings);
          saveFeishuSettings(nextFeishuSettings);
          showToast("Calendar needs attention");
          return;
        }
        const latestState = stateRef.current;
        persist({
          ...latestState,
          calendarEvents: replaceCalendarEvent(latestState.calendarEvents, {
            ...optimisticEvent,
            meta: {
              ...optimisticEvent.meta,
              status: "error",
              updatedAt: nowIso()
            }
          })
        });
        handleSaveBackend({
          ...backendSettingsRef.current,
          status: "error",
          lastError: feishuErrorMessage(error)
        });
        showToast("Feishu create failed; saved locally");
      }
      return;
    }

    const tokenSet = loadFeishuToken();
    const settings = feishuSettingsRef.current;
    if (canUseFeishuConnection(settings, tokenSet)) {
      try {
        const client = await resolveFeishuClient(settings, tokenSet);
        const writableCalendarId = await resolveWritableCalendarId(settings, client);
        const remote = await client.createEvent(writableCalendarId, {
          title: draft.title.trim(),
          startsAt,
          endsAt,
          description: draftDescription || undefined
        });
        const syncedEvent = calendarEventFromFeishu(remote, {
          existing: optimisticEvent,
          sourceItemId: draft.todoId,
          strategyThreadId: sourceTodo?.meta.strategyThreadId
        });
        const latestState = stateRef.current;
        persist({
          ...latestState,
          calendarEvents: dedupeSyncedCalendarDuplicates(
            dedupeCalendarEvents(replaceCalendarEvent(latestState.calendarEvents, syncedEvent))
          )
        });
        showToast("Created in Feishu");
      } catch (error) {
        const latestState = stateRef.current;
        const failedEvent = {
          ...optimisticEvent,
          meta: {
            ...optimisticEvent.meta,
            status: "error" as const,
            updatedAt: nowIso()
          }
        };
        persist({
          ...latestState,
          calendarEvents: replaceCalendarEvent(latestState.calendarEvents, failedEvent)
        });
        const next = {
          ...settings,
          status: "permission_error" as const,
          lastError: feishuErrorMessage(error)
        };
        setFeishuSettings(next);
        saveFeishuSettings(next);
        showToast("Feishu create failed; saved locally");
      }
      return;
    }

    showToast("Saved locally; calendar sync needs attention");
  }

  async function deleteCalendarEvent(event: DecryptedCalendarEvent) {
    const canDeleteRemote =
      event.meta.provider === "feishu" &&
      event.meta.status !== "readonly" &&
      event.meta.canDelete !== false &&
      Boolean(event.meta.externalCalendarId && event.meta.externalEventId);

    if (!canDeleteRemote) {
      const currentState = stateRef.current;
      persist({
        ...currentState,
        calendarEvents: currentState.calendarEvents.filter((item) => !sameCalendarEventIdentity(item, event))
      });
      setSelectedCalendarEventKey(null);
      showToast("Hidden locally");
      return;
    }

    const beforeDeleteState = stateRef.current;
    persist({
      ...beforeDeleteState,
      calendarEvents: beforeDeleteState.calendarEvents.filter((item) => !sameCalendarEventIdentity(item, event))
    });
    setSelectedCalendarEventKey(null);
    showToast("Deleting from Feishu");

    if (loadBackendSession()) {
      try {
        await withBackendClient((client) => client.deleteEvent(event.meta.externalCalendarId!, event.meta.externalEventId!));
        showToast("Deleted from Feishu");
      } catch (error) {
        const message = feishuErrorMessage(error);
        const latestState = stateRef.current;
        persist({
          ...latestState,
          calendarEvents: [
            {
              ...event,
              meta: {
                ...event.meta,
                status: needsCalendarAuthorization(error) ? "local_pending_delete" : "error",
                updatedAt: nowIso()
              }
            },
            ...latestState.calendarEvents
          ]
        });
        handleSaveBackend({
          ...backendSettingsRef.current,
          feishuConnected: !needsCalendarAuthorization(error),
          status: needsCalendarAuthorization(error) ? "connected" : "error",
          lastError: needsCalendarAuthorization(error) ? "Calendar needs attention" : message
        });
        showToast(needsCalendarAuthorization(error) ? "Calendar needs attention" : "Feishu delete failed");
      }
      return;
    }

    const tokenSet = loadFeishuToken();
    const settings = feishuSettingsRef.current;
    if (!canUseFeishuConnection(settings, tokenSet)) {
      const latestState = stateRef.current;
      persist({
        ...latestState,
        calendarEvents: [
          {
            ...event,
            meta: {
              ...event.meta,
              status: "local_pending_delete",
              updatedAt: nowIso()
            }
          },
          ...latestState.calendarEvents
        ]
      });
      showToast("Marked for delete; calendar sync needs attention");
      return;
    }

    try {
      const client = await resolveFeishuClient(settings, tokenSet);
      await client.deleteEvent(event.meta.externalCalendarId!, event.meta.externalEventId!);
      showToast("Deleted from Feishu");
    } catch (error) {
      const message = feishuErrorMessage(error);
      const latestState = stateRef.current;
      persist({
        ...latestState,
        calendarEvents: [
          {
            ...event,
            meta: {
              ...event.meta,
              status: "error",
              updatedAt: nowIso()
            }
          },
          ...latestState.calendarEvents
        ]
      });
      const next = {
        ...settings,
        status: "permission_error" as const,
        lastError: message
      };
      setFeishuSettings(next);
      saveFeishuSettings(next);
      showToast("Feishu delete failed");
    }
  }

  async function updateCalendarEvent(event: DecryptedCalendarEvent, draft: CalendarEventEditDraft) {
    const title = draft.title.trim();
    if (!title) return;
    if (event.meta.status === "readonly" || event.meta.canEdit === false) {
      showToast("Readonly Feishu event");
      return;
    }

    const startsAt = new Date(`${draft.date}T${draft.time}:00`).toISOString();
    const currentEnd = new Date(event.meta.endsAt ?? event.meta.startsAt).getTime();
    const currentStart = new Date(event.meta.startsAt).getTime();
    const duration = Math.max(currentEnd - currentStart, 30 * 60_000);
    const endsAt = new Date(new Date(startsAt).getTime() + duration).toISOString();
    const payload = event.payload as CalendarEventPayload;
    const canSyncRemote = Boolean(
      event.meta.provider === "feishu" &&
      event.meta.externalCalendarId &&
      event.meta.externalEventId
    );
    const optimisticEvent: DecryptedCalendarEvent = {
      ...event,
      meta: {
        ...event.meta,
        startsAt,
        endsAt,
        status: canSyncRemote ? "local_pending_update" : event.meta.status,
        updatedAt: nowIso()
      },
      payload: {
        ...payload,
        title,
        description: draft.description.trim() || undefined
      }
    };
    const currentState = stateRef.current;
    persist({
      ...currentState,
      calendarEvents: replaceCalendarEvent(currentState.calendarEvents, optimisticEvent)
    });
    setSelectedDate(draft.date);
    setSelectedCalendarEventKey(displayCalendarEventKey(optimisticEvent));

    if (canSyncRemote && loadBackendSession()) {
      try {
        const { event: remote } = await withBackendClient((client) => client.updateEvent({
          calendarId: event.meta.externalCalendarId!,
          eventId: event.meta.externalEventId!,
          title,
          startsAt,
          endsAt,
          description: draft.description.trim() || undefined
        }));
        const syncedEvent = {
          ...remote,
          meta: {
            ...remote.meta,
            sourceItemId: event.meta.sourceItemId,
            strategyThreadId: event.meta.strategyThreadId
          }
        };
        const latestState = stateRef.current;
        persist({
          ...latestState,
          calendarEvents: dedupeSyncedCalendarDuplicates(
            dedupeCalendarEvents(replaceCalendarEvent(latestState.calendarEvents, syncedEvent))
          )
        });
        setSelectedCalendarEventKey(displayCalendarEventKey(syncedEvent));
        showToast("Updated in Feishu");
      } catch (error) {
        const latestState = stateRef.current;
        persist({
          ...latestState,
          calendarEvents: replaceCalendarEvent(latestState.calendarEvents, {
            ...optimisticEvent,
            meta: {
              ...optimisticEvent.meta,
              status: needsCalendarAuthorization(error) ? "local_pending_update" : "error",
              updatedAt: nowIso()
            }
          })
        });
        handleSaveBackend({
          ...backendSettingsRef.current,
          feishuConnected: !needsCalendarAuthorization(error),
          status: needsCalendarAuthorization(error) ? "connected" : "error",
          lastError: needsCalendarAuthorization(error) ? "Calendar needs attention" : feishuErrorMessage(error)
        });
        showToast(needsCalendarAuthorization(error) ? "Calendar needs attention" : "Feishu update failed");
      }
      return;
    }

    const settings = feishuSettingsRef.current;
    const tokenSet = loadFeishuToken();
    if (!canSyncRemote || !canUseFeishuConnection(settings, tokenSet)) {
      showToast(canSyncRemote ? "Saved locally; calendar sync needs attention" : "Updated locally");
      return;
    }

    try {
      const client = await resolveFeishuClient(settings, tokenSet);
      const remote = await client.updateEvent(event.meta.externalCalendarId!, event.meta.externalEventId!, {
        title,
        startsAt,
        endsAt,
        description: draft.description.trim() || undefined
      });
      const syncedEvent = calendarEventFromFeishu(remote, { existing: optimisticEvent });
      const latestState = stateRef.current;
      persist({
        ...latestState,
        calendarEvents: dedupeSyncedCalendarDuplicates(
          dedupeCalendarEvents(replaceCalendarEvent(latestState.calendarEvents, syncedEvent))
        )
      });
      setSelectedCalendarEventKey(displayCalendarEventKey(syncedEvent));
      showToast("Updated in Feishu");
    } catch (error) {
      const latestState = stateRef.current;
      persist({
        ...latestState,
        calendarEvents: replaceCalendarEvent(latestState.calendarEvents, {
          ...optimisticEvent,
          meta: {
            ...optimisticEvent.meta,
            status: "error",
            updatedAt: nowIso()
          }
        })
      });
      const next = {
        ...settings,
        status: "permission_error" as const,
        lastError: feishuErrorMessage(error)
      };
      setFeishuSettings(next);
      saveFeishuSettings(next);
      showToast("Feishu update failed");
    }
  }

  function attachTodoToStrategy(todoId: string, threadId: string) {
    updateTodo(todoId, { strategyThreadId: threadId || undefined });
    showToast(threadId ? "Linked to Strategy" : "Strategy link cleared");
  }

  function addStrategyTask() {
    const title = strategyTaskText.trim();
    if (!title || !selectedThreadId) return;
    const todo = createTodoItem({ title, strategyThreadId: selectedThreadId });
    persist({
      ...state,
      items: [todo, ...state.items]
    });
    setStrategyTaskText("");
    showToast("Subtask added to Todo");
  }

  function addTodoSubtask(parentTodo: DecryptedItem, title: string) {
    const cleanTitle = title.trim();
    if (!cleanTitle) return;
    const subtask = createTodoItem({
      title: cleanTitle,
      parentItemId: parentTodo.meta.id,
      strategyThreadId: parentTodo.meta.strategyThreadId
    });
    const latest = stateRef.current;
    persist({
      ...latest,
      items: [
        subtask,
        ...latest.items.map((item) =>
          item.meta.id === parentTodo.meta.id
            ? {
                ...item,
                meta: {
                  ...item.meta,
                  updatedAt: nowIso()
                }
              }
            : item
        )
      ]
    });
    showToast("Subtask added");
  }

  function reorderTodo(sourceId: string, targetId: string, placement: ReorderPlacement = "before") {
    if (sourceId === targetId) return;
    const latest = stateRef.current;
    const source = latest.items.find((item) => item.meta.id === sourceId);
    const target = latest.items.find((item) => item.meta.id === targetId);
    if (!source || !target || source.meta.type !== "task" || target.meta.type !== "task") return;
    const sameParent = (source.meta.parentItemId ?? "") === (target.meta.parentItemId ?? "");
    const sameStrategy = (source.meta.strategyThreadId ?? "") === (target.meta.strategyThreadId ?? "");
    if (!sameParent || !sameStrategy) {
      showToast("Move within the same group");
      return;
    }

    const siblings = latest.items
      .filter(
        (item) =>
          item.meta.type === "task" &&
          activeTodoStatuses.has(item.meta.status) &&
          (item.meta.parentItemId ?? "") === (source.meta.parentItemId ?? "") &&
          (item.meta.strategyThreadId ?? "") === (source.meta.strategyThreadId ?? "")
      )
      .sort(compareTodoItems);
    const sourceItem = siblings.find((item) => item.meta.id === sourceId);
    if (!sourceItem) return;
    const withoutSource = siblings.filter((item) => item.meta.id !== sourceId);
    const targetIndex = withoutSource.findIndex((item) => item.meta.id === targetId);
    if (targetIndex < 0) return;
    const insertIndex = placement === "after" ? targetIndex + 1 : targetIndex;
    const reordered = [...withoutSource.slice(0, insertIndex), sourceItem, ...withoutSource.slice(insertIndex)];
    const nextOrder = new Map(reordered.map((item, index) => [item.meta.id, index + 1]));
    const timestamp = nowIso();
    persist({
      ...latest,
      items: latest.items.map((item) => {
        const sortOrder = nextOrder.get(item.meta.id);
        if (sortOrder == null) return item;
        return {
          ...item,
          meta: {
            ...item.meta,
            updatedAt: timestamp
          },
          payload: {
            ...(item.payload as ItemPayload),
            sortOrder
          }
        };
      })
    });
  }

  function reorderStrategyThread(sourceId: string, targetId: string, placement: ReorderPlacement = "before") {
    if (sourceId === targetId) return;
    const latest = stateRef.current;
    const source = latest.strategyThreads.find((thread) => thread.meta.id === sourceId);
    const target = latest.strategyThreads.find((thread) => thread.meta.id === targetId);
    if (!source || !target || source.meta.status === "archived" || target.meta.status === "archived") return;
    const activeThreads = latest.strategyThreads
      .filter((thread) => thread.meta.status !== "archived")
      .sort(compareStrategyThreads);
    const sourceThread = activeThreads.find((thread) => thread.meta.id === sourceId);
    if (!sourceThread) return;
    const withoutSource = activeThreads.filter((thread) => thread.meta.id !== sourceId);
    const targetIndex = withoutSource.findIndex((thread) => thread.meta.id === targetId);
    if (targetIndex < 0) return;
    const insertIndex = placement === "after" ? targetIndex + 1 : targetIndex;
    const reordered = [...withoutSource.slice(0, insertIndex), sourceThread, ...withoutSource.slice(insertIndex)];
    const nextOrder = new Map(reordered.map((thread, index) => [thread.meta.id, index + 1]));
    const timestamp = nowIso();
    persist({
      ...latest,
      strategyThreads: latest.strategyThreads.map((thread) => {
        const sortOrder = nextOrder.get(thread.meta.id);
        if (sortOrder == null) return thread;
        return {
          ...thread,
          meta: {
            ...thread.meta,
            updatedAt: timestamp
          },
          payload: {
            ...thread.payload,
            sortOrder
          }
        };
      })
    });
  }

  function addStrategyThread() {
    const title = strategyDraft.title.trim();
    if (!title) return;
    const thread = createStrategyThread({
      title,
      currentHypothesis: strategyDraft.currentHypothesis.trim() || undefined
    });
    persist({
      ...state,
      strategyThreads: [thread, ...state.strategyThreads]
    });
    setSelectedThreadId(thread.meta.id);
    setStrategyDraft({ title: "", currentHypothesis: "" });
    showToast("Strategy direction added");
  }

  function handleSaveFeishu(next: FeishuSettingsState) {
    const normalized = {
      ...next,
      redirectUri: next.redirectUri || localFeishuRedirectUri()
    };
    setFeishuSettings(normalized);
    saveFeishuSettings(normalized);
    void saveSecureFeishuCredentials(normalized).catch((error) => {
      console.warn("Could not save Feishu credentials to Keychain", error);
    });
  }

  function handleSaveBackend(next: BackendSettingsState) {
    const normalized = {
      ...next,
      supabaseUrl: next.supabaseUrl || resolveSupabaseUrl,
      publishableKey: next.publishableKey || resolveSupabasePublishableKey
    };
    backendSettingsRef.current = normalized;
    setBackendSettings(normalized);
    saveBackendSettings(normalized);
  }

  async function ensureBackendSession(forceRefresh = false) {
    const settings = backendSettingsRef.current;
    let session = loadBackendSession();
    if (!session) throw new Error("Sign in first.");
    if (forceRefresh || shouldRefreshBackendSession(session)) {
      const client = new ResolveBackendClient(settings, session);
      session = await client.refreshSession();
      saveBackendSession(session);
    }
    return session;
  }

  async function connectedBackendClient(forceRefresh = false) {
    const session = await ensureBackendSession(forceRefresh);
    return new ResolveBackendClient(backendSettingsRef.current, session);
  }

  async function withBackendClient<T>(operation: (client: ResolveBackendClient) => Promise<T>) {
    try {
      return await operation(await connectedBackendClient());
    } catch (error) {
      if (!isBackendJwtExpired(error)) throw error;
      return operation(await connectedBackendClient(true));
    }
  }

  async function createAppSyncClient(forceRefresh = false) {
    const syncSecret = await loadSecureSyncSecret();
    if (!syncSecret || !backendSettingsRef.current.email) return null;
    const session = await ensureBackendSession(forceRefresh);
    return ResolveAppEncryptedSync.create(backendSettingsRef.current, session, syncSecret);
  }

  async function withFreshAppSync<T>(operation: (sync: ResolveAppEncryptedSync) => Promise<T>) {
    let sync: ResolveAppEncryptedSync | null = await createAppSyncClient();
    if (!sync) return undefined;
    try {
      return await operation(sync);
    } catch (error) {
      const shouldRetry = isBackendJwtExpired(error);
      await sync.dispose().catch((disposeError) => {
        console.warn("Could not dispose expired sync client", disposeError);
      });
      sync = null;
      if (!shouldRetry) throw error;
      sync = await createAppSyncClient(true);
      if (!sync) return undefined;
      return await operation(sync);
    } finally {
      await sync?.dispose().catch((error) => {
        console.warn("Could not dispose sync client", error);
      });
    }
  }

  async function handleBackendSignIn(password: string) {
    setFeishuConnecting(true);
    try {
      const settings = backendSettingsRef.current;
      const client = new ResolveBackendClient(settings);
      const session = await client.signInWithPassword(password);
      saveBackendSession(session);
      await saveSecureSyncSecret(password);
      setSyncSecretReady(true);
      const authedClient = new ResolveBackendClient(settings, session);
      const status = await authedClient.status().catch(() => undefined);
      const calendarConnected = status?.connected === true;
      const calendarNeedsAuth = status?.needsAuthorization === true || status?.status === "needs_auth";
      handleSaveBackend({
        ...settings,
        status: "connected",
        feishuConnected: calendarConnected,
        lastSyncedAt: status?.lastServerSyncAt ?? settings.lastSyncedAt,
        lastError: calendarNeedsAuth ? "Calendar needs attention" : undefined
      });
      if (!calendarConnected) {
        const nextFeishuSettings = {
          ...feishuSettingsRef.current,
          status: "not_connected" as const,
          lastError: calendarNeedsAuth ? "Calendar needs attention" : undefined
        };
        setFeishuSettings(nextFeishuSettings);
        saveFeishuSettings(nextFeishuSettings);
      }
      showToast("Signed in");
    } catch (error) {
      handleSaveBackend({
        ...backendSettingsRef.current,
        status: "error",
        lastError: feishuErrorMessage(error)
      });
      showToast("Sign in failed");
    } finally {
      setFeishuConnecting(false);
    }
  }

  async function syncBackendCalendar(options: { silent?: boolean } = {}) {
    if (syncInFlightRef.current) {
      syncQueuedRef.current = true;
      return;
    }
    syncInFlightRef.current = true;
    try {
      const settings = feishuSettingsRef.current;

      async function fetchBackendCalendar(forceRefresh = false) {
        const client = await connectedBackendClient(forceRefresh);
        const now = new Date();
        const startsAt = addDaysToIso(now, -settings.pastDays);
        const endsAt = addDaysToIso(now, settings.futureDays);
        let syncError: unknown;
        let timestamp = nowIso();

        try {
          const syncResult = await client.syncNow();
          timestamp = syncResult.syncedAt ?? timestamp;
        } catch (error) {
          if (needsCalendarAuthorization(error) || isBackendJwtExpired(error)) throw error;
          syncError = error;
        }

        const remoteEvents = await client.listEvents(startsAt, endsAt).catch((error) => {
          if (isBackendJwtExpired(error)) throw error;
          throw syncError ?? error;
        });
        return { remoteEvents, syncError, timestamp };
      }

      let result: Awaited<ReturnType<typeof fetchBackendCalendar>>;
      try {
        result = await fetchBackendCalendar();
      } catch (error) {
        if (!isBackendJwtExpired(error)) throw error;
        result = await fetchBackendCalendar(true);
      }

      const { remoteEvents, syncError, timestamp } = result;
      const currentState = stateRef.current;
      persist({
        ...currentState,
        calendarEvents: mergeBackendCalendarEvents(currentState.calendarEvents, remoteEvents)
      });
      handleSaveBackend({
        ...backendSettingsRef.current,
        status: syncError ? "error" : "connected",
        feishuConnected: true,
        lastSyncedAt: timestamp,
        lastError: syncError ? `Live sync failed: ${feishuErrorMessage(syncError)}` : undefined
      });
      const nextFeishuSettings = {
        ...settings,
        status: "connected" as const,
        lastSyncedAt: timestamp,
        lastError: syncError ? `Live sync failed: ${feishuErrorMessage(syncError)}` : undefined
      };
      setFeishuSettings(nextFeishuSettings);
      saveFeishuSettings(nextFeishuSettings);
      if (!options.silent) showToast(syncError ? `Showing ${remoteEvents.length} cached events` : `Synced ${remoteEvents.length} events`);
    } catch (error) {
      if (needsCalendarAuthorization(error)) {
        handleSaveBackend({
          ...backendSettingsRef.current,
          status: "connected",
          feishuConnected: false,
          lastError: "Calendar needs attention"
        });
        const nextFeishuSettings = {
          ...feishuSettingsRef.current,
          status: "not_connected" as const,
          lastError: "Calendar needs attention"
        };
        setFeishuSettings(nextFeishuSettings);
        saveFeishuSettings(nextFeishuSettings);
        if (!options.silent) showToast("Calendar needs attention");
        return;
      }
      handleSaveBackend({
        ...backendSettingsRef.current,
        status: "error",
        lastError: feishuErrorMessage(error)
      });
      if (!options.silent) showToast("Sync failed");
    } finally {
      syncInFlightRef.current = false;
      if (syncQueuedRef.current) {
        syncQueuedRef.current = false;
        window.setTimeout(() => {
          void syncBackendCalendar({ silent: true });
        }, 250);
      }
    }
  }

  async function handleBackendFeishuConnect() {
    setFeishuConnecting(true);
    try {
      const oauth = await withBackendClient((client) => client.startFeishuOAuth());
      if (!oauth.authorizeUrl) throw new Error("Feishu authorization failed to start.");
      await openExternalUrl(oauth.authorizeUrl);
      showToast("Authorize Calendar");

      for (let index = 0; index < 24; index += 1) {
        await new Promise((resolve) => window.setTimeout(resolve, 5000));
        const status = await withBackendClient((client) => client.status()).catch(() => undefined);
        if (status?.connected) {
          handleSaveBackend({
            ...backendSettingsRef.current,
            status: "connected",
            feishuConnected: true,
            lastSyncedAt: status.lastServerSyncAt,
          lastError: undefined
        });
          showToast("Calendar authorized");
          return;
        }
      }
      showToast("Return after authorization");
    } catch (error) {
      handleSaveBackend({
        ...backendSettingsRef.current,
        status: "error",
        lastError: feishuErrorMessage(error)
      });
      showToast("Calendar authorization failed");
    } finally {
      setFeishuConnecting(false);
    }
  }

  async function handleConnectFeishu() {
    if (loadBackendSession()) {
      await handleBackendFeishuConnect();
      return;
    }
    showToast("Sign in first");
  }

  async function resolveFeishuClient(settings: FeishuSettingsState, tokenSet: TokenSet) {
    let nextToken = tokenSet;
    let client = new FeishuOpenApiClient(feishuConfig(settings), nextToken);
    if (isTokenExpired(nextToken)) {
      nextToken = await client.refreshAccessToken();
      saveFeishuToken(nextToken);
      client = new FeishuOpenApiClient(feishuConfig(settings), nextToken);
    }
    return client;
  }

  async function syncFeishuCalendar(
    settings = feishuSettingsRef.current,
    tokenSet = loadFeishuToken(),
    options: { silent?: boolean } = {}
  ) {
    if (backendSettingsRef.current.feishuConnected && loadBackendSession()) {
      await syncBackendCalendar(options);
      return;
    }
    if (syncInFlightRef.current) {
      syncQueuedRef.current = true;
      return;
    }
    if (!settings.appId || !settings.appSecret) {
      if (!options.silent) showToast("Sign in first");
      return;
    }
    if (!tokenSet) {
      const next = {
        ...settings,
        status: "token_expired" as const,
        lastError: "Calendar connection needs attention."
      };
      setFeishuSettings(next);
      saveFeishuSettings(next);
      if (!options.silent) showToast("Calendar needs attention");
      return;
    }

    syncInFlightRef.current = true;
    try {
      const client = await resolveFeishuClient(settings, tokenSet);
      const primary = await client.getPrimaryCalendar();
      const calendarId = settings.defaultCalendar === "primary" ? primary.calendarId : settings.defaultCalendar || primary.calendarId;
      const calendars = await readableFeishuCalendars(client, primary, calendarId);
      const now = new Date();
      const startsAt = addDaysToIso(now, -settings.pastDays);
      const endsAt = addDaysToIso(now, settings.futureDays);
      const events: FeishuEvent[] = [];
      const syncedCalendarIds = new Set<string>();
      const deletedRemoteKeys = new Set<string>();

      for (const calendar of calendars) {
        syncedCalendarIds.add(calendar.calendarId);
        let pageToken: string | undefined;
        do {
          const page = await client.listEvents({ calendarId: calendar.calendarId, anchorTime: startsAt, pageToken, pageSize: 1000 });
          page.events.forEach((event) => {
            const key = `${event.calendarId}:${event.eventId}`;
            if (isCancelledFeishuEvent(event)) {
              deletedRemoteKeys.add(key);
            } else {
              events.push(event);
            }
          });
          pageToken = page.nextPageToken;
        } while (pageToken);
      }

      const currentState = stateRef.current;
      const normalizedCalendarEvents = normalizePrimaryCalendarAliases(currentState.calendarEvents, calendarId);
      const remoteEvents = events.map((event) => {
        const existing = normalizedCalendarEvents.find(
          (item) =>
            item.meta.externalCalendarId === event.calendarId &&
            item.meta.externalEventId === event.eventId
        );
        return calendarEventFromFeishu(event, { existing });
      });
      const timestamp = nowIso();
      persist({
        ...currentState,
        calendarEvents: mergeFeishuEvents(normalizedCalendarEvents, remoteEvents, {
          syncedCalendarIds,
          windowStartsAt: startsAt,
          windowEndsAt: endsAt,
          deletedRemoteKeys
        })
      });

      const next = {
        ...settings,
        defaultCalendar: calendarId,
        status: "connected" as const,
        lastSyncedAt: timestamp,
        lastError: undefined
      };
      setFeishuSettings(next);
      saveFeishuSettings(next);
      if (!options.silent) showToast(`Feishu synced ${events.length} events`);
    } catch (error) {
      const next = {
        ...settings,
        status: "permission_error" as const,
        lastError: feishuErrorMessage(error)
      };
      setFeishuSettings(next);
      saveFeishuSettings(next);
      if (!options.silent) showToast("Feishu sync failed");
    } finally {
      syncInFlightRef.current = false;
      if (syncQueuedRef.current) {
        syncQueuedRef.current = false;
        window.setTimeout(() => {
          void syncFeishuCalendar(feishuSettingsRef.current, loadFeishuToken(), { silent: true });
        }, 250);
      }
    }
  }

  function handleDisconnectFeishu() {
    clearFeishuToken();
    const next = {
      ...feishuSettings,
      status: "not_connected" as const,
      lastSyncedAt: undefined,
      lastError: undefined
    };
    setFeishuSettings(next);
    saveFeishuSettings(next);
    showToast("Feishu disconnected locally");
  }

  return (
    <div className={`app-shell tab-${tab}`}>
      <aside className="focus-sidebar">
        <div className="sidebar-brand">
          <ResolveMark />
          <div>
            <strong>Resolve</strong>
            <span>Tasks, calendar, strategy</span>
          </div>
        </div>

        <div className="sidebar-scroll">
          <CaptureBox value={captureText} onChange={setCaptureText} onSave={handleCapture} disabled={tab !== "todo"} />
          <SidebarNav
            active={tab}
            todoCount={todoItems.length}
            calendarCount={state.calendarEvents.length}
            strategyCount={state.strategyThreads.length}
            onChange={setTab}
          />
          <SyncPanel feishuSettings={feishuSettings} backendSettings={backendSettings} />
        </div>
      </aside>

      <main className="app-main">
        <TopBar
          tab={tab}
          feishuSettings={feishuSettings}
          backendSettings={backendSettings}
          syncing={manualSyncing || syncInFlightRef.current}
          onSync={() => void manualSyncNow()}
        />

        <section className="workspace">
          {tab === "todo" && (
            <TodoView
              todos={todoItems}
              completedTodos={completedTodos}
              archivedTodos={archivedTodos}
              threads={strategyThreads}
              calendarEvents={state.calendarEvents}
              selectedTodo={selectedTodo}
              selectedTodoSubtasks={selectedTodoSubtasks}
              showCompleted={showCompleted}
              showArchived={showArchived}
              onOpenCalendar={openCalendarDraft}
              onAttachStrategy={attachTodoToStrategy}
              onComplete={(todo) => {
                updateTodo(todo.meta.id, { status: "done" });
                if (selectedTodoId === todo.meta.id) setSelectedTodoId(null);
              }}
              onArchive={(todo) => {
                archiveTodoWithDescendants(todo);
                if (selectedTodoId === todo.meta.id) setSelectedTodoId(null);
              }}
              onRestore={restoreTodo}
              onSelectTodo={(todo) => setSelectedTodoId(todo.meta.id)}
              onCloseDetail={() => setSelectedTodoId(null)}
              onUpdateTodoMeta={updateTodo}
              onUpdateTodoPayload={updateTodoPayload}
              onSaveTodoDetail={updateTodoDetail}
              onAddSubtask={addTodoSubtask}
              onToggleSubtask={(todo) => updateTodo(todo.meta.id, { status: todo.meta.status === "done" ? "active" : "done" })}
              onReorderTodo={reorderTodo}
              onReorderStrategyThread={reorderStrategyThread}
              onToggleCompleted={() => setShowCompleted((value) => !value)}
              onToggleArchived={() => setShowArchived((value) => !value)}
              onDeleteArchived={(todo) => deleteTodosPermanently([todo.meta.id])}
              onClearArchived={clearArchivedTodos}
            />
          )}
          {tab === "calendar" && (
            <CalendarView
              events={state.calendarEvents}
              viewMode={calendarViewMode}
              monthCursor={monthCursor}
              selectedDate={selectedDate}
              selectedEventKey={selectedCalendarEventKey}
              draft={calendarDraft}
              onViewMode={setCalendarViewMode}
              onMonthCursor={setMonthCursor}
              onSelectedDate={(date) => {
                setSelectedDate(date);
                setSelectedCalendarEventKey(null);
              }}
              onSelectedEventKey={setSelectedCalendarEventKey}
              onOpenDraft={(date) => {
                setSelectedCalendarEventKey(null);
                openCalendarDraft(undefined, date);
              }}
              onDraft={patchCalendarDraft}
              onSaveDraft={saveCalendarDraft}
              onUpdateEvent={(event, draft) => void updateCalendarEvent(event, draft)}
              onDeleteEvent={(event) => void deleteCalendarEvent(event)}
            />
          )}
          {tab === "strategy" && (
            <StrategyView
              threads={strategyThreads}
              selectedThreadId={selectedThreadId}
              selectedThread={selectedThread}
              strategyTodos={strategyTodos}
              strategyCompletedTodos={strategyCompletedTodos}
              strategySignals={strategySignals}
              taskText={strategyTaskText}
              strategyDraft={strategyDraft}
              onSelectThread={setSelectedThreadId}
              onTaskText={setStrategyTaskText}
              onStrategyDraft={setStrategyDraft}
              onAddThread={addStrategyThread}
              onAddTask={addStrategyTask}
              selectedTodo={selectedStrategyTodo}
              selectedTodoSubtasks={selectedStrategyTodoSubtasks}
              calendarEvents={state.calendarEvents}
              onSelectTodo={(todo) => setSelectedStrategyTodoId(todo.meta.id)}
              onCloseDetail={() => setSelectedStrategyTodoId(null)}
              onOpenCalendar={openCalendarDraft}
              onComplete={(todo) => {
                updateTodo(todo.meta.id, { status: "done" });
                if (selectedStrategyTodoId === todo.meta.id) setSelectedStrategyTodoId(null);
              }}
              onArchive={(todo) => {
                archiveTodoWithDescendants(todo);
                if (selectedStrategyTodoId === todo.meta.id) setSelectedStrategyTodoId(null);
              }}
              onArchiveThread={archiveStrategyWithTasks}
              onUpdateTodoMeta={updateTodo}
              onUpdateTodoPayload={updateTodoPayload}
              onSaveTodoDetail={updateTodoDetail}
              onAddSubtask={addTodoSubtask}
              onToggleSubtask={(todo) => updateTodo(todo.meta.id, { status: todo.meta.status === "done" ? "active" : "done" })}
            />
          )}
          {tab === "settings" && (
            <SettingsView
              settings={feishuSettings}
              backendSettings={backendSettings}
              syncSecretReady={syncSecretReady}
              connecting={feishuConnecting}
              onBackendChange={handleSaveBackend}
              onBackendSignIn={handleBackendSignIn}
              onBackendSignOut={() => {
                clearBackendSession();
                void clearSecureSyncSecret();
                setSyncSecretReady(false);
                handleSaveBackend({
                  ...backendSettingsRef.current,
                  status: "signed_out",
                  feishuConnected: false,
                  lastError: undefined
                });
                showToast("Signed out");
              }}
              onConnect={handleConnectFeishu}
            />
          )}
        </section>
      </main>
      {tab === "todo" && <MobileCaptureBar value={captureText} onChange={setCaptureText} onSave={handleCapture} />}
      <QuickCaptureOverlay
        open={quickCaptureOpen}
        value={captureText}
        onChange={setCaptureText}
        onSave={handleCapture}
        onClose={() => setQuickCaptureOpen(false)}
      />
      {toast && <Toast message={toast} />}
    </div>
  );
}

function TopBar({
  tab,
  feishuSettings,
  backendSettings,
  syncing,
  onSync
}: {
  tab: Tab;
  feishuSettings: FeishuSettingsState;
  backendSettings: BackendSettingsState;
  syncing: boolean;
  onSync: () => void;
}) {
  const title = {
    todo: "Todo",
    calendar: "Calendar",
    strategy: "Strategy",
    settings: "Settings"
  }[tab];

  return (
    <header className="top-bar">
      <div>
        <div className="eyebrow">Resolve</div>
        <h1>{title}</h1>
      </div>
      <div className="top-actions">
        <SyncStatusBadge feishuSettings={feishuSettings} backendSettings={backendSettings} />
        <button className="secondary-button top-sync-button" onClick={onSync} disabled={syncing}>
          <RefreshCw size={14} />
          {syncing ? "Syncing" : "Sync"}
        </button>
      </div>
    </header>
  );
}

function ResolveMark() {
  return (
    <div className="brand-mark" aria-hidden="true">
      <svg viewBox="0 0 32 32" role="img">
        <circle cx="16" cy="16" r="14" fill="#2F66DD" />
        <path
          d="M9.4 16.3l4.4 4.3 8.8-9.5"
          fill="none"
          stroke="#fff"
          strokeLinecap="round"
          strokeLinejoin="round"
          strokeWidth="3.25"
        />
      </svg>
    </div>
  );
}

function CaptureBox({
  value,
  onChange,
  onSave,
  disabled = false
}: {
  value: string;
  onChange: (value: string) => void;
  onSave: () => void;
  disabled?: boolean;
}) {
  return (
    <section className={`capture-box ${disabled ? "disabled" : ""}`}>
      <div className="capture-title">
        <Search size={18} />
        <span>快速记录</span>
      </div>
      <textarea
        data-command-input
        value={value}
        disabled={disabled}
        onChange={(event) => onChange(event.target.value)}
        onKeyDown={(event) => {
          if (disabled) return;
          if (isImeComposing(event)) return;
          if (event.key === "Enter" && !event.shiftKey) {
            event.preventDefault();
            onSave();
          }
          if (event.key === "Escape") onChange("");
        }}
        placeholder="记一下..."
      />
      <div className="capture-actions">
        <button className="primary-button" onClick={onSave} disabled={disabled}>
          <Send size={16} />
          Save
        </button>
      </div>
    </section>
  );
}

function MobileCaptureBar({
  value,
  onChange,
  onSave
}: {
  value: string;
  onChange: (value: string) => void;
  onSave: () => void;
}) {
  return (
    <form
      className="mobile-capture-bar"
      onSubmit={(event) => {
        event.preventDefault();
        onSave();
      }}
    >
      <Search size={17} />
      <textarea
        data-command-input
        value={value}
        rows={1}
        onChange={(event) => onChange(event.target.value)}
        onKeyDown={(event) => {
          if (isImeComposing(event)) return;
          if (event.key === "Enter" && !event.shiftKey) {
            event.preventDefault();
            onSave();
          }
          if (event.key === "Escape") onChange("");
        }}
        placeholder="记一下..."
      />
      <button className="primary-button" aria-label="Save capture" type="submit">
        <Send size={16} />
      </button>
    </form>
  );
}

function QuickCaptureOverlay({
  open,
  value,
  onChange,
  onSave,
  onClose
}: {
  open: boolean;
  value: string;
  onChange: (value: string) => void;
  onSave: () => void;
  onClose: () => void;
}) {
  const inputRef = useRef<HTMLTextAreaElement | null>(null);

  useEffect(() => {
    if (!open) return;
    window.requestAnimationFrame(() => inputRef.current?.focus());
  }, [open]);

  if (!open) return null;

  return (
    <div className="quick-capture-backdrop" onClick={onClose}>
      <section
        className="quick-capture"
        role="dialog"
        aria-modal="true"
        aria-label="Quick capture"
        onClick={(event) => event.stopPropagation()}
      >
        <Search className="quick-capture-icon" size={20} />
        <textarea
          ref={inputRef}
          value={value}
          onChange={(event) => onChange(event.target.value)}
          onKeyDown={(event) => {
            if (isImeComposing(event)) return;
            if (event.key === "Enter" && !event.shiftKey) {
              event.preventDefault();
              onSave();
            }
            if (event.key === "Escape") onClose();
          }}
          placeholder="记一下..."
        />
        <div className="quick-capture-actions">
          <button className="icon-button" onClick={onClose} aria-label="Close quick capture">
            <X size={16} />
          </button>
          <button className="primary-button" onClick={onSave} aria-label="Save quick capture">
            <Send size={16} />
          </button>
        </div>
      </section>
    </div>
  );
}

function SyncPanel({
  feishuSettings,
  backendSettings
}: {
  feishuSettings: FeishuSettingsState;
  backendSettings: BackendSettingsState;
}) {
  const summary = calendarConnectionSummary(feishuSettings, backendSettings);
  const accountStatus = summary.signedIn ? "Signed in" : "Signed out";

  return (
    <section className="sync-panel">
      <div className="panel-head">
        <Lock size={16} />
        <span>Status</span>
      </div>
      <div className="sync-lines">
        <span>Calendar: {summary.panelLabel}</span>
        <span>Account: {accountStatus}</span>
        {summary.error && <span className="error-line">{summary.error}</span>}
      </div>
    </section>
  );
}

interface TodoTreeEntry {
  todo: DecryptedItem;
  depth: number;
  childCount: number;
}

function buildTodoTreeEntries(roots: DecryptedItem[], pool: DecryptedItem[], collapsedIds: Set<string>) {
  const childrenByParent = new Map<string, DecryptedItem[]>();
  pool.forEach((todo) => {
    const parentId = todo.meta.parentItemId;
    if (!parentId) return;
    childrenByParent.set(parentId, [...(childrenByParent.get(parentId) ?? []), todo]);
  });
  childrenByParent.forEach((children, parentId) => {
    childrenByParent.set(
      parentId,
      [...children].sort((a, b) => {
        if (a.meta.status !== b.meta.status) return a.meta.status === "done" ? 1 : -1;
        return compareTodoItems(a, b);
      })
    );
  });

  const entries: TodoTreeEntry[] = [];
  const visit = (todo: DecryptedItem, depth: number) => {
    const children = childrenByParent.get(todo.meta.id) ?? [];
    entries.push({ todo, depth, childCount: children.length });
    if (collapsedIds.has(todo.meta.id)) return;
    children.forEach((child) => visit(child, depth + 1));
  };
  roots.forEach((root) => visit(root, 0));
  return entries;
}

function todoIdsWithChildren(pool: DecryptedItem[]) {
  const ids = new Set<string>();
  pool.forEach((todo) => {
    const parentId = todo.meta.parentItemId;
    if (parentId) ids.add(parentId);
  });
  return ids;
}

function directTodoSubtasks(pool: DecryptedItem[], parentId?: string) {
  if (!parentId) return [];
  return pool
    .filter((todo) => todo.meta.parentItemId === parentId && todo.meta.status !== "archived" && todo.meta.status !== "deleted")
    .sort((a, b) => {
      if (a.meta.status !== b.meta.status) return a.meta.status === "done" ? 1 : -1;
      return compareTodoItems(a, b);
    });
}

function TodoView({
  todos,
  completedTodos,
  archivedTodos,
  threads,
  calendarEvents,
  selectedTodo,
  selectedTodoSubtasks,
  showCompleted,
  showArchived,
  onOpenCalendar,
  onAttachStrategy,
  onComplete,
  onArchive,
  onRestore,
  onSelectTodo,
  onCloseDetail,
  onUpdateTodoMeta,
  onUpdateTodoPayload,
  onSaveTodoDetail,
  onAddSubtask,
  onToggleSubtask,
  onReorderTodo,
  onReorderStrategyThread,
  onToggleCompleted,
  onToggleArchived,
  onDeleteArchived,
  onClearArchived
}: {
  todos: DecryptedItem[];
  completedTodos: DecryptedItem[];
  archivedTodos: DecryptedItem[];
  threads: DecryptedStrategyThread[];
  calendarEvents: DecryptedCalendarEvent[];
  selectedTodo?: DecryptedItem;
  selectedTodoSubtasks: DecryptedItem[];
  showCompleted: boolean;
  showArchived: boolean;
  onOpenCalendar: (todo: DecryptedItem) => void;
  onAttachStrategy: (todoId: string, threadId: string) => void;
  onComplete: (todo: DecryptedItem) => void;
  onArchive: (todo: DecryptedItem) => void;
  onRestore: (todoId: string) => void;
  onSelectTodo: (todo: DecryptedItem) => void;
  onCloseDetail: () => void;
  onUpdateTodoMeta: (itemId: string, patch: Partial<DecryptedItem["meta"]>) => void;
  onUpdateTodoPayload: (itemId: string, patch: Partial<ItemPayload>) => void;
  onSaveTodoDetail: (
    itemId: string,
    metaPatch: Partial<DecryptedItem["meta"]>,
    payloadPatch: Partial<ItemPayload>
  ) => void;
  onAddSubtask: (parentTodo: DecryptedItem, title: string) => void;
  onToggleSubtask: (todo: DecryptedItem) => void;
  onReorderTodo: (sourceId: string, targetId: string, placement?: ReorderPlacement) => void;
  onReorderStrategyThread: (sourceId: string, targetId: string, placement?: ReorderPlacement) => void;
  onToggleCompleted: () => void;
  onToggleArchived: () => void;
  onDeleteArchived: (todo: DecryptedItem) => void;
  onClearArchived: () => void;
}) {
  const [collapsedTodoIds, setCollapsedTodoIds] = useState<Set<string>>(() => new Set());
  const [collapsedStrategyIds, setCollapsedStrategyIds] = useState<Set<string>>(() => new Set());
  const [pointerDrag, setPointerDrag] = useState<{
    kind: "todo" | "strategy";
    sourceId: string;
    targetId?: string;
    placement?: ReorderPlacement;
  } | null>(null);
  const suppressNextClickRef = useRef(false);
  const calendarByTodo = new Map(
    calendarEvents
      .filter((event) => event.meta.sourceItemId)
      .map((event) => [event.meta.sourceItemId, event] as const)
  );
  const activeIds = new Set(todos.map((todo) => todo.meta.id));
  const rootTodos = todos.filter((todo) => !todo.meta.parentItemId || !activeIds.has(todo.meta.parentItemId));
  const rootsByStrategy = new Map<string, DecryptedItem[]>();
  rootTodos.forEach((todo) => {
    const key = todo.meta.strategyThreadId ?? "";
    rootsByStrategy.set(key, [...(rootsByStrategy.get(key) ?? []), todo]);
  });
  const ungroupedRoots = rootsByStrategy.get("") ?? [];
  const strategyGroups = threads
    .map((thread) => ({ thread, roots: rootsByStrategy.get(thread.meta.id) ?? [] }))
    .filter((group) => group.roots.length > 0);
  const orphanGroups = Array.from(rootsByStrategy.entries())
    .filter(([threadId]) => threadId && !threads.some((thread) => thread.meta.id === threadId))
    .map(([threadId, roots]) => ({ threadId, roots }));
  const toggleCollapse = (todoId: string) => {
    setCollapsedTodoIds((current) => {
      const next = new Set(current);
      if (next.has(todoId)) next.delete(todoId);
      else next.add(todoId);
      return next;
    });
  };
  const toggleStrategyGroup = (threadId: string) => {
    setCollapsedStrategyIds((current) => {
      const next = new Set(current);
      if (next.has(threadId)) next.delete(threadId);
      else next.add(threadId);
      return next;
    });
  };

  const beginPointerReorder = (
    kind: "todo" | "strategy",
    sourceId: string,
    event: ReactPointerEvent<HTMLElement>
  ) => {
    if (event.button !== 0 || (kind === "todo" && isInteractiveDragTarget(event.target))) return;
    const startX = event.clientX;
    const startY = event.clientY;
    let active = false;
    let latestTargetId: string | undefined;
    let latestPlacement: ReorderPlacement | undefined;
    const selector = kind === "todo" ? "[data-todo-id]" : "[data-strategy-id]";
    const datasetKey = kind === "todo" ? "todoId" : "strategyId";

    const updateTarget = (clientX: number, clientY: number) => {
      const targetElement = document.elementFromPoint(clientX, clientY)?.closest(selector) as HTMLElement | null;
      const targetId = targetElement?.dataset[datasetKey];
      latestTargetId = targetId && targetId !== sourceId ? targetId : undefined;
      latestPlacement = latestTargetId && targetElement ? reorderPlacementFromPoint(targetElement, clientY) : undefined;
      setPointerDrag({ kind, sourceId, targetId: latestTargetId, placement: latestPlacement });
    };

    const handlePointerMove = (moveEvent: PointerEvent) => {
      const distance = Math.hypot(moveEvent.clientX - startX, moveEvent.clientY - startY);
      if (!active && distance < 7) return;
      active = true;
      moveEvent.preventDefault();
      updateTarget(moveEvent.clientX, moveEvent.clientY);
    };

    const finish = (upEvent: PointerEvent) => {
      window.removeEventListener("pointermove", handlePointerMove);
      window.removeEventListener("pointerup", finish);
      window.removeEventListener("pointercancel", cancel);
      if (active) {
        suppressNextClickRef.current = true;
        updateTarget(upEvent.clientX, upEvent.clientY);
        if (latestTargetId) {
          if (kind === "todo") onReorderTodo(sourceId, latestTargetId, latestPlacement);
          else onReorderStrategyThread(sourceId, latestTargetId, latestPlacement);
        }
        window.setTimeout(() => {
          suppressNextClickRef.current = false;
        }, 0);
      }
      setPointerDrag(null);
    };

    const cancel = () => {
      window.removeEventListener("pointermove", handlePointerMove);
      window.removeEventListener("pointerup", finish);
      window.removeEventListener("pointercancel", cancel);
      setPointerDrag(null);
    };

    window.addEventListener("pointermove", handlePointerMove, { passive: false });
    window.addEventListener("pointerup", finish);
    window.addEventListener("pointercancel", cancel);
  };

  const renderEntry = (entry: TodoTreeEntry) => (
    <TodoCard
      key={entry.todo.meta.id}
      todo={entry.todo}
      threads={threads}
      calendarEvent={calendarByTodo.get(entry.todo.meta.id)}
      selected={selectedTodo?.meta.id === entry.todo.meta.id}
      dragging={pointerDrag?.kind === "todo" && pointerDrag.sourceId === entry.todo.meta.id}
      dragOver={pointerDrag?.kind === "todo" && pointerDrag.targetId === entry.todo.meta.id}
      dragPlacement={pointerDrag?.kind === "todo" && pointerDrag.targetId === entry.todo.meta.id ? pointerDrag.placement : undefined}
      depth={entry.depth}
      childCount={entry.childCount}
      collapsed={collapsedTodoIds.has(entry.todo.meta.id)}
      onToggleCollapse={toggleCollapse}
      onPointerReorderStart={(event) => beginPointerReorder("todo", entry.todo.meta.id, event)}
      onComplete={onComplete}
      onSelectTodo={(todo) => {
        if (!suppressNextClickRef.current) onSelectTodo(todo);
      }}
    />
  );

  return (
    <div className={`todo-workspace ${selectedTodo ? "has-detail" : ""}`}>
      <div className="todo-list-pane">
        <div className="section-title-row">
          <div>
            <h2>Active</h2>
            <p>主动推进、等待回应、战略子任务都在这里。</p>
          </div>
          <div className="section-title-actions">
            <StatusPill tone="success" label={`${todos.length} active`} />
          </div>
        </div>
        {todos.length ? (
          <>
            {ungroupedRoots.map((root) => buildTodoTreeEntries([root], todos, collapsedTodoIds).map(renderEntry))}
            {strategyGroups.map(({ thread, roots }) => (
              <div
                className={`todo-strategy-group ${pointerDrag?.kind === "strategy" && pointerDrag.sourceId === thread.meta.id ? "dragging" : ""} ${
                  pointerDrag?.kind === "strategy" && pointerDrag.targetId === thread.meta.id ? "drag-over" : ""
                } ${
                  pointerDrag?.kind === "strategy" && pointerDrag.targetId === thread.meta.id && pointerDrag.placement
                    ? `drop-${pointerDrag.placement}`
                    : ""
                }`}
                key={thread.meta.id}
                data-strategy-id={thread.meta.id}
              >
                <button
                  className={`todo-strategy-group-head ${collapsedStrategyIds.has(thread.meta.id) ? "collapsed" : ""}`}
                  onPointerDown={(event) => beginPointerReorder("strategy", thread.meta.id, event)}
                  onClick={() => {
                    if (!suppressNextClickRef.current) toggleStrategyGroup(thread.meta.id);
                  }}
                  aria-label={collapsedStrategyIds.has(thread.meta.id) ? `Expand ${thread.payload.title}` : `Collapse ${thread.payload.title}`}
                >
                  <ChevronRight size={13} />
                  <Brain size={14} />
                  <span>{thread.payload.title}</span>
                  <small>{roots.length}</small>
                </button>
                {!collapsedStrategyIds.has(thread.meta.id) &&
                  buildTodoTreeEntries(roots, todos, collapsedTodoIds).map(renderEntry)}
              </div>
            ))}
            {orphanGroups.map(({ threadId, roots }) => (
              <div className="todo-strategy-group" key={threadId}>
                <button
                  className={`todo-strategy-group-head ${collapsedStrategyIds.has(threadId) ? "collapsed" : ""}`}
                  onClick={() => toggleStrategyGroup(threadId)}
                  aria-label={collapsedStrategyIds.has(threadId) ? "Expand Strategy" : "Collapse Strategy"}
                >
                  <ChevronRight size={13} />
                  <Brain size={14} />
                  <span>Strategy</span>
                  <small>{roots.length}</small>
                </button>
                {!collapsedStrategyIds.has(threadId) &&
                  buildTodoTreeEntries(roots, todos, collapsedTodoIds).map(renderEntry)}
              </div>
            ))}
          </>
        ) : (
          <EmptyState label="Todo 是空的" />
        )}

        <TodoArchiveSection
          kind="completed"
          title="Completed"
          items={completedTodos}
          open={showCompleted}
          emptyLabel="还没有完成项"
          onToggle={onToggleCompleted}
          onRestore={onRestore}
        />
        <TodoArchiveSection
          kind="archived"
          title="Archived"
          items={archivedTodos}
          open={showArchived}
          emptyLabel="还没有归档项"
          onToggle={onToggleArchived}
          onRestore={onRestore}
          onDelete={onDeleteArchived}
          onClear={onClearArchived}
        />
      </div>
      {selectedTodo && (
        <TodoDetailPanel
          todo={selectedTodo}
          threads={threads}
          subtasks={selectedTodoSubtasks}
          calendarEvent={calendarByTodo.get(selectedTodo.meta.id)}
          onClose={onCloseDetail}
          onOpenCalendar={onOpenCalendar}
          onSave={onSaveTodoDetail}
          onUpdatePayload={onUpdateTodoPayload}
          onAddSubtask={onAddSubtask}
          onToggleSubtask={onToggleSubtask}
          onArchive={onArchive}
        />
      )}
    </div>
  );
}

function TodoCard({
  todo,
  threads,
  calendarEvent,
  selected,
  dragging,
  dragOver,
  dragPlacement,
  depth = 0,
  childCount = 0,
  collapsed = false,
  onToggleCollapse,
  onPointerReorderStart,
  onComplete,
  onSelectTodo
}: {
  todo: DecryptedItem;
  threads: DecryptedStrategyThread[];
  calendarEvent?: DecryptedCalendarEvent;
  selected?: boolean;
  dragging?: boolean;
  dragOver?: boolean;
  dragPlacement?: ReorderPlacement;
  depth?: number;
  childCount?: number;
  collapsed?: boolean;
  onToggleCollapse?: (todoId: string) => void;
  onPointerReorderStart?: (event: ReactPointerEvent<HTMLElement>) => void;
  onComplete: (todo: DecryptedItem) => void;
  onSelectTodo: (todo: DecryptedItem) => void;
}) {
  const payload = todo.payload as ItemPayload;
  const thread = threads.find((item) => item.meta.id === todo.meta.strategyThreadId);

  return (
    <article
      className={`item-card todo-card ${selected ? "selected" : ""} ${depth > 0 ? "child" : ""} ${dragging ? "dragging" : ""} ${
        dragOver ? "drag-over" : ""
      } ${dragOver && dragPlacement ? `drop-${dragPlacement}` : ""}`}
      style={{ "--todo-depth": depth } as CSSProperties}
      role="button"
      tabIndex={0}
      data-todo-id={todo.meta.id}
      onPointerDown={onPointerReorderStart}
      onClick={() => onSelectTodo(todo)}
      onKeyDown={(event) => {
        if (event.target !== event.currentTarget) return;
        if (event.key === "Enter" || event.key === " ") {
          event.preventDefault();
          onSelectTodo(todo);
        }
      }}
    >
      <button
        className="completion-circle"
        aria-label={`Complete ${payload.title}`}
        onClick={(event) => {
          event.stopPropagation();
          onComplete(todo);
        }}
      >
        <Check size={13} />
      </button>
      <div className="item-main">
        <div className="todo-title-row">
          <p>{payload.title}</p>
        </div>
        {childCount > 0 && (
          <button
            className={`subtask-toggle-chip ${collapsed ? "collapsed" : ""}`}
            aria-label={collapsed ? `Show ${childCount} subtasks` : `Hide ${childCount} subtasks`}
            onClick={(event) => {
              event.stopPropagation();
              onToggleCollapse?.(todo.meta.id);
            }}
          >
            <ChevronRight size={12} />
            {collapsed ? "Show" : "Hide"} {childCount} subtasks
          </button>
        )}
        {todo.meta.dueAt && (
          <div className="todo-date-pill">
            <CalendarDays size={14} />
            {todoDateLabel(todo.meta.dueAt)}
          </div>
        )}
        <div className="meta-row">
          <span>{relativeAgeLabel(todo.meta.createdAt)}</span>
          {calendarEvent && <span>Feishu: {calendarEvent.meta.status}</span>}
          {thread && <span>Strategy: {thread.payload.title}</span>}
          {payload.attachments?.length ? <span>{payload.attachments.length} attachments</span> : null}
        </div>
      </div>
    </article>
  );
}

function TodoArchiveSection({
  kind,
  title,
  items,
  open,
  emptyLabel,
  onToggle,
  onRestore,
  onDelete,
  onClear
}: {
  kind: "completed" | "archived";
  title: string;
  items: DecryptedItem[];
  open: boolean;
  emptyLabel: string;
  onToggle: () => void;
  onRestore: (todoId: string) => void;
  onDelete?: (todo: DecryptedItem) => void;
  onClear?: () => void;
}) {
  const isCompleted = kind === "completed";

  return (
    <section className={`collapsed-list ${kind}`}>
      <button className={`collapsed-list-toggle ${open ? "open" : ""}`} onClick={onToggle}>
        <span className="collapse-title">
          <ChevronRight className="collapse-chevron" size={14} />
          {title}
        </span>
        <small>{items.length}</small>
      </button>
      {open && (
        <div className="collapsed-list-body">
          {!isCompleted && items.length > 0 && onClear && (
            <div className="archive-tools">
              <button className="ghost-button danger-text" onClick={onClear}>
                <Trash2 size={13} />
                Clear archive
              </button>
            </div>
          )}
          {items.length ? (
            items.map((item) => {
              const payload = item.payload as ItemPayload;
              return (
                <article className={`mini-row ${kind}`} key={item.meta.id}>
                  {isCompleted ? (
                    <button
                      className="completion-circle completed"
                      aria-label={`Mark active ${payload.title}`}
                      title="Mark active"
                      onClick={() => onRestore(item.meta.id)}
                    >
                      <Check size={13} />
                    </button>
                  ) : (
                    <span className="archive-glyph" aria-hidden="true">
                      <Archive size={13} />
                    </span>
                  )}
                  <div className="mini-row-main">
                    <span className="mini-row-title">{payload.title}</span>
                    {item.meta.dueAt && <small>{todoDateLabel(item.meta.dueAt)}</small>}
                  </div>
                  {!isCompleted && (
                    <div className="mini-row-actions">
                      <button
                        className="archive-restore-button"
                        aria-label={`Restore ${payload.title}`}
                        title="Restore"
                        onClick={() => onRestore(item.meta.id)}
                      >
                        <RefreshCw size={13} />
                      </button>
                      <button
                        className="archive-restore-button danger-text"
                        aria-label={`Delete ${payload.title}`}
                        title="Delete forever"
                        onClick={() => onDelete?.(item)}
                      >
                        <Trash2 size={13} />
                      </button>
                    </div>
                  )}
                </article>
              );
            })
          ) : (
            <EmptyState label={emptyLabel} />
          )}
        </div>
      )}
    </section>
  );
}

function TodoDetailPanel({
  todo,
  threads,
  subtasks,
  calendarEvent,
  onClose,
  onOpenCalendar,
  onSave,
  onUpdatePayload,
  onAddSubtask,
  onToggleSubtask,
  onArchive
}: {
  todo: DecryptedItem;
  threads: DecryptedStrategyThread[];
  subtasks: DecryptedItem[];
  calendarEvent?: DecryptedCalendarEvent;
  onClose: () => void;
  onOpenCalendar: (todo: DecryptedItem) => void;
  onSave: (
    itemId: string,
    metaPatch: Partial<DecryptedItem["meta"]>,
    payloadPatch: Partial<ItemPayload>
  ) => void;
  onUpdatePayload: (itemId: string, patch: Partial<ItemPayload>) => void;
  onAddSubtask: (parentTodo: DecryptedItem, title: string) => void;
  onToggleSubtask: (todo: DecryptedItem) => void;
  onArchive: (todo: DecryptedItem) => void;
}) {
  const activeTodo = todo;
  const payload = activeTodo.payload as ItemPayload;
  const dueDate = activeTodo.meta.dueAt ? formatDateInput(new Date(activeTodo.meta.dueAt)) : "";
  const dueTime = inputTimeValue(activeTodo.meta.dueAt);
  const [draft, setDraft] = useState(() => ({
    title: payload.title,
    notes: payload.notes ?? "",
    dueDate,
    dueTime,
    strategyThreadId: activeTodo.meta.strategyThreadId ?? ""
  }));
  const [subtaskTitle, setSubtaskTitle] = useState("");

  useEffect(() => {
    setDraft({
      title: payload.title,
      notes: payload.notes ?? "",
      dueDate,
      dueTime,
      strategyThreadId: activeTodo.meta.strategyThreadId ?? ""
    });
    setSubtaskTitle("");
  }, [activeTodo.meta.id, activeTodo.meta.dueAt, activeTodo.meta.strategyThreadId, payload.title, payload.notes, dueDate, dueTime]);

  const draftDueAt = draft.dueDate ? new Date(`${draft.dueDate}T${draft.dueTime || "09:00"}:00`).toISOString() : undefined;
  const isDirty =
    draft.title !== payload.title ||
    draft.notes !== (payload.notes ?? "") ||
    draftDueAt !== activeTodo.meta.dueAt ||
    draft.strategyThreadId !== (activeTodo.meta.strategyThreadId ?? "");

  function saveDetail() {
    const title = draft.title.trim();
    if (!title) return;
    onSave(
      activeTodo.meta.id,
      {
        dueAt: draftDueAt,
        strategyThreadId: draft.strategyThreadId || undefined
      },
      {
        title,
        notes: draft.notes.trim() || undefined
      }
    );
  }

  function submitSubtask() {
    const title = subtaskTitle.trim();
    if (!title) return;
    onAddSubtask(activeTodo, title);
    setSubtaskTitle("");
  }

  return (
    <aside className="todo-detail-panel">
      <div className="detail-head">
        <div>
          <span>Todo Detail</span>
          {isDirty && <small>Unsaved changes</small>}
        </div>
        <button className="icon-button" onClick={onClose} aria-label="Close detail">
          <X size={16} />
        </button>
      </div>
      <label className="detail-field">
        <span>Title</span>
        <input
          value={draft.title}
          onChange={(event) => setDraft((current) => ({ ...current, title: event.target.value }))}
        />
      </label>
      <label className="detail-field">
        <span>Comment</span>
        <textarea
          value={draft.notes}
          onChange={(event) => setDraft((current) => ({ ...current, notes: event.target.value }))}
          placeholder="补充上下文、下一步、判断依据..."
        />
      </label>
      <div className="detail-field">
        <span>Date</span>
        <div className="detail-date-row">
          <input
            type="date"
            value={draft.dueDate}
            onChange={(event) => setDraft((current) => ({ ...current, dueDate: event.target.value }))}
          />
          <input
            type="time"
            value={draft.dueTime}
            onChange={(event) =>
              setDraft((current) => ({
                ...current,
                dueDate: current.dueDate || formatDateInput(new Date()),
                dueTime: event.target.value
              }))
            }
          />
        </div>
        {calendarEvent && <small>Linked Feishu: {calendarEvent.meta.status}</small>}
      </div>
      <label className="detail-field">
        <span>Strategy</span>
        <select
          value={draft.strategyThreadId}
          onChange={(event) => setDraft((current) => ({ ...current, strategyThreadId: event.target.value }))}
        >
          <option value="">No strategy</option>
          {threads.map((thread) => (
            <option key={thread.meta.id} value={thread.meta.id}>
              {thread.payload.title}
            </option>
          ))}
        </select>
      </label>
      <div className="detail-field detail-subtasks-field">
        <div className="detail-section-label">
          <span>Subtasks</span>
          <small>{subtasks.length}</small>
        </div>
        <div className="detail-subtask-composer">
          <input
            value={subtaskTitle}
            onChange={(event) => setSubtaskTitle(event.target.value)}
            onKeyDown={(event) => {
              if (isImeComposing(event)) return;
              if (event.key === "Enter") {
                event.preventDefault();
                submitSubtask();
              }
            }}
            placeholder="Add subtask..."
          />
          <button className="secondary-button" onClick={submitSubtask} disabled={!subtaskTitle.trim()}>
            <Plus size={14} />
            Add
          </button>
        </div>
        {subtasks.length ? (
          <div className="detail-subtask-list">
            {subtasks.map((subtask) => {
              const subtaskPayload = subtask.payload as ItemPayload;
              const done = subtask.meta.status === "done";
              return (
                <button
                  className={`detail-subtask-row ${done ? "done" : ""}`}
                  key={subtask.meta.id}
                  onClick={() => onToggleSubtask(subtask)}
                >
                  <span className="mini-completion-circle">
                    {done && <Check size={11} />}
                  </span>
                  <span>{subtaskPayload.title}</span>
                </button>
              );
            })}
          </div>
        ) : (
          <small>No subtasks yet</small>
        )}
      </div>
      <div className="detail-field">
        <span>Files</span>
        <label className="file-drop">
          <Paperclip size={16} />
          <input
            type="file"
            multiple
            onChange={(event) => {
              const next = Array.from(event.target.files ?? []).map((file) => ({
                id: makeId("file"),
                name: file.name,
                size: file.size,
                addedAt: nowIso()
              }));
              onUpdatePayload(activeTodo.meta.id, {
                attachments: [...(payload.attachments ?? []), ...next]
              });
              event.target.value = "";
            }}
          />
          Attach local files
        </label>
        {payload.attachments?.length ? (
          <div className="attachment-list">
            {payload.attachments.map((file) => (
              <span key={file.id}>{file.name}</span>
            ))}
          </div>
        ) : (
          <small>No attachments yet</small>
        )}
      </div>
      <div className="detail-actions">
        <button className="primary-button" onClick={saveDetail} disabled={!isDirty || !draft.title.trim()}>
          <Check size={15} />
          Save
        </button>
        <button className="secondary-button" onClick={() => onOpenCalendar(activeTodo)}>
          <CalendarDays size={15} />
          Put on calendar
        </button>
        <button className="ghost-button" onClick={() => onArchive(activeTodo)}>
          <Archive size={15} />
          Archive
        </button>
      </div>
    </aside>
  );
}

function CalendarView({
  events,
  viewMode,
  monthCursor,
  selectedDate,
  selectedEventKey,
  draft,
  onViewMode,
  onMonthCursor,
  onSelectedDate,
  onSelectedEventKey,
  onOpenDraft,
  onDraft,
  onSaveDraft,
  onUpdateEvent,
  onDeleteEvent
}: {
  events: DecryptedCalendarEvent[];
  viewMode: CalendarViewMode;
  monthCursor: Date;
  selectedDate: string;
  selectedEventKey: string | null;
  draft: CalendarDraft | null;
  onViewMode: (mode: CalendarViewMode) => void;
  onMonthCursor: (date: Date) => void;
  onSelectedDate: (date: string) => void;
  onSelectedEventKey: (key: string | null) => void;
  onOpenDraft: (date: string) => void;
  onDraft: (patch: Partial<CalendarDraft> | null) => void;
  onSaveDraft: () => void;
  onUpdateEvent: (event: DecryptedCalendarEvent, draft: CalendarEventEditDraft) => void;
  onDeleteEvent: (event: DecryptedCalendarEvent) => void;
}) {
  const todayKey = formatDateInput(new Date());
  const calendarGridRef = useRef<HTMLDivElement | null>(null);
  const [visibleEventsPerCell, setVisibleEventsPerCell] = useState(3);
  const [expandedDayKey, setExpandedDayKey] = useState<string | null>(null);
  const days =
    viewMode === "week"
      ? weekDays(selectedDate)
      : monthDays(monthCursor);
  const rangeStart = viewMode === "year" ? new Date(monthCursor.getFullYear(), 0, 1) : days[0];
  const rangeEnd =
    viewMode === "year"
      ? new Date(monthCursor.getFullYear() + 1, 0, 1)
      : new Date(days[days.length - 1].getFullYear(), days[days.length - 1].getMonth(), days[days.length - 1].getDate() + 1);
  const displayEvents = dedupeDisplayCalendarEvents(
    expandRecurringCalendarEvents(events.filter(visibleCalendarEvent), rangeStart, rangeEnd)
  );
  const eventsByDate = new Map<string, DecryptedCalendarEvent[]>();
  displayEvents.forEach((event) => {
    const key = dateKey(event.meta.startsAt);
    const list = eventsByDate.get(key) ?? [];
    list.push(event);
    eventsByDate.set(key, list);
  });
  eventsByDate.forEach((list, key) => {
    eventsByDate.set(key, [...list].sort(compareCalendarEvents));
  });

  const selectedEvent = displayEvents.find((event) => displayCalendarEventKey(event) === selectedEventKey);
  const expandedDayEvents = expandedDayKey ? eventsByDate.get(expandedDayKey) ?? [] : [];
  const monthLabel =
    viewMode === "week"
      ? `${days[0].toLocaleDateString("zh-CN", { month: "numeric", day: "numeric" })} - ${days[6].toLocaleDateString("zh-CN", { month: "numeric", day: "numeric" })}`
      : viewMode === "year"
        ? `${monthCursor.getFullYear()}`
        : monthCursor.toLocaleDateString("zh-CN", { year: "numeric", month: "long" });

	  useEffect(() => {
	    if (viewMode === "year") return;
	    const updateVisibleCount = () => {
	      const grid = calendarGridRef.current;
	      if (!grid) return;
        const gridRect = grid.getBoundingClientRect();
	      const rows = viewMode === "week" ? 1 : 6;
	      const cellHeight = gridRect.height / rows;
        const cellWidth = gridRect.width / 7;
      if (viewMode === "month") {
        const dateArea = 31;
        const verticalPadding = 14;
        const rowHeight = 17;
        const rowGap = 4;
        const available = Math.max(cellHeight - dateArea - verticalPadding, 0);
        const rawSlots = Math.max(0, Math.min(9, Math.floor((available + rowGap) / (rowHeight + rowGap))));
        const monthSlots = cellWidth < 92 || cellHeight < 76 ? 0 : rawSlots;
        setVisibleEventsPerCell((current) => (current === monthSlots ? current : monthSlots));
        return;
      }
      const available = Math.max(cellHeight - 26, 0);
      const next = Math.max(10, Math.floor(available / 16));
	      const capped = Math.min(24, next);
      setVisibleEventsPerCell((current) => (current === capped ? current : capped));
    };

    updateVisibleCount();
    const grid = calendarGridRef.current;
    if (typeof ResizeObserver !== "undefined" && grid) {
      const observer = new ResizeObserver(updateVisibleCount);
      observer.observe(grid);
      return () => observer.disconnect();
    }

    window.addEventListener("resize", updateVisibleCount);
    return () => window.removeEventListener("resize", updateVisibleCount);
  }, [viewMode, monthCursor]);

  function movePeriod(delta: number) {
    const next = new Date(monthCursor);
    if (viewMode === "year") next.setFullYear(monthCursor.getFullYear() + delta);
    else if (viewMode === "week") {
      const selected = new Date(`${selectedDate}T00:00:00`);
      selected.setDate(selected.getDate() + delta * 7);
      onSelectedDate(formatDateInput(selected));
      next.setFullYear(selected.getFullYear(), selected.getMonth(), 1);
    } else next.setMonth(monthCursor.getMonth() + delta);
    onMonthCursor(next);
  }

  function revealCalendarPopoverOnPhone() {
    if (!window.matchMedia("(max-width: 760px)").matches) return;
    window.requestAnimationFrame(() => {
      document.querySelector(".calendar-popover")?.scrollIntoView({ block: "nearest", behavior: "smooth" });
    });
  }

  function openDraftForDate(date: string) {
    setExpandedDayKey(null);
    onSelectedDate(date);
    onSelectedEventKey(null);
    onOpenDraft(date);
    revealCalendarPopoverOnPhone();
  }

  function selectEvent(event: DecryptedCalendarEvent) {
    setExpandedDayKey(null);
    onDraft(null);
    onSelectedDate(dateKey(event.meta.startsAt));
    onSelectedEventKey(displayCalendarEventKey(event));
    revealCalendarPopoverOnPhone();
  }

  function openDayList(date: string) {
    onDraft(null);
    onSelectedDate(date);
    onSelectedEventKey(null);
    setExpandedDayKey(date);
    revealCalendarPopoverOnPhone();
  }

  function closeCalendarPopover() {
    if (draft) onDraft(null);
    onSelectedEventKey(null);
    setExpandedDayKey(null);
  }

  useEffect(() => {
    if (!draft && !selectedEventKey && !expandedDayKey) return;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") closeCalendarPopover();
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [draft, selectedEventKey, expandedDayKey]);

  return (
    <div className="calendar-layout">
      <section className="section-card calendar-month">
        <div className="calendar-toolbar">
          <button className="icon-button" onClick={() => movePeriod(-1)} aria-label="Previous period">
            <ChevronLeft size={18} />
          </button>
          <div className="calendar-title-block">
            <h2>{monthLabel}</h2>
            <span>{viewMode}</span>
          </div>
          <div className="calendar-toolbar-actions">
            <button className="primary-button calendar-new-button" onClick={() => openDraftForDate(selectedDate)} aria-label="New event">
              <Plus size={14} />
              <span>New</span>
            </button>
            <select
              className="calendar-view-select"
              value={viewMode}
              aria-label="Calendar view"
              onChange={(event) => onViewMode(event.target.value as CalendarViewMode)}
            >
              <option value="week">Week</option>
              <option value="month">Month</option>
              <option value="year">Year</option>
            </select>
            <button className="icon-button" onClick={() => movePeriod(1)} aria-label="Next period">
            <ChevronRight size={18} />
            </button>
          </div>
        </div>
        {viewMode === "year" ? (
          <YearCalendar
            year={monthCursor.getFullYear()}
            eventsByDate={eventsByDate}
            onSelectMonth={(month) => {
              const next = new Date(monthCursor);
              next.setMonth(month);
              onMonthCursor(next);
              onViewMode("month");
            }}
          />
        ) : (
          <>
            <div className="weekday-row">
              {["一", "二", "三", "四", "五", "六", "日"].map((day) => (
                <span key={day}>{day}</span>
              ))}
            </div>
            <div className={`calendar-grid ${viewMode === "week" ? "week-grid" : ""}`} ref={calendarGridRef}>
	              {days.map((day) => {
	                const key = formatDateInput(day);
	                const dayEvents = eventsByDate.get(key) ?? [];
	                const eventSlots = visibleEventsPerCell;
	                const compactOverflow = viewMode === "month" && eventSlots === 0 && dayEvents.length > 0;
	                const hasMoreEvents = dayEvents.length > eventSlots;
	                const visibleInlineEvents = compactOverflow ? 0 : hasMoreEvents ? Math.max(0, eventSlots - 1) : eventSlots;
	                const hiddenEventCount = dayEvents.length - visibleInlineEvents;
	                const isCurrentMonth = day.getMonth() === monthCursor.getMonth();
	                const isSelected = key === selectedDate;
	                const isPast = key < todayKey;
                const isToday = key === todayKey;
                return (
                  <div
                    role="button"
                    tabIndex={0}
                    className={`calendar-cell ${isCurrentMonth ? "" : "muted"} ${isPast ? "past" : ""} ${isToday ? "today" : ""} ${isSelected ? "selected" : ""}`}
                    key={key}
                    onClick={() => openDraftForDate(key)}
                    onKeyDown={(event) => {
                      if (event.key === "Enter" || event.key === " ") {
                        event.preventDefault();
                        openDraftForDate(key);
                      }
                    }}
	                  >
	                    <div className="calendar-day-header">
                        <span className="calendar-date-number">
                          {viewMode === "week" ? day.toLocaleDateString("zh-CN", { month: "short", day: "numeric" }) : day.getDate()}
                        </span>
                        {compactOverflow && (
                          <button
                            className="calendar-day-count"
                            aria-label={`还有 ${hiddenEventCount} 项`}
                            title={`还有 ${hiddenEventCount} 项`}
                            onClick={(clickEvent) => {
                              clickEvent.stopPropagation();
                              openDayList(key);
                            }}
                            type="button"
                          >
                            +{hiddenEventCount}
                          </button>
                        )}
                      </div>
		                    <div className="calendar-cell-events">
		                      {dayEvents.slice(0, visibleInlineEvents).map((event) => (
		                        <button
		                          className={`calendar-event-chip ${displayCalendarEventKey(event) === selectedEventKey ? "selected" : ""}`}
                          key={displayCalendarEventKey(event)}
                          onClick={(clickEvent) => {
                            clickEvent.stopPropagation();
                            openDayList(dateKey(event.meta.startsAt));
                          }}
                          type="button"
                        >
		                          <span className="calendar-event-dot" />
                              <span className="calendar-event-time">{eventTimeLabel(event.meta.startsAt)}</span>
                              <span className="calendar-event-title">{(event.payload as CalendarEventPayload).title}</span>
		                        </button>
			                      ))}
		                    </div>
		                    {hasMoreEvents && !compactOverflow && (
		                      <button
		                        className="calendar-more-label"
		                        aria-label={`还有 ${hiddenEventCount} 项`}
		                        title={`还有 ${hiddenEventCount} 项`}
		                        onClick={(clickEvent) => {
		                          clickEvent.stopPropagation();
		                          openDayList(key);
		                        }}
		                        type="button"
		                      >
		                        <span className="calendar-more-full">还有 {hiddenEventCount} 项</span>
		                        <span className="calendar-more-compact">+{hiddenEventCount}</span>
		                        <span className="calendar-more-tiny">{hiddenEventCount}</span>
		                      </button>
		                    )}
		                  </div>
                );
              })}
            </div>
          </>
        )}
      </section>

	      {(draft || selectedEvent || expandedDayKey) && (
	        <div className="calendar-popover-backdrop" onClick={closeCalendarPopover}>
	          <div className="calendar-popover" onClick={(event) => event.stopPropagation()}>
	            {draft && (
	              <CalendarDraftDetailPanel
                draft={draft}
                title={draft.todoId ? "Todo -> Calendar" : "New Event"}
                onChange={onDraft}
                onClose={() => onDraft(null)}
                onSave={onSaveDraft}
              />
            )}
            {selectedEvent && (
              <CalendarEventDetailPanel
                event={selectedEvent}
                onClose={() => onSelectedEventKey(null)}
                onUpdate={(draft) => onUpdateEvent(selectedEvent, draft)}
	                onDelete={onDeleteEvent}
	              />
	            )}
	            {!draft && !selectedEvent && expandedDayKey && (
	              <CalendarDayEventListPanel
	                date={expandedDayKey}
	                events={expandedDayEvents}
	                onClose={closeCalendarPopover}
	                onSelect={selectEvent}
	              />
	            )}
	          </div>
	        </div>
	      )}
    </div>
  );
}

function StrategyView({
  threads,
  selectedThreadId,
  selectedThread,
  strategyTodos,
  strategyCompletedTodos,
  strategySignals,
  taskText,
  strategyDraft,
  selectedTodo,
  selectedTodoSubtasks,
  calendarEvents,
  onSelectThread,
  onTaskText,
  onStrategyDraft,
  onAddThread,
  onAddTask,
  onSelectTodo,
  onCloseDetail,
  onOpenCalendar,
  onComplete,
  onArchive,
  onArchiveThread,
  onUpdateTodoMeta,
  onUpdateTodoPayload,
  onSaveTodoDetail,
  onAddSubtask,
  onToggleSubtask
}: {
  threads: DecryptedStrategyThread[];
  selectedThreadId: string;
  selectedThread?: DecryptedStrategyThread;
  strategyTodos: DecryptedItem[];
  strategyCompletedTodos: DecryptedItem[];
  strategySignals: DecryptedItem[];
  taskText: string;
  strategyDraft: StrategyDraft;
  selectedTodo?: DecryptedItem;
  selectedTodoSubtasks: DecryptedItem[];
  calendarEvents: DecryptedCalendarEvent[];
  onSelectThread: (id: string) => void;
  onTaskText: (text: string) => void;
  onStrategyDraft: (draft: StrategyDraft) => void;
  onAddThread: () => void;
  onAddTask: () => void;
  onSelectTodo: (todo: DecryptedItem) => void;
  onCloseDetail: () => void;
  onOpenCalendar: (todo: DecryptedItem) => void;
  onComplete: (todo: DecryptedItem) => void;
  onArchive: (todo: DecryptedItem) => void;
  onArchiveThread: (thread: DecryptedStrategyThread) => void;
  onUpdateTodoMeta: (itemId: string, patch: Partial<DecryptedItem["meta"]>) => void;
  onUpdateTodoPayload: (itemId: string, patch: Partial<ItemPayload>) => void;
  onSaveTodoDetail: (
    itemId: string,
    metaPatch: Partial<DecryptedItem["meta"]>,
    payloadPatch: Partial<ItemPayload>
  ) => void;
  onAddSubtask: (parentTodo: DecryptedItem, title: string) => void;
  onToggleSubtask: (todo: DecryptedItem) => void;
}) {
  const [collapsedTodoIds, setCollapsedTodoIds] = useState<Set<string>>(() => new Set());
  const [showStrategyCompleted, setShowStrategyCompleted] = useState(false);
  const calendarByTodo = new Map(
    calendarEvents
      .filter((event) => event.meta.sourceItemId)
      .map((event) => [event.meta.sourceItemId, event] as const)
  );
  const strategyTodoIds = new Set(strategyTodos.map((todo) => todo.meta.id));
  const strategyTodoRoots = strategyTodos.filter(
    (todo) => !todo.meta.parentItemId || !strategyTodoIds.has(todo.meta.parentItemId)
  );
  const toggleStrategyTodoCollapse = (todoId: string) => {
    setCollapsedTodoIds((current) => {
      const next = new Set(current);
      if (next.has(todoId)) next.delete(todoId);
      else next.add(todoId);
      return next;
    });
  };

  return (
    <div className={`strategy-layout ${selectedTodo ? "has-task-detail" : ""}`}>
      <aside className="thread-list">
        <div className="new-thread-box">
          <input
            value={strategyDraft.title}
            onChange={(event) => onStrategyDraft({ ...strategyDraft, title: event.target.value })}
            placeholder="新的战略方向"
          />
          <textarea
            value={strategyDraft.currentHypothesis}
            onChange={(event) => onStrategyDraft({ ...strategyDraft, currentHypothesis: event.target.value })}
            placeholder="当前假设（可选）"
          />
          <button className="primary-button" onClick={onAddThread}>
            <Plus size={15} />
            Add Direction
          </button>
        </div>
        {threads.map((thread) => (
          <StrategyThreadCard
            key={thread.meta.id}
            thread={thread}
            active={thread.meta.id === selectedThreadId}
            onClick={() => {
              onSelectThread(thread.meta.id);
              onCloseDetail();
            }}
          />
        ))}
      </aside>
      <section className="thread-detail">
        {selectedThread ? (
          <>
            <div className="thread-heading">
              <div>
                <div className="eyebrow">Strategy Panel</div>
                <h2>{selectedThread.payload.title}</h2>
                <p>{selectedThread.payload.currentHypothesis}</p>
              </div>
              <div className="thread-heading-actions">
                <StatusPill tone="strategy" label={selectedThread.meta.status} />
                <button className="ghost-button" onClick={() => onArchiveThread(selectedThread)}>
                  <Archive size={15} />
                  Archive
                </button>
              </div>
            </div>

            <div className="note-composer">
              <input
                value={taskText}
                onChange={(event) => onTaskText(event.target.value)}
                onKeyDown={(event) => {
                  if (isImeComposing(event)) return;
                  if (event.key === "Enter") onAddTask();
                }}
                placeholder="添加战略子任务，会同步出现在 Todo"
              />
              <button className="primary-button" onClick={onAddTask}>
                <Plus size={16} />
                Add Subtask
              </button>
            </div>

            <SectionCard icon={<LayoutList size={17} />} title="Subtasks in Todo">
              {strategyTodos.length ? (
                buildTodoTreeEntries(strategyTodoRoots, strategyTodos, collapsedTodoIds).map(({ todo, depth, childCount }) => (
                  <article
                    className={`strategy-note ${selectedTodo?.meta.id === todo.meta.id ? "selected" : ""} ${depth > 0 ? "child" : ""}`}
                    style={{ "--todo-depth": depth } as CSSProperties}
                    key={todo.meta.id}
                    role="button"
                    tabIndex={0}
                    onClick={() => onSelectTodo(todo)}
                    onKeyDown={(event) => {
                      if (event.target !== event.currentTarget) return;
                      if (event.key === "Enter" || event.key === " ") {
                        event.preventDefault();
                        onSelectTodo(todo);
                      }
                    }}
                  >
                    <button
                      className="completion-circle"
                      aria-label={`Complete ${(todo.payload as ItemPayload).title}`}
                      onClick={(event) => {
                        event.stopPropagation();
                        onComplete(todo);
                      }}
                    >
                      <Check size={13} />
                    </button>
                    <div>
                      <StatusPill tone="success" label="Todo" />
                      <div className="strategy-note-title-row">
                        <h3>{(todo.payload as ItemPayload).title}</h3>
                      </div>
                      {childCount > 0 && (
                        <button
                          className={`subtask-toggle-chip ${collapsedTodoIds.has(todo.meta.id) ? "collapsed" : ""}`}
                          aria-label={collapsedTodoIds.has(todo.meta.id) ? `Show ${childCount} subtasks` : `Hide ${childCount} subtasks`}
                          onClick={(event) => {
                            event.stopPropagation();
                            toggleStrategyTodoCollapse(todo.meta.id);
                          }}
                        >
                          <ChevronRight size={12} />
                          {collapsedTodoIds.has(todo.meta.id) ? "Show" : "Hide"} {childCount} subtasks
                        </button>
                      )}
                      {todo.meta.dueAt && (
                        <p className="strategy-note-date">
                          <CalendarDays size={13} />
                          {todoDateLabel(todo.meta.dueAt)}
                        </p>
                      )}
                    </div>
                    <ChevronRight className="strategy-note-arrow" size={16} />
                  </article>
                ))
              ) : (
                <EmptyState label="这个战略线程还没有子任务" />
              )}
            </SectionCard>

            {strategyCompletedTodos.length > 0 && (
              <section className="strategy-completed-section">
                <button
                  className={`collapsed-list-toggle ${showStrategyCompleted ? "open" : ""}`}
                  onClick={() => setShowStrategyCompleted((value) => !value)}
                >
                  <span className="collapse-title">
                    <ChevronRight className="collapse-chevron" size={14} />
                    Completed
                  </span>
                  <small>{strategyCompletedTodos.length}</small>
                </button>
                {showStrategyCompleted && (
                  <div className="collapsed-list-body">
                    {strategyCompletedTodos.map((todo) => (
                      <article className="mini-row completed" key={todo.meta.id}>
                        <span className="completion-circle completed">
                          <Check size={12} />
                        </span>
                        <div>
                          <div className="mini-row-title">{(todo.payload as ItemPayload).title}</div>
                          <span>{relativeAgeLabel(todo.meta.updatedAt)}</span>
                        </div>
                      </article>
                    ))}
                  </div>
                )}
              </section>
            )}

            {strategySignals.length > 0 && (
              <SectionCard icon={<Brain size={17} />} title="Signals">
                {strategySignals.map((note) => (
                  <CompactItem key={note.meta.id} item={note} />
                ))}
              </SectionCard>
            )}
          </>
        ) : (
          <EmptyState label="选择一个战略线程" />
        )}
      </section>
      {selectedTodo && (
        <TodoDetailPanel
          todo={selectedTodo}
          threads={threads}
          subtasks={selectedTodoSubtasks}
          calendarEvent={calendarByTodo.get(selectedTodo.meta.id)}
          onClose={onCloseDetail}
          onOpenCalendar={onOpenCalendar}
          onSave={onSaveTodoDetail}
          onUpdatePayload={onUpdateTodoPayload}
          onAddSubtask={onAddSubtask}
          onToggleSubtask={onToggleSubtask}
          onArchive={onArchive}
        />
      )}
    </div>
  );
}

function SettingsView({
  settings,
  backendSettings,
  syncSecretReady,
  connecting,
  onBackendChange,
  onBackendSignIn,
  onBackendSignOut,
  onConnect
}: {
  settings: FeishuSettingsState;
  backendSettings: BackendSettingsState;
  syncSecretReady: boolean;
  connecting: boolean;
  onBackendChange: (settings: BackendSettingsState) => void;
  onBackendSignIn: (password: string) => void | Promise<void>;
  onBackendSignOut: () => void;
  onConnect: () => void | Promise<void>;
}) {
  const [password, setPassword] = useState("");
  const connected = settings.status === "connected" || backendSettings.feishuConnected;
  const hasAccountSession = backendSettings.status === "connected" || backendSettings.feishuConnected;
  const needsLocalUnlock = hasAccountSession && !syncSecretReady;
  const signedIn = hasAccountSession && syncSecretReady;
  const lastSyncedAt = backendSettings.lastSyncedAt ?? settings.lastSyncedAt;
  const lastSyncLabel = connected
    ? lastSyncedAt
      ? `Synced ${relativeAgeLabel(lastSyncedAt)}`
      : "Ready"
    : signedIn
      ? "Authorization needed"
      : "Sign in first";
  const signInError = needsLocalUnlock
    ? "Enter your password once on this Mac to sync Todo."
    : !signedIn
      ? backendSettings.lastError
      : undefined;
  const calendarError = signedIn ? backendSettings.lastError ?? settings.lastError : undefined;

  return (
    <div className="settings-view">
      <section className="feishu-connect-card">
        <div className="feishu-connect-hero">
          <div className="feishu-connect-icon">
            <ShieldCheck size={22} />
          </div>
          <div className="feishu-connect-copy">
            <div className="eyebrow">Account</div>
            <h2>Resolve</h2>
          </div>
          <StatusPill
            tone={signedIn ? "success" : needsLocalUnlock ? "warning" : "muted"}
            label={signedIn ? "signed in" : needsLocalUnlock ? "unlock sync" : "signed out"}
          />
        </div>

        {signedIn ? (
          <div className="account-summary-row">
            <div>
              <span>Signed in as</span>
              <strong>{backendSettings.email || "Resolve account"}</strong>
            </div>
            <button className="ghost-button" onClick={onBackendSignOut} disabled={connecting}>
              <X size={16} />
              Sign out
            </button>
          </div>
        ) : (
          <>
            <div className="settings-grid advanced-settings-grid">
              <SettingsRow label="Email">
                <input
                  value={backendSettings.email}
                  onChange={(event) => onBackendChange({ ...backendSettings, email: event.target.value.trim() })}
                  placeholder="you@example.com"
                  autoComplete="email"
                />
              </SettingsRow>
              <SettingsRow label="Password">
                <input
                  value={password}
                  onChange={(event) => setPassword(event.target.value)}
                  type="password"
                  placeholder="Password"
                  autoComplete="current-password"
                />
              </SettingsRow>
            </div>

            <div className="settings-actions">
              <button
                className="primary-button"
                onClick={() => {
                  void onBackendSignIn(password);
                  setPassword("");
                }}
                disabled={connecting || !backendSettings.email || !password}
              >
                <KeyRound size={16} />
                Sign in
              </button>
            </div>
          </>
        )}
        {signInError && <p className="settings-error">{signInError}</p>}
      </section>

      <section className="feishu-connect-card">
        <div className="feishu-connect-hero">
          <div className="feishu-connect-icon">
            <CalendarDays size={22} />
          </div>
          <div className="feishu-connect-copy">
            <div className="eyebrow">Calendar</div>
            <h2>Calendar</h2>
          </div>
          <SyncStatusBadge feishuSettings={settings} backendSettings={backendSettings} />
        </div>

        <div className="feishu-status-strip">
          <div>
            <RefreshCw size={16} />
            <span>{lastSyncLabel}</span>
          </div>
        </div>

        {!connected && (
          <div className="settings-actions">
            <button className="primary-button" onClick={() => void onConnect()} disabled={connecting || !signedIn}>
              <ExternalLink size={16} />
              {connecting ? "Opening" : signedIn ? "Authorize Calendar" : "Sign in first"}
            </button>
          </div>
        )}

        {connected && (
          <div className="settings-actions">
            <button className="secondary-button" disabled>
              <Check size={16} />
              Authorized
            </button>
          </div>
        )}

        {calendarError && <p className="settings-error">{friendlyCalendarError(calendarError)}</p>}
      </section>

    </div>
  );
}

function SectionCard({
  icon,
  title,
  overflow,
  children
}: {
  icon: ReactNode;
  title: string;
  overflow?: number;
  children: ReactNode;
}) {
  return (
    <section className="section-card">
      <div className="section-card-head">
        <div>
          {icon}
          <span>{title}</span>
        </div>
        {overflow ? <span className="more-label">+ {overflow} more</span> : null}
      </div>
      <div className="section-card-body">{children}</div>
    </section>
  );
}

function CalendarEventCard({
  event,
  selected,
  onSelect,
  onDelete
}: {
  event: DecryptedCalendarEvent;
  selected?: boolean;
  onSelect: () => void;
  onDelete: (event: DecryptedCalendarEvent) => void;
}) {
  const payload = event.payload as CalendarEventPayload;
  const deleteLabel = event.meta.status === "readonly" || event.meta.canDelete === false ? "Hide locally" : "Delete";
  return (
    <article className={`calendar-card ${selected ? "selected" : ""}`} onClick={onSelect}>
      <span>{eventTimeLabel(event.meta.startsAt)}</span>
      <div>
        <h3>{payload.title}</h3>
        <p>
          {event.meta.provider}
          {event.meta.status === "readonly" ? " · Readonly from Feishu" : ` · ${event.meta.status}`}
        </p>
      </div>
      <button
        className="icon-button calendar-delete-button"
        aria-label={`${deleteLabel} ${payload.title}`}
        title={deleteLabel}
        onClick={(clickEvent) => {
          clickEvent.stopPropagation();
          onDelete(event);
        }}
      >
        <Trash2 size={15} />
      </button>
    </article>
  );
}

function CalendarDayEventListPanel({
  date,
  events,
  onClose,
  onSelect
}: {
  date: string;
  events: DecryptedCalendarEvent[];
  onClose: () => void;
  onSelect: (event: DecryptedCalendarEvent) => void;
}) {
  const day = new Date(`${date}T00:00:00`);
  const dayLabel = day.toLocaleDateString("zh-CN", {
    month: "long",
    day: "numeric",
    weekday: "long"
  });

  return (
    <SectionCard icon={<CalendarDays size={17} />} title="Day Events">
      <div className="day-events-panel">
        <div className="detail-head">
          <div className="day-events-heading">
            <h3>{dayLabel}</h3>
            <p>{events.length} events</p>
          </div>
          <button className="icon-button" onClick={onClose} aria-label="Close day events">
            <X size={16} />
          </button>
        </div>

        <div className="day-events-list">
          {events.map((event) => {
            const payload = event.payload as CalendarEventPayload;
            return (
              <button className="day-event-row" key={displayCalendarEventKey(event)} onClick={() => onSelect(event)}>
                <time>{eventTimeLabel(event.meta.startsAt)}</time>
                <span>{payload.title}</span>
                {event.meta.status === "readonly" && <small>Readonly</small>}
              </button>
            );
          })}
        </div>
      </div>
    </SectionCard>
  );
}

function CalendarDraftDetailPanel({
  draft,
  title,
  onChange,
  onClose,
  onSave
}: {
  draft: CalendarDraft;
  title: string;
  onChange: (patch: Partial<CalendarDraft> | null) => void;
  onClose: () => void;
  onSave: () => void;
}) {
  return (
    <SectionCard icon={<Clock3 size={17} />} title={title}>
      <div
        className="event-detail-panel"
        onKeyDown={(event) => {
          const target = event.target as HTMLElement | null;
          if (isImeComposing(event)) return;
          if (event.key === "Enter" && target?.closest("input, select")) {
            event.preventDefault();
          }
        }}
      >
        <div className="detail-head">
          <StatusPill tone="calendar" label="New Feishu event" />
          <button className="icon-button" onClick={onClose} aria-label="Close new event">
            <X size={16} />
          </button>
        </div>
        <label className="detail-field">
          <span>Title</span>
          <input
            value={draft.title}
            onChange={(event) => onChange({ title: event.target.value })}
            placeholder="日程标题"
          />
        </label>
        <div className="detail-field">
          <span>Date</span>
          <div className="date-quick-row">
            {quickDateOptions().map((option) => (
              <button
                className={draft.date === option.value ? "active" : ""}
                key={option.value}
                onClick={() => onChange({ date: option.value })}
              >
                {option.label}
              </button>
            ))}
          </div>
          <div className="detail-date-row">
            <input
              type="date"
              value={draft.date}
              onChange={(event) => onChange({ date: event.target.value })}
            />
            <select value={draft.time} onChange={(event) => onChange({ time: event.target.value })}>
              {timeSelectOptions().map((time) => (
                <option key={time} value={time}>
                  {time}
                </option>
              ))}
            </select>
          </div>
        </div>
        <label className="detail-field">
          <span>Description</span>
          <textarea
            value={draft.description}
            onChange={(event) => onChange({ description: event.target.value })}
            placeholder="会议上下文、准备事项、结论..."
          />
        </label>
        <div className="detail-actions">
          <button className="primary-button" onClick={onSave}>
            <Send size={15} />
            Create
          </button>
          <button className="ghost-button" onClick={onClose}>
            <X size={15} />
            Cancel
          </button>
        </div>
      </div>
    </SectionCard>
  );
}

function CalendarEventDetailPanel({
  event,
  onClose,
  onUpdate,
  onDelete
}: {
  event: DecryptedCalendarEvent;
  onClose: () => void;
  onUpdate: (draft: CalendarEventEditDraft) => void;
  onDelete: (event: DecryptedCalendarEvent) => void;
}) {
  const payload = event.payload as CalendarEventPayload;
  const [draft, setDraft] = useState<CalendarEventEditDraft>(() => ({
    title: payload.title,
    date: formatDateInput(new Date(event.meta.startsAt)),
    time: inputTimeValue(event.meta.startsAt),
    description: payload.description ?? ""
  }));
  const [isEditing, setIsEditing] = useState(false);
  const readonly = event.meta.status === "readonly" || event.meta.canEdit === false;
  const deleteLabel = event.meta.status === "readonly" || event.meta.canDelete === false ? "Hide locally" : "Delete";

  useEffect(() => {
    setDraft({
      title: payload.title,
      date: formatDateInput(new Date(event.meta.startsAt)),
      time: inputTimeValue(event.meta.startsAt),
      description: payload.description ?? ""
    });
    setIsEditing(false);
  }, [event.meta.id, event.meta.startsAt, payload.title, payload.description]);

  return (
    <SectionCard icon={<Clock3 size={17} />} title="Event Detail">
      <div className="event-detail-panel">
        <div className="detail-head">
          <StatusPill tone={readonly ? "muted" : "calendar"} label={readonly ? "Readonly from Feishu" : event.meta.status} />
          <div className="detail-head-actions">
            {!readonly && !isEditing && (
              <button className="secondary-button" onClick={() => setIsEditing(true)}>
                Edit
              </button>
            )}
            <button className="icon-button" onClick={onClose} aria-label="Close event detail">
              <X size={16} />
            </button>
          </div>
        </div>

        {!isEditing ? (
          <div className="event-readonly-summary">
            <h3>{payload.title}</h3>
            <div className="event-readonly-meta">
              <CalendarDays size={15} />
              <span>{todoDateLabel(event.meta.startsAt)}</span>
            </div>
            <div className="event-comment-block">
              <span>Comment</span>
              {payload.description ? <p>{payload.description}</p> : <p className="muted-comment">No comment</p>}
            </div>
            {payload.meetingUrl && (
              <button className="secondary-button meeting-link-button" onClick={() => window.open(payload.meetingUrl, "_blank", "noopener,noreferrer")}>
                <ExternalLink size={15} />
                Open meeting link
              </button>
            )}
          </div>
        ) : (
          <>
            <label className="detail-field">
              <span>Title</span>
              <input
                value={draft.title}
                onChange={(changeEvent) => setDraft({ ...draft, title: changeEvent.target.value })}
              />
            </label>
            <div className="detail-field">
              <span>Date</span>
              <div className="date-quick-row">
                {quickDateOptions().map((option) => (
                  <button
                    className={draft.date === option.value ? "active" : ""}
                    key={option.value}
                    onClick={() => setDraft({ ...draft, date: option.value })}
                  >
                    {option.label}
                  </button>
                ))}
              </div>
              <div className="detail-date-row">
                <input
                  type="date"
                  value={draft.date}
                  onChange={(changeEvent) => setDraft({ ...draft, date: changeEvent.target.value })}
                />
                <select
                  value={draft.time}
                  onChange={(changeEvent) => setDraft({ ...draft, time: changeEvent.target.value })}
                >
                  {timeSelectOptions().map((time) => (
                    <option key={time} value={time}>
                      {time}
                    </option>
                  ))}
                </select>
              </div>
            </div>
            <label className="detail-field">
              <span>Description</span>
              <textarea
                value={draft.description}
                onChange={(changeEvent) => setDraft({ ...draft, description: changeEvent.target.value })}
                placeholder="会议上下文、准备事项、结论..."
              />
            </label>
          </>
        )}

        <div className="detail-actions">
          {isEditing ? (
            <>
              <button
                className="primary-button"
                onClick={() => {
                  onUpdate(draft);
                  setIsEditing(false);
                }}
              >
                <Send size={15} />
                Save to Feishu
              </button>
              <button className="ghost-button" onClick={() => setIsEditing(false)}>
                <X size={15} />
                Cancel
              </button>
            </>
          ) : (
            <button className="ghost-button" onClick={() => onDelete(event)}>
              <Trash2 size={15} />
              {deleteLabel}
            </button>
          )}
        </div>
      </div>
    </SectionCard>
  );
}

function SegmentedControl({
  value,
  options,
  onChange
}: {
  value: string;
  options: Array<{ value: string; label: string }>;
  onChange: (value: string) => void;
}) {
  return (
    <div className="segmented-control">
      {options.map((option) => (
        <button
          className={value === option.value ? "active" : ""}
          key={option.value}
          onClick={() => onChange(option.value)}
        >
          {option.label}
        </button>
      ))}
    </div>
  );
}

function YearCalendar({
  year,
  eventsByDate,
  onSelectMonth
}: {
  year: number;
  eventsByDate: Map<string, DecryptedCalendarEvent[]>;
  onSelectMonth: (month: number) => void;
}) {
  return (
    <div className="year-grid">
      {Array.from({ length: 12 }, (_, month) => {
        const monthEvents = Array.from(eventsByDate.entries())
          .filter(([key]) => {
            const date = new Date(`${key}T00:00:00`);
            return date.getFullYear() === year && date.getMonth() === month;
          })
          .flatMap(([, value]) => value);
        return (
          <button className="year-month-card" key={month} onClick={() => onSelectMonth(month)}>
            <strong>{new Date(year, month, 1).toLocaleDateString("zh-CN", { month: "long" })}</strong>
            <span>{monthEvents.length} events</span>
            {monthEvents.slice(0, 3).map((event) => (
              <small key={displayCalendarEventKey(event)}>{(event.payload as CalendarEventPayload).title}</small>
            ))}
          </button>
        );
      })}
    </div>
  );
}

function CompactItem({ item }: { item: DecryptedItem }) {
  const payload = item.payload as ItemPayload | StrategyNotePayload;
  return (
    <article className="compact-item">
      <span />
      <p>{payload.title}</p>
      <small>{item.meta.status}</small>
    </article>
  );
}

function StrategyThreadCard({
  thread,
  active,
  onClick
}: {
  thread: DecryptedStrategyThread;
  active: boolean;
  onClick: () => void;
}) {
	  return (
	    <button className={`thread-card ${active ? "active" : ""}`} onClick={onClick}>
	      <span>{thread.payload.title}</span>
	    </button>
	  );
}

function RouteButton({
  icon,
  label,
  danger,
  onClick
}: {
  icon: ReactNode;
  label: string;
  danger?: boolean;
  onClick: () => void;
}) {
  return (
    <button className={`route-button ${danger ? "danger" : ""}`} onClick={onClick}>
      {icon}
      <span>{label}</span>
    </button>
  );
}

function StatusPill({ label, tone = "muted" }: { label: string; tone?: "success" | "warning" | "calendar" | "strategy" | "muted" }) {
  return <span className={`status-pill ${tone}`}>{label}</span>;
}

function calendarConnectionSummary(feishuSettings: FeishuSettingsState, backendSettings: BackendSettingsState) {
  const signedIn = backendSettings.status === "connected" || backendSettings.feishuConnected;
  const connected = backendSettings.feishuConnected || feishuSettings.status === "connected";
  const lastSyncedAt = backendSettings.lastSyncedAt ?? feishuSettings.lastSyncedAt;
  const rawError = signedIn ? backendSettings.lastError ?? feishuSettings.lastError : undefined;
  const friendlyError = friendlyCalendarError(rawError);
  const needsAuthorization = Boolean(rawError && /attention|authorization|auth|token|permission/i.test(rawError));
  const hasSyncWarning = connected && Boolean(rawError && !needsAuthorization);

  if (connected) {
    const syncedLabel = lastSyncedAt ? `Synced ${relativeAgeLabel(lastSyncedAt)}` : "Connected";
    return {
      signedIn,
      connected,
      tone: hasSyncWarning ? "warning" as const : "success" as const,
      badgeLabel: hasSyncWarning ? "Calendar sync delayed" : `Calendar ${syncedLabel}`,
      panelLabel: hasSyncWarning ? "Sync delayed" : syncedLabel,
      error: friendlyError
    };
  }

  if (signedIn) {
    return {
      signedIn,
      connected,
      tone: "warning" as const,
      badgeLabel: "Calendar authorization missing",
      panelLabel: "Needs authorization",
      error: friendlyError || "Calendar authorization is not available on this device."
    };
  }

  return {
    signedIn,
    connected,
    tone: "muted" as const,
    badgeLabel: "Calendar not connected",
    panelLabel: "Not connected",
    error: undefined
  };
}

function friendlyCalendarError(error?: string) {
  if (!error) return undefined;
  if (/503|service unavailable|temporarily unavailable/i.test(error)) {
    return "Calendar sync is temporarily unavailable.";
  }
  if (/attention|authorization|auth|token|permission/i.test(error)) {
    return "Calendar authorization missing on backend.";
  }
  return error;
}

function SyncStatusBadge({
  feishuSettings,
  backendSettings
}: {
  feishuSettings: FeishuSettingsState;
  backendSettings: BackendSettingsState;
}) {
  const summary = calendarConnectionSummary(feishuSettings, backendSettings);
  return <StatusPill tone={summary.tone} label={summary.badgeLabel} />;
}

function EmptyState({ label }: { label: string }) {
  return <div className="empty-state">{label}</div>;
}

function SettingsRow({ label, children }: { label: string; children: ReactNode }) {
  return (
    <label className="settings-row">
      <span>{label}</span>
      <div>{children}</div>
    </label>
  );
}

function SidebarNav({
  active,
  todoCount,
  calendarCount,
  strategyCount,
  onChange
}: {
  active: Tab;
  todoCount: number;
  calendarCount: number;
  strategyCount: number;
  onChange: (tab: Tab) => void;
}) {
  const tabs: Array<{ id: Tab; label: string; icon: ReactNode; count?: number }> = [
    { id: "todo", label: "Todo", icon: <LayoutList size={18} />, count: todoCount },
    { id: "calendar", label: "Calendar", icon: <CalendarDays size={18} />, count: calendarCount },
    { id: "strategy", label: "Strategy", icon: <Brain size={18} />, count: strategyCount },
    { id: "settings", label: "Settings", icon: <Settings size={18} /> }
  ];

  return (
    <nav className="sidebar-nav">
      {tabs.map((tabItem) => (
        <button
          className={active === tabItem.id ? "active" : ""}
          key={tabItem.id}
          onClick={() => onChange(tabItem.id)}
        >
          {tabItem.icon}
          <span>{tabItem.label}</span>
          {tabItem.count != null && <small>{tabItem.count}</small>}
        </button>
      ))}
    </nav>
  );
}

function Toast({ message }: { message: string }) {
  return <div className="toast">{message}</div>;
}
