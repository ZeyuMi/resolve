import type { DecryptedCalendarEvent, DecryptedItem, DecryptedStrategyThread } from "./types";
import { addDays, makeId, nowIso } from "./utils";
import { emptyEncryptedFields } from "./routing";

const todayAt = (hour: number, minute = 0) => {
  const date = new Date();
  date.setHours(hour, minute, 0, 0);
  return date.toISOString();
};

export function createSampleData() {
  const now = nowIso();

  const calendarEvents: DecryptedCalendarEvent[] = [
    {
      title: "Investor call",
      startsAt: todayAt(14),
      endsAt: todayAt(14, 45),
      provider: "feishu" as const,
      status: "readonly" as const
    },
    {
      title: "Cardio",
      startsAt: todayAt(18, 30),
      endsAt: todayAt(19),
      provider: "feishu" as const,
      status: "synced" as const
    },
    {
      title: "和 Alex 聊候选人",
      startsAt: addDays(new Date(todayAt(15)), 1).toISOString(),
      endsAt: addDays(new Date(todayAt(15, 30)), 1).toISOString(),
      provider: "feishu" as const,
      status: "synced" as const
    }
  ].map((event) => ({
    meta: {
      id: makeId("cal"),
      provider: event.provider,
      status: event.status,
      startsAt: event.startsAt,
      endsAt: event.endsAt,
      createdAt: now,
      updatedAt: now,
      lastSyncedAt: now,
      canEdit: event.status !== "readonly",
      canDelete: event.status !== "readonly",
      ...emptyEncryptedFields
    },
    payload: {
      title: event.title
    }
  }));

  return {
    items: [] satisfies DecryptedItem[],
    strategyThreads: [] satisfies DecryptedStrategyThread[],
    calendarEvents,
    notes: []
  };
}
