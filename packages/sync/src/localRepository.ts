import { createSampleData, type DecryptedCalendarEvent, type DecryptedItem, type DecryptedNote, type DecryptedStrategyThread } from "@resolve/core";

export interface ResolveState {
  items: DecryptedItem[];
  strategyThreads: DecryptedStrategyThread[];
  calendarEvents: DecryptedCalendarEvent[];
  notes: DecryptedNote[];
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
    const parsed = JSON.parse(raw) as Partial<ResolveState>;
    return {
      items: parsed.items ?? [],
      strategyThreads: parsed.strategyThreads ?? [],
      calendarEvents: parsed.calendarEvents ?? [],
      notes: parsed.notes ?? []
    };
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
