import { invoke } from "@tauri-apps/api/tauri";
import type { HttpVerb } from "@tauri-apps/api/http";
import type { FeishuSettingsState } from "../data/feishuLocalStore";
import { isTauriRuntime } from "../data/feishuLocalStore";

const keychainService = "ai.tiiny.resolve.feishu";

interface NativeFeishuOAuthResult {
  code: string;
  state: string;
  redirect_uri: string;
}

interface SecureStoreKey {
  service: string;
  account: string;
}

interface NativeNoteFileResult {
  path: string;
  content: string;
}

type ResolveNativeWindow = Window & {
  __resolveNativeHttpBridgeInstalled?: boolean;
};

function key(account: string): SecureStoreKey {
  return { service: keychainService, account };
}

export async function runNativeFeishuOAuth(appId: string, scopes: string[]) {
  if (!isTauriRuntime()) {
    throw new Error("Native Feishu OAuth is only available in the Resolve Mac app.");
  }
  return invoke<NativeFeishuOAuthResult>("run_feishu_oauth", {
    appId,
    scopes
  });
}

export async function installNativeHttpBridge() {
  const nativeWindow = window as ResolveNativeWindow;
  if (!isTauriRuntime() || nativeWindow.__resolveNativeHttpBridgeInstalled) return;

  const { fetch: tauriFetch, Body, ResponseType } = await import("@tauri-apps/api/http");
  const originalFetch = window.fetch.bind(window);
  nativeWindow.__resolveNativeHttpBridgeInstalled = true;

  window.fetch = async (input, init) => {
    const url = typeof input === "string" ? input : input instanceof Request ? input.url : String(input);
    if (!url.startsWith("https://open.feishu.cn/open-apis")) {
      return originalFetch(input, init);
    }

    const headers = new Headers(init?.headers);
    const headerRecord: Record<string, string> = {};
    headers.forEach((value, key) => {
      headerRecord[key] = value;
    });

    const body = init?.body
      ? Body.text(typeof init.body === "string" ? init.body : String(init.body))
      : undefined;
    const response = await tauriFetch<string>(url, {
      method: (init?.method ?? "GET").toUpperCase() as HttpVerb,
      headers: headerRecord,
      body,
      responseType: ResponseType.Text
    });

    return new Response(response.data ?? "", {
      status: response.status,
      headers: {
        "content-type": String(response.headers["content-type"] ?? "application/json")
      }
    });
  };
}

export async function loadSecureFeishuCredentials() {
  if (!isTauriRuntime()) return {};
  const [appId, appSecret] = await Promise.all([
    invoke<string | null>("secure_store_get", { key: key("app_id") }),
    invoke<string | null>("secure_store_get", { key: key("app_secret") })
  ]);
  return {
    ...(appId ? { appId } : {}),
    ...(appSecret ? { appSecret } : {})
  } satisfies Partial<FeishuSettingsState>;
}

export async function saveSecureFeishuCredentials(settings: FeishuSettingsState) {
  if (!isTauriRuntime()) return;
  const writes: Array<Promise<unknown>> = [];
  if (settings.appId) {
    writes.push(invoke("secure_store_set", { key: key("app_id"), value: settings.appId }));
  }
  if (settings.appSecret) {
    writes.push(invoke("secure_store_set", { key: key("app_secret"), value: settings.appSecret }));
  }
  await Promise.all(writes);
}

export async function clearSecureFeishuCredentials() {
  if (!isTauriRuntime()) return;
  await Promise.all([
    invoke("secure_store_delete", { key: key("app_id") }),
    invoke("secure_store_delete", { key: key("app_secret") })
  ]);
}

export async function loadSecureSyncSecret() {
  if (!isTauriRuntime()) return localStorage.getItem("resolve:sync-secret:v1");
  return invoke<string | null>("secure_store_get", { key: key("sync_secret") });
}

export async function saveSecureSyncSecret(secret: string) {
  if (!secret) return;
  if (!isTauriRuntime()) {
    localStorage.setItem("resolve:sync-secret:v1", secret);
    return;
  }
  await invoke("secure_store_set", { key: key("sync_secret"), value: secret });
}

export async function clearSecureSyncSecret() {
  if (!isTauriRuntime()) {
    localStorage.removeItem("resolve:sync-secret:v1");
    return;
  }
  await invoke("secure_store_delete", { key: key("sync_secret") });
}

export async function ensureResolveVaultRoot() {
  if (!isTauriRuntime()) return "Resolve Vault";
  return invoke<string>("resolve_vault_root");
}

export async function readNoteFile(path: string) {
  if (!isTauriRuntime()) {
    return {
      path,
      content: localStorage.getItem(`resolve:note-file:${path}`) ?? ""
    };
  }
  return invoke<NativeNoteFileResult>("note_file_read", { path });
}

export async function writeNoteFile(path: string, content: string) {
  if (!isTauriRuntime()) {
    localStorage.setItem(`resolve:note-file:${path}`, content);
    return { path, content };
  }
  return invoke<NativeNoteFileResult>("note_file_write", { input: { path, content } });
}
