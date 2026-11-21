/**
 * The tab's standing Hub Subscriptions: every one the hub accepted that the
 * tab has yet to cancel, whichever of the session's connections made or
 * resumed it, and the socket last confirmed on each. It holds the rule that
 * every Hub Subscription the hub accepted is cancelled exactly once, and that
 * the session's FHIRcast teardown settles within its 3-second best-effort
 * bound (ADR 0011), retirements in flight included.
 *
 * The session (`session.ts`) holds one for each session's FHIRcast, and hands
 * it to every connection it makes (`connection.ts`). A connection reports to
 * it each hub token it obtains, each subscription the hub accepts, each socket
 * the hub confirms, and each subscription it moves off; the session ends it.
 * It reaches the hub through the hub protocol alone.
 */

import type { Hub } from "./model";
import type { Channel } from "./notifications";
import { type HubToken, UNSUBSCRIBE_TIMEOUT_MS, unsubscribe } from "./protocol";

/** A Hub Subscription still standing: its hub, and the socket last confirmed on it. */
interface Standing {
  hub: Hub;
  socket: Channel | null;
}

/** `work`, given up once {@link UNSUBSCRIBE_TIMEOUT_MS} have passed, whichever is first. */
async function withinBound(work: Promise<unknown>): Promise<void> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  const givenUp = new Promise<void>((settle) => {
    timer = setTimeout(settle, UNSUBSCRIBE_TIMEOUT_MS);
  });
  await Promise.race([work, givenUp]);
  clearTimeout(timer);
}

/**
 * The tab's standing Hub Subscriptions, by channel endpoint: a subscription
 * two connections hold — one a Retry's connection resumed — stands once.
 * Each is cancelled once, with the newest hub token the tab obtained at its
 * hub while it is unexpired — one whose lifetime the hub did not state never
 * expires — or else a fresh one from `freshHubToken`: when a connection
 * moves off it ({@link retire}), or else when the session's FHIRcast ends
 * ({@link end}). One cancelled stands no more, whatever the hub answered.
 */
export class StandingSubscriptions {
  private readonly standing = new Map<string, Standing>();
  /** The subscriptions retired and still being unsubscribed: ending waits for them. */
  private readonly retiring = new Set<Promise<void>>();
  /** The newest hub token the tab obtained at each hub, by its URL, across every connection: held in memory alone. */
  private readonly newest = new Map<string, HubToken>();

  /**
   * @param freshHubToken a hub token at `hub` for the dynamic client the
   *   profile kept, registering and forgetting none: what a cancellation
   *   obtains once the newest has expired.
   */
  constructor(private readonly freshHubToken: (hub: Hub) => Promise<HubToken>) {}

  /** `token`, which a connection obtained at `hub`, as the newest there. */
  obtained(hub: Hub, token: HubToken) {
    this.newest.set(hub.hubUrl, token);
  }

  /** The subscription at `endpoint`, which the hub accepted at `hub`, standing. */
  accepted(hub: Hub, endpoint: string) {
    if (!this.standing.has(endpoint)) this.standing.set(endpoint, { hub, socket: null });
  }

  /** `socket`, which the hub confirmed the standing subscription at `endpoint` on, as its socket. */
  confirmed(endpoint: string, socket: Channel) {
    const subscription = this.standing.get(endpoint);
    if (subscription !== undefined) subscription.socket = socket;
  }

  /**
   * The standing subscription at `endpoint` cancelled now, best-effort and
   * never awaited: a connection moved off it, and closed its socket. Nothing
   * for one already cancelled.
   */
  retire(endpoint: string) {
    const subscription = this.standing.get(endpoint);
    if (subscription === undefined) return;
    this.standing.delete(endpoint);
    const { hub } = subscription;
    const cancelling = this.cancel(hub, endpoint, this.token(hub)).then(() => {
      this.retiring.delete(cancelling);
    });
    this.retiring.add(cancelling);
  }

  /**
   * Every standing subscription cancelled, side by side, with one hub token
   * for each hub, then every socket confirmed on one closed: settled once those
   * cancellations and every retirement still in flight are done, or
   * {@link UNSUBSCRIBE_TIMEOUT_MS} on, whichever is first, a fresh hub token
   * included (ADR 0011). Never rejects. A subscription the hub accepts later
   * stands until it is retired.
   */
  async end(): Promise<void> {
    const ending = [...this.standing];
    this.standing.clear();
    const tokens = new Map<string, Promise<string>>();
    const tokenAt = (hub: Hub): Promise<string> => {
      const token = tokens.get(hub.hubUrl) ?? this.token(hub);
      tokens.set(hub.hubUrl, token);
      return token;
    };
    await withinBound(
      Promise.all([
        ...this.retiring,
        ...ending.map(([endpoint, { hub }]) => this.cancel(hub, endpoint, tokenAt(hub))),
      ]),
    );
    for (const [, { socket }] of ending) socket?.close();
  }

  /** `endpoint` unsubscribed at `hub` with `token`; nothing, when no hub token is to be had. */
  private async cancel(hub: Hub, endpoint: string, token: Promise<string>): Promise<void> {
    let bearer: string;
    try {
      bearer = await token;
    } catch {
      // No hub token to unsubscribe with: the lease ends the subscription at the hub.
      return;
    }
    await unsubscribe(hub, endpoint, bearer);
  }

  /** The newest hub token obtained at `hub` while it is unexpired; otherwise a fresh one there. */
  private async token(hub: Hub): Promise<string> {
    const newest = this.newest.get(hub.hubUrl);
    if (newest !== undefined && (newest.expiresAt === null || newest.expiresAt > Date.now())) {
      return newest.token;
    }
    return (await this.freshHubToken(hub)).token;
  }
}
