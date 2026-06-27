export type Tab = "todo" | "calendar" | "strategy" | "vault" | "uiLab" | "settings";

export type UiThemeId = "native" | "dense" | "soft";

export type CalendarViewMode = "week" | "month" | "year";

export interface CalendarDraft {
  todoId?: string;
  date: string;
  time: string;
  title: string;
  description: string;
}

export interface CalendarEventEditDraft {
  title: string;
  date: string;
  time: string;
  description: string;
}

export interface StrategyDraft {
  title: string;
  currentHypothesis: string;
}
