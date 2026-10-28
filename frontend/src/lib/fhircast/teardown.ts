/**
 * Tearing a Hub Registration down: every subscription and Hub Token it holds
 * cancelled, then the registration deleted, then its sockets closed — in
 * order, unsubscribe → revoke → delete the registration → close, because a hub
 * refuses the Hub Tokens of a registration it no longer holds. Every request
 * is the hub protocol's best-effort, bounded one (ADR 0011), so tearing down
 * never fails and settles within a few seconds however the hub behaves.
 */

import type { Client, Granted, Stored } from "./model";
import { deleteRegistration, revoke, unsubscribe } from "./protocol";

/**
 * What tearing a Hub Registration down takes: the registration, every
 * subscription and Hub Token of it still standing, and how to close its
 * sockets once it is gone.
 */
export interface Teardown {
  client: Client;
  granted: readonly Granted[];
  close: () => void;
}

/**
 * One Hub Subscription and its Hub Token cancelled, best-effort and in order:
 * the subscription at its endpoint, when it has one, then the token revoked,
 * when the hub names a revocation endpoint — each unless `kept`, the one
 * staying live, shares it.
 */
export async function cancel(
  client: Client,
  { endpoint, hubToken }: Granted,
  kept: Granted | null = null,
): Promise<void> {
  if (endpoint !== null && (kept === null || endpoint !== kept.endpoint)) {
    await unsubscribe(client.hub, endpoint, hubToken);
  }
  if (client.revocation !== null && (kept === null || hubToken !== kept.hubToken)) {
    await revoke(client.revocation, client, hubToken);
  }
}

/**
 * Tears each registration `teardowns` name down, side by side: every
 * subscription and Hub Token it holds cancelled, each once however many
 * teardowns name it, then the registration deleted when the hub said how, and
 * only then its sockets closed. Deleting comes last because a hub refuses the
 * Hub Tokens of a registration it no longer holds.
 */
export async function tearDown(teardowns: readonly Teardown[]): Promise<void> {
  const byClient = new Map<string, Teardown>();
  for (const teardown of teardowns) {
    const same = byClient.get(teardown.client.clientId);
    byClient.set(
      teardown.client.clientId,
      same === undefined
        ? teardown
        : {
            client: same.client,
            close: () => {
              same.close();
              teardown.close();
            },
            granted: [...same.granted, ...teardown.granted],
          },
    );
  }
  await Promise.all(
    [...byClient.values()].map(async ({ client, close, granted }) => {
      const distinct = new Map(granted.map((each) => [`${each.endpoint} ${each.hubToken}`, each]));
      for (const each of distinct.values()) await cancel(client, each);
      if (client.management !== null) await deleteRegistration(client.management);
      close();
    }),
  );
}

/** What tearing down what the tab kept takes: its subscription, if any; no socket. */
export function teardownOf({ client, subscription }: Stored): Teardown {
  return {
    client,
    close: () => undefined,
    granted: subscription === null ? [] : [subscription],
  };
}
