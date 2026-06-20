export interface SupabaseEncryptedItemRow {
  user_id: string;
  id: string;
  type: string;
  status: string;
  route?: string | null;
  source: string;
  created_at: string;
  updated_at: string;
  due_at?: string | null;
  review_at?: string | null;
  strategy_thread_id?: string | null;
  parent_item_id?: string | null;
  source_item_id?: string | null;
  deleted_at?: string | null;
  encrypted_payload: string;
  payload_nonce: string;
  payload_version: number;
}

export interface SupabaseEncryptedStrategyThreadRow {
  user_id: string;
  id: string;
  status: "active" | "quiet" | "archived";
  created_at: string;
  updated_at: string;
  next_review_at?: string | null;
  encrypted_payload: string;
  payload_nonce: string;
  payload_version: number;
}

export interface SupabaseEncryptedCalendarEventRow {
  user_id: string;
  id: string;
  provider: "feishu" | "local";
  external_calendar_id?: string | null;
  external_event_id?: string | null;
  status: string;
  starts_at: string;
  ends_at?: string | null;
  is_all_day: boolean;
  created_at: string;
  updated_at: string;
  remote_updated_at?: string | null;
  last_synced_at?: string | null;
  source_item_id?: string | null;
  strategy_thread_id?: string | null;
  can_edit?: boolean | null;
  can_delete?: boolean | null;
  encrypted_payload: string;
  payload_nonce: string;
  payload_version: number;
}

export interface SupabaseSyncStateRow {
  user_id: string;
  provider: "feishu";
  status: "ok" | "needs_auth" | "error" | "disabled";
  last_full_sync_at?: string | null;
  last_incremental_sync_at?: string | null;
  encrypted_sync_token?: string | null;
  sync_token_nonce?: string | null;
  encrypted_error?: string | null;
  error_nonce?: string | null;
  updated_at: string;
}

export interface SupabaseFeishuConnectionRow {
  user_id: string;
  mode: "client_e2ee" | "server_connector_opt_in";
  status: "not_connected" | "connected" | "needs_auth" | "permission_error" | "error";
  encrypted_config?: string | null;
  config_nonce?: string | null;
  encrypted_token_vault?: string | null;
  token_vault_nonce?: string | null;
  updated_at: string;
}

export type ResolveRealtimeTable =
  | "resolve_items"
  | "resolve_strategy_threads"
  | "resolve_calendar_events"
  | "resolve_sync_states"
  | "resolve_device_messages";

export type ResolveRemoteChangeKind =
  | "items"
  | "strategyThreads"
  | "calendarEvents"
  | "syncStates"
  | "deviceMessages";
