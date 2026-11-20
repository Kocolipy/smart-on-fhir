/**
 * The key module's `ClientStore` seam, in memory, for the suites that hand a
 * store in: its lock run as the fake browser profile's are
 * (`../fake-browser-profile.testHelpers.ts`).
 */
import { fakeLocks } from "../fake-browser-profile.testHelpers";
import type { ClientStore, DynamicClient } from "./client-key";

/**
 * A {@link ClientStore} holding its clients in memory, one task at a time
 * under each audience's lock.
 */
export function memoryClientStore(): ClientStore & { kept: Map<string, DynamicClient> } {
  const kept = new Map<string, DynamicClient>();
  const locks = fakeLocks();
  return {
    exclusively: (audience, task) => locks.request(audience, task),
    keep: (audience, client) => {
      kept.set(audience, client);
      return Promise.resolve();
    },
    kept,
    read: (audience) => Promise.resolve(kept.get(audience) ?? null),
  };
}
