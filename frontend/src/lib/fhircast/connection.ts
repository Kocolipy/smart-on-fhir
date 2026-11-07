/**
 * The renewing connection: one connection to a hub, made with the SMART
 * access token it holds in memory for its whole life — the subscription and
 * its socket, renewal before the lease runs out, reconnecting with a doubling
 * backoff when a socket drops or a renewal fails, one live Hub Subscription
 * at a time, and resuming the stored subscription after a reload. It ends
 * with the token: once the hub denies the live subscription, or the token's
 * expiry passes, it stops for good. Everything it comes to is one status,
 * reported to its {@link ConnectionListener}.
 *
 * It holds no module state: the session (`session.ts`) makes each connection
 * and holds them. It reaches the hub through the hub protocol and the socket,
 * keeps its subscription in the tab's storage, and renews and reconnects on
 * the browser's timers.
 */

import type { ConnectionListener, FhircastStatus, Hub, Subscription } from "./model";
import { type Channel, type Lost, open, type Opened } from "./notifications";
import { HubFailure, subscribe, unsubscribe } from "./protocol";
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
 * When to renew a lease of `seconds`, in milliseconds from when it was
 * granted: at 80% of it or 60 s before it ends, whichever is sooner — though
 * never sooner than {@link SOONEST_RENEWAL_MS}.
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
 * The one generic status a failure to connect is: `blocked` when the browser
 * kept the tab off the hub, `unavailable` for anything else — a hub that
 * refused, or did not answer in time, included.
 */
async function failedStatus(failure: unknown): Promise<FhircastStatus> {
  if (failure instanceof HubFailure && failure.unreachable && (await blockedByBrowser())) {
    return "blocked";
  }
  return "unavailable";
}

type Timer = ReturnType<typeof setTimeout>;

