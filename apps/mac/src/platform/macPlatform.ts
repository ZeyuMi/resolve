export type MacTrayAction =
  | "quick_capture"
  | "open_today"
  | "open_inbox"
  | "open_todo"
  | "open_calendar"
  | "open_strategy"
  | "sync_feishu"
  | "settings";

export interface MacPlatformHandlers {
  onQuickCapture: () => void;
  onTrayAction: (action: MacTrayAction) => void;
}

export function isTauriRuntime() {
  return typeof window !== "undefined" && "__TAURI__" in window;
}

export async function registerMacPlatformHandlers(handlers: MacPlatformHandlers) {
  if (!isTauriRuntime()) return () => {};

  const { listen } = await import("@tauri-apps/api/event");
  const unlistenQuickCapture = await listen("open-quick-capture", () => handlers.onQuickCapture());
  const unlistenTrayAction = await listen<string>("tray-action", (event) => {
    handlers.onTrayAction(event.payload as MacTrayAction);
  });

  return () => {
    unlistenQuickCapture();
    unlistenTrayAction();
  };
}
