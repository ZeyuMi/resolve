import {
  emptyEncryptedFields,
  makeId,
  nowIso,
  type DecryptedCalendarEvent,
  type DecryptedItem,
  type DecryptedStrategyThread,
  type ItemPayload
} from "@resolve/core";
import type { ResolveState } from "@resolve/sync";

export const activeTodoStatuses = new Set(["active", "waiting", "watching", "discuss", "review_later"]);

export function normalizeState(state: ResolveState): ResolveState {
  return {
    ...state,
    items: state.items.map((item) => {
      if (item.meta.type === "strategy_note") return item;
      const status =
        item.meta.status === "deleted" ||
        item.meta.status === "archived" ||
        item.meta.status === "done" ||
        item.meta.status === "killed"
          ? item.meta.status
          : "active";
      return {
        ...item,
        meta: {
          ...item.meta,
          type: "task",
          status,
          updatedAt: item.meta.updatedAt
        },
        payload: {
          title: (item.payload as ItemPayload).title,
          content: (item.payload as ItemPayload).content,
          notes: (item.payload as ItemPayload).notes
        }
      } satisfies DecryptedItem;
    })
  };
}

export function createTodoItem(input: {
  title: string;
  source?: DecryptedItem["meta"]["source"];
  strategyThreadId?: string;
  dueAt?: string;
  sourceItemId?: string;
}): DecryptedItem {
  const timestamp = nowIso();
  return {
    meta: {
      id: makeId("item"),
      type: "task",
      status: "active",
      source: input.source ?? "mac",
      createdAt: timestamp,
      updatedAt: timestamp,
      dueAt: input.dueAt,
      strategyThreadId: input.strategyThreadId,
      sourceItemId: input.sourceItemId,
      ...emptyEncryptedFields
    },
    payload: {
      title: input.title
    }
  };
}

export function createStrategyThread(input: { title: string; currentHypothesis?: string }): DecryptedStrategyThread {
  const timestamp = nowIso();
  return {
    meta: {
      id: makeId("thread"),
      status: "active",
      createdAt: timestamp,
      updatedAt: timestamp,
      ...emptyEncryptedFields
    },
    payload: {
      title: input.title,
      currentHypothesis: input.currentHypothesis
    }
  };
}

export function createCalendarEvent(input: {
  title: string;
  startsAt: string;
  endsAt?: string;
  description?: string;
  sourceItemId?: string;
  strategyThreadId?: string;
}): DecryptedCalendarEvent {
  const timestamp = nowIso();
  return {
    meta: {
      id: makeId("cal"),
      provider: "feishu",
      status: "local_pending_create",
      startsAt: input.startsAt,
      endsAt: input.endsAt,
      createdAt: timestamp,
      updatedAt: timestamp,
      sourceItemId: input.sourceItemId,
      strategyThreadId: input.strategyThreadId,
      canEdit: true,
      canDelete: true,
      ...emptyEncryptedFields
    },
    payload: {
      title: input.title,
      description: input.description
    }
  };
}
