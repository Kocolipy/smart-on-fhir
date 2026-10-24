# 16. The SMART access token is released to the SPA for FHIRcast

Date: 2026-10-24

## Status

Accepted, and in force. It replaces ADR 0012 D29 for the SMART access token
alone: that one token is released to the signed-in SPA, through one endpoint,
so the browser can connect to Epic's local FHIRcast hub. The `id_token` and any
refresh token stay server-side exactly as D29 keeps them. It is also the revisit
D28 asks for once FHIR scopes are added, and the revisit of D29's at-rest and
revocation positions. ADR 0011 records the deviations it accepts. Last revised
2026-10-24: the connection is renewed and reconnected, and the card shows six
generic states. History is in git.

## Context

A clinician who opens a chart in Epic should see this application follow that
patient. Epic announces the chart a clinician opens and closes as FHIRcast STU3
events (`Patient-open`, `Patient-close`) on a hub. For the deployments in scope
that hub runs on the clinician's own workstation, so its `hub.url` is a loopback
address. Epic's token response names the hub (`hub.url`) and the clinician's
session topic (`hub.topic`) beside the launch context, when the authorize
request asks for a `fhircast/` scope.

The hub authorizes a subscriber in three steps: an RFC 7591 dynamic client
registration presented with the SMART access token as `Bearer`, a Hub Token
obtained with the RFC 7523 `jwt-bearer` grant, signed by the key registered,
and a websocket subscription made with that Hub Token.

The backend cannot do this for the browser. The hub listens on the clinician's
loopback interface, which only a process on that workstation reaches; the
server never can. So the browser makes all three calls, and the first needs the
SMART access token, which D29 keeps server-side and never releases.

## Decision

- **Scopes.** Epic Login asks for
  `launch openid fhirUser fhircast/Patient-open.read fhircast/Patient-close.read`,
  in that order (FHIRcast STU3 scope syntax). No FHIR resource scope is added:
  the access token still reads no FHIR resource.
- **The hub is kept beside the tokens.** At the callback, `hub.url` and
  `hub.topic` are read from the token response (`FhircastHub`) and kept on the
  signed-in session beside `EpicTokenSet`, as the attribute
  `app.epic.fhircast-hub` (`SignedInSession.keepFhircastHub`). A session
  attribute for D29's reason: it ends with the session on every path. The pair
  is kept only when both members pass:
  - `hub.url` is an `http` or `https` URL whose host is exactly `127.0.0.1`, on
    any port, with no user-info;
  - `hub.topic` matches `[A-Za-z0-9\-._~]{1,128}`.

  Either failing drops the pair, and nothing about it is logged. `patient` and
  `encounter` still go to the landing's fragment unchanged (D8).

- **One endpoint releases the access token.**
  `GET /api/auth/epic/fhircast` (`EpicFhircastController`) answers the signed-in
  session's SMART access token, its expiry, its granted scope, `hub.url` and
  `hub.topic`, and nothing else: the `id_token` and any refresh token never
  leave the backend. It is self-service — a session with baseline access, no
  Permission — and answers `Cache-Control: no-store`. It has no rate limit: the
  caller is already an authenticated session, and every call is audited. It
  answers `404` for a password Login's session, a session that kept no hub, and
  an access token past its expiry; while Epic Login is off the release gate
  answers `404` for it, as for every Epic route. The release is
  `FhircastAccessService.release`, which reads the session through the
  `EpicTokens` port (`forSession`, `fhircastHubForSession`) and hands the
  controller a `FhircastAccess`, a projection with no field the `id_token` or a
  refresh token could go in.
- **Every answer is audited.** Each release, and each refusal the controller
  makes, writes one `FHIRCAST_TOKEN_RELEASE` row against the caller: `SUCCESS`
  fail-closed, so a token this service cannot account for is not released, and
  `FAILURE` fail-open with its reason as the `errorCode` (`NO_EPIC_TOKENS`,
  `NO_FHIRCAST_HUB`, `TOKEN_EXPIRED`). No row and no log record carries a token
  value (D22). The release gate's `404` while Epic Login is off reaches no
  controller and is not recorded: the route does not exist then.
- **One named exception to "Epic's tokens never reach a REST controller".**
  `ArchitectureTest.epic_tokens_never_reach_a_rest_controller` still holds every
  REST controller away from `EpicTokenSet` and `EpicTokens`, this one included.
  `ArchitectureTest.the_released_access_token_reaches_one_rest_controller` holds
  `FhircastAccess`, the type carrying the released access token, to
  `EpicFhircastController` alone, and `EpicTokenRulesTests` shows any other
  controller failing it.
- **The CSP admits the loopback hub, and no Epic origin.** `connect-src` adds
  `http://127.0.0.1:*`, `https://127.0.0.1:*`, `ws://127.0.0.1:*` and
  `wss://127.0.0.1:*`, nothing else. The hub's port is Epic's to choose, so the
  port is open; the host is not.
