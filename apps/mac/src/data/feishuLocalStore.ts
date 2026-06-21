import type { FeishuConfig, TokenSet } from "@resolve/feishu";

export interface FeishuSettingsState {
  appId: string;
  appSecret: string;
  redirectUri: string;
  defaultCalendar: string;
  pastDays: number;
  futureDays: number;
  status: "not_connected" | "connected" | "token_expired" | "permission_error";
  lastSyncedAt?: string;
  lastError?: string;
}

const feishuSettingsKey = "resolve:feishu-settings:v1";
const feishuTokenKey = "resolve:feishu-token:v1";
export const nativeFeishuRedirectUri = "http://127.0.0.1:36321/oauth/feishu/callback";

export function loadFeishuSettings(): FeishuSettingsState {
  const defaults = defaultFeishuSettings();
  const raw = localStorage.getItem(feishuSettingsKey);
  if (!raw) return defaults;
  const parsed = { ...defaults, ...JSON.parse(raw) } as FeishuSettingsState;
  return {
    ...parsed,
    pastDays: Math.max(parsed.pastDays || defaults.pastDays, defaults.pastDays),
    futureDays: Math.max(parsed.futureDays || defaults.futureDays, defaults.futureDays)
  };
}

export function saveFeishuSettings(settings: FeishuSettingsState) {
  localStorage.setItem(
    feishuSettingsKey,
    JSON.stringify({
      ...settings,
      appSecret: isTauriRuntime() ? "" : settings.appSecret,
      redirectUri: settings.redirectUri || localFeishuRedirectUri()
    })
  );
}

export function defaultFeishuSettings(): FeishuSettingsState {
  return {
    appId: "",
    appSecret: "",
    redirectUri: localFeishuRedirectUri(),
    defaultCalendar: "primary",
    pastDays: 3650 * 2,
    futureDays: 3650,
    status: "not_connected"
  };
}

export function localFeishuRedirectUri() {
  if (typeof window === "undefined") return nativeFeishuRedirectUri;
  if (isTauriRuntime()) return nativeFeishuRedirectUri;
  return `${window.location.origin}/oauth/feishu/callback`;
}

export function isTauriRuntime() {
  return typeof window !== "undefined" && "__TAURI_IPC__" in (window as Window & { __TAURI_IPC__?: unknown });
}

export function feishuApiBaseUrl() {
  if (typeof window === "undefined") return undefined;
  return ["127.0.0.1", "localhost"].includes(window.location.hostname)
    ? "/feishu-openapi"
    : undefined;
}

export function feishuConfig(settings: FeishuSettingsState): FeishuConfig {
  const apiBaseUrl = feishuApiBaseUrl();
  return {
    appId: settings.appId,
    appSecret: settings.appSecret,
    redirectUri: settings.redirectUri || localFeishuRedirectUri(),
    ...(apiBaseUrl ? { apiBaseUrl } : {})
  };
}

export function loadFeishuToken(): TokenSet | null {
  const raw = localStorage.getItem(feishuTokenKey);
  if (!raw) return null;
  try {
    return JSON.parse(raw) as TokenSet;
  } catch {
    return null;
  }
}

export function saveFeishuToken(tokenSet: TokenSet) {
  localStorage.setItem(feishuTokenKey, JSON.stringify(tokenSet));
}

export function clearFeishuToken() {
  localStorage.removeItem(feishuTokenKey);
}

export function isTokenExpired(tokenSet: TokenSet) {
  if (!tokenSet.expiresAt) return false;
  return new Date(tokenSet.expiresAt).getTime() < Date.now() + 60_000;
}

export function canUseFeishuConnection(settings: FeishuSettingsState, tokenSet: TokenSet | null): tokenSet is TokenSet {
  return Boolean(settings.appId && settings.appSecret && tokenSet);
}
