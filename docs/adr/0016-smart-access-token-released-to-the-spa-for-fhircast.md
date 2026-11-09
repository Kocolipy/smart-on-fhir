---
type: Decision Record
title: "ADR 0016: SMART access token released for FHIRcast"
description: The one Epic token that leaves the backend — the SMART access token released to the SPA so the browser can connect to Epic's loopback FHIRcast hub, and how the tab connects, stays connected, ends with that token and tears down.
tags: [fhircast, epic, smart-on-fhir, spa]
---

# 16. The SMART access token is released to the SPA for FHIRcast

Date: 2026-10-24

## Status

Accepted. It decides the one Epic token that leaves the backend: the SMART
access token, released to the signed-in SPA through one endpoint so the browser
can connect to Epic's local FHIRcast hub. ADR 0012's D29 keeps every Epic token
server-side but this one, and states, with D13 and D28, what the FHIRcast scopes
mean for each; ADR 0011 records the deviations this decision accepts.

`/docs/domain/epic-login.md` is the behaviour in full: the connection's states,
its timings, the reload table and the teardown's steps. This file holds the
decisions and their reasons.

## Context

A clinician who opens a chart in Epic should see this application follow that
patient. Epic announces the chart a clinician opens and closes as FHIRcast STU3
events (`Patient-open`, `Patient-close`) on a hub. For the deployments in scope
that hub runs on the clinician's own workstation, so its `hub.url` is a loopback
address. Epic's token response names the hub (`hub.url`) and the clinician's
session topic (`hub.topic`) beside the launch context, when the authorize
request asks for a `fhircast/` scope.

A subscriber reaches the hub as FHIRcast STU3 describes: a websocket
subscription request to `hub.url`, answered with the `hub.channel.endpoint` the
subscriber opens its socket at, and cancelled by an unsubscription request.
Epic's hub authorizes the subscription by the SMART access token of the launch,
presented as `Bearer`: the Epic team has confirmed that it accepts that token on
the Hub Subscription (ADR 0012, "External confirmations").

The backend cannot do this for the browser. The hub listens on the clinician's
loopback interface, which only a process on that workstation reaches; the
server never can. So the browser makes every hub call, and each needs the SMART
access token, which ADR 0012's D29 otherwise keeps server-side.

## Decision

### The backend releases the access token, and nothing else

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

### The SPA connects

A hand-rolled FHIRcast module beside the HTTP transport
(`frontend/src/lib/fhircast/`, imported through its facade `index.ts`), whose
hub protocol (`protocol.ts`) and notifications (`notifications.ts`) are the only
places besides the transport allowed to use `fetch` or `WebSocket`, makes every
hub call:

1. it fetches the release, through the session seam;
2. it subscribes at `hub.url`, held to `127.0.0.1` (`hub.channel.type=websocket`,
   `hub.mode=subscribe`, `hub.events=Patient-open,Patient-close`, `hub.topic`),
   the SMART access token as `Bearer`, and gives the hub 10 seconds to answer;
3. it requires the `hub.channel.endpoint` the hub answers to be `ws` or `wss`
   on `127.0.0.1`, opens the socket there and accepts the subscription
   confirmation, which the hub has 10 seconds to send.

- **Every request of the hub is bounded.** A subscription the hub leaves
  unanswered for 10 seconds is given up and fails as a refused one does: the
  first shows `FHIRcast unavailable`, and a renewal's reconnects (below). The
  bound is the 10 seconds the hub has to confirm the subscription on its
  socket, since both wait on the hub to accept it; an unsubscription gets 3
  (below, "The connection's lifetime"). The request is aborted when given up,
  so nothing of it lingers and no answer after it is read: a subscription the
  hub went on to make of it has an endpoint the tab never learned, so none of
  the tab's teardowns can name it; no socket is ever confirmed on it, and its
  lease ends it at the hub. A timeout is the hub's silence, not the browser's
  refusal, so it is never `local hub blocked by browser`.

  _Why:_ unbounded, a hung renewal would hold the connection in its renewing
  state, in which a dropped socket is left to the renewal: the card would say
  `connected` with no socket until the SMART access token expired, and a hung
  first subscription would leave it `connecting` for good.

- **The Hub Subscription is made with the SMART access token, held in memory
  for the connection's life.** The connection keeps the token in the tab's
  memory from the release until it is torn down, and presents it on every
  subscription, renewal and unsubscription it makes. It is never stored: a
  reload fetches the release again, and the token with it.
- **The tab keeps only its hub, its one live Hub Subscription and when the
  token expires** in `sessionStorage` (`frontend/src/lib/fhircast/storage.ts`):
  the hub URL and topic, the subscription's channel endpoint and lease expiry,
  and the SMART access token's expiry instant, never the token (ADR 0011,
  "FHIRcast in the browser"). No key, `client_id` or other hub credential is
  kept, because the hub issues none. A reload so loses nothing it must tear
  down, and every end of the session clears it.
