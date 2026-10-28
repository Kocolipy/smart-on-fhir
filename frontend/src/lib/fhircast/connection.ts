/**
 * The renewing connection: one Hub Registration's connection once its key is
 * registered — steps 3 to 5 of connecting, then renewal before the Hub Token
 * or the lease runs out, reconnecting with a doubling backoff when a socket
 * drops or a renewal fails, one live Hub Subscription at a time, and resuming
 * the stored subscription after a reload.
 *
 * It holds no module state and reaches nothing itself: the hub protocol, the
 * socket, the tab's storage and the clock are the {@link ConnectionOperations}
 * it is handed.
 */

import type { Channel, Opened } from "./notifications";
import type {
  Client,
  FhircastHandlers,
  Granted,
  Hub,
  HubToken,
  PatientEvent,
  Subscription,
} from "./model";
import type { Teardown } from "./teardown";

/** The wait before the first retry once a connection is lost; each later one doubles it. */
const RECONNECT_BACKOFF_MS = 1_000;

/** How many retries a lost connection gets before it is given up. */
const RECONNECT_ATTEMPTS = 5;

/** The longest wait a browser timer holds: anything longer fires at once. */
const LONGEST_TIMER_MS = 2_147_483_647;

/**
 * The soonest a renewal starts after what it renews was granted. Only a
 * lifetime under 65 s brings 60 s before its end this close: one of a minute
 * or less has no such time left at all, and renewing it the moment it is
 * granted would mint a Hub Token and subscribe again without pause.
 */
const SOONEST_RENEWAL_MS = 5_000;

/**
 * When to renew what lasts `seconds`, in milliseconds from when it was
 * granted: at 80% of its lifetime or 60 s before it ends, whichever is sooner
 * — though never sooner than {@link SOONEST_RENEWAL_MS}.
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
  /** Step 3: a Hub Token for the client, from a fresh assertion. */
  hubToken: (client: Client) => Promise<HubToken>;
  /** Step 4: the subscription on `hub` with `token`; the channel endpoint the hub answered. */
  subscribe: (hub: Hub, token: string) => Promise<string>;
  /** Step 5: the socket at `endpoint`, as the hub answered it. */
  open: (
    endpoint: string,
    topic: string,
    onEvent: (event: PatientEvent) => void,
    onDropped: () => void,
  ) => Promise<Opened>;
  /** `before`, a subscription the connection moved off, cancelled, sparing what `kept` shares with it. */
  cancel: (client: Client, before: Granted, kept: Granted) => Promise<void>;
  /** Called with each subscription a socket is confirmed on: the tab keeps it, if it keeps this connection. */
  keep: (subscription: Subscription) => void;
  clock: Clock;
}