/** The SMART access token a connection is made with, and when it expires on `Date.now()`'s clock. */
export interface SmartToken {
  accessToken: string;
  expiresAt: number;
}

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
 * One connection to `hub`, every request it makes with the SMART access token
 * of `token`: the subscription and its socket, then renewal before the lease
 * runs out, and reconnecting when a socket drops or a renewal fails. A socket
 * that drops while a renewal is under way is left to the renewal. One Hub
 * Subscription is live at a time: once a new one's socket is confirmed, the
 * tab keeps it and the one before is unsubscribed. A subscription whose
 * socket was never confirmed — a renewal or retry that failed — is kept, and
 * unsubscribed when the connection is torn down.
 *
 * The connection lasts as long as the token, and no longer. It ends — no
 * renewal, no retry, the socket closed, and `ended` reported — when the hub
 * denies the live subscription on its confirmed socket, or when the token's
 * expiry passes, whether connecting, connected, reconnecting or given up;
 * with the expiry already past it asks the hub nothing. A denial of an
 * endpoint being reopened is no end: a retry subscribes anew.
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
  /** When the lease is due for renewal, on `Date.now()`'s clock. */
  private renewsAt = Infinity;
  private timer: Timer | undefined;
  /** The timer that ends the connection at the token's expiry. */
  private expiryTimer: Timer | undefined;
  private renewing = false;
  private closed = false;
  /** Whether a retry subscribes anew, rather than reopening the endpoint. */
  private resubscribing = false;
  /** The subscriptions moved off, being unsubscribed in turn: tearing down waits for them. */
  private retiring: Promise<void> = Promise.resolve();

  constructor(
    private readonly hub: Hub,
    private readonly token: SmartToken,
    private readonly listener: ConnectionListener,
    resumed: Subscription | null = null,
  ) {
    this.current = resumed;
  }

  /**
   * Connects, reporting `connected` once a socket is confirmed, and how it
   * failed otherwise. It reopens the stored subscription's endpoint, for a
   * connection resumed, while its lease lasts, renewal due by its stored
   * expiry; and subscribes anew when there is none, its lease has run out, or
   * the hub denies the endpoint or the socket fails. With the token's expiry
   * already past it ends at once, asking the hub nothing. Never rejects.
   */
  async start(): Promise<void> {
    this.watchExpiry();
    if (this.closed) return;
    let status: FhircastStatus;
    try {
      status = (await this.connect()) ? "connected" : "unavailable";
    } catch (failure) {
      status = await failedStatus(failure);
    }
    if (!this.closed) this.listener.status(status);
  }

  /** Whether a socket was confirmed, as {@link start} connects; a failure when the hub refused the subscription. */
  private async connect(): Promise<boolean> {
    const stored = this.current;
    if (stored !== null && this.unexpired(stored.leaseExpiresAt)) {
      if ((await this.reopen(stored.endpoint, () => stored.leaseExpiresAt)) === true) return true;
    }
    return this.subscribe();
  }

  /** Stops everything: no renewal, no retry, and the socket closed. */
  close() {
    this.closed = true;
    clearTimeout(this.timer);
    clearTimeout(this.expiryTimer);
    this.channel?.close();
  }

  /** Ends the connection with its token, as {@link close} stops it, reporting `ended`; nothing once closed. */
  private end() {
    if (this.closed) return;
    this.close();
    this.listener.status("ended");
  }

  /**
   * Ends the connection once the token's expiry has passed, now or by a timer
   * set for it — re-set while the expiry is further off than a timer holds.
   */
  private watchExpiry() {
    const { expiresAt } = this.token;
    if (expiresAt <= Date.now()) {
      this.end();
      return;
    }
    this.expiryTimer = setTimeout(
      () => this.watchExpiry(),
      Math.min(expiresAt - Date.now(), LONGEST_TIMER_MS),
    );
  }

  /**
   * Stops everything, as {@link close} does, and answers what tearing the
   * connection down takes, once the subscriptions it moved off are
   * unsubscribed: the SMART access token, and the endpoints of the
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
    const { abandoned, current, hub, latest, token } = this;
    return {
      close: () => channel?.close(),
      endpoints: [
        ...(current === null ? [] : [current.endpoint]),
        ...abandoned,
        ...(latest === null ? [] : [latest]),
      ],
      hub,
      token: token.accessToken,
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
   * A new subscription, and its socket. Whether a socket was confirmed; a
   * failure when the hub refused the subscription, or did not answer it in
   * time. One the hub answers once the connection is closed is kept by
   * nothing, and unsubscribed at once.
   */
  private async subscribe(): Promise<boolean> {
    if (this.latest !== null) this.abandoned.push(this.latest);
    this.latest = null;
    const endpoint = await subscribe(this.hub, this.token.accessToken);
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
    const opened = await open(
      endpoint,
      this.hub.hubTopic,
      (event) => this.listener.event(event),
      (how) => this.lost(how),
    );
    if (typeof opened === "string") return opened;
    if (this.closed) opened.close();
    else this.adopt(opened, { endpoint, leaseExpiresAt: leaseExpiry(opened.lease) });
    return true;
  }

  /**
   * `opened`, on `subscription`, as the socket: the one before closed, renewal
   * due by its lease, and the subscription kept in the tab and as
   * {@link current} rather than {@link latest}. A subscription it moves off
   * is unsubscribed.
   */
  private adopt(opened: Channel, subscription: Subscription) {
    const before = this.current;
    this.channel?.close();
    this.channel = opened;
    this.current = subscription;
    this.latest = null;
    this.renewsAt = this.renewalAt(subscription.leaseExpiresAt);
    this.schedule();
    persist({ hub: this.hub, subscription });
    if (before !== null && before.endpoint !== subscription.endpoint) this.retire(before.endpoint);
  }

  /** When to renew a lease that runs out at `expiresAt`, by {@link renewalDelay} from now: never, for no expiry. */
  private renewalAt(expiresAt: number | null): number {
    if (expiresAt === null) return Infinity;
    return Date.now() + renewalDelay((expiresAt - Date.now()) / 1000);
  }

  /**
   * Unsubscribes `endpoint`, best-effort and after any unsubscribed already.
   * Never awaited by the connection: a hub failing or silent leaves it as it is.
   */
  private retire(endpoint: string) {
    const { hub, token } = this;
    this.retiring = this.retiring.then(() => unsubscribe(hub, endpoint, token.accessToken));
  }

  /** Sets the renewal timer for when the lease is due. */
  private schedule() {
    clearTimeout(this.timer);
    if (!Number.isFinite(this.renewsAt)) return;
    this.timer = setTimeout(
      () => void this.renew(),
      Math.min(this.renewsAt - Date.now(), LONGEST_TIMER_MS),
    );
  }

  /** Renewal: a new subscription and its socket; reconnecting, by subscribing anew, when it fails. */
  private async renew() {
    this.renewing = true;
    let renewed = false;
    try {
      renewed = await this.subscribe();
    } catch {
      // The hub refused the subscription, or did not answer it in time: the renewal failed.
    }
    this.renewing = false;
    if (this.closed || renewed) return;
    this.channel?.close();
    this.channel = null;
    this.resubscribing = true;
    this.reconnect(0);
  }

  /**
   * The confirmed socket, lost from the hub's side: the end of the connection
   * when the hub denied the subscription on it, a renewal under way or not;
   * otherwise reconnecting. Nothing more once the connection is closed.
   */
  private lost(how: Lost) {
    this.channel = null;
    if (how === "denied") this.end();
    if (this.closed || this.renewing) return;
    clearTimeout(this.timer);
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

  /** One retry: connected again once a socket is confirmed, or the next retry. */
  private async retry(attempt: number) {
    try {
      await this.restore();
    } catch {
      // The hub refused the subscription, or did not answer it in time: this retry failed.
    }
    if (this.closed) return;
    if (this.channel === null) this.reconnect(attempt + 1);
    else this.listener.status("connected");
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
