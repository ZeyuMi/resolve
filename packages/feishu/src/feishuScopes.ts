export const feishuCalendarScopes = [
  {
    key: "calendar:calendar",
    label: "更新日历和日程信息"
  },
  {
    key: "calendar:calendar:readonly",
    label: "读取日历、日程和忙闲信息"
  },
  {
    key: "calendar:calendar:read",
    label: "读取日历信息"
  },
  {
    key: "calendar:calendar.event:read",
    label: "读取日程列表"
  },
  {
    key: "calendar:calendar.event:create",
    label: "创建日程"
  },
  {
    key: "calendar:calendar.event:update",
    label: "更新日程"
  },
  {
    key: "calendar:calendar.event:delete",
    label: "删除日程"
  },
  {
    key: "contact:user.email:readonly",
    label: "读取参与人邮箱（可选，未默认申请）"
  },
] as const;

export type FeishuScopeKey = (typeof feishuCalendarScopes)[number]["key"];
