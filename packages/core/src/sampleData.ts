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

  const threads: DecryptedStrategyThread[] = [
    ["融资策略", "从 market size 叙事转向 execution velocity + AI-native workflow。"],
    ["产品方向 / PMF", "关注 enterprise workflow 是否比 consumer angle 更强。"],
    ["核心人才招募", "把关键候选人的推进节奏放在每天可见的位置。"],
    ["组织节奏与授权边界", "减少创始人上下文切换，明确谁能推进什么。"],
    ["Vibe Coding / 工具想法", "把自己真实使用中的工具冲动沉淀为可试的系统。"]
  ].map(([title, currentHypothesis]) => ({
    meta: {
      id: makeId("thread"),
      status: "active",
      createdAt: now,
      updatedAt: now,
      ...emptyEncryptedFields
    },
    payload: {
      title,
      currentHypothesis
    }
  }));

  const capturedTodoTitles = [
    "和 Alex 聊完，感觉融资叙事应该强调速度优势",
    "明天问 Sarah 候选人推进到哪一步了",
    "产品 onboarding 里用户可能卡在第一步",
    "开车时想到，可以做一个 personal radar 自动总结"
  ];

  const capturedTodos: DecryptedItem[] = capturedTodoTitles.map((title, index) => ({
    meta: {
      id: makeId("item"),
      type: "task",
      status: "active",
      source: "mac",
      createdAt: now,
      updatedAt: now,
      strategyThreadId: index === 0 ? threads[0]?.meta.id : index === 2 ? threads[1]?.meta.id : undefined,
      ...emptyEncryptedFields
    },
    payload: { title }
  }));

  const tasks: DecryptedItem[] = [
    "Investor call 准备",
    "问 Alex 候选人进展",
    "有氧 30 分钟"
  ].map((title) => ({
    meta: {
      id: makeId("item"),
      type: "task",
      status: "active",
      source: "manual",
      createdAt: now,
      updatedAt: now,
      dueAt: todayAt(23, 59),
      strategyThreadId: title.includes("Investor") ? threads[0]?.meta.id : undefined,
      ...emptyEncryptedFields
    },
    payload: { title }
  }));

  const followUpTodos: DecryptedItem[] = [
    {
      title: "B 推荐增长负责人",
      strategyThreadId: threads[2]?.meta.id
    },
    {
      title: "下次和 C 聊 enterprise angle",
      strategyThreadId: threads[1]?.meta.id
    }
  ].map((todo) => ({
    meta: {
      id: makeId("item"),
      type: "task",
      status: "active" as const,
      source: "manual",
      createdAt: now,
      updatedAt: now,
      reviewAt: todayAt(9),
      strategyThreadId: todo.strategyThreadId,
      ...emptyEncryptedFields
    },
    payload: {
      title: todo.title
    }
  }));

  const strategyNotes: DecryptedItem[] = [
    "融资策略：最近投资人更关心执行速度",
    "产品方向：enterprise workflow 可能更强"
  ].map((title, index) => ({
    meta: {
      id: makeId("item"),
      type: "strategy_note",
      status: "active",
      source: "manual",
      createdAt: now,
      updatedAt: now,
      strategyThreadId: threads[index]?.meta.id,
      ...emptyEncryptedFields
    },
    payload: {
      title,
      kind: "observation"
    }
  }));

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
    items: [...capturedTodos, ...tasks, ...followUpTodos, ...strategyNotes],
    strategyThreads: threads,
    calendarEvents
  };
}
