/**
 * Tearing connections down at the hub: every Hub Subscription they hold
 * unsubscribed with the SMART access token, then their sockets closed. Every
 * unsubscription is the hub protocol's best-effort, bounded one (ADR 0011),
 * so tearing down never fails and settles within a few seconds however the
 * hub behaves.
 */

import type { Hub } from "./model";
import { unsubscribe } from "./protocol";

/**
 * What tearing a connection down takes: its hub, the SMART access token it
 * holds, the channel endpoint of every subscription of it still standing, and
 * how to close its socket once they are cancelled.
 */
export interface Teardown {
  hub: Hub;
  token: string;
  endpoints: readonly string[];
  close: () => void;
}

/**
 * Tears down each connection `teardowns` name: every endpoint unsubscribed,
 * side by side and once however many name it — with the token of the last
 * that does, the newest the tab was released — and only then every socket
 * closed.
 */
export async function tearDown(teardowns: readonly Teardown[]): Promise<void> {
  const byEndpoint = new Map<string, Teardown>();
  for (const teardown of teardowns) {
    for (const endpoint of teardown.endpoints) byEndpoint.set(endpoint, teardown);
  }
  await Promise.all(
    [...byEndpoint].map(([endpoint, { hub, token }]) => unsubscribe(hub, endpoint, token)),
  );
  for (const { close } of teardowns) close();
}
