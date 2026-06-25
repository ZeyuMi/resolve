import type { RealtimeChannel, SupabaseClient } from "@supabase/supabase-js";
import type {
  CalendarEventPayload,
  DecryptedCalendarEvent,
  DecryptedItem,
  DecryptedStrategyThread,
  ItemPayload,
  StrategyNotePayload,
  StrategyThreadPayload,
  TrackerItemPayload
} from "@resolve/core";
import { decryptPayload, encryptPayload } from "@resolve/crypto";
import type { ResolveState } from "./localRepository";
import type {
  ResolveRemoteChangeKind,
  SupabaseEncryptedCalendarEventRow,
  SupabaseEncryptedItemRow,
  SupabaseEncryptedStrategyThreadRow,
  SupabaseFeishuConnectionRow,
  SupabaseSyncStateRow
} from "./supabaseTypes";

const forbiddenPlaintextKeys = new Set([
  "title",
  "content",
  "notes",
  "description",
  "location",
  "attendees",
  "recurrence",
  "reminders",
  "feishuRaw",
  "currentHypothesis"
]);

function optional<T>(value: T | null | undefined): T | undefined {
  return value ?? undefined;
}

function assertNoPlaintextColumns(row: Record<string, unknown>, table: string) {
  for (const key of Object.keys(row)) {
    if (forbiddenPlaintextKeys.has(key)) {
      throw new Error(`Refusing to sync plaintext column "${key}" to ${table}. Encrypt it into encrypted_payload first.`);
    }
  }
}

async function throwIfSupabaseError<T>(request: PromiseLike<{ data: T; error: unknown }>) {
  const result = await request;
  if (result.error) {
    const error = result.error as { message?: string };
    throw new Error(error.message ?? "Supabase request failed.");
  }
  return result.data;
}

export class SupabaseEncryptedSync {
  constructor(
    private readonly client: SupabaseClient,
    private readonly userId: string,
    private readonly vaultKey: CryptoKey
  ) {}

  async pushState(state: ResolveState, options: { changedSince?: string } = {}) {
    const changedSince = options.changedSince?.trim();
    const changedAfter = <T extends { meta: { updatedAt: string } }>(items: T[]) =>
      changedSince ? items.filter((item) => item.meta.updatedAt > changedSince) : items;

    await Promise.all([
      this.pushItems(changedAfter(state.items)),
      this.pushStrategyThreads(changedAfter(state.strategyThreads)),
      this.pushCalendarEvents(changedAfter(state.calendarEvents))
    ]);
  }

  async pullState(options: { includeCalendarEvents?: boolean; changedSince?: string } = {}): Promise<ResolveState> {
    const includeCalendarEvents = options.includeCalendarEvents ?? true;
    const changedSince = options.changedSince?.trim();
    let itemQuery = this.client
      .from("resolve_items")
      .select("*")
      .eq("user_id", this.userId);
    if (changedSince) itemQuery = itemQuery.gt("updated_at", changedSince);
    let threadQuery = this.client
      .from("resolve_strategy_threads")
      .select("*")
      .eq("user_id", this.userId);
    if (changedSince) threadQuery = threadQuery.gt("updated_at", changedSince);
    let eventQuery = this.client
      .from("resolve_calendar_events")
      .select("*")
      .eq("user_id", this.userId)
      .eq("encryption_scheme", "vault_v1");
    if (changedSince) eventQuery = eventQuery.gt("updated_at", changedSince);

    const [itemRows, threadRows, eventRows] = await Promise.all([
      throwIfSupabaseError(itemQuery.order("updated_at", { ascending: false })) as Promise<SupabaseEncryptedItemRow[]>,
      throwIfSupabaseError(threadQuery.order("updated_at", { ascending: false })) as Promise<SupabaseEncryptedStrategyThreadRow[]>,
      includeCalendarEvents
        ? (throwIfSupabaseError(eventQuery.order("updated_at", { ascending: false })) as Promise<SupabaseEncryptedCalendarEventRow[]>)
        : Promise.resolve([] as SupabaseEncryptedCalendarEventRow[])
    ]);

    const [items, strategyThreads, calendarEvents] = await Promise.all([
      Promise.all(itemRows.map((row) => this.itemFromRow(row))),
      Promise.all(threadRows.map((row) => this.strategyThreadFromRow(row))),
      Promise.all(eventRows.map((row) => this.calendarEventFromRow(row)))
    ]);

    return { items, strategyThreads, calendarEvents };
  }

  async deleteRemoteItem(itemId: string) {
    const deletedAt = new Date().toISOString();
    await throwIfSupabaseError(
      this.client
        .from("resolve_items")
        .update({
          status: "deleted",
          deleted_at: deletedAt,
          updated_at: deletedAt
        })
        .eq("user_id", this.userId)
        .eq("id", itemId)
    );
  }

  async deleteRemoteItems(itemIds: string[]) {
    const ids = Array.from(new Set(itemIds)).filter(Boolean);
    if (!ids.length) return;
    const deletedAt = new Date().toISOString();
    await throwIfSupabaseError(
      this.client
        .from("resolve_items")
        .update({
          status: "deleted",
          deleted_at: deletedAt,
          updated_at: deletedAt
        })
        .eq("user_id", this.userId)
        .in("id", ids)
    );
  }

