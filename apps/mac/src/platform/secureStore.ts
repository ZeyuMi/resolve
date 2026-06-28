import { invoke } from "@tauri-apps/api/tauri";

export interface SecureStoreKey {
  service: string;
  account: string;
}

export function isNativeSecureStoreAvailable() {
  return typeof window !== "undefined" && "__TAURI_IPC__" in (window as Window & { __TAURI_IPC__?: unknown });
}

export async function secureStoreGet(key: SecureStoreKey) {
  if (!isNativeSecureStoreAvailable()) return null;
  return invoke<string | null>("secure_store_get", { key });
}

export async function secureStoreSet(key: SecureStoreKey, value: string) {
  if (!isNativeSecureStoreAvailable()) return;
  await invoke("secure_store_set", { key, value });
}

export async function secureStoreDelete(key: SecureStoreKey) {
  if (!isNativeSecureStoreAvailable()) return;
  await invoke("secure_store_delete", { key });
}