- **The connection stays up by subscribing again.** Before the subscription's
  lease runs out, the module subscribes again with the same SMART access token;
  the new subscription's socket replaces the old once confirmed. A dropped
  socket and a failed renewal — one the hub refused, or left unanswered for
  10 seconds — reconnect with a bounded, doubling backoff, each
  retry reopening the endpoint, or subscribing again with the SMART access
  token once the hub denies it or a renewal failed; when the retries run out
  the card shows `disconnected` and a Retry, which connects again from the
  release. The renewals are not bound to the session's lifetime (ADR 0011).
- **The connection ends with the SMART access token, and the clinician
  relaunches from Epic.** The hub ties a Hub Subscription to the access token
  it was made with: once that token expires or is revoked, it denies the
  subscription on its socket, as FHIRcast lets a hub deny one at any time
  (`hub.mode` `denied`), and that endpoint cannot be used again. Epic Login asks for no refresh
  token (below, "Alternatives considered"), so nothing can renew the token,
  and the connection stops — no renewal, no reconnect, the socket closed —
  when either comes first: the hub denies the live Hub Subscription on its
  confirmed socket, or the token's expiry, as the release stated it, passes
  on the browser's clock, though the hub has said nothing yet. The card then
  shows its final state, telling the clinician to reopen the application
  from Epic: that relaunch is a fresh Epic Login, with a new token. It offers
  no Retry. A reload or Retry once the expiry has passed shows the same final
  state and asks the hub nothing: the backend refuses to release a token past
  its expiry, and the tab tells that refusal from "FHIRcast unavailable" by
  the expiry it kept. A denial at an endpoint being reopened, which a
  reconnect meets once a lease has run out, is not this end: the retry
  subscribes anew.
- **The card says little.** It shows one of seven generic states —
  `connecting`, `connected`, `reconnecting`, `disconnected`,
  `FHIRcast unavailable`, `local hub blocked by browser`, and the final
  `FHIRcast ended`, which says to reopen the application from Epic — the one
  status the connection reports, `ended` its only signal that it ended, and never
  renders or logs a hub's or Epic's `error_description`, a denial's
  `hub.reason`, or any response body.

### The connection's lifetime

The connection lasts the Epic session, on every signed-in page, not only while
Home is mounted.

- **One connection per tab, outside React.** The FHIRcast module holds the
  session's connection as a module-level singleton: what it shows (the card's
  state and the last event) and how to close it. Its start is idempotent, so a
  second start — a remount, a navigation, React's double effects in
  development — changes nothing. It is outside React because no component lives
  as long as the session: Home unmounts whenever the clinician opens another
  page, and a connection held in Home's state would subscribe and reconnect
  again on every return, and lose every event sent meanwhile.
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
- **Only the end of the session tears it down** — sign-out, expiry, an Idle
  sign-out, a password change — through `endFhircast`; the end of the SMART
  access token only stops it (above). It clears what the tab kept at
  once, resets the singleton so the next session connects anew, and then tears
  down at the hub every connection this page load made: it unsubscribes the
  live Hub Subscription, and any subscription whose socket was never
  confirmed, with the SMART access token the connection holds, then closes the
  socket. It requests no revocation and no registration deletion: the hub
  issued nothing to revoke or delete. It runs after the backend has ended the
  session, never before, each step best-effort and bounded, so a hub that
  fails or hangs cannot keep a clinician signed in (ADR 0011). A subscription a
  page before a reload kept that no connection here resumed is only forgotten:
  the token it was made with was never stored, and its lease ends it at the
  hub. Leaving Home stops nothing.

### A reload, and one live subscription

- **A reload resumes the subscription.** The signed-in layout still fetches the
  release first, for its SMART access token; the module then resumes the
  subscription the tab kept when it names the release's hub URL and topic. It
  reopens the stored endpoint while the lease lasts, with no request to the
  hub, and subscribes again with the SMART access token once the lease has run
  out or the hub denies the endpoint. With nothing kept, or what was kept
  naming another hub URL or topic, it forgets what was kept and subscribes from
  scratch. What is kept is decoded rather than trusted: anything malformed or
  partial counts as nothing kept and is cleared, and every URL in it is held
  to `127.0.0.1` again.

  _Why:_ a tab that subscribed anew on every reload would leave the
  subscription before it standing at the hub until its lease ran out, since
  the page before tore nothing down.

