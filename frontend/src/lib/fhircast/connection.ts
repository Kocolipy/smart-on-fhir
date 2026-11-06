/**
 * The renewing connection: one connection to a hub, made with the SMART
 * access token it holds in memory for its whole life — the subscription and
 * its socket, renewal before the lease runs out, reconnecting with a doubling
 * backoff when a socket drops or a renewal fails, one live Hub Subscription
 * at a time, and resuming the stored subscription after a reload.
 *
 * It holds no module state and reaches nothing itself: the hub protocol, the
 * socket, the tab's storage and the clock are the {@link ConnectionOperations}
 * it is handed.
 */

import type { Channel, Opened } from "./notifications";
import type { FhircastHandlers, Hub, PatientEvent, Subscription } from "./model";
import type { Teardown } from "./teardown";

/** The wait before the first retry once a connection is lost; each later one doubles it. */
const RECONNECT_BACKOFF_MS = 1_000;

/** How many retries a lost connection gets before it is given up. */
const RECONNECT_ATTEMPTS = 5;

/** The longest wait a browser timer holds: anything longer fires at once. */
const LONGEST_TIMER_MS = 2_147_483_647;

/**
 * The soonest a renewal starts after the lease it renews was granted. Only a
 * lease under 65 s brings 60 s before its end this close: one of a minute or
 * less has no such time left at all, and renewing it the moment it is
 * granted would subscribe again without pause.
 */
const SOONEST_RENEWAL_MS = 5_000;

/**
 * When to renew a lease of `seconds`, in milliseconds from when it was
 * granted: at 80% of it or 60 s before it ends, whichever is sooner — though
 * never sooner than {@link SOONEST_RENEWAL_MS}.
 */
function renewalDelay(seconds: number): number {
  return Math.max(1000 * Math.min(seconds * 0.8, seconds - 60), SOONEST_RENEWAL_MS);
}

type Timer = ReturnType<typeof setTimeout>;

/** The time, and the timers, a connection renews and reconnects by. */
export interface Clock {
  /** Now, on `Date.now()`'s clock. */
  now: () => number;
  /** Runs `run` once `ms` has passed. */
  after: (ms: number, run: () => void) => Timer;
  /** Stops `timer`, if it has yet to run. */
  cancel: (timer: Timer | undefined) => void;
}

/** The browser's clock and timers. */
export const systemClock: Clock = {
  after: (ms, run) => setTimeout(run, ms),
  cancel: (timer) => clearTimeout(timer),
  now: () => Date.now(),
};

/** What a connection does through others: the hub protocol, the socket, the tab's storage, the clock. */
export interface ConnectionOperations {
  /** The subscription on `hub` with the SMART access token `token`; the channel endpoint the hub answered. */
  subscribe: (hub: Hub, token: string) => Promise<string>;
  /** The socket at `endpoint`, as the hub answered it. */
  open: (
    endpoint: string,
    topic: string,
    onEvent: (event: PatientEvent) => void,
    onDropped: () => void,
  ) => Promise<Opened>;
  /** The subscription at `endpoint` cancelled with `token`, best-effort and bounded. */
  unsubscribe: (hub: Hub, endpoint: string, token: string) => Promise<void>;
  /** Called with each subscription a socket is confirmed on: the tab keeps it, if it keeps this connection. */
  keep: (subscription: Subscription) => void;
  clock: Clock;
}

/**
 * One connection to `hub`, every request it makes with the SMART access token
 * `token`: the subscription and its socket, then renewal before the lease
 * runs out, and reconnecting when a socket drops or a renewal fails. A socket
 * that drops while a renewal is under way is left to the renewal. One Hub
 * Subscription is live at a time: once a new one's socket is confirmed, the
 * one before is unsubscribed. A subscription whose socket was never confirmed
 * — a renewal or retry that failed — is kept, and unsubscribed when the
 * connection is torn down.
 */