  async upsertSyncState(state: Omit<SupabaseSyncStateRow, "user_id">) {
    const row: SupabaseSyncStateRow = {
      ...state,
      user_id: this.userId
    };
    assertNoPlaintextColumns(row as unknown as Record<string, unknown>, "resolve_sync_states");
    await throwIfSupabaseError(
      this.client
        .from("resolve_sync_states")
        .upsert(row, { onConflict: "user_id,provider" })
    );
  }

  async pullSyncStates() {
    return throwIfSupabaseError(
      this.client
        .from("resolve_sync_states")
        .select("*")
        .eq("user_id", this.userId)
    ) as Promise<SupabaseSyncStateRow[]>;
  }

  async upsertFeishuConnection(connection: Omit<SupabaseFeishuConnectionRow, "user_id">) {
    const row: SupabaseFeishuConnectionRow = {
      ...connection,
      user_id: this.userId
    };
    assertNoPlaintextColumns(row as unknown as Record<string, unknown>, "resolve_feishu_connections");
    await throwIfSupabaseError(
      this.client
        .from("resolve_feishu_connections")
        .upsert(row, { onConflict: "user_id" })
    );
  }

  subscribeToRemoteChanges(onChange: (kind: ResolveRemoteChangeKind) => void) {
    const channel = this.client.channel(`resolve-sync:${this.userId}`);
    this.subscribeTable(channel, "resolve_items", "items", onChange);
    this.subscribeTable(channel, "resolve_strategy_threads", "strategyThreads", onChange);
    this.subscribeTable(channel, "resolve_calendar_events", "calendarEvents", onChange);
    this.subscribeTable(channel, "resolve_sync_states", "syncStates", onChange);
    this.subscribeTable(channel, "resolve_device_messages", "deviceMessages", onChange);
    channel.subscribe();
    return () => {
      void this.client.removeChannel(channel);
    };
  }

  private async pushItems(items: DecryptedItem[]) {
    if (!items.length) return;
    const rows = await Promise.all(items.map((item) => this.itemToRow(item)));
    rows.forEach((row) => assertNoPlaintextColumns(row as unknown as Record<string, unknown>, "resolve_items"));
    await throwIfSupabaseError(
      this.client
        .from("resolve_items")
        .upsert(rows, { onConflict: "user_id,id" })
    );
  }

  private async pushStrategyThreads(threads: DecryptedStrategyThread[]) {
    if (!threads.length) return;
    const rows = await Promise.all(threads.map((thread) => this.strategyThreadToRow(thread)));
    rows.forEach((row) =>
      assertNoPlaintextColumns(row as unknown as Record<string, unknown>, "resolve_strategy_threads")
    );
    await throwIfSupabaseError(
      this.client
        .from("resolve_strategy_threads")
        .upsert(rows, { onConflict: "user_id,id" })
    );
  }

  private async pushCalendarEvents(events: DecryptedCalendarEvent[]) {
    if (!events.length) return;
    const rows = await Promise.all(events.map((event) => this.calendarEventToRow(event)));
    rows.forEach((row) =>
      assertNoPlaintextColumns(row as unknown as Record<string, unknown>, "resolve_calendar_events")
    );
    await throwIfSupabaseError(
      this.client
        .from("resolve_calendar_events")
        .upsert(rows, { onConflict: "user_id,id" })
    );
  }

  private async itemToRow(item: DecryptedItem): Promise<SupabaseEncryptedItemRow> {
    const encrypted = await encryptPayload(item.payload, this.vaultKey);
    return {
      user_id: this.userId,
      id: item.meta.id,
      type: item.meta.type,
      status: item.meta.status,
      route: item.meta.route ?? null,
      source: item.meta.source,
      created_at: item.meta.createdAt,
      updated_at: item.meta.updatedAt,
      due_at: item.meta.dueAt ?? null,
      review_at: item.meta.reviewAt ?? null,
      strategy_thread_id: item.meta.strategyThreadId ?? null,
      parent_item_id: item.meta.parentItemId ?? null,
      source_item_id: item.meta.sourceItemId ?? null,
      deleted_at: item.meta.deletedAt ?? null,
      encrypted_payload: encrypted.encryptedPayload,
      payload_nonce: encrypted.payloadNonce,
      payload_version: encrypted.payloadVersion
    };
  }

  private async strategyThreadToRow(thread: DecryptedStrategyThread): Promise<SupabaseEncryptedStrategyThreadRow> {
    const encrypted = await encryptPayload(thread.payload, this.vaultKey);
    return {
      user_id: this.userId,
      id: thread.meta.id,
      status: thread.meta.status,
      created_at: thread.meta.createdAt,
      updated_at: thread.meta.updatedAt,
      next_review_at: thread.meta.nextReviewAt ?? null,
      encrypted_payload: encrypted.encryptedPayload,
      payload_nonce: encrypted.payloadNonce,
      payload_version: encrypted.payloadVersion
    };
  }

