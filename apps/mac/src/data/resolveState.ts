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

const sampleTodoTitles = new Set([
  "和 Alex 聊完，感觉融资叙事应该强调速度优势",
  "明天问 Sarah 候选人推进到哪一步了",
  "产品 onboarding 里用户可能卡在第一步",
  "开车时想到，可以做一个 personal radar 自动总结",
  "Investor call 准备",
  "问 Alex 候选人进展",
  "有氧 30 分钟",
  "B 推荐增长负责人",
  "下次和 C 聊 enterprise angle",
  "产品方向：enterprise workflow 可能更强",
  "融资策略：最近投资人更关心执行速度"
]);

const sampleThreadTitles = new Set([
  "融资策略",
  "产品方向 / PMF",
  "核心人才招募",
  "组织节奏与授权边界",
  "Vibe Coding / 工具想法"
]);

const sampleThreadHypotheses = new Set([
  "融资策略|从 market size 叙事转向 execution velocity + AI-native workflow。",
  "融资策略|强调 execution velocity + AI-native workflow。",
  "产品方向 / PMF|关注 enterprise workflow 是否比 consumer angle 更强。",
  "产品方向 / PMF|继续验证 enterprise workflow。",
  "核心人才招募|把关键候选人的推进节奏放在每天可见的位置。",
  "核心人才招募|把关键候选人推进变成可见节奏。",
  "组织节奏与授权边界|减少创始人上下文切换，明确谁能推进什么。",
  "组织节奏与授权边界|降低创始人上下文切换。",
  "Vibe Coding / 工具想法|把自己真实使用中的工具冲动沉淀为可试的系统。",
  "Vibe Coding / 工具想法|先服务自己真实使用。"
]);

function isSeedThread(thread: DecryptedStrategyThread) {
  const title = thread.payload.title.trim();
  const hypothesis = (thread.payload.currentHypothesis ?? "").trim();
  return sampleThreadTitles.has(title) && sampleThreadHypotheses.has(`${title}|${hypothesis}`);
}

export function normalizeState(state: ResolveState): ResolveState {
  const sampleThreadIds = new Set(
    state.strategyThreads
      .filter(isSeedThread)
      .map((thread) => thread.meta.id)
  );

  return {
    ...state,
    strategyThreads: state.strategyThreads.filter((thread) => !sampleThreadIds.has(thread.meta.id)),
    items: state.items.filter((item) => !sampleTodoTitles.has((item.payload as ItemPayload).title)).map((item) => {
      if (item.meta.type === "strategy_note") {
        return {
          ...item,
          meta: {
            ...item.meta,
            strategyThreadId: item.meta.strategyThreadId && sampleThreadIds.has(item.meta.strategyThreadId)
              ? undefined
              : item.meta.strategyThreadId
          }
        };
      }
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
          strategyThreadId: item.meta.strategyThreadId && sampleThreadIds.has(item.meta.strategyThreadId)
            ? undefined
            : item.meta.strategyThreadId,
          updatedAt: item.meta.updatedAt
        },
        payload: {
          title: (item.payload as ItemPayload).title,
          content: (item.payload as ItemPayload).content,
          notes: (item.payload as ItemPayload).notes,
          noteId: (item.payload as ItemPayload).noteId,
          sortOrder: (item.payload as ItemPayload).sortOrder,
          statusChangedAt: (item.payload as ItemPayload).statusChangedAt,
          attachments: (item.payload as ItemPayload).attachments
        }
      } satisfies DecryptedItem;
    })
  };
}

export function createTodoItem(input: {
  title: string;
  source?: DecryptedItem["meta"]["source"];
  strategyThreadId?: string;
  parentItemId?: string;
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
      parentItemId: input.parentItemId,
      sourceItemId: input.sourceItemId,
      ...emptyEncryptedFields
    },
    payload: {
      title: input.title,
      statusChangedAt: timestamp
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