- **The SPA connects.** A hand-rolled FHIRcast module beside the HTTP transport
  (`frontend/src/lib/fhircast.ts`), the only place besides the transport allowed
  to use `fetch` or `WebSocket`, runs on Home in an Epic session:
  1. fetches the endpoint, through the session seam;
  2. reads the hub's `.well-known/fhircast-configuration` under `hub.url`
     (falling back to `.well-known/smart-configuration`) for
     `registration_endpoint` and `token_endpoint`, each of which must be on
     `127.0.0.1`;
  3. generates an ES384 key pair with WebCrypto and registers its public half by
     DCR, the SMART access token as `Bearer`;
  4. obtains a Hub Token with the `jwt-bearer` grant, its assertion `iss` and
     `sub` the registered `client_id`, `aud` the token endpoint, valid for five
     minutes, with a unique `jti`;
  5. subscribes (`hub.channel.type=websocket`, `hub.mode=subscribe`,
     `hub.events=Patient-open,Patient-close`, `hub.topic`);
  6. requires `hub.channel.endpoint` to be `ws` or `wss` on `127.0.0.1`, opens
     the socket and accepts the subscription confirmation.

  The SMART access token, the Hub Token, the dynamic `client_id` and the exported
  private JWK are kept in the tab's `sessionStorage` and cleared when the session
  ends (ADR 0011). The card shows one of six generic states — `connecting`,
  `connected`, `reconnecting`, `disconnected`, `FHIRcast unavailable`, `local
  hub blocked by browser` — and never renders or logs a hub's or Epic's
  `error_description` or any response body.
- **The connection stays up.** Before the Hub Token or the subscription's lease
  (`hub.lease_seconds`) expires, the module renews: a new Hub Token from a fresh
  assertion signed with the key it registered, then a new subscription, whose
  socket replaces the old one once confirmed. Renewal starts at 80% of the
  lifetime or 60 s before expiry, whichever is sooner, and never sooner than
  5 s after the grant, so a lifetime of a minute or less is not renewed without
  pause. A socket the hub drops, and a failed renewal, put the card in
  `reconnecting`: up to five retries with a doubling backoff, each reopening the
  endpoint, or subscribing anew once the hub denies it or after a failed
  renewal. When they run out the card shows `disconnected` and a retry button,
  which connects again from the release. The last event stays shown through
  renewal and `reconnecting`. The chain is not bound to the session's lifetime
  (ADR 0011).

### Data classification (D28, revisited)

The SMART access token is now a key to patient context: with it a holder can
register at the hub and learn which patient the clinician opens and closes. The
`Patient-open` and `Patient-close` events carry the patient's identity in
clinical context, so **the patient-context event data is classified Restricted,
Sensitive High**, and the access token is handled as a credential to that data:
it is released only to the session it was issued for, only over this one
audited, `no-store` endpoint, and never logged. The data owner's classification
(ADR 0012's open item) supersedes this one when it lands.

### Redis at-rest encryption and revocation (D29, revisited)

- **At rest: still not encrypted by the application, an accepted risk.** The
  scopes added are FHIRcast subscription scopes; the access token reads no FHIR
  resource, and no refresh token is asked for. A token copied out of Redis lets
  its holder register at the hub only from the clinician's own workstation,
  since the hub listens on loopback alone. Application-level encryption would
  still need a key held beside the Redis credentials (D16's position), so it
  would protect against a Redis-only compromise and nothing else. The ticket
  that adds a FHIR resource scope or a refresh token revisits this again; the
  options remain envelope encryption under a KMS key, or an encrypted
  replication group.
- **Revocation: still none at session end.** The released token now also lives
  in the tab's `sessionStorage` until the session ends. Epic sets its lifetime
  (ADR 0011 records lifetimes over 30 minutes); nothing revokes it at Epic, so
  it stays valid there until it expires, as D13 already accepts. The dynamic
  registration and the Hub Token are the hub's, on the clinician's workstation,
  and end with the hub's own lifetimes.

## Consequences

- The browser holds a bearer credential Epic issued. A script running in the
  SPA's origin can read it; the CSP (`script-src 'self'`, no inline script) is
  what keeps that origin the application's own.
- The application's audit trail records each release, not what the browser does
  with the token: the DCR registration, the Hub Token and the subscription are
  the hub's to record (ADR 0011).
- Chrome's Local Network Access prompt guards a public origin's requests to
  loopback. A clinician who refuses it sees "local hub blocked by browser"; a
  deployment can allow the application's origin with the
  `LocalNetworkAccessAllowedForUrls` policy (`frontend/docs/BACKEND_CONTRACT.md`).
- Adding a FHIR resource scope, a refresh token, or releasing anything else
  Epic issued is a new decision, not an extension of this one.
