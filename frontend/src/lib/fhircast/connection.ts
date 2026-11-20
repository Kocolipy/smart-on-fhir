/**
 * The renewing connection: one connection to a hub, made with the hub tokens
 * it obtains and holds in memory, the newest at a time — the subscription and
 * its socket, renewal with a new hub token before the hub token or the lease
 * runs out, reconnecting with a doubling backoff when a socket drops, the hub
 * denies the live subscription or a renewal fails, one live Hub Subscription
 * at a time, and resuming the stored subscription after a reload. It lasts
 * the session: it stops for good only once the hub has forgotten the
 * profile's dynamic client and the tab cannot register one again
 * ({@link ClientLost}). Everything it comes to is one status, reported to its
 * {@link ConnectionListener}.
 *
 * It holds no module state: the session (`session.ts`) makes each connection
 * and holds them. It reaches the hub through the hub protocol and the socket,
 * keeps its subscription in the tab's storage, and renews and reconnects on
 * the browser's timers.
 */

import { ClientLost } from "./hub-token";
import type { ConnectionListener, FhircastStatus, Hub, Subscription } from "./model";
import { type Channel, type Lost, open, type Opened } from "./notifications";
import { HubFailure, type HubToken, subscribe, unsubscribe } from "./protocol";
import { persist } from "./storage";

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
 * When to renew what lasts `seconds` — a lease, or a hub token — in
 * milliseconds from when it was granted: at 80% of it or 60 s before it ends,
 * whichever is sooner, though never sooner than {@link SOONEST_RENEWAL_MS}.
 */
function renewalDelay(seconds: number): number {
  return Math.max(1000 * Math.min(seconds * 0.8, seconds - 60), SOONEST_RENEWAL_MS);
}

/** The permission Chrome's Local Network Access prompt answers. */
const LOCAL_NETWORK_ACCESS = "local-network-access";

/**
 * Whether the browser refused this tab the local network: the clinician
 * declined Chrome's Local Network Access prompt. Any browser without the
 * permission says nothing, and the hub is merely unavailable.
 */
async function blockedByBrowser(): Promise<boolean> {
  try {
    const status = await navigator.permissions.query({
      name: LOCAL_NETWORK_ACCESS as PermissionName,
    });
    return status.state === "denied";
  } catch {
    return false;
  }
}

/**
 * The one generic status a failure to connect is: `ended` when the hub has
 * forgotten the profile's client and the tab cannot register one again,
 * `blocked` when the browser kept the tab off the hub, `unavailable` for
 * anything else — a hub that refused, or did not answer in time, included.
 */
async function failedStatus(failure: unknown): Promise<FhircastStatus> {
  if (failure instanceof ClientLost) return "ended";
  if (failure instanceof HubFailure && failure.unreachable && (await blockedByBrowser())) {
    return "blocked";
  }
  return "unavailable";
}

type Timer = ReturnType<typeof setTimeout>;

/** What a connection is made with: how it obtains its hub tokens. */
export interface ConnectionTerms {
  /**
   * A new hub token, which the subscription made next is made with: obtained
   * as the connection starts, and again for each renewal and each retry that
   * subscribes anew. A {@link ClientLost} when the hub has forgotten the
   * profile's client and the tab cannot register one again; a
   * {@link HubFailure} when the hub refuses it.
   */
  obtainHubToken: () => Promise<HubToken>;
  /**
   * A hub token to tear the connection down with, for the client the profile
   * kept, registering none: what a teardown obtains once the one held has
   * expired.
   */
  teardownHubToken: () => Promise<HubToken>;
}

/**
 * What tearing a connection down takes: its hub, the hub token to unsubscribe
 * with, the channel endpoint of every subscription of it still standing, and
 * how to close its socket once they are cancelled.
 */
export interface Teardown {
  hub: Hub;
  /**
   * The hub token to unsubscribe with: the one the connection holds while it
   * is unexpired, otherwise a fresh one ({@link ConnectionTerms.teardownHubToken}).
   */
  token: () => Promise<string>;
  endpoints: readonly string[];
  close: () => void;
}

