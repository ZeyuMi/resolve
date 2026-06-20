import type { DecryptedCalendarEvent, DecryptedItem, TodaySummary } from "./types";
import { isOnOrBeforeLocalDay, isSameLocalDay } from "./utils";

const visible = <T>(items: T[], max: number) => ({
  items: items.slice(0, max),
  overflow: Math.max(0, items.length - max)
});

export function selectInboxItems(items: DecryptedItem[], now = new Date()) {
  const cutoff = now.getTime() - 48 * 60 * 60 * 1000;
  return items
    .filter((item) => item.meta.type === "capture")
    .filter((item) => item.meta.status === "inbox")
    .filter((item) => new Date(item.meta.createdAt).getTime() >= cutoff)
    .sort((a, b) => b.meta.createdAt.localeCompare(a.meta.createdAt));
}

export function selectColdStorageItems(items: DecryptedItem[], now = new Date()) {
  const cutoff = now.getTime() - 48 * 60 * 60 * 1000;
  return items
    .filter((item) => item.meta.type === "capture")
    .filter((item) => item.meta.status === "inbox")
    .filter((item) => new Date(item.meta.createdAt).getTime() < cutoff)
    .sort((a, b) => b.meta.createdAt.localeCompare(a.meta.createdAt));
}

export function buildTodaySummary(input: {
  items: DecryptedItem[];
  calendarEvents: DecryptedCalendarEvent[];
  now?: Date;
}): TodaySummary {
  const now = input.now ?? new Date();

  const calendar = input.calendarEvents
    .filter((event) => event.meta.status !== "remote_deleted")
    .filter((event) => event.meta.status !== "archived_locally")
    .filter((event) => isSameLocalDay(event.meta.startsAt, now))
    .sort((a, b) => a.meta.startsAt.localeCompare(b.meta.startsAt));

  const focus = input.items
    .filter((item) => item.meta.type === "task")
    .filter((item) => item.meta.status === "active")
    .filter((item) => !item.meta.dueAt || isOnOrBeforeLocalDay(item.meta.dueAt, now))
    .sort((a, b) => (a.meta.dueAt ?? a.meta.createdAt).localeCompare(b.meta.dueAt ?? b.meta.createdAt));

  const followUps = input.items
    .filter((item) => item.meta.type === "tracker")
    .filter((item) => ["waiting", "watching", "discuss", "review_later", "active"].includes(item.meta.status))
    .filter((item) => !item.meta.reviewAt || isOnOrBeforeLocalDay(item.meta.reviewAt, now))
    .sort((a, b) => (a.meta.reviewAt ?? a.meta.createdAt).localeCompare(b.meta.reviewAt ?? b.meta.createdAt));

  const strategySignals = input.items
    .filter((item) => item.meta.type === "strategy_note")
    .filter((item) => item.meta.status === "active")
    .sort((a, b) => b.meta.createdAt.localeCompare(a.meta.createdAt));

  const shownCalendar = visible(calendar, 5);
  const shownFocus = visible(focus, 3);
  const shownFollowUps = visible(followUps, 5);
  const shownStrategy = visible(strategySignals, 3);

  return {
    calendar: shownCalendar.items,
    focus: shownFocus.items,
    followUps: shownFollowUps.items,
    strategySignals: shownStrategy.items,
    overflow: {
      calendar: shownCalendar.overflow,
      focus: shownFocus.overflow,
      followUps: shownFollowUps.overflow,
      strategySignals: shownStrategy.overflow
    }
  };
}
