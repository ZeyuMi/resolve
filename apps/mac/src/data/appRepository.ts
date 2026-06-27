import type { ResolveStateRepository } from "@resolve/core";
import { BrowserLocalRepository } from "@resolve/sync";

export function createAppRepository(): ResolveStateRepository {
  return new BrowserLocalRepository();
}
