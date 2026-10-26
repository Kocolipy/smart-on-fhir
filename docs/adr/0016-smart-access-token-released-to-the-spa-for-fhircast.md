# 16. The SMART access token is released to the SPA for FHIRcast

Date: 2026-10-24

## Status

Accepted, and in force. It replaces ADR 0012 D29 for the SMART access token
alone: that one token is released to the signed-in SPA, through one endpoint,
so the browser can connect to Epic's local FHIRcast hub. The `id_token` and any
refresh token stay server-side exactly as D29 keeps them. It is also the revisit
D28 asks for once FHIR scopes are added, and the revisit of D29's at-rest and
revocation positions. ADR 0011 records the deviations it accepts. Last revised
2026-10-26: a reload resumes the tab's Hub Registration rather than registering
again, and one Hub Subscription is live at a time ("A reload, and one live
subscription"). Revised 2026-10-25: every end of the session tears the
connection down at the hub, and the connection lasts the session rather than
Home ("The connection's lifetime"); and the hub's events move the tab's EHR
context ("The events and the EHR context"). History is in git.

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
  to use `fetch` or `WebSocket`, runs for the whole of an Epic session ("The
  connection's lifetime", below):
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

  The tab's `sessionStorage` keeps the Hub Registration and its one live Hub
  Subscription: the exported private JWK and its `kid`, the dynamic
  `client_id`, the token and revocation endpoints, the
  `registration_client_uri` and `registration_access_token` when the hub
  answered them, the Hub Token and its expiry, the channel endpoint and its
  lease's expiry, and the hub URL and topic they belong to. It is cleared when
  the session ends (ADR 0011). The SMART access token is never kept: only
  registering needs it. The card shows one of six generic states —
  `connecting`, `connected`, `reconnecting`, `disconnected`,
  `FHIRcast unavailable`, `local hub blocked by browser` — and never renders or
  logs a hub's or Epic's `error_description` or any response body.

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
  which connects again from the release, resuming the tab's Hub Registration
  as a reload does. The last event stays shown through
  renewal and `reconnecting`. The chain is not bound to the session's lifetime
  (ADR 0011).

### The connection's lifetime

Amended 2026-10-25 (#201). The connection lasts the Epic session, on every
signed-in page, not only while Home is mounted.

- **One connection per tab, outside React.** The FHIRcast module holds the
  session's connection as a module-level singleton: what it shows (the card's
  state and the last event) and how to close it. Its start is idempotent, so
  a second start — a remount, a navigation, React's double effects in
  development — changes nothing; its Retry closes the connection and connects
  again from the release, with no last event, resuming the Hub Registration the
  tab kept. It is outside React because no
  component lives as long as the session: Home unmounts whenever the clinician
  opens another page, and a connection held in Home's state would register,
  subscribe and reconnect again on every return, and lose every event sent
  meanwhile.
- **Who starts it.** The signed-in layout (`SignedInShell`), which every
  signed-in page renders inside, starts it while the session is signed in and
  the tab holds an **EHR context** (`/CONTEXT.md`) — the tab an EHR launch
  began. The glue sits on the auth side (`useSessionFhircast`): it hands the
  module a source that fetches the release through the session seam, so a
  `401` ends the session as it does anywhere else. The module itself imports
  nothing of the auth layer.
- **The card is a view.** The FHIRcast card on Home reads the singleton and
  starts nothing: its status line, Retry when `disconnected`, and the last
  event, which so survives navigation.
- **What stops it.** Only the end of the session — sign-out, expiry, an Idle
  sign-out, a password change — through `endFhircast`, the teardown above:
  the singleton is reset, so the next session's start connects anew, and every
  connection the tab registered is unsubscribed, its Hub Token revoked, its
  registration deleted and its socket closed, then the tab's storage cleared.
  Leaving Home stops nothing.

### A reload, and one live subscription

Amended 2026-10-26 (#206).

- **A reload resumes the connection.** The FHIRcast module reads what the tab
  kept on load. The signed-in layout still fetches the release first.
  - With nothing kept, the tab connects from scratch.
  - What was kept for another hub URL or topic than the release's is torn down,
    best-effort as at the end of the session, and forgotten. Then the tab
    connects from scratch.
  - While the stored Hub Token and lease both last, the tab reopens the stored
    channel endpoint with no request to the hub. Renewal is due by the stored
    expiries.
  - Once either has run out, or the hub denies the endpoint, the tab mints a
    new Hub Token with the stored key and `client_id`, then subscribes again.
  - If the token endpoint refuses the stored client (`400` or `401`), the
    stored registration is deleted, best-effort, and the tab registers anew
    with the release's SMART access token.

  What is kept is decoded rather than trusted. Anything malformed or partial
  counts as nothing kept, and is cleared. Every URL in it is held to
  `127.0.0.1` again on read.

  _Why:_ before this, every reload registered at the hub again and orphaned the
  registration before it. The registration access token lived only in memory,
  so the orphan could not be deleted at the end of the session either, and
  registrations piled up at the hub, one per reload. Keeping the SMART access
  token served nothing: a fresh registration fetches a new release anyway.

- **One live Hub Subscription.** A renewal, a resubscribe on resume and a
  resubscribe after a denial each move the connection to a new subscription.
  Once the new socket is confirmed, the subscription before is cancelled: its
  endpoint unsubscribed with its own Hub Token, then that token revoked when
  the hub names a `revocation_endpoint`. Each step is bounded at 3 s and
  best-effort, as the teardown's are, and neither holds up the connection.
  What the tab keeps always describes exactly that one live subscription.

  _Why:_ before this, a renewal subscribed again and closed the old socket,
  but never unsubscribed the old endpoint or revoked its Hub Token. The
  teardown knew only the latest ones, so older subscriptions outlived the
  session until their leases ran out.

- **The end of the session tears down what is kept.** `endFhircast` reads what
  the tab kept, clears it at once, and tears it down with whatever this page
  load holds in memory. A reload followed by sign-out, before the connection
  resumed, still unsubscribes, revokes and deletes. It also waits for any
  cancellation still running before it deletes the registration, since a hub
  refuses the Hub Tokens of a registration it no longer holds.
- **Duplicated tabs need nothing special.** Chrome copies `sessionStorage` into
  a duplicated tab, so both resume the same registration. When one moves to a
  new subscription and cancels the old, the hub denies the other at the old
  endpoint, and that tab subscribes on its own. Logout ends the shared session
  in both.

### The events and the EHR context

Amended 2026-10-25 (#202). The hub's `Patient-open` and `Patient-close` move
the tab's **EHR context** (`/CONTEXT.md`), so the Patient card follows the
chart the clinician has open in Epic, on whichever signed-in page the tab is
on. The FHIRcast module hands each event it shows to whoever follows its
events; the auth-side glue (`useSessionFhircast`) follows them into the EHR
context's store, so the module still imports nothing of the auth layer. The
card's Last event is unchanged.

- **A `Patient-open` naming a valid id sets the patient, and drops the
  encounter.** The event names a patient and no encounter, and the encounter
  held belonged to the chart before; kept beside a different patient it would
  be a mismatched pair, and nothing can tell whether the clinician reopened the
  same patient in the same encounter, so it is dropped even when the patient
  is the one already in context. That same patient is no new read: the
  Patient card is left as it is.
- **A `Patient-open` naming no valid id is ignored.** One whose context names
  no Patient within FHIR's id grammar says nothing the SPA could read, so the
  context is left as it was rather than emptied on a malformed event; the
  next valid event sets it right.
- **A `Patient-close` always empties it, whatever id it names or none.** A
  close means the clinician has no chart open for this tab to follow; matching
  its id against the patient in context first would leave a patient shown
  that the EHR no longer has open whenever the hub's id and ours disagree,
  which is the worse mistake for a clinical display. The context stays, as one
  naming nothing, so the tab is still the one an EHR launch began: the FHIRcast
  card and its connection stay, and the next `Patient-open` brings a patient
  back.
- **An event never makes a context.** Only a tab already holding one is moved,
  so an event that arrives as the session ends cannot leave a context behind
  for the next.
- **The context is kept across a lost connection.** Reconnecting,
  disconnected, unavailable, blocked, and a reload all keep the EHR context as
  last known. Clearing it would blank the Patient card on every network blip
  and reload, and the EHR has not said the chart closed; the FHIRcast card's
  status, anything but `connected`, is what tells the clinician the Patient
  card may be out of date.

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
- **Revocation: still none at session end.** The released token is held only
  in the tab's memory, for as long as registering takes; it is never kept in
  `sessionStorage`. Epic sets its lifetime
  (ADR 0011 records lifetimes over 30 minutes); nothing revokes it at Epic, so
  it stays valid there until it expires, as D13 already accepts. The dynamic
  registration and the Hub Token are the hub's, on the clinician's workstation:
  every end of the session unsubscribes, revokes the Hub Token by RFC 7009 and
  deletes the registration by RFC 7592, where the hub supports each, and leaves
  the rest to the hub's own lifetimes — best-effort, an accepted deviation (ADR
  0011).

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