/**
 * The connection once the key is registered: steps 3 to 5, then renewal
 * before the Hub Token or the lease runs out, and reconnecting when a socket
 * drops or a renewal fails. A socket that drops while a renewal is under way
 * is left to the renewal. One Hub Subscription is live at a time: once a new
 * one's socket is confirmed, the one before is cancelled. A grant that never
 * became current — a renewal or retry whose socket was never confirmed — is
 * kept, and cancelled when the connection is torn down.
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
   * The Hub Token the hub last granted, and its endpoint once subscribed,
   * until a socket is confirmed on it and it becomes {@link current}: `null`
   * until the hub grants one, and again once it is current. Still here when
   * the hub grants the next, it moves to {@link abandoned}.
   */
  private latest: Granted | null = null;
  /**
   * Every Hub Token the hub granted, each with its endpoint once subscribed,
   * whose socket was never confirmed and that a later grant replaced as
   * {@link latest}: a renewal or retry that failed. Never cancelled before
   * {@link teardown} hands them over, so no grant the hub made goes uncancelled.
   */
  private readonly abandoned: Granted[] = [];
  /** When the Hub Token, and the lease, are due for renewal, on `Date.now()`'s clock. */
  private tokenRenewsAt = Infinity;
  private leaseRenewsAt = Infinity;
  private timer: Timer | undefined;
  private renewing = false;
  private closed = false;
  /** Whether a retry subscribes anew, rather than reopening the endpoint. */
  private resubscribing = false;
  /** The subscriptions moved off, being cancelled in turn: tearing down waits for them. */
  private retiring: Promise<void> = Promise.resolve();

  constructor(
    readonly client: Client,
    private readonly handlers: Required<FhircastHandlers>,
    private readonly operations: ConnectionOperations,
    resumed: Subscription | null = null,
  ) {
    this.current = resumed;
  }

  /**
   * Steps 3 to 5: a Hub Token from a fresh assertion, a new subscription, and
   * its socket. Whether a socket was confirmed; a failure when the hub refused
   * a step. Once closed, it keeps nothing it is granted.
   */
  async subscribe(): Promise<boolean> {
    const granted = await this.operations.hubToken(this.client);
    return !this.closed && (await this.subscribeWith(granted));
  }

  /**
   * Resumes the stored subscription after a reload: reopens its endpoint while
   * its Hub Token and lease both last, renewal due by their stored expiries;
   * subscribes anew once either has run out, or the hub denies the endpoint or
   * the socket fails. Whether a socket was confirmed.
   */
  async resume(): Promise<boolean> {
    const stored = this.current;
    if (
      stored !== null &&
      this.unexpired(stored.hubTokenExpiresAt) &&
      this.unexpired(stored.leaseExpiresAt)
    ) {
      this.tokenRenewsAt = this.renewsAt(stored.hubTokenExpiresAt);
      if ((await this.reopen(stored, () => stored.leaseExpiresAt)) === true) return true;
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
   * connection down takes, once the subscriptions it moved off are cancelled:
   * the subscription last confirmed, every grant that never became current
   * ({@link abandoned}), and the Hub Token last granted if it has not either,
   * each with its endpoint, and how to close the socket. The socket is the
   * teardown's from the start: a `close` asked for meanwhile leaves it open,
   * and one the hub causes is not reconnected.
   */
  async teardown(): Promise<Teardown> {
    const channel = this.channel;
    this.channel = null;
    this.close();
    await this.retiring;
    const { abandoned, current, latest } = this;
    return {
      client: this.client,
      close: () => channel?.close(),
      granted: [
        ...(current === null ? [] : [current]),
        ...abandoned,
        ...(latest === null ? [] : [latest]),
      ],
    };
  }

  /** When what lasts `seconds` from now runs out: `null` for a lifetime the hub did not say. */
  private readonly expiryOf = (seconds: number | null): number | null =>
    seconds === null ? null : this.operations.clock.now() + seconds * 1000;

  /** When to renew what runs out at `expiresAt`, by {@link renewalDelay} from now: never, for no expiry. */
  private renewsAt(expiresAt: number | null): number {
    if (expiresAt === null) return Infinity;
    const { now } = this.operations.clock;
    return now() + renewalDelay((expiresAt - now()) / 1000);
  }

  /** Whether what runs out at `expiresAt` has yet to. */
  private unexpired(expiresAt: number | null): boolean {
    return expiresAt === null || expiresAt > this.operations.clock.now();
  }

  /** Steps 4 and 5 with the Hub Token `granted`, which renewal is then due by. */
  private async subscribeWith(granted: HubToken): Promise<boolean> {
    const hubTokenExpiresAt = this.expiryOf(granted.lifetime);
    this.tokenRenewsAt = this.renewsAt(hubTokenExpiresAt);
    if (this.latest !== null) this.abandoned.push(this.latest);
    this.latest = { endpoint: null, hubToken: granted.token };
    const endpoint = await this.operations.subscribe(this.client.hub, granted.token);
    this.latest = { endpoint, hubToken: granted.token };
    const opened = await this.reopen(
      { endpoint, hubToken: granted.token, hubTokenExpiresAt },
      this.expiryOf,
    );
    return opened === true;
  }

  /**
   * Opens `subscription`'s endpoint: `true` once the hub confirms the socket,
   * which then replaces the one before, or is closed again when the
   * connection was closed meanwhile; otherwise `denied` or `failed`, as
   * the socket answered. The lease runs out when `leaseExpiry` says, given
   * the lease the confirmation grants: {@link expiryOf} it, or a resumed
   * subscription's stored expiry.
   */
  private async reopen(
    subscription: Omit<Subscription, "leaseExpiresAt">,
    leaseExpiry: (granted: number | null) => number | null,
  ): Promise<true | Exclude<Opened, Channel>> {
    const opened = await this.operations.open(
      subscription.endpoint,
      this.client.hub.hubTopic,
      this.handlers.onEvent,
      () => this.dropped(),
    );
    if (typeof opened === "string") return opened;
    if (this.closed) opened.close();
    else {
      this.adopt(opened, {
        ...subscription,
        leaseExpiresAt: leaseExpiry(opened.lease),
      });
    }
    return true;
  }

  /**
   * `opened`, on `subscription`, as the socket: the one before closed, renewal
   * due by its lease too, and the subscription kept as {@link current} rather
   * than {@link latest}. A subscription it moves off is cancelled.
   */
  private adopt(opened: Channel, subscription: Subscription) {
    const before = this.current;
    this.channel?.close();
    this.channel = opened;
    this.current = subscription;
    this.latest = null;
    this.leaseRenewsAt = this.renewsAt(subscription.leaseExpiresAt);
    this.schedule();
    this.operations.keep(subscription);
    if (before !== null) this.retire(before, subscription);
  }

  /**
   * Once on `after`, cancels `before`, best-effort and after any cancelled
   * already, sparing what `after` shares with it. Never awaited by the
   * connection: a hub failing or silent at either step leaves it as it is.
   */
  private retire(before: Subscription, after: Subscription) {
    const { client, operations } = this;
    this.retiring = this.retiring.then(() => operations.cancel(client, before, after));
  }

  /** Sets the renewal timer for whichever of the Hub Token and the lease is due first. */
  private schedule() {
    const { clock } = this.operations;
    clock.cancel(this.timer);
    const due = Math.min(this.tokenRenewsAt, this.leaseRenewsAt);
    if (!Number.isFinite(due)) return;
    this.timer = clock.after(
      Math.min(due - clock.now(), LONGEST_TIMER_MS),
      () => void this.renew(),
    );
  }

  /** Renewal: steps 3 to 5 again; reconnecting, by subscribing anew, when it fails. */
  private async renew() {
    this.renewing = true;
    let renewed = false;
    try {
      renewed = await this.subscribe();
    } catch {
      // The hub refused a step: the renewal failed.
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
      // The hub refused a step: this retry failed.
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
    if (reopening !== null && (await this.reopen(reopening, this.expiryOf)) !== "denied") return;
    this.resubscribing = true;
    this.resubscribing = !(await this.subscribe());
  }
}