- **One live Hub Subscription.** Whenever the connection moves to a new
  subscription — a renewal, a resubscribe on resume, a resubscribe after a
  denial — it cancels the one before once the new socket is confirmed: that
  endpoint unsubscribed with the SMART access token, best-effort and bounded,
  never holding up the connection. What the tab keeps always describes exactly
  that one live subscription, and the teardown waits for a cancellation still
  running before it unsubscribes the rest.

  _Why:_ a renewal that only closed the old socket would leave the old
  endpoint subscribed, and a teardown that knew only the latest would let older
  subscriptions outlive the session until their leases ran out.

- **Duplicated tabs get no handling of their own.** Chrome copies
  `sessionStorage` into a duplicated tab, so both resume the same
  subscription. When one moves to a new subscription and cancels the old, the
  other loses the old endpoint. A hub that closes that tab's socket sends it
  reconnecting; the reopened endpoint is denied, and that tab subscribes on its
  own. A hub that instead denies the subscription on the live socket ends that
  tab's connection, as every denial of a live subscription does: the SPA reads
  no `hub.reason`, so it cannot tell a cancelled subscription from an expired
  token, and that tab shows `ended` until the clinician reopens the application
  from Epic. A duplicated tab is rare enough to accept that. Logout ends the
  shared session in both.

### The events and the EHR context

The hub's `Patient-open` and `Patient-close` move the tab's **EHR context**
(`/CONTEXT.md`), so the Patient card follows the chart the clinician has open in
Epic, on whichever signed-in page the tab is on. The FHIRcast module hands each
event it shows to whoever follows its events; the auth-side glue
(`useSessionFhircast`) follows them into the EHR context's store, so the module
still imports nothing of the auth layer.

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
  disconnected, unavailable, blocked, ended, and a reload all keep the EHR
  context as last known. Clearing it would blank the Patient card on every network blip
  and reload, and the EHR has not said the chart closed; the FHIRcast card's
  status, anything but `connected`, is what tells the clinician the Patient
  card may be out of date.

## Consequences

- The browser holds a bearer credential Epic issued, in memory for as long as
  the connection lasts. A script running in the SPA's origin can read it; the
  CSP (`script-src 'self'`, no inline script) is what keeps that origin the
  application's own.
- The access token is a key to patient-context events, which ADR 0012's D28
  classifies Restricted, Sensitive High; it stays unencrypted at rest in Redis
  and unrevoked at Epic, as D29 and D13 accept.
- The application's audit trail records each release, not what the browser does
  with the token: the subscriptions are the hub's to record (ADR 0011).
- Chrome's Local Network Access prompt guards a public origin's requests to
  loopback. A clinician who refuses it sees "local hub blocked by browser"; a
  deployment can allow the application's origin with the
  `LocalNetworkAccessAllowedForUrls` policy (`frontend/docs/BACKEND_CONTRACT.md`).
  Chrome holds the first subscription while that prompt waits, so one left
  unanswered past the subscription's 10 seconds shows `FHIRcast unavailable`;
  a reload once it is allowed connects.
- FHIRcast lasts no longer than the SMART access token, about an hour after
  the launch in Epic's examples. A clinician whose session outlasts it sees
  the card's final state, and follows charts again only by reopening the
  application from Epic; until then the Patient card stays as last known,
  and the card's state is what says it may be out of date.
- Adding a FHIR resource scope, or releasing anything else Epic issued, is a
  new decision, not an extension of this one.

## Alternatives considered

**Registering the tab at the hub and subscribing with a Hub Token.** The tab
would register an ES384 key of its own by RFC 7591 dynamic client
registration, presenting the SMART access token, then obtain a Hub Token by the
RFC 7523 `jwt-bearer` grant with an assertion signed by that key, and subscribe
with the Hub Token; tearing down would revoke the Hub Token (RFC 7009) and
delete the registration (RFC 7592). The SMART access token would then be needed
only to register. Rejected: Epic's local hub does not authenticate subscribers
that way, and the Epic team confirmed that it accepts the SMART access token
itself on the Hub Subscription. The chain would also have put a private key and
hub credentials in `sessionStorage` for no hub to accept.

**Asking for a refresh token, to keep FHIRcast past the access token's
expiry.** Epic Login would ask for `online_access` (or `offline_access`), and
before the access token expired the backend would refresh it and release the
new one, so the tab could subscribe again with it and stay connected for the
whole session. Rejected: Epic's own guidance is that "refresh tokens are not
typically needed for embedded (SMART on FHIR) launches", and an embedded
launch is the only way in here; the clinician reopens the application from
Epic instead, which is a fresh Epic Login with a new token. A refresh token
would also reopen the at-rest risk ADR 0012 accepts only because none is
issued: it would sit unencrypted in Redis on the session (D29), valid at Epic
for its own, longer lifetime, and unrevoked when the session ends (D13) —
the most valuable Epic token, kept for nothing but to extend FHIRcast.