/**
 * The hub token a connection holds before it has obtained one: none, expired
 * from the start, so a teardown before the first obtains a fresh one.
 */
const NO_HUB_TOKEN: HubToken = { expiresAt: 0, token: "" };

/** The sooner of two instants, `null` standing for one that never comes. */
function sooner(first: number | null, second: number | null): number | null {
  if (first === null) return second;
  if (second === null) return first;
  return Math.min(first, second);
}

/**
 * One connection to `hub`, every request it makes of the hub with the newest
 * hub token `terms` obtained: the subscription and its socket, then renewal
 * — a new hub token, and a new subscription made with it — before the hub
 * token the live subscription was made with or its lease runs out, whichever
 * is first; and reconnecting when a socket drops, the hub denies the live
 * subscription, or a renewal fails. A socket that drops, or is denied, while
 * a renewal is under way is left to the renewal. One Hub Subscription is live
 * at a time: once a new one's socket is confirmed, the tab keeps it and the
 * one before is unsubscribed. A subscription whose socket was never confirmed
 * — a renewal or retry that failed — is kept, and unsubscribed when the
 * connection is torn down.
 *
 * The connection lasts the session, past the SMART access token's expiry. It
 * ends — no renewal, no retry, the socket closed, and `ended` reported — only
 * when obtaining a hub token fails with a {@link ClientLost}: the hub has
 * forgotten the profile's client and the tab cannot register one again.
 *
 * Once closed it reports nothing more, and keeps nothing in the tab.
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
  /** When the live subscription is due for renewal, on `Date.now()`'s clock. */
  private renewsAt = Infinity;
  private timer: Timer | undefined;
  private renewing = false;
  private closed = false;
  /** Whether a retry subscribes anew, rather than reopening the endpoint. */
  private resubscribing = false;
  /** The subscriptions moved off, being unsubscribed in turn: tearing down waits for them. */
  private retiring: Promise<void> = Promise.resolve();
  /** The newest hub token the connection obtained: held in memory alone, and never stored. */
  private hubToken: HubToken = NO_HUB_TOKEN;

  constructor(
    private readonly hub: Hub,
    private readonly terms: ConnectionTerms,
    private readonly listener: ConnectionListener,
    resumed: Subscription | null = null,
  ) {
    this.current = resumed;
  }

  /**
   * Connects, reporting `connected` once a socket is confirmed, and how it
   * failed otherwise — `ended` when the hub has forgotten the profile's
   * client for good, having subscribed nothing. It obtains a hub token first,
   * then reopens the stored subscription's endpoint, for a connection
   * resumed, while both its lease and the hub token it was made with last,
   * renewal due by the sooner of their stored expiries; and subscribes anew
   * with the new hub token when there is none, either has run out, or the
   * hub denies the endpoint or the socket fails. Never rejects.
   */
  async start(): Promise<void> {
    let status: FhircastStatus;
    try {
      this.hubToken = await this.terms.obtainHubToken();
      if (this.closed) return;
      status = (await this.connect()) ? "connected" : "unavailable";
    } catch (failure) {
      status = await failedStatus(failure);
    }
    if (!this.closed) this.listener.status(status);
  }

  /** Whether a socket was confirmed, as {@link start} connects; a failure when the hub refused the subscription. */
  private async connect(): Promise<boolean> {
    const stored = this.current;
    if (
      stored !== null &&
      this.unexpired(stored.leaseExpiresAt) &&
      this.unexpired(stored.hubTokenExpiresAt)
    ) {
      if ((await this.reopen(stored.endpoint, () => stored)) === true) return true;
    }
    return this.subscribe(false);
  }

  /** Stops everything: no renewal, no retry, and the socket closed. */
  close() {
    this.closed = true;
    clearTimeout(this.timer);
    this.channel?.close();
  }

  /** Ends the connection, as {@link close} stops it, reporting `ended`; nothing once closed. */
  private end() {
    if (this.closed) return;
    this.close();
    this.listener.status("ended");
  }

  /**
   * Stops everything, as {@link close} does, and answers what tearing the
   * connection down takes, once the subscriptions it moved off are
   * unsubscribed: the hub token to unsubscribe with, and the endpoints of the
   * subscription last confirmed, every subscription that never became current
   * ({@link abandoned}), and the one last answered if it has not either, and
   * how to close the socket. The socket is the teardown's from the start, and
   * one the hub closes meanwhile is not reconnected.
   */
  async teardown(): Promise<Teardown> {
    const channel = this.channel;
    this.channel = null;
    this.close();
    await this.retiring;
    const { abandoned, current, hub, hubToken, latest, terms } = this;
    return {
      close: () => channel?.close(),
      endpoints: [
        ...(current === null ? [] : [current.endpoint]),
        ...abandoned,
        ...(latest === null ? [] : [latest]),
      ],
      hub,
      token: async () =>
        (this.unexpired(hubToken.expiresAt) ? hubToken : await terms.teardownHubToken()).token,
    };
  }

  /** When a lease of `seconds` from now runs out: `null` for a lease the hub did not state. */
  private readonly expiryOf = (seconds: number | null): number | null =>
    seconds === null ? null : Date.now() + seconds * 1000;

  /** Whether what runs out at `expiresAt` has yet to. */
  private unexpired(expiresAt: number | null): boolean {
    return expiresAt === null || expiresAt > Date.now();
  }

  /**
   * A new subscription, and its socket: made with a new hub token when
   * `withNewHubToken`, otherwise with the one held. Whether a socket was
   * confirmed; a failure when the hub refused the hub token or the
   * subscription, or did not answer in time. One the hub answers once the
   * connection is closed is kept by nothing, and unsubscribed at once.
   */
  private async subscribe(withNewHubToken: boolean): Promise<boolean> {
    if (this.latest !== null) this.abandoned.push(this.latest);
    this.latest = null;
    if (withNewHubToken) {
      const obtained = await this.terms.obtainHubToken();
      if (this.closed) return false;
      this.hubToken = obtained;
    }
    const madeWith = this.hubToken;
    const endpoint = await subscribe(this.hub, madeWith.token);
    if (this.closed) {
      this.retire(endpoint);
      return false;
    }
    this.latest = endpoint;
    const subscribed = (granted: number | null): Subscription => ({
      endpoint,
      hubTokenExpiresAt: madeWith.expiresAt,
      leaseExpiresAt: this.expiryOf(granted),
    });
    return (await this.reopen(endpoint, subscribed)) === true;
  }

  /**
   * Opens `endpoint`: `true` once the hub confirms the socket, which then
   * replaces the one before, or is closed again when the connection was
   * closed meanwhile; otherwise `denied` or `failed`, as the socket answered.
   * The socket stands for the subscription `confirmed` describes, given the
   * lease the confirmation grants.
   */
  private async reopen(
    endpoint: string,
    confirmed: (granted: number | null) => Subscription,
  ): Promise<true | Exclude<Opened, Channel>> {
    const opened = await open(
      endpoint,
      this.hub.hubTopic,
      (event) => this.listener.event(event),
      (how) => this.lost(how),
    );
    if (typeof opened === "string") return opened;
    if (this.closed) opened.close();
    else this.adopt(opened, confirmed(opened.lease));
    return true;
  }

  /**
   * `opened`, on `subscription`, as the socket: the one before closed, renewal
   * due by the sooner of its lease's and its hub token's expiry, and the
   * subscription kept in the tab and as {@link current} rather than
   * {@link latest}. A subscription it moves off is unsubscribed.
   */
  private adopt(opened: Channel, subscription: Subscription) {
    const before = this.current;
    this.channel?.close();
    this.channel = opened;
    this.current = subscription;
    this.latest = null;
    this.renewsAt = this.renewalAt(
      sooner(subscription.leaseExpiresAt, subscription.hubTokenExpiresAt),
    );
    this.schedule();
    persist({ hub: this.hub, subscription });
    if (before !== null && before.endpoint !== subscription.endpoint) this.retire(before.endpoint);
  }

  /** When to renew what runs out at `expiresAt`, by {@link renewalDelay} from now: never, for no expiry. */
  private renewalAt(expiresAt: number | null): number {
    if (expiresAt === null) return Infinity;
    return Date.now() + renewalDelay((expiresAt - Date.now()) / 1000);
  }

  /**
   * Unsubscribes `endpoint` with the newest hub token, best-effort and after
   * any unsubscribed already. Never awaited by the connection: a hub failing
   * or silent leaves it as it is.
   */
  private retire(endpoint: string) {
    const { hub, hubToken } = this;
    this.retiring = this.retiring.then(() => unsubscribe(hub, endpoint, hubToken.token));
  }

  /** Sets the renewal timer for when the live subscription is due. */
  private schedule() {
    clearTimeout(this.timer);
    if (!Number.isFinite(this.renewsAt)) return;
    this.timer = setTimeout(
      () => void this.renew(),
      Math.min(this.renewsAt - Date.now(), LONGEST_TIMER_MS),
    );
  }

  /**
   * Renewal: a new hub token, a new subscription made with it, and its
   * socket; reconnecting, by subscribing anew, when it fails; the end, when
   * the hub has forgotten the profile's client for good.
   */
  private async renew() {
    this.renewing = true;
    let renewed = false;
    try {
      renewed = await this.subscribe(true);
    } catch (failure) {
      // The hub refused the hub token or the subscription, or did not answer
      // in time: the renewal failed.
      if (failure instanceof ClientLost) this.end();
    }
    this.renewing = false;
    if (this.closed || renewed) return;
    this.channel?.close();
    this.channel = null;
    this.resubscribing = true;
    this.reconnect(0);
  }

  /**
   * The confirmed socket, lost from the hub's side: reconnecting, by
   * subscribing anew once the hub denied the subscription on it. A socket a
   * renewal under way will replace is left to it; nothing more once the
   * connection is closed.
   */
  private lost(how: Lost) {
    this.channel = null;
    if (this.closed || this.renewing) return;
    clearTimeout(this.timer);
    if (how === "denied") this.resubscribing = true;
    this.reconnect(0);
  }

  /** Retry `attempt`, from 0, after its backoff; once the retries run out, disconnected. */
  private reconnect(attempt: number) {
    if (attempt === 0) this.listener.status("reconnecting");
    if (attempt === RECONNECT_ATTEMPTS) {
      this.listener.status("disconnected");
      return;
    }
    this.timer = setTimeout(() => void this.retry(attempt), RECONNECT_BACKOFF_MS * 2 ** attempt);
  }

  /**
   * One retry: connected again once a socket is confirmed, or the next retry;
   * the end, when the hub has forgotten the profile's client for good.
   */
  private async retry(attempt: number) {
    try {
      await this.restore();
    } catch (failure) {
      // The hub refused the hub token or the subscription, or did not answer
      // in time: this retry failed.
      if (failure instanceof ClientLost) this.end();
    }
    if (this.closed) return;
    if (this.channel === null) this.reconnect(attempt + 1);
    else this.listener.status("connected");
  }

  /**
   * Reopens the endpoint; subscribes anew, with a new hub token, when the hub
   * denies it, after a failed renewal or once the hub denied the live
   * subscription, until a new subscription's socket is confirmed.
   */
  private async restore() {
    const reopening = this.resubscribing ? null : this.current;
    if (
      reopening !== null &&
      (await this.reopen(reopening.endpoint, (granted) => ({
        ...reopening,
        leaseExpiresAt: this.expiryOf(granted),
      }))) !== "denied"
    ) {
      return;
    }
    this.resubscribing = true;
    this.resubscribing = !(await this.subscribe(true));
  }
}
