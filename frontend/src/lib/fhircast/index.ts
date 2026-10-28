/**
 * The FHIRcast module's facade: connects this tab to Epic's local FHIRcast hub
 * (ADR 0016), and the only path the rest of the SPA imports it by
 * (`fhircast-only-through-the-facade`). It holds no code of its own: each
 * export comes from the internal module that owns it, beside this file —
 *
 * - `model.ts` — the vocabulary the others share: a Hub Registration and its
 *   subscriptions as the tab holds them (`Client`, `Granted`, `Subscription`,
 *   `Stored`), the `PatientEvent` a caller is handed, and the loopback rule;
 * - `release.ts` — the release the backend hands over, and its decoder;
 * - `protocol.ts` — every HTTP request made of the hub, steps 1 to 4 and
 *   tearing down's three;
 * - `notifications.ts` — the socket, step 5, and the event notifications read
 *   off it (step 6);
 * - `storage.ts` — what the tab keeps in `sessionStorage`, and its cleanup;
 * - `teardown.ts` — tearing a registration down, in order;
 * - `connection.ts` — the renewing connection: renewal, reconnecting, one
 *   live subscription, resuming after a reload;
 * - `session.ts` — connecting a release, and the session's one connection:
 *   every piece of the module's state.
 *
 * `protocol.ts` and `notifications.ts` are, beside the HTTP transport, the
 * only places allowed to use `fetch` or `WebSocket`
 * (`test/arch/rawNetwork.test.ts`): the hub is another origin, on the
 * clinician's loopback interface, so none of `apiFetch`'s session, CSRF or
 * status rules apply to it.
 *
 * Given a release — the session's SMART access token and the hub its Epic
 * Login named, fetched from the backend through the session seam — it runs:
 *
 * 1. discovery: the hub's `.well-known/fhircast-configuration` under
 *    `hub.url` (falling back to `smart-configuration`) for its
 *    `registration_endpoint` and `token_endpoint`, each held to `127.0.0.1`,
 *    and its `revocation_endpoint` when it names one there;
 * 2. an ES384 key pair from WebCrypto, its public half registered by RFC 7591
 *    dynamic client registration, the SMART access token as `Bearer`;
 * 3. a Hub Token by the `jwt-bearer` grant, the assertion signed by that key —
 *    `iss` and `sub` the registered `client_id`, `aud` the token endpoint,
 *    valid five minutes, a `jti` of its own;
 * 4. the subscription to `Patient-open` and `Patient-close` on the topic, over
 *    a websocket, the Hub Token as `Bearer`;
 * 5. the socket at `hub.channel.endpoint`, held to `ws(s)` on `127.0.0.1`,
 *    connected once the hub confirms the subscription on it;
 * 6. on the connected socket, each event notification held to the FHIRcast
 *    STU3 shape, the two events and the topic, acknowledged with its `id` and
 *    handed on as a {@link PatientEvent}; anything else ignored, unanswered.
 *
 * Nothing a notification carries is logged.
 *
 * Connected, it stays connected. Before the Hub Token or the lease the hub
 * granted (`hub.lease_seconds`) runs out, it renews: steps 3 to 5 again, with
 * the key and `client_id` it registered, its new socket replacing the old
 * once confirmed. A socket the hub drops, and a renewal that fails, start
 * reconnecting: retries spaced by a doubling backoff, each reopening the
 * socket — or subscribing anew once the hub denies that endpoint, or after a
 * failed renewal — until one connects or the retries run out.
 *
 * One Hub Subscription is live at a time. Whenever the connection moves to a
 * new one — a renewal, or any subscribing anew — it cancels the one before
 * once the new socket is confirmed: that endpoint unsubscribed with its own
 * Hub Token, then that token revoked. Both steps are best-effort and bounded,
 * as tearing down's are, and never hold the connection up.
 *
 * What it obtains is kept in the tab's `sessionStorage`, one entry under
 * {@link FHIRCAST_STORAGE_KEYS} — an accepted deviation (ADR 0011): the hub
 * URL and topic, the private JWK and its `kid`, the `client_id`, the token and
 * revocation endpoints, the `registration_client_uri` and
 * `registration_access_token` when the hub answered them, and the one live
 * subscription — its channel endpoint, its Hub Token and when each runs out.
 * Never the SMART access token: only registering takes it, and registering
 * anew fetches a new release. A reload resumes what is kept rather than
 * registering again, the release still fetched first; what is kept is
 * decoded, never trusted — anything malformed or partial is taken for none,
 * and cleared, and every URL is held to `127.0.0.1` again:
 *
 * - kept for another hub URL or topic than the release's: torn down and
 *   forgotten, and the tab connects from scratch;
 * - the Hub Token and lease both unexpired: the channel endpoint reopened,
 *   with no request to the hub, renewal due by the stored expiries;
 * - either run out, or the hub denies the endpoint: steps 3 to 5 with the
 *   stored key and `client_id`;
 * - the token endpoint refusing the stored client: the registration deleted,
 *   best-effort, then the tab connects from scratch.
 *
 * When the session ends, {@link endFhircast} forgets what the tab kept at
 * once, then tears down every connection the tab registered or resumed, and
 * what it kept even when this page load never resumed it, in order:
 *
 * 1. `hub.mode=unsubscribe` for the topic, at the subscription's channel
 *    endpoint, the Hub Token as `Bearer`;
 * 2. the Hub Token revoked by RFC 7009 at the metadata's
 *    `revocation_endpoint`, the client authenticated by an assertion signed
 *    with its key;
 * 3. the registration deleted by RFC 7592 at the `registration_client_uri`
 *    the registration answered, its `registration_access_token` as `Bearer`;
 * 4. the socket closed.
 *
 * Steps 2 and 3 are skipped for a hub that names no such endpoint on
 * `127.0.0.1`, and a step with nothing to act on is skipped too. Every request
 * is best-effort and bounded: one that fails, or goes unanswered for three
 * seconds, never stops the next (ADR 0011).
 *
 * It answers one generic outcome and nothing else: no hub's or Epic's
 * `error_description`, and no response body, is ever handed back or logged.
 */

export type { PatientEvent } from "./model";
export { decodeFhircastRelease, type FhircastRelease } from "./release";
export {
  connectFhircast,
  endFhircast,
  type FhircastReleaseSource,
  fhircastSnapshot,
  type FhircastStatus,
  followFhircastEvents,
  retryFhircast,
  startFhircast,
  watchFhircast,
} from "./session";
export { clearFhircastStorage, FHIRCAST_STORAGE_KEYS } from "./storage";

// The types of the interface above that no caller names yet: part of the
// module's public surface all the same, as they were before the split, so kept
// here for a caller to name rather than reached for past the facade.
// fallow-ignore-next-line unused-type
export type { FhircastConnectionState, FhircastHandlers } from "./model";
// fallow-ignore-next-line unused-type
export type { FhircastOutcome, FhircastSnapshot } from "./session";
