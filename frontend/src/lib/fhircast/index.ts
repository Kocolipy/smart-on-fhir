/**
 * The FHIRcast module's facade: connects this tab to Epic's local FHIRcast hub
 * (ADR 0016), and the only path the rest of the SPA imports it by
 * (`fhircast-only-through-the-facade`). It holds no code of its own: each
 * export comes from the internal module that owns it, beside this file —
 *
 * - `model.ts` — the vocabulary the others share: the hub and its Hub
 *   Subscriptions as the tab holds them (`Hub`, `Subscription`, `Stored`), the
 *   `PatientEvent` a caller is handed, the one `FhircastStatus` a connection
 *   reports, and the loopback rule;
 * - `release.ts` — the release the backend hands over — the hub always, the
 *   SMART access token while it is unexpired — and its decoder;
 * - `protocol.ts` — every HTTP request made of the hub, each bounded: the
 *   configuration, the registration, the hub token, the subscription and the
 *   unsubscription;
 * - `client-key.ts` — the browser profile's dynamic client: its
 *   non-extractable RS384 key, kept in IndexedDB with its `client_id`, the
 *   client assertion it signs, and the Web Locks lock a registration is held
 *   under;
 * - `hub-token.ts` — the hub token: the configuration, the profile's client or
 *   a new registration — once more, when the hub has forgotten the client —
 *   and the token for that client's assertion;
 * - `notifications.ts` — the socket, and the event notifications read off it;
 * - `storage.ts` — what the tab keeps in `sessionStorage`, and its cleanup;
 * - `connection.ts` — the renewing connection: renewal with a new hub token,
 *   reconnecting, one live subscription, resuming after a reload, ending once
 *   the profile's client is lost, each reported as its one status; every hub
 *   token it obtains, subscription the hub accepts, socket the hub confirms
 *   and subscription it moves off reported to the tab's standing Hub
 *   Subscriptions;
 * - `subscriptions.ts` — the tab's standing Hub Subscriptions: every one the
 *   hub accepted across every connection of the session, Retries included,
 *   the newest hub token the tab obtained at each hub, and the rule that each is
 *   cancelled exactly once and the teardown settles within its 3-second
 *   bound (ADR 0011);
 * - `session.ts` — the session's one connection, from its release to the end
 *   of its FHIRcast, and the standing Hub Subscriptions it ends then: every
 *   piece of the module's state.
 *
 * `protocol.ts` and `notifications.ts` are, beside the HTTP transport, the
 * only places allowed to use `fetch` or `WebSocket`
 * (`test/arch/rawNetwork.test.ts`): the hub is another origin, on the
 * clinician's loopback interface, so none of `apiFetch`'s session, CSRF or
 * status rules apply to it. `client-key.ts` is the only one allowed
 * `crypto.subtle`, `indexedDB` and `navigator.locks`
 * (`test/arch/browserKeys.test.ts`).
 *
 * Given a release — the hub its Epic Login named and the application's
 * `softwareId`, and the session's SMART access token while it is unexpired,
 * fetched from the backend through the session seam — it runs:
 *
 * 1. the hub's configuration (`<hub.url>/subspace-configuration`), for its
 *    `audience` and whether it is read-only, at `hub.url` held to `127.0.0.1`;
 * 2. the browser profile's dynamic client for that audience, from IndexedDB —
 *    or, with none, the hub not read-only and a SMART access token unexpired,
 *    a registration under the Web Locks lock named for the audience: the
 *    profile checked again, a new non-extractable RS384 key generated, its
 *    public JWK registered (`<hub.url>/register`) with the release's
 *    `softwareId` and the SMART access token as `Bearer`, and the key and
 *    `client_id` kept; a tab that finds a client once it holds the lock uses
 *    it, so concurrent tabs register once. A read-only hub with no client
 *    kept is `unavailable`; no client kept and no SMART access token is
 *    `ended`;
 * 3. a hub token (`<hub.url>/token`), for a `jwt-bearer` client assertion
 *    that key signs RS384 — `iss` and `sub` the `client_id`, `aud` the
 *    audience, `exp` four minutes ahead, `jti` a random UUID — and its
 *    `expires_in`. When the hub answers `INVALID-CLIENT`, it has forgotten
 *    the client: the profile forgets it too and, under the lock, registers
 *    once more with the SMART access token — or uses the client another tab
 *    registered meanwhile — then asks again;
 * 4. the subscription to `patient-open` and `patient-close` on the topic, over
 *    a websocket, at `hub.url`, the hub token as `Bearer`;
 * 5. the socket at the `hub.channel.endpoint` the hub answers, held to `ws(s)`
 *    on `127.0.0.1`, connected once the hub confirms the subscription on it;
 * 6. on the connected socket, each event notification held to the FHIRcast
 *    STU2 shape, the two events in any case and the topic, acknowledged with
 *    its `id` and handed on as a {@link PatientEvent}; a denial of the
 *    subscription a reconnect; anything else ignored, unanswered.
 *
 * Nothing a notification carries is logged.
 *
 * A refusal of the configuration, the registration or the token request, or
 * one left unanswered for ten seconds, is `unavailable`, as a refused
 * subscription is.
 *
 * The SMART access token and the hub tokens stay in the tab's memory and are
 * never stored; the private key never leaves WebCrypto. Connected, it stays
 * connected for the session, past the SMART access token's expiry. Before the
 * sooner of the hub token's expiry and the end of the lease the hub granted
 * (`hub.lease_seconds`, or `hub.lease-seconds`), it renews: a new hub token,
 * a new subscription made with it, its new socket replacing the old once
 * confirmed. A socket the hub drops, the hub's denial of the live
 * subscription, and a renewal that fails, start reconnecting: retries spaced
 * by a doubling backoff, each reopening the socket — or subscribing anew with
 * a new hub token once the hub denies that endpoint or the live
 * subscription, or after a failed renewal — until one connects or the
 * retries run out. Every subscription is bounded: one the hub leaves
 * unanswered for ten seconds is given up as a refusal, so a hung first
 * subscription shows `unavailable` and a hung renewal reconnects.
 *
 * The connection ends, for good, only when the hub has forgotten the
 * profile's client and the tab cannot register one again — no SMART access
 * token is left, the hub is read-only, or registering once more fails or is
 * forgotten in turn: it renews nothing, reconnects nothing, closes the
 * socket and shows `ended`, and only a fresh launch from Epic connects
 * again. A reload or Retry the backend releases nothing to shows
 * `unavailable`.
 *
 * One Hub Subscription is live at a time. Whenever the connection moves to a
 * new one — a renewal, or any subscribing anew — it retires the one before
 * once the new socket is confirmed: the tab's standing Hub Subscriptions
 * unsubscribe that endpoint with the newest hub token the tab obtained there,
 * best-effort and bounded, as tearing down's is, never holding the
 * connection up.
 *
 * The tab keeps its hub and its live Hub Subscription in its
 * `sessionStorage`, under {@link FHIRCAST_STORAGE_KEYS} — an accepted
 * deviation (ADR 0011): the hub URL and topic, and the subscription's channel
 * endpoint, when its lease runs out and when the hub token it was made with
 * expires; never a token. A reload fetches the release first, obtains a hub
 * token for the profile's client — with no SMART access token at all, once
 * it has expired — then resumes what is kept; what is kept is decoded, never
 * trusted — anything malformed or partial is taken for none, and cleared,
 * and every URL is held to `127.0.0.1` again:
 *
 * - kept for another hub URL or topic than the release's: forgotten, and the
 *   tab subscribes from scratch;
 * - the lease and the hub token it was made with unexpired: the channel
 *   endpoint reopened, with no new subscription, renewal due by the sooner
 *   stored expiry;
 * - either run out, or the hub denies the endpoint: subscribed anew with the
 *   new hub token.
 *
 * When the session ends, {@link endFhircast} forgets what the tab kept at
 * once, stops the session's connection, then ends the tab's standing Hub
 * Subscriptions, which every connection the tab made in this page load
 * reported to, a Retry's included: `hub.mode=unsubscribe` for the topic at
 * each channel endpoint still standing — the live subscription's, and any
 * never confirmed — once however many connections held it, side by side, the
 * newest hub token the tab obtained at its hub as `Bearer`, or a fresh one for the
 * profile's client once that has expired, then every socket closed. A
 * subscription already retired is not unsubscribed again. It is best-effort
 * and bounded: a request that fails never stops the rest, and the whole
 * teardown, a fresh hub token and the retirements still in flight included,
 * is given up three seconds on (ADR 0011). It keeps the profile's dynamic
 * client, which the next session in the browser profile connects with; a
 * Logout's `Clear-Site-Data` drops it with the origin's storage.
 *
 * It reports one status, the snapshot's, and nothing else: no hub's or Epic's
 * `error_description`, no denial's `hub.reason`, and no response body, is
 * ever handed back or logged; of a refused token request, only its `error`
 * is read, for `INVALID-CLIENT`.
 */

export type { FhircastStatus, PatientEvent } from "./model";
export { decodeFhircastRelease, type FhircastRelease } from "./release";
export {
  endFhircast,
  type FhircastReleaseSource,
  fhircastSnapshot,
  followFhircastEvents,
  retryFhircast,
  startFhircast,
  watchFhircast,
} from "./session";
export { clearFhircastStorage, FHIRCAST_STORAGE_KEYS } from "./storage";
