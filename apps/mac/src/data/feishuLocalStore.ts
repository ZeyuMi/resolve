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

export function loadFeishuSettings(): FeishuSettingsState {
  const defaults = defaultFeishuSettings();
  const raw = localStorage.getItem(feishuSettingsKey);
  if (!raw) return defaults;
  return { ...defaults, ...JSON.parse(raw) };
}

export function saveFeishuSettings(settings: FeishuSettingsState) {
  localStorage.setItem(feishuSettingsKey, JSON.stringify(settings));
}

export function defaultFeishuSettings(): FeishuSettingsState {
  return {
    appId: "",
    appSecret: "",
    redirectUri: localFeishuRedirectUri(),
    defaultCalendar: "primary",
    pastDays: 14,
    futureDays: 90,
    status: "not_connected"
  };
}

export function localFeishuRedirectUri() {
  if (typeof window === "undefined") return "http://127.0.0.1:5173/oauth/feishu/callback";
  return `${window.location.origin}/oauth/feishu/callback`;
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
    redirectUri: settings.redirectUri,
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
