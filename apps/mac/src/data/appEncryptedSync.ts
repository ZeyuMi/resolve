import { createClient, type SupabaseClient } from "@supabase/supabase-js";
import { deriveVaultKeyFromPhrase } from "@resolve/crypto";
import { SupabaseEncryptedSync, type ResolveState, type ResolveRemoteChangeKind } from "@resolve/sync";
import type { BackendSettingsState, BackendSession } from "./resolveBackend";

function base64UrlDecode(value: string) {
  const normalized = value.replace(/-/g, "+").replace(/_/g, "/");
  const padded = normalized.padEnd(Math.ceil(normalized.length / 4) * 4, "=");
  return atob(padded);
}

export function userIdFromAccessToken(accessToken: string) {
  const [, payload] = accessToken.split(".");
  if (!payload) throw new Error("Invalid backend session.");
  const json = JSON.parse(base64UrlDecode(payload)) as { sub?: string };
  if (!json.sub) throw new Error("Backend session has no user id.");
  return json.sub;
}

export function syncSaltForEmail(email: string) {
  return `resolve:${email.trim().toLowerCase()}:vault:v1`;
}

function shouldSyncCalendarEvent(event: ResolveState["calendarEvents"][number]) {
  return event.meta.provider !== "feishu" || !event.meta.externalEventId;
}

function newestByUpdatedAt<T extends { meta: { id: string; updatedAt: string } }>(left: T[], right: T[]) {
  const map = new Map<string, T>();
  [...left, ...right].forEach((item) => {
    const existing = map.get(item.meta.id);
    if (!existing || item.meta.updatedAt.localeCompare(existing.meta.updatedAt) >= 0) {
      map.set(item.meta.id, item);
    }
  });
  return Array.from(map.values());
}

export function mergeEncryptedRemoteState(local: ResolveState, remote: ResolveState): ResolveState {
  return {
    items: newestByUpdatedAt(local.items, remote.items).filter((item) => !item.meta.deletedAt),
    strategyThreads: newestByUpdatedAt(local.strategyThreads, remote.strategyThreads),
    calendarEvents: [
      ...local.calendarEvents.filter((event) => event.meta.provider === "feishu" && event.meta.externalEventId),
      ...newestByUpdatedAt(
        local.calendarEvents.filter(shouldSyncCalendarEvent),
        remote.calendarEvents
      )
    ]
  };
}

export class ResolveAppEncryptedSync {
  private readonly client: SupabaseClient;
  private readonly sync: SupabaseEncryptedSync;

  private constructor(client: SupabaseClient, sync: SupabaseEncryptedSync) {
    this.client = client;
    this.sync = sync;
  }

  static async create(settings: BackendSettingsState, session: BackendSession, syncSecret: string) {
    const userId = userIdFromAccessToken(session.accessToken);
    const vaultKey = await deriveVaultKeyFromPhrase(syncSecret, syncSaltForEmail(settings.email));
    const client = createClient(settings.supabaseUrl, settings.publishableKey, {
      auth: {
        persistSession: false,
        autoRefreshToken: false
      },
      global: {
        headers: {
          authorization: `Bearer ${session.accessToken}`
        }
      }
    });
    client.realtime.setAuth(session.accessToken);
    return new ResolveAppEncryptedSync(client, new SupabaseEncryptedSync(client, userId, vaultKey));
  }

  async pull() {
    return this.sync.pullState();
  }

  async push(state: ResolveState) {
    await this.sync.pushState({
      ...state,
      calendarEvents: state.calendarEvents.filter(shouldSyncCalendarEvent)
    });
  }

  subscribe(onChange: (kind: ResolveRemoteChangeKind) => void) {
    return this.sync.subscribeToRemoteChanges(onChange);
  }

  async dispose() {
    await this.client.removeAllChannels();
  }
}