  private async calendarEventToRow(event: DecryptedCalendarEvent): Promise<SupabaseEncryptedCalendarEventRow> {
    const encrypted = await encryptPayload(event.payload, this.vaultKey);
    return {
      user_id: this.userId,
      id: event.meta.id,
      encryption_scheme: "vault_v1",
      provider: event.meta.provider,
      external_calendar_id: event.meta.externalCalendarId ?? null,
      external_event_id: event.meta.externalEventId ?? null,
      status: event.meta.status,
      starts_at: event.meta.startsAt,
      ends_at: event.meta.endsAt ?? null,
      is_all_day: Boolean(event.meta.isAllDay),
      created_at: event.meta.createdAt,
      updated_at: event.meta.updatedAt,
      remote_updated_at: event.meta.remoteUpdatedAt ?? null,
      last_synced_at: event.meta.lastSyncedAt ?? null,
      source_item_id: event.meta.sourceItemId ?? null,
      strategy_thread_id: event.meta.strategyThreadId ?? null,
      can_edit: event.meta.canEdit ?? null,
      can_delete: event.meta.canDelete ?? null,
      encrypted_payload: encrypted.encryptedPayload,
      payload_nonce: encrypted.payloadNonce,
      payload_version: encrypted.payloadVersion
    };
  }

  private async itemFromRow(row: SupabaseEncryptedItemRow): Promise<DecryptedItem> {
    const payload = await decryptPayload<ItemPayload | TrackerItemPayload | StrategyNotePayload>(
      {
        encryptedPayload: row.encrypted_payload,
        payloadNonce: row.payload_nonce,
        payloadVersion: row.payload_version
      },
      this.vaultKey
    );
    return {
      meta: {
        id: row.id,
        type: row.type as DecryptedItem["meta"]["type"],
        status: row.status as DecryptedItem["meta"]["status"],
        route: optional(row.route) as DecryptedItem["meta"]["route"],
        source: row.source as DecryptedItem["meta"]["source"],
        createdAt: row.created_at,
        updatedAt: row.updated_at,
        dueAt: optional(row.due_at),
        reviewAt: optional(row.review_at),
        strategyThreadId: optional(row.strategy_thread_id),
        parentItemId: optional(row.parent_item_id),
        sourceItemId: optional(row.source_item_id),
        deletedAt: optional(row.deleted_at) ?? (row.status === "deleted" ? row.updated_at : undefined),
        encryptedPayload: row.encrypted_payload,
        payloadNonce: row.payload_nonce,
        payloadVersion: row.payload_version
      },
      payload
    };
  }

  private async strategyThreadFromRow(row: SupabaseEncryptedStrategyThreadRow): Promise<DecryptedStrategyThread> {
    const payload = await decryptPayload<StrategyThreadPayload>(
      {
        encryptedPayload: row.encrypted_payload,
        payloadNonce: row.payload_nonce,
        payloadVersion: row.payload_version
      },
      this.vaultKey
    );
    return {
      meta: {
        id: row.id,
        status: row.status,
        createdAt: row.created_at,
        updatedAt: row.updated_at,
        nextReviewAt: optional(row.next_review_at),
        encryptedPayload: row.encrypted_payload,
        payloadNonce: row.payload_nonce,
        payloadVersion: row.payload_version
      },
      payload
    };
  }

  private async calendarEventFromRow(row: SupabaseEncryptedCalendarEventRow): Promise<DecryptedCalendarEvent> {
    const payload = await decryptPayload<CalendarEventPayload>(
      {
        encryptedPayload: row.encrypted_payload,
        payloadNonce: row.payload_nonce,
        payloadVersion: row.payload_version
      },
      this.vaultKey
    );
    return {
      meta: {
        id: row.id,
        provider: row.provider,
        externalCalendarId: optional(row.external_calendar_id),
        externalEventId: optional(row.external_event_id),
        status: row.status as DecryptedCalendarEvent["meta"]["status"],
        startsAt: row.starts_at,
        endsAt: optional(row.ends_at),
        isAllDay: row.is_all_day,
        createdAt: row.created_at,
        updatedAt: row.updated_at,
        remoteUpdatedAt: optional(row.remote_updated_at),
        lastSyncedAt: optional(row.last_synced_at),
        sourceItemId: optional(row.source_item_id),
        strategyThreadId: optional(row.strategy_thread_id),
        canEdit: optional(row.can_edit),
        canDelete: optional(row.can_delete),
        encryptedPayload: row.encrypted_payload,
        payloadNonce: row.payload_nonce,
        payloadVersion: row.payload_version
      },
      payload
    };
  }

  private subscribeTable(
    channel: RealtimeChannel,
    table: string,
    kind: ResolveRemoteChangeKind,
    onChange: (kind: ResolveRemoteChangeKind) => void
  ) {
    channel.on(
      "postgres_changes",
      {
        event: "*",
        schema: "public",
        table,
        filter: `user_id=eq.${this.userId}`
      },
      () => onChange(kind)
    );
  }
}