export class Connection {
  private channel: Channel | null = null;
  /**
   * The subscription a socket was last confirmed on, which a retry reopens
   * and the tab keeps: the stored one, for a connection resumed; otherwise
   * `null` until the first is confirmed.
   */
  private current: Subscription | null;
  /**
   * The channel endpoint the hub last answered a subscription with, until a
   * socket is confirmed on it and it becomes {@link current}: `null` until the
   * hub answers one, and again once it is current. Still here when the
   * connection subscribes again, it moves to {@link abandoned}.
   */
  private latest: string | null = null;
  /**
   * Every channel endpoint the hub answered whose socket was never confirmed
   * and that a later subscription replaced as {@link latest}: a renewal or
   * retry that failed. Never unsubscribed before {@link teardown} hands them
   * over, so no subscription the hub accepted goes uncancelled.
   */
  private readonly abandoned: string[] = [];
  /** When the lease is due for renewal, on `Date.now()`'s clock. */
  private renewsAt = Infinity;
  private timer: Timer | undefined;
  private renewing = false;
  private closed = false;
  /** Whether a retry subscribes anew, rather than reopening the endpoint. */
  private resubscribing = false;
  /** The subscriptions moved off, being unsubscribed in turn: tearing down waits for them. */
  private retiring: Promise<void> = Promise.resolve();

  constructor(
    private readonly hub: Hub,
    private readonly token: string,
    private readonly handlers: Required<FhircastHandlers>,
    private readonly operations: ConnectionOperations,
    resumed: Subscription | null = null,
  ) {
    this.current = resumed;
  }

  /**
   * Connects: reopens the stored subscription's endpoint, for a connection
   * resumed, while its lease lasts, renewal due by its stored expiry; and
   * subscribes anew when there is none, its lease has run out, or the hub
   * denies the endpoint or the socket fails. Whether a socket was confirmed;
   * a failure when the hub refused the subscription.
   */
  async connect(): Promise<boolean> {
    const stored = this.current;
    if (stored !== null && this.unexpired(stored.leaseExpiresAt)) {
      if ((await this.reopen(stored.endpoint, () => stored.leaseExpiresAt)) === true) return true;
    }
    return this.subscribe();
  }

  /** Stops everything: no renewal, no retry, and the socket closed. */
  close() {
    this.closed = true;
    this.operations.clock.cancel(this.timer);
    this.channel?.close();
  }

  /**
   * Stops everything, as {@link close} does, and answers what tearing the
   * connection down takes, once the subscriptions it moved off are
   * unsubscribed: the SMART access token, and the endpoints of the
   * subscription last confirmed, every subscription that never became current
   * ({@link abandoned}), and the one last answered if it has not either, and
   * how to close the socket. The socket is the teardown's from the start: a
   * `close` asked for meanwhile leaves it open, and one the hub causes is not
   * reconnected.
   */
  async teardown(): Promise<Teardown> {
    const channel = this.channel;
    this.channel = null;
    this.close();
    await this.retiring;
    const { abandoned, current, hub, latest, token } = this;
    return {
      close: () => channel?.close(),
      endpoints: [
        ...(current === null ? [] : [current.endpoint]),
        ...abandoned,
        ...(latest === null ? [] : [latest]),
      ],
      hub,
      token,
    };
  }

  /** When a lease of `seconds` from now runs out: `null` for a lease the hub did not state. */
  private readonly expiryOf = (seconds: number | null): number | null =>
    seconds === null ? null : this.operations.clock.now() + seconds * 1000;

  /** Whether what runs out at `expiresAt` has yet to. */
  private unexpired(expiresAt: number | null): boolean {
    return expiresAt === null || expiresAt > this.operations.clock.now();
  }

  /**
   * A new subscription, and its socket. Whether a socket was confirmed; a
   * failure when the hub refused the subscription. One the hub answers once
   * the connection is closed is kept by nothing, and unsubscribed at once.
   */
  private async subscribe(): Promise<boolean> {
    if (this.latest !== null) this.abandoned.push(this.latest);
    this.latest = null;
    const endpoint = await this.operations.subscribe(this.hub, this.token);
    if (this.closed) {
      this.retire(endpoint);
      return false;
    }
    this.latest = endpoint;
    return (await this.reopen(endpoint, this.expiryOf)) === true;
  }

