import { createSampleData, type DecryptedCalendarEvent, type DecryptedItem, type DecryptedStrategyThread } from "@resolve/core";

export interface ResolveState {
  items: DecryptedItem[];
  strategyThreads: DecryptedStrategyThread[];
  calendarEvents: DecryptedCalendarEvent[];
}

const storageKey = "resolve:v1";

export class BrowserLocalRepository {
  load(): ResolveState {
    const raw = localStorage.getItem(storageKey);
    if (!raw) {
      const sample = createSampleData();
      this.save(sample);
      return sample;
    }
    return JSON.parse(raw) as ResolveState;
  }

  save(state: ResolveState) {
    localStorage.setItem(storageKey, JSON.stringify(state));
  }

  resetToSample() {
    const sample = createSampleData();
    this.save(sample);
    return sample;
  }
}
