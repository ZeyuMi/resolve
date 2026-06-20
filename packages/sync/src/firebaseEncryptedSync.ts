import type { FirebaseApp } from "firebase/app";
import {
  collection,
  deleteDoc,
  doc,
  getDocs,
  getFirestore,
  setDoc
} from "firebase/firestore";
import type {
  DecryptedCalendarEvent,
  DecryptedItem,
  DecryptedStrategyThread,
  ItemPayload,
  StrategyThreadPayload,
  CalendarEventPayload
} from "@resolve/core";
import { decryptPayload, encryptPayload } from "@resolve/crypto";
import type {
  FirestoreEncryptedCalendarEvent,
  FirestoreEncryptedItem,
  FirestoreEncryptedStrategyThread
} from "./firestoreTypes";
import type { ResolveState } from "./localRepository";

export class FirebaseEncryptedSync {
  private readonly db;

  constructor(
    app: FirebaseApp,
    private readonly userId: string,
    private readonly vaultKey: CryptoKey
  ) {
    this.db = getFirestore(app);
  }

  async pushState(state: ResolveState) {
    await Promise.all([
      ...state.items.map((item) => this.pushItem(item)),
      ...state.strategyThreads.map((thread) => this.pushStrategyThread(thread)),
      ...state.calendarEvents.map((event) => this.pushCalendarEvent(event))
    ]);
  }

  async pullState(): Promise<ResolveState> {
    const [itemsSnapshot, threadsSnapshot, calendarSnapshot] = await Promise.all([
      getDocs(collection(this.db, "users", this.userId, "items")),
      getDocs(collection(this.db, "users", this.userId, "strategyThreads")),
      getDocs(collection(this.db, "users", this.userId, "calendarEvents"))
    ]);

    const items = await Promise.all(
      itemsSnapshot.docs.map(async (snapshot) => {
        const data = snapshot.data() as FirestoreEncryptedItem;
        const payload = await decryptPayload<ItemPayload>(data, this.vaultKey);
        return {
          meta: {
            id: data.id,
            type: data.type as DecryptedItem["meta"]["type"],
            status: data.status as DecryptedItem["meta"]["status"],
            source: data.source as DecryptedItem["meta"]["source"],
            createdAt: data.createdAt,
            updatedAt: data.updatedAt,
            dueAt: data.dueAt,
            reviewAt: data.reviewAt,
            strategyThreadId: data.strategyThreadId,
            parentItemId: data.parentItemId,
            sourceItemId: data.sourceItemId,
            encryptedPayload: data.encryptedPayload,
            payloadNonce: data.payloadNonce,
            payloadVersion: data.payloadVersion,
            deletedAt: data.deletedAt
          },
          payload
        } satisfies DecryptedItem;
      })
    );

    const strategyThreads = await Promise.all(
      threadsSnapshot.docs.map(async (snapshot) => {
        const data = snapshot.data() as FirestoreEncryptedStrategyThread;
        const payload = await decryptPayload<StrategyThreadPayload>(data, this.vaultKey);
        return {
          meta: {
            id: data.id,
            status: data.status,
            createdAt: data.createdAt,
            updatedAt: data.updatedAt,
            nextReviewAt: data.nextReviewAt,
            encryptedPayload: data.encryptedPayload,
            payloadNonce: data.payloadNonce,
            payloadVersion: data.payloadVersion
          },
          payload
        } satisfies DecryptedStrategyThread;
      })
    );

    const calendarEvents = await Promise.all(
      calendarSnapshot.docs.map(async (snapshot) => {
        const data = snapshot.data() as FirestoreEncryptedCalendarEvent;
        const payload = await decryptPayload<CalendarEventPayload>(data, this.vaultKey);
        return {
          meta: {
            id: data.id,
            provider: data.provider,
            externalCalendarId: data.externalCalendarId,
            externalEventId: data.externalEventId,
            status: data.status as DecryptedCalendarEvent["meta"]["status"],
            startsAt: data.startsAt,
            endsAt: data.endsAt,
            isAllDay: data.isAllDay,
            createdAt: data.createdAt,
            updatedAt: data.updatedAt,
            remoteUpdatedAt: data.remoteUpdatedAt,
            lastSyncedAt: data.lastSyncedAt,
            sourceItemId: data.sourceItemId,
            strategyThreadId: data.strategyThreadId,
            encryptedPayload: data.encryptedPayload,
            payloadNonce: data.payloadNonce,
            payloadVersion: data.payloadVersion
          },
          payload
        } satisfies DecryptedCalendarEvent;
      })
    );

    return { items, strategyThreads, calendarEvents };
  }

  async deleteRemoteItem(itemId: string) {
    await deleteDoc(doc(this.db, "users", this.userId, "items", itemId));
  }

  private async pushItem(item: DecryptedItem) {
    const encrypted = await encryptPayload(item.payload, this.vaultKey);
    const remote: FirestoreEncryptedItem = {
      id: item.meta.id,
      userId: this.userId,
      type: item.meta.type,
      status: item.meta.status,
      source: item.meta.source,
      createdAt: item.meta.createdAt,
      updatedAt: item.meta.updatedAt,
      dueAt: item.meta.dueAt,
      reviewAt: item.meta.reviewAt,
      strategyThreadId: item.meta.strategyThreadId,
      parentItemId: item.meta.parentItemId,
      sourceItemId: item.meta.sourceItemId,
      deletedAt: item.meta.deletedAt,
      ...encrypted
    };
    await setDoc(doc(this.db, "users", this.userId, "items", item.meta.id), remote, { merge: true });
  }

  private async pushStrategyThread(thread: DecryptedStrategyThread) {
    const encrypted = await encryptPayload(thread.payload, this.vaultKey);
    const remote: FirestoreEncryptedStrategyThread = {
      id: thread.meta.id,
      userId: this.userId,
      status: thread.meta.status,
      createdAt: thread.meta.createdAt,
      updatedAt: thread.meta.updatedAt,
      nextReviewAt: thread.meta.nextReviewAt,
      ...encrypted
    };
    await setDoc(doc(this.db, "users", this.userId, "strategyThreads", thread.meta.id), remote, { merge: true });
  }

  private async pushCalendarEvent(event: DecryptedCalendarEvent) {
    const encrypted = await encryptPayload(event.payload, this.vaultKey);
    const remote: FirestoreEncryptedCalendarEvent = {
      id: event.meta.id,
      userId: this.userId,
      provider: event.meta.provider,
      externalCalendarId: event.meta.externalCalendarId,
      externalEventId: event.meta.externalEventId,
      status: event.meta.status,
      startsAt: event.meta.startsAt,
      endsAt: event.meta.endsAt,
      isAllDay: event.meta.isAllDay,
      createdAt: event.meta.createdAt,
      updatedAt: event.meta.updatedAt,
      remoteUpdatedAt: event.meta.remoteUpdatedAt,
      lastSyncedAt: event.meta.lastSyncedAt,
      sourceItemId: event.meta.sourceItemId,
      strategyThreadId: event.meta.strategyThreadId,
      ...encrypted
    };
    await setDoc(doc(this.db, "users", this.userId, "calendarEvents", event.meta.id), remote, { merge: true });
  }
}
