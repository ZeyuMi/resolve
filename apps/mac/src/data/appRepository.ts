import { BrowserLocalRepository, type ResolveState } from "@resolve/sync";

export interface AppRepository {
  load(): ResolveState;
  save(state: ResolveState): void;
}

export function createAppRepository(): AppRepository {
  return new BrowserLocalRepository();
}
