import type {
  CalendarEventMetadata,
  CalendarEventPayload,
  DecryptedCalendarEvent,
  DecryptedItem,
  ItemPayload,
  ItemRoute,
  ItemSource,
  StrategyNotePayload,
  TrackerItemPayload,
  TrackerStatus
} from "./types";
import { addDays, makeId, nowIso } from "./utils";

export interface EmptyEncryptedFields {
  encryptedPayload: "";
  payloadNonce: "";
  payloadVersion: 1;
}

export const emptyEncryptedFields: EmptyEncryptedFields = {
  encryptedPayload: "",
  payloadNonce: "",
  payloadVersion: 1
};

export function createCaptureItem(input: {
  title: string;
  source: ItemSource;
  now?: string;
}): DecryptedItem {
  const timestamp = input.now ?? nowIso();
  return {
    meta: {
      id: makeId("item"),
      type: "capture",
      status: "inbox",
      source: input.source,
      createdAt: timestamp,
      updatedAt: timestamp,
      ...emptyEncryptedFields
    },
    payload: {
      title: input.title.trim()
    }
  };
}

export interface RouteSuggestion {
  route: ItemRoute;
  trackerStatus?: TrackerStatus;
  reviewAt?: string;
  reason: string;
}

const calendarWords = /(明天|后天|今天|周一|周二|周三|周四|周五|周六|周日|星期|下午|上午|中午|晚上|早上|\d+\s*点|\d+:\d+)/;
const trackerWords = /(等|回复|问|跟进|推进|下次|提醒|观察|看看)/;
const strategyWords = /(战略|融资|产品|组织|人才|PMF|叙事|市场|enterprise|workflow|假设|判断|方向)/i;

export function suggestRoute(text: string, now = new Date()): RouteSuggestion {
  if (strategyWords.test(text)) {
    return {
      route: "strategy",
      reason: "像战略观察或判断，建议进入 Strategy Thread。"
    };
  }

  if (trackerWords.test(text)) {
    return {
      route: "tracker",
      trackerStatus: text.includes("等") || text.includes("回复") ? "waiting" : "discuss",
      reviewAt: calendarWords.test(text) ? addDays(now, 1).toISOString() : undefined,
      reason: "像需要等待、观察或下次讨论的事项。"
    };
  }

  if (calendarWords.test(text)) {
    return {
      route: "calendar",
      reason: "包含明确时间线索，可以先生成 Calendar Draft。"
    };
  }

  return {
    route: "task",
    reason: "像一个近期主动推进事项。"
  };
}

export function routeCapture(input: {
  item: DecryptedItem;
  route: ItemRoute;
  now?: string;
  strategyThreadId?: string;
  trackerStatus?: TrackerStatus;
  reviewAt?: string;
}): {
  source: DecryptedItem;
  created?: DecryptedItem | DecryptedCalendarEvent;
} {
  const timestamp = input.now ?? nowIso();
  const source: DecryptedItem = {
    ...input.item,
    meta: {
      ...input.item.meta,
      status: input.route === "delete" ? "deleted" : input.route === "archive" ? "archived" : "triaged",
      route: input.route,
      updatedAt: timestamp,
      deletedAt: input.route === "delete" ? timestamp : input.item.meta.deletedAt
    }
  };

  const title = (input.item.payload as ItemPayload).title;

  if (input.route === "task") {
    return {
      source,
      created: {
        meta: {
          id: makeId("item"),
          type: "task",
          status: "active",
          source: input.item.meta.source,
          createdAt: timestamp,
          updatedAt: timestamp,
          sourceItemId: input.item.meta.id,
          ...emptyEncryptedFields
        },
        payload: { title }
      }
    };
  }

  if (input.route === "tracker") {
    const status = input.trackerStatus ?? "watching";
    const payload: TrackerItemPayload = {
      title,
      status,
      reviewAt: input.reviewAt,
      sourceItemId: input.item.meta.id,
      strategyThreadId: input.strategyThreadId
    };
    return {
      source,
      created: {
        meta: {
          id: makeId("item"),
          type: "tracker",
          status,
          source: input.item.meta.source,
          createdAt: timestamp,
          updatedAt: timestamp,
          reviewAt: input.reviewAt,
          sourceItemId: input.item.meta.id,
          strategyThreadId: input.strategyThreadId,
          ...emptyEncryptedFields
        },
        payload
      }
    };
  }

  if (input.route === "strategy") {
    const payload: StrategyNotePayload = {
      title,
      kind: "observation"
    };
    return {
      source,
      created: {
        meta: {
          id: makeId("item"),
          type: "strategy_note",
          status: "active",
          source: input.item.meta.source,
          createdAt: timestamp,
          updatedAt: timestamp,
          sourceItemId: input.item.meta.id,
          strategyThreadId: input.strategyThreadId,
          ...emptyEncryptedFields
        },
        payload
      }
    };
  }

  if (input.route === "calendar") {
    const startsAt = input.reviewAt ?? addDays(new Date(), 1).toISOString();
    const meta: CalendarEventMetadata = {
      id: makeId("cal"),
      provider: "local",
      status: "local_pending_create",
      startsAt,
      endsAt: new Date(new Date(startsAt).getTime() + 30 * 60_000).toISOString(),
      createdAt: timestamp,
      updatedAt: timestamp,
      sourceItemId: input.item.meta.id,
      strategyThreadId: input.strategyThreadId,
      canEdit: true,
      canDelete: true,
      ...emptyEncryptedFields
    };
    const payload: CalendarEventPayload = { title };
    return {
      source,
      created: { meta, payload }
    };
  }

  return { source };
}
