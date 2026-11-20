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
 *   a new registration, and the token for that client's assertion;
 * - `notifications.ts` — the socket, and the event notifications read off it;
 * - `storage.ts` — what the tab keeps in `sessionStorage`, and its cleanup;
 * - `connection.ts` — the renewing connection: renewal, reconnecting, one
 *   live subscription, resuming after a reload, ending with the token, each
 *   reported as its one status;
 * - `session.ts` — the session's one connection, from its release to its
 *   teardown at the hub: every piece of the module's state.
 *
 * `protocol.ts` and `notifications.ts` are, beside the HTTP transport, the
 * only places allowed to use `fetch` or `WebSocket`
 * (`test/arch/rawNetwork.test.ts`): the hub is another origin, on the
 * clinician's loopback interface, so none of `apiFetch`'s session, CSRF or
 * status rules apply to it. `client-key.ts` is the only one allowed
 * `crypto.subtle`, `indexedDB` and `navigator.locks`
 * (`test/arch/browserKeys.test.ts`).
 *
 * Given a release — the session's SMART access token, the application's
 * `softwareId` and the hub its Epic Login named, fetched from the backend
 * through the session seam — it runs:
 *
 * 1. the hub's configuration (`<hub.url>/subspace-configuration`), for its
 *    `audience` and whether it is read-only, at `hub.url` held to `127.0.0.1`;
 * 2. the browser profile's dynamic client for that audience, from IndexedDB —
 *    or, with none and the hub not read-only, a registration under the Web
 *    Locks lock named for the audience: the profile checked again, a new
 *    non-extractable RS384 key generated, its public JWK registered
 *    (`<hub.url>/register`) with the release's `softwareId` and the SMART
 *    access token as `Bearer`, and the key and `client_id` kept; a tab that
 *    finds a client once it holds the lock uses it, so concurrent tabs
 *    register once. A read-only hub with no client kept is `unavailable`;
 * 3. a hub token (`<hub.url>/token`), for a `jwt-bearer` client assertion
 *    that key signs RS384 — `iss` and `sub` the `client_id`, `aud` the
 *    audience, `exp` four minutes ahead, `jti` a random UUID;
 * 4. the subscription to `patient-open` and `patient-close` on the topic, over
 *    a websocket, at `hub.url`, the hub token as `Bearer`;
 * 5. the socket at the `hub.channel.endpoint` the hub answers, held to `ws(s)`
 *    on `127.0.0.1`, connected once the hub confirms the subscription on it;
 * 6. on the connected socket, each event notification held to the FHIRcast
 *    STU2 shape, the two events in any case and the topic, acknowledged with
 *    its `id` and handed on as a {@link PatientEvent}; a denial of the
 *    subscription the connection's end; anything else ignored, unanswered.
 *
 * Nothing a notification carries is logged.
 *
 * A refusal of the configuration, the registration or the token request, or
 * one left unanswered for ten seconds, is `unavailable`, as a refused
 * subscription is.
 *
 * The SMART access token and the hub token stay in the tab's memory, held by
 * the connection for its whole life, and are never stored; the private key
 * never leaves WebCrypto. Connected, it stays connected. Before the lease the
 * hub granted (`hub.lease_seconds`, or `hub.lease-seconds`) runs out, it
 * renews: a new subscription with the same hub token, its new socket replacing the old
 * once confirmed. A socket the hub drops, and a renewal that fails, start
 * reconnecting: retries spaced by a doubling backoff, each reopening the
 * socket — or subscribing anew once the hub denies that endpoint, or after a
 * failed renewal — until one connects or the retries run out. Every
 * subscription is bounded: one the hub leaves unanswered for ten seconds is
 * given up as a refusal, so a hung first subscription shows `unavailable` and
 * a hung renewal reconnects.
 *
 * The connection ends with the SMART access token, for good: once the hub
 * denies the live subscription on its confirmed socket, or the token's
 * expiry passes, it renews nothing, reconnects nothing and closes the
 * socket, and shows `ended` — only a fresh launch from Epic connects again.
 * A release whose expiry has passed is taken to no hub, nor is a release of
 * the hub alone, without the token, which shows `ended`: the backend answers
 * one once the token has expired. A reload or Retry the backend releases
 * nothing to shows `ended` too once the expiry the tab kept has passed,
 * `unavailable` otherwise.
 *
 * One Hub Subscription is live at a time. Whenever the connection moves to a
 * new one — a renewal, or any subscribing anew — it cancels the one before
 * once the new socket is confirmed: that endpoint unsubscribed with the hub
 * token, best-effort and bounded, as tearing down's is, never holding the
 * connection up.
 *
 * The tab keeps its hub, its live Hub Subscription and when the SMART access
 * token expires in its `sessionStorage`, under {@link FHIRCAST_STORAGE_KEYS}
 * — an accepted deviation (ADR 0011): the hub URL and topic, the
 * subscription's channel endpoint and when its lease runs out, and the
 * token's expiry instant. A reload
 * fetches the release first, for its SMART access token, and obtains a hub
 * token for the profile's client, then resumes what is kept; what is kept is
 * decoded, never trusted — anything malformed or partial
 * is taken for none, and cleared, and every URL is held to `127.0.0.1` again:
 *
 * - kept for another hub URL or topic than the release's: forgotten, and the
 *   tab subscribes from scratch;
 * - the lease unexpired: the channel endpoint reopened, with no new
 *   subscription, renewal due by the stored expiry;
 * - the lease run out, or the hub denies the endpoint: subscribed anew with
 *   the hub token.
 *
 * When the session ends, {@link endFhircast} forgets what the tab kept at
 * once, then tears down every connection the tab made in this page load:
 * `hub.mode=unsubscribe` for the topic at each channel endpoint it holds — the
 * live subscription's, and any never confirmed — the hub token as `Bearer`,
 * then the socket closed. Every request is best-effort and bounded:
 * one that fails, or goes unanswered for three seconds, never stops the rest
 * (ADR 0011).
 *
 * It reports one status, the snapshot's, and nothing else: no hub's or Epic's
 * `error_description`, no denial's `hub.reason`, and no response body, is
 * ever handed back or logged.
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