  /**
   * Opens `endpoint`: `true` once the hub confirms the socket, which then
   * replaces the one before, or is closed again when the connection was
   * closed meanwhile; otherwise `denied` or `failed`, as the socket answered.
   * The lease runs out when `leaseExpiry` says, given the lease the
   * confirmation grants: {@link expiryOf} it, or a resumed subscription's
   * stored expiry.
   */
  private async reopen(
    endpoint: string,
    leaseExpiry: (granted: number | null) => number | null,
  ): Promise<true | Exclude<Opened, Channel>> {
    const opened = await this.operations.open(
      endpoint,
      this.hub.hubTopic,
      this.handlers.onEvent,
      () => this.dropped(),
    );
    if (typeof opened === "string") return opened;
    if (this.closed) opened.close();
    else this.adopt(opened, { endpoint, leaseExpiresAt: leaseExpiry(opened.lease) });
    return true;
  }

  /**
   * `opened`, on `subscription`, as the socket: the one before closed, renewal
   * due by its lease, and the subscription kept as {@link current} rather
   * than {@link latest}. A subscription it moves off is unsubscribed.
   */
  private adopt(opened: Channel, subscription: Subscription) {
    const before = this.current;
    this.channel?.close();
    this.channel = opened;
    this.current = subscription;
    this.latest = null;
    this.renewsAt = this.renewalAt(subscription.leaseExpiresAt);
    this.schedule();
    this.operations.keep(subscription);
    if (before !== null && before.endpoint !== subscription.endpoint) this.retire(before.endpoint);
  }

  /** When to renew a lease that runs out at `expiresAt`, by {@link renewalDelay} from now: never, for no expiry. */
  private renewalAt(expiresAt: number | null): number {
    if (expiresAt === null) return Infinity;
    const { now } = this.operations.clock;
    return now() + renewalDelay((expiresAt - now()) / 1000);
  }

  /**
   * Unsubscribes `endpoint`, best-effort and after any unsubscribed already.
   * Never awaited by the connection: a hub failing or silent leaves it as it is.
   */
  private retire(endpoint: string) {
    const { hub, operations, token } = this;
    this.retiring = this.retiring.then(() => operations.unsubscribe(hub, endpoint, token));
  }

  /** Sets the renewal timer for when the lease is due. */
  private schedule() {
    const { clock } = this.operations;
    clock.cancel(this.timer);
    if (!Number.isFinite(this.renewsAt)) return;
    this.timer = clock.after(
      Math.min(this.renewsAt - clock.now(), LONGEST_TIMER_MS),
      () => void this.renew(),
    );
  }

  /** Renewal: a new subscription and its socket; reconnecting, by subscribing anew, when it fails. */
  private async renew() {
    this.renewing = true;
    let renewed = false;
    try {
      renewed = await this.subscribe();
    } catch {
      // The hub refused the subscription: the renewal failed.
    }
    this.renewing = false;
    if (this.closed || renewed) return;
    this.channel?.close();
    this.channel = null;
    this.resubscribing = true;
    this.reconnect(0);
  }

  /** The confirmed socket, closed by the hub: nothing more once the connection is closed. */
  private dropped() {
    this.channel = null;
    if (this.closed || this.renewing) return;
    this.operations.clock.cancel(this.timer);
    this.reconnect(0);
  }

  /** Retry `attempt`, from 0, after its backoff; once the retries run out, disconnected. */
  private reconnect(attempt: number) {
    const { onConnection } = this.handlers;
    if (attempt === 0) onConnection("reconnecting");
    if (attempt === RECONNECT_ATTEMPTS) {
      onConnection("disconnected");
      return;
    }
    this.timer = this.operations.clock.after(
      RECONNECT_BACKOFF_MS * 2 ** attempt,
      () => void this.retry(attempt),
    );
  }

  /** One retry: connected again once a socket is confirmed, or the next retry. */
  private async retry(attempt: number) {
    try {
      await this.restore();
    } catch {
      // The hub refused the subscription: this retry failed.
    }
    if (this.closed) return;
    if (this.channel === null) this.reconnect(attempt + 1);
    else this.handlers.onConnection("connected");
  }

  /**
   * Reopens the endpoint; subscribes anew when the hub denies it, or after a
   * failed renewal, until a new subscription's socket is confirmed.
   */
  private async restore() {
    const reopening = this.resubscribing ? null : this.current;
    if (reopening !== null && (await this.reopen(reopening.endpoint, this.expiryOf)) !== "denied") {
      return;
    }
    this.resubscribing = true;
    this.resubscribing = !(await this.subscribe());
  }
}
