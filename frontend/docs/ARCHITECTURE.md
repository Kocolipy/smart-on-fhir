---
type: Architecture Reference
title: Frontend architecture
description: The SPA's layers file by file, why every request goes through lib/http.ts and every time through lib/time.ts, the gated read and write, the @/ alias, the token pipeline, routing, and what is deliberately absent.
tags: [architecture, react, spa]
---

# Architecture

What is here, and where a new concern belongs.

## The layers

```
src/
  main.tsx            composition root — seeds the EHR context from an Epic landing's Launch context, mounts React, imports index.css
  App.tsx             app root — BrowserRouter, AuthProvider, the route table
  index.css           Tailwind entry + the design tokens
  vite-env.d.ts       /// <reference types="vite/client" />
  auth/               session state, Permission checks, route guards, request seam
  shell/              the signed-in layout every page renders inside, and the page table (SIGNED_IN_PAGES)
  pages/              one folder per page — its component, its API module declaring its endpoints, and the parts only it uses
  components/         shared non-primitive components (error-boundary.tsx)
  components/ui/      shadcn primitives (see "Why components/ui/ is fenced off")
  lib/                framework-agnostic helpers; a leaf
```

Imports run one way: `main` → `App` → `shell` → `pages` → `auth` →
`components/ui` → `lib`. A layer may skip a step (a page may call `cn` directly, `auth/` reaches
straight into `lib/http`) but may never point back up.
`test/.dependency-cruiser.cjs` encodes that, and `npm run test:arch` runs it.

### `auth/`, file by file

`src/auth/` is a _concern_, not a screen. This is the authoritative per-file map.

- `api.ts` — the `/api/auth/*` calls, each mapping a status to a domain outcome
  (`401` on `/me` is a guest, not an error), the decoder for the session's
  `permissions` and `superuser`, and `PERMISSIONS`, the closed set the backend's
  `Permission` enum defines (a session reporting another name fails decoding).
  `superuser` is the Superuser's standing, a boolean beside the Permissions and
  never one of them. The **session actions** — `login`, `logout`,
  `changePassword` — and the start-up check, `getCurrentUser`, each resolve to a closed outcome (`LoginOutcome`,
  `LogoutOutcome`, `PasswordChangeOutcome`, `SessionCheckOutcome`) and never
  throw.
- `session-action-copy.ts` — the copy for every session-action outcome, and the
  only place it lives (`loginMessage`, `logoutMessage`, `passwordChangeMessage`,
  `sessionCheckMessage`).
- `use-session-action.ts` — `useSessionAction`, the one way a page runs a
  session action: it withdraws the earlier line, holds the pending flag, and
  shows nothing for an outcome that moved the session (the guards move the
  visitor on) or else the copy's line. Inside the signed-in layout it reports
  to the page status; outside it (Login, the idle warning's Sign out) it hands
  back `error`. No caller reads an outcome or catches. `run` resolves `"stayed"`
  or `"moved-on"`.
- `permissions.ts` — `holds` / `holdsAny`, `SUPERUSER`, and the one rule for
  whether a session may open a page. `holds` answers a `Requirement`: a
  Permission, or the Superuser, which no number of Permissions satisfies. The one
  rule: `requirementOf` reads a page's `permissions` as "any
  one of these", `mayOpen` decides. Which Permission a _request_ needs is
  declared on the endpoint instead.
- `declarations.ts` — an endpoint as data: a `ReadDeclaration` (Permission,
  path, decoder, opt-in `absentOnNotFound`) and a `WriteDeclaration` (plus method
  and optional JSON body, a Permission or the Superuser as its requirement, and
  an optional `decodeFailure` reading the one refusal its page shows itself).
  The API module that exports one builds its path from the call's arguments, so
  no page concatenates one. A page reads
  `declaration.permission` to offer a control and hands the same declaration to
  the hook that sends it, so the two cannot drift — each control asks about the
  declaration it sends, never a neighbour's that happens to share its Permission.
- `page-status.ts` — the signed-in page's single error line: `PageStatusScope`
  holds it, `useStatusReporter` is a source's handle on it. Every gated read and
  write reports there, and **the latest event wins**: a failure becomes the
  line; a success withdraws only its own source's failure; a write starting
  withdraws every failure. The scope exists once, rendered by `SignedInShell`
  (it throws when nested; a reporter throws outside one and can never read). It
  resets on every path change, and a source that unmounts withdraws its failure.
- `session-transitions.ts` — `useSessionTransitions`, the one place the auth
  state (status, user, sign-in reason) changes, as `signIn(user)` or
  `end(reason)`, each forgetting the CSRF token in the same step (ADR 0009);
  `end` forgets the tab's EHR context too, and stops the session's FHIRcast
  connection (`endFhircast`).
  `checkFailed` (status `unchecked`) and `recheck` change the status without
  changing the session, so they keep the token. It also owns the **session
  generation** — which session is current: every `signIn` and every `end`
  starts the next. `generation()` reads it, and `expire(generation)` ends the
  session as `expired` only while that generation is still current, so a
  `401` that lands after a sign-out or a fresh sign-in changes nothing.
- `ehr-context.ts` — the tab's **EHR context**, what the EHR has open for the
  tab (a patient and an encounter), as a subscribable store under one
  `sessionStorage` key: `adoptLaunchLanding()` (called by `main.tsx` before
  the router reads the URL) reads an Epic landing's fragment, its Launch
  context, holds each value to FHIR's id grammar, seeds the EHR context with it
  and strips the fragment; `ehrContext()` reads it back, decoded rather than
  trusted, the same value until it changes, and `null` when the tab holds none
  — which is how Home knows an EHR launch began the session;
  `subscribeEhrContext()` / `useEhrContext()` re-render a reader when it
  changes; `followPatientEvent()` moves it as the FHIRcast hub's
  `Patient-open` / `Patient-close` say (ADR 0016, "The events and the EHR
  context"); and `clearEhrContext()` forgets it, on every session end and a
  password Login. A password Login also calls `clearFhircastStorage()`, and
  every session end starts `endFhircast()`, unawaited, which reads what the
  FHIRcast module kept and clears it at once (`docs/BACKEND_CONTRACT.md`).
- `auth-context.tsx` — the `AuthProvider`: checks the session on mount and on
  `retrySessionCheck` (`ok` signs in, `401` ends, anything else is
  `checkFailed`) and maps each session event onto a transition with its sign-in
  reason.
- `auth-context-value.ts` — the context and `useAuth`, split out so the hook
  does not pull in the provider. `useAuth` exposes no way to _end_ a session and
  no sign-in reason; that is `useAuthState`'s, internal to `src/auth`.
- `sign-in-reason.ts` — the closed set of **sign-in reasons**, how each
  reaches the login page (router state, or the backend's
  `?signin=refused|unavailable` marker for the Epic two), which wins (an Epic
  marker), and the copy. Router state is decoded against the set, never cast.
  `signInNoticeFor` is all the login page calls.
- `session-route.ts` — `resolveSessionRoute`, the whole routing contract as one
  pure transition table (see "Routing").
- `route-guards.tsx` — `ProtectedRoute` and `GuestRoute`, thin adapters over
  that table sharing one pending view and one `unchecked` view (an error line
  and "Check again"). An `unchecked` session is not a Guest, so neither guard
  renders its page or redirects.
- `use-session-request.ts` — the seam features request through. It acts on
  `unauthenticated` itself and returns a `SessionResult` with no such member,
  so no page can forget to relay a session ending. It reads the session
  generation as it sends a request, and on that request's `401` expires that
  generation, never a later session.
- `use-session-fhircast.ts` — `useSessionFhircast`, called by the signed-in
  layout: starts the FHIRcast module's session-long connection while the
  session is signed in and the tab holds an EHR context, handing it a source
  for the FHIRcast release (`GET /api/auth/epic/fhircast`) through that seam.
  Not a declaration: the operation is self-service and carries no Permission,
  and the module needs only the release or `null`. It also follows the
  connection's events (`followFhircastEvents`) into the EHR context
  (`followPatientEvent`), so the Patient card follows the EHR on every
  signed-in page. The glue is here because `src/lib` imports nothing of
  `src/auth`.
- `use-gated-read.ts` / `use-gated-write.ts` — the gated read and write (below).
- `idle-sign-out.tsx` — `IdleSignOut`, mounted by `AuthProvider` for an
  authenticated session (`docs/BACKEND_CONTRACT.md` has the rule it keeps).
- `password-policy.ts` — the backend's password length bounds, mirrored so the
  form can state them; the backend still decides.

### The gated read and write

**Every listing read** — Users, Groups, connectors, the audit trail, the
patient in context — goes through `useGatedRead`, handed a declaration, its
arguments and the page's failure copy. The hook keeps no shared cache, so each
caller is its own read: a value two parts of the screen show has one reader,
as the patient in context has (`pages/home/patient-in-context.ts`). It sends nothing without the Permission, resets when
the Permission or path changes, and **the newest read wins**: every read it
sends (on opening, a key change, `reload()`, or a write's `after`) supersedes
the one in flight, in the view and the page status alike. A `reload()` in the
same event as a change of arguments is that change's one read, not a second.
It returns one closed `ReadView` — `not-permitted`, `loading`, `failed`,
`ready`, or `stale` (a refresh failed, the last data kept) — so a page switches
on the view instead of re-checking flags.

- **`absentOnNotFound`** (on the declaration, because whether "not found" is
  news is a fact about the endpoint) adds a sixth view, `absent`, for an
  endpoint whose `404` means "nothing there". It reports nothing and keeps
  nothing (`ready` → `absent`, never `stale`). Opt in only where `404` means
  nothing there, never where it means "this went away under you". The view type
  follows the declaration (`ReadView<T, N>`), so a read that never opts in has
  no `absent` case.
- **`forbiddenAsFailure`** (the page's option, since it chooses copy) reports a
  `403` with the page's failure copy, still as `failed`, still without ending
  the session. The read of the patient in context is its one user.

**Every page action** — Unlock, a Group's Permissions, connector and token changes —
goes through `useGatedWrite`, handed a declaration, its arguments, the success
handling, the copy for the statuses the page cares about, and optionally an
`after` step run inside the pending window (Connectors' listing re-read). It
sends nothing for a session lacking the Permission (settling `forbidden`), owns
the pending flag and the refusal copy, and withdraws every page-status failure
as it starts. It has no branch for ADR 0015's protected-resource `400 scimType: mutability`: no
endpoint the pages call answers it.

`mb-transport-is-behind-the-session-seam` holds the direction: only `src/auth/`
and `src/lib/` may import `lib/http.ts`, so a page cannot call `apiFetch`.
`mb-pages-request-through-the-gated-hooks` holds the step above it: no page,
shell or shared component imports `use-session-request.ts`, so every request
they send passes through the gated read or write, where its declaration's
Permission is applied. `mb-session-transitions-have-one-caller` keeps
`session-transitions.ts` to its one production importer, `auth-context.tsx`.

### The pages

Pages consume `auth/`; they hold no session or Permission logic and never
decide where a visitor goes. A page's folder never imports another's: a part
two pages share moves up to `src/pages/` itself, and imports no page folder
(`mb-pages-do-not-import-another-page`, `mb-shared-page-parts-do-not-import-a-page`).

- `accounts.tsx` — the widest page: the Users and Groups projections, each
  read a page at a time with a search box and one shared pager, Unlock
  of a locked User by stable id, a Group's members a page at a time behind
  its member count (only for a session holding `group:read` and `user:read`,
  the one read declared `{ allOf }`), and — for the Superuser alone, on
  every Group but the Admin group — "Edit permissions". An action replaces the
  affected row on the page shown from its response, which _is_ that User's or
  Group's new state, rather than re-reading. Nothing the directory owns has a control, and the page
  hides the actions the backend would refuse (its own row, the one whose stable
  id the session's `AuthUser.id` holds; Unlock on a User
  that is not locked, the Bootstrap Admin always among them; the Admin group's
  Permissions). An Unlock the backend still refuses `409` — another
  administrator unlocked the User first — says the User is no longer locked.
  Both projections render their rows directly through the shadcn `Table`
  primitives, and the page owns only their sort headings and the Groups
  whose members are open: the backend sorts and pages, `paged-read.ts` holds
  the page shown, and the pager moves from the page's own `startIndex` and row
  count. A search or sort, the search shown submitted again included, reads
  the first page afresh. Every pager, the audit page's included, is the
  shadcn `Pagination` holding `Button` moves — «, a number per page, » —
  « and » each disabled at its end, and none of it while every row sits on
  the first page.
- `group-permissions-dialog.tsx` — the Accounts page's Group Permissions editor,
  not a page: a modal checkbox dialog fed by `GET /api/admin/permissions` and
  pre-ticked with the Group's current set. It hands the complete set to the
  page, which owns the `PUT` — the dialog closes once the save settles, and a
  reporter that unmounts withdraws what it reported, so the write's outcome must
  belong to the page's line.
- `connectors.tsx` — the Accounts page's connector panel, not a page: it lists,
  creates and deletes connectors and issues, rotates and revokes their tokens.
  Each connector section reads its own active tokens, five at a time through
  the shared pager, and re-reads that page after every token change (one change
  can touch more than one token); creating or deleting a connector re-reads the
  listing. A token's plaintext lives only in component state, shown once.
  Create, delete and every token action are offered only to a Superuser (the
  declarations require it); a `connector:read` holder sees the listing alone. A
  delete is sent only from its confirmation dialog, which states that every token
  is revoked, provisioning stops at once, the connector's Users and Groups are
  kept, and the deletion cannot be undone. The issue form offers every token
  Permission, Users before Groups and read before write, while the request keeps
  the backend's order; a rotation refused `409` has its own copy.
- `accounts-api.ts` — the wire types both share and every endpoint they call;
  derives `ADMINISTRATION_PERMISSIONS` from the listing declarations.
- `audit.tsx` / `audit-filter-popover.tsx` / `audit-api.ts` — the audit listing,
  20 a page, filtered on the server by one or more operations, outcome, actor id,
  resource id and a time range. The API module checks each filter before the
  request is built, so a malformed one is refused in the filter panel, beside its field. Ids stay ids
  (resolving them would need `user:read`). It reads on mount, on Apply, Reset, paging and
  Refresh, **never on a timer**: every
  request renews the server session, so a polling page would never reach the idle
  timeout. The pager names a total only once a read of the current query has stated one.
  A row shows Occurred, Operation, Outcome, Actor and Resource; its disclosure
  button, or a click on the row or its open detail, toggles a detail row with the
  changed paths, status, request and details. A click that ends a text selection
  toggles nothing, so ids stay copyable.
- `audit-operation-labels.ts` — each operation's label, in the rows and in the
  Operation picker, which searches and shows labels and sends the operation, and
  while anything is selected offers Clear selection first and last in its list. A
  `Record` keyed by `AuditOperation`, so a new operation fails typecheck until
  it has one.
- `status-badge.tsx` — the one status-to-Badge-variant mapping, for an audit
  outcome and a User's active state (a Deactivated User reads "Deactivated"),
  on Accounts and Audit.
- `home.tsx` — the Patient card, then beside it the FHIRcast card whenever the
  tab holds an EHR context, read through `useEhrContext()` so it comes and goes
  as that changes, then the Pages card: a link to each page the session may
  open.
- `fhircast-card.tsx` — the FHIRcast card: a pure view of the session's
  FHIRcast connection, the singleton `lib/fhircast/session.ts` holds behind
  the facade (`fhircastSnapshot`, `watchFhircast`, imported from
  `@/lib/fhircast`), which the signed-in layout starts; it shows a closed set of
  generic states and starts and closes nothing, so leaving Home keeps the
  connection and its last event. The module renews the subscription unseen,
  and reconnects a dropped socket while the card shows `Reconnecting`;
  once its retries run out the card shows `Disconnected` and a Retry button
  (`retryFhircast`), which connects again from the release. Once the hub
  denies the live subscription, or the SMART access token's expiry passes,
  the connection ends and the card shows its final state, which says to
  reopen the application from Epic and offers no Retry. While connected or
  reconnecting it lists the last event the module hands on — name, time, and
  the Patient id when there is one — held by the module's singleton only, so a
  reload, or a Retry, starts with none. It reports nothing to the page
  status: its state is its own line.
- `patient-in-context.ts` / `home-api.ts` — the **one reader of the patient in
  context**. `PatientInContextScope`, which the signed-in layout renders once
  inside its page status, reads `GET /api/patient/{id}` through `PATIENT`
  (opting in to `absent`, a `403` reported as failure) for the EHR context's
  patient, decoding the R5 `Patient` (typed by `@types/fhir`'s `fhir5`); with
  none in context it reads nothing. The FHIRcast hub's events move that
  patient: a new id is a new path, so a new first read, and a late answer for
  the id before never lands. A failure is reported to the page's error line
  once, by this read. `usePatientInContext()` hands its view — `none`, or the
  gated read's — to the top bar and the Patient card, which only render it, so
  each patient change sends one request on every page. The read is a sibling
  of the layout's children, never their parent, so a patient coming into
  context or leaving it never remounts the page. It lives in `pages/home/`
  because that is the lowest layer both the Shell and the Patient card may
  import that also holds `PATIENT`: `mb-pages-do-not-import-the-shell` rules
  out `src/shell/`.
- `patient-card.tsx` — the Patient card, rendered from `usePatientInContext()`:
  "No patient in context" for none or a `404`, "Loading the patient…", an empty
  card for a failure (its copy is on the error line), or the banner. Every
  field renders as a plain text node; the age is computed from the card's
  injectable `Clock`.
- `listing-body.tsx` — the one listing body: skeleton rows during the first
  read, with the page's loading copy visually hidden in a `status` region for
  screen readers; an icon above the page's empty copy for a successful read
  with no rows; and a blank body for a failure (whose copy is on the error
  line) or a session without the read's Permission. Each caller names its
  `staleRows` choice: Connectors keeps the last rows, and `paged-listing.tsx`
  hides them for every paged read. Its `ReadAgain` is the one "Try again"
  every listing offers, only once the latest read was refused or failed: the
  paged listing places it beside its pager, and Connectors, the one listing
  that is not paged, under its body, so a failed connector listing is read
  again without a reload of the page. The Patient card and the Group
  Permissions dialog render their own states, not through it.
- `paged-read.ts` — **the paged read**, `usePagedRead`: the one hook every
  paged listing reads through — the Users and Groups projections, a Group's
  members, each connector's tokens and the audit trail. A listing hands it its
  declaration, its own arguments (a search and sort, a Group's id, a parsed
  filter set) and its page size; the hook holds the `startIndex`, sends the
  gated read, and hands `PagedListing` the view, the move, the page size and
  the read again. New arguments read the first page again, in the same render,
  and `restart` reads the first page afresh as one read, even for the
  arguments shown: a projection's search or sort goes through it.
  The declarations are data in each `*-api.ts`, built from the paging
  vocabulary in `src/lib/paging.ts` (ADR 0017): `pagedPath`, the one query
  string, the listing's own parameters then `startIndex` and `count`; and
  `pageDecoder`, the one decoder of a page's `totalResults`, `startIndex` and
  rows, which refuses any other field and hands the rows on as `rows`
  whatever field they arrived in. An API module imports no hook
  (`lb-api-modules-are-data`), which is why the vocabulary sits in `lib/`.
- `paged-listing.tsx` — the one paged listing, which every paged read renders
  through, handed the `usePagedRead` result: the listing body, the pager and
  "Try again", around the rows the page renders as its own table or list. It
  decides, once, what each page used to: rows a failed re-read could not
  confirm are hidden; the empty line comes from `page-empty-copy.ts`; the
  pager shows for the page the latest read stated for the current path —
  `ready`, or `stale` — and never while a first read is in flight or after one
  failed with nothing read; and "Try again" re-reads the current path through
  the read's `reload`, keeping the page shown, where searching again on
  Accounts restarts from the first page. Audit passes `readAgain={false}` and offers
  "Refresh" always, in its card header's top right, since the trail grows with
  nothing on the page to say so. Filters, search boxes and toolbars stay the
  page's.
- `pager-move.tsx` — a pager's Previous or Next: a `Button` with its chevron,
  not shadcn's `PaginationPrevious` / `PaginationNext`, whose `<a>` would have
  no `href` because paging is component state rather than a URL. `pager.tsx`
  places it in its `PaginationItem`s.
- `pager.tsx` — the one pager, for every paged read on Accounts and Audit, which
  `paged-listing.tsx` places: the position line, the Previous and Next rules,
  and what an empty page shows. `page-empty-copy.ts` chooses the body's empty
  line for the same reads: a later page says it emptied, where the first gives
  the listing's own copy.
- `change-password.tsx` — runs `changePassword` through `useSessionAction`, not
  `useSessionRequest`: its marked `401` is about the current password, and the
  seam would end the session on it (`docs/BACKEND_CONTRACT.md`). Its inputs are
  uncontrolled, because React mirrors a controlled input's value into the DOM
  `value` attribute.

### Why every request goes through `lib/http.ts`

`apiFetch()` is the one place that knows the CSRF rule
(`docs/BACKEND_CONTRACT.md`). It returns an `ApiResult`: `ok` carries data from
an explicit decoder (`void` when the request omits one), `unauthenticated` means
the session ended, `forbidden` is an authorization refusal, `csrf-expired` is a
token fetch that itself failed, and `failed` covers every other transport,
HTTP or decoding failure. Pages keep their own copy; the meaning is shared.

A body is never cast (`as T`). A hand-written decoder takes `unknown` and
returns the value or throws `DecodeError`, built from `lib/decode.ts`'s
primitives and living beside the wire type it decodes; a success body that does
not decode is `failed`.

It lives in `lib/` because `auth/` sits above it and it must not know about
sessions or React — `mb-lib-is-a-leaf` holds that.

**The one other module that reaches the network is the FHIRcast module,
`lib/fhircast/`** (ADR 0016). It talks to Epic's local FHIRcast hub on
`127.0.0.1` — another origin, to which none of `apiFetch`'s session, CSRF or
status rules apply — so it uses `fetch` and `WebSocket` itself: the
subscription, with the SMART access token as `Bearer`, and the socket at the
endpoint the hub answers, each URL the hub names held to loopback, and every
request bounded: a subscription the hub leaves unanswered for ten seconds is
aborted and taken for a refusal, so a hung first subscription shows
`unavailable` and a hung renewal reconnects. The connection holds the token in
memory for its whole life and never stores it. It reports one closed
`FhircastStatus` — `connecting`, `connected`, `reconnecting`, `disconnected`,
`unavailable`, `blocked` or `ended`, the last its only signal of an ending —
which is the snapshot's status, and never surfaces what the hub said.
On a connected socket it holds every message to the FHIRcast STU3 notification
shape, the subscribed events and the session's topic, acknowledges a valid one
on the socket, and hands the caller only a `PatientEvent`: the event, its
`timestamp` and a grammar-checked Patient id. Nothing of a message is logged.
It keeps the tab's hub and its one live Hub Subscription in `sessionStorage`,
so a reload resumes the subscription rather than subscribing again, and
unsubscribes each subscription it moves off once the new one's socket is
confirmed. `endFhircast()`, which the session transitions start on every end
of the session, clears what the tab kept at once, then tears down at the hub
every connection this page load made — each endpoint unsubscribed with the
SMART access token, then the socket closed — best-effort, each unsubscription
bounded. Every subscription a connection was answered is among them, including
those of a renewal or retry whose socket was never confirmed, which the
connection keeps until then rather than cancelling at once; each distinct
endpoint is unsubscribed once.
It holds the session's one connection as a module-level singleton
(`startFhircast`, idempotent; `retryFhircast`; `fhircastSnapshot` and
`watchFhircast` for the card), which `endFhircast()` resets: no component
lives as long as the session, so none could own it (ADR 0016, "The
connection's lifetime"). The release it starts from still comes from the
backend through the session seam (`auth/use-session-fhircast.ts`).

To the rest of the SPA it is one module: its facade, `lib/fhircast/index.ts`,
imported as `@/lib/fhircast`, is the only file in the folder anything outside
may import (`fhircast-only-through-the-facade`), and it holds no code of its
own. Behind it, each capability is a module of its own, with its tests beside
it:

| Module             | Owns                                                                                                                                                                                                                                                                                                                                                                                    |
| ------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `model.ts`         | the vocabulary the others import: `Hub`, `Subscription`, `Stored`, the `PatientEvent` a caller is handed, the one `FhircastStatus` a connection reports, and the loopback rule every URL a hub names is held to                                                                                                                                                                         |
| `release.ts`       | the release the backend hands over, and `decodeFhircastRelease`                                                                                                                                                                                                                                                                                                                         |
| `protocol.ts`      | every HTTP request made of the hub: the subscription and the unsubscription, each with the SMART access token as `Bearer`, and each bounded (10 s and 3 s)                                                                                                                                                                                                                              |
| `notifications.ts` | the socket at the channel endpoint: the hub's confirmation, the STU3 notification decoder (subscribed events, the session's topic, the Patient id grammar) and the acknowledgement                                                                                                                                                                                                      |
| `storage.ts`       | the `fhircast-connection` and `fhircast-token-expiry` `sessionStorage` entries, written and decoded, and `clearFhircastStorage`                                                                                                                                                                                                                                                         |
| `connection.ts`    | the renewing connection, holding the SMART access token: connecting (`blocked` when the `local-network-access` permission is denied), renewal timers, reconnecting with backoff, one live subscription kept in the tab, resuming after a reload, ending with the token — each reported as its one status. It calls the protocol, the socket and storage itself, on the browser's timers |
| `session.ts`       | the session's one connection, from the release to the teardown at the hub (every endpoint the tab's connections hold unsubscribed once, then the sockets closed): every piece of the module's state (the attempt under way, the connections to tear down, the snapshot)                                                                                                                 |

`test/arch/rawNetwork.test.ts` (`no_raw_network_outside_transport`) names
`lib/http.ts`, `lib/fhircast/protocol.ts` and `lib/fhircast/notifications.ts`
and no others.

### Why every time goes through `lib/time.ts`

The SPA reads and shows every time in Asia/Singapore, the zone the backend
names for operators, whatever the viewer's machine. Singapore is a fixed +08:00,
so `lib/time.ts` states the offset as a constant and is the one module that
knows it: an instant is shown as `formatDate` / `formatInstant`; a typed
`datetime-local` (Audit's From and To) is read by `parseTypedTime`; a FHIR
`date` is checked by `isCalendarDate` and shown by `formatCalendarDate`; and
`today` reads a `Clock` a component is handed, so no page writes `new Date()`.

The unit suite pins its machine zone to one that is neither UTC nor Singapore
(`vitest.config.ts`), so a slip back to the machine's zone shows.
`test/arch/timeModule.test.ts` fails any other source that builds a `Date`,
formats through `Intl` or a local getter, or names a zone.

### Why `components/ui/` is fenced off

It holds the in-house shadcn component library, in source form rather than as
a published package — `frontend/docs/DESIGN.md` documents the design language
(colors, type scale, spacing, shapes) it was built against. It stays leaf-only
on purpose: `mb-ui-primitives-are-leaves` holds its contents to `cn()` and its
own siblings, so a primitive can never depend on a page or a feature, and
stays swappable for the published package the day that exists. Shared
non-primitive components go in `src/components/` or beside the page that owns
them.

Its style is shadcn new-york-v4: `data-slot` attributes, `cva` variant tables,
`asChild`/`Slot.Root` through the `radix-ui` meta-package, and `lucide-react`
icons. `src/lib/hooks/use-mobile.ts` is its one supporting hook (`Sidebar`'s
`useIsMobile`), where `components.json`'s `hooks` alias points.
`components.json` is configured for the shadcn CLI (`src/index.css` as the token
source, `@/lib/utils` as `cn`, `@/components/ui` as the target), so
`npx shadcn@latest add <component>` adds a primitive this set lacks. If the
library is ever published as an npm dependency: add it to `dependencies`, delete
`src/components/ui/` and `src/lib/hooks/`, and repoint `@/components/ui` at the
package.

### Why there is no `src/types/`, `src/hooks/` or `src/utils/`

Those collect by _kind_ rather than _concern_, smearing a feature across
folders. Types live where they are owned; shared helpers and hooks go in
`src/lib/` (where `components.json` points the shadcn CLI's hooks).
`mb-no-top-level-catchall-dirs` enforces the ban, and `mb-src-layout-is-declared`
and its `-for-importers` twin fail any file outside `auth/`, `components/`,
`lib/`, `pages/`, `shell/` and the composition root — so a new folder under `src/` is
added to those rules and to this document in the same change.
`lb-api-modules-are-data` keeps `pages/*/*-api.ts` free of React and components.

## The `@/` alias

`@/x` resolves to `src/x`, declared in the places below, which must agree:

| File                           | Mechanism                              |
| ------------------------------ | -------------------------------------- |
| `tsconfig.json`                | `compilerOptions.paths`                |
| `vite.config.ts`               | `resolve.alias`                        |
| `vitest.config.ts`             | `resolve.alias`                        |
| `test/.dependency-cruiser.cjs` | `options.tsConfig` (reads the `paths`) |

Vitest does not inherit `vite.config.ts` (the unit suite deliberately skips
`@tailwindcss/vite`). Missing dependency-cruiser is the quiet failure: aliased
imports read as unresolvable and every rule silently stops matching them.

## Styling and the token pipeline

`src/index.css` is the whole Tailwind v4 configuration; `@tailwindcss/vite` is
the whole build-side setup:

1. `@import "tailwindcss"`.
2. `@custom-variant dark (&:is(.dark *))` — dark mode is a class on an ancestor.
3. `:root` and `.dark` hold the palette as raw `oklch()` — **the only place a
   raw colour value belongs.**
4. `@theme inline` maps each variable onto utilities (`--color-card` →
   `bg-card`, `text-card`, …).
5. `@layer base` applies the default border colour and the body's colours.

A new shade goes in all three of `:root`, `.dark` and `@theme inline` — a token
with no dark value is a bug only dark mode shows. `test/arch/designTokens.test.ts`
fails a colour literal anywhere else; Tailwind compiles `bg-[#0f172a]` happily.
Step 1 brings Tailwind's own palette with it — nothing resets `--color-*`, so
`bg-red-500`, `text-white` and `bg-black` also compile, render identically in
both modes and survive a rebrand untouched. The same test fails a utility
naming one, outside `src/components/ui/`, which arrived with the framework's
defaults in its overlays and thumbs and is exempt until the published package
replaces it.
The unit suite stubs CSS imports, so the E2E suite exercises the pipeline in a
browser: `smoke.spec.ts` asserts the body's computed `background-color`
resolved, and `packaged-type.spec.ts` the packaged bundle's type scale and
IBM Plex Sans 500 face. The one unit test that reads the pipeline is
`src/type-scale.test.ts`, which compiles `src/index.css` with Tailwind itself
and checks each type-scale utility against DESIGN.md.

## Build

`vite build` emits one `vendor` chunk for `node_modules` and the app in `index`,
with `.gz` and `.br` siblings. There is **no PWA plugin and no service worker**.
Adding one means `vite-plugin-pwa`, an `entry` line in `.fallowrc.jsonc` for the
worker source (nothing imports it), and a `registerType` decision.

A directory a test run writes `.html` into belongs on `vite.config.ts`'s
`server.watch.ignored`, or a dev server you started full-reloads every open
page. The server Playwright starts for E2E (`E2E_DEV_SERVER=1`) watches nothing
and opens no HMR socket, so no page reloads under a spec.

## Routing

`App.tsx` owns the route table: `/login` behind `GuestRoute`, one layout route —
`ProtectedRoute` around `SignedInShell` — holding every signed-in page, and `*`
falling back to `/`.

**Each signed-in page is declared once**, as an entry in `SIGNED_IN_PAGES`
(`shell/signed-in-pages.tsx`): path, element, optional `permissions` guard,
optional `homeLink`, and the shell's metadata (the title, and whether it is kept
for screen readers only). `App.tsx` maps the table to routes, wrapping an entry
with `permissions` in its own `ProtectedRoute`; the shell finds the current
entry with `matchPath`, and its navigation sidebar links each entry with a
`homeLink` that `mayOpen` admits — beneath its collapsible group's button where
the entry names a `group` (Accounts and Audit are both in Admin), a group
standing where its first entry would and showing only while it links one.
**Adding a signed-in page is one entry there**, nothing in `App.tsx` or the
shell. (`BrowserRouter` has no route `handle` / `useMatches`, so the table is
the single source.)

`SignedInShell` owns the navigation sidebar (`shell/navigation-sidebar.tsx`,
which folds to its icons from its top-right control, is a sheet on a phone,
and holds Sign out at its foot), the top bar (`shell/top-bar.tsx`:
the patient in the tab's EHR context by name and NRIC, rendered from the
layout's one read of them (`PatientInContextScope`), which Home's Patient card
renders from too, then Notifications, the one link to Settings,
Help and who is signed in: the username, and beneath it the Groups the session
was signed in with), the page title (kept for screen readers only on every page
the sidebar or the top bar reaches, `titleHidden`) and the page's one error
line (the page-status scope); a page renders only its body. Settings
(`SETTINGS_PATH`, `/settings`) holds the password change, both the voluntary
one and the one the change-required flag requires: a flagged session is
confined to Settings, in its usual layout. A failed or CSRF-expired logout ends
nothing — the session and its token stand (ADR 0009) — and the line goes to
the page status.
The idle warning's Sign out runs the same session action; the Idle sign-out at
the limit is a separate path, and ends the session even when its logout fails.
It also starts the session's FHIRcast connection while the tab holds an EHR
context (`useSessionFhircast`): it outlives every page beneath it, so the
connection lasts across navigation and Home remounts.

`resolveSessionRoute` is the transition table behind both guards. It sends a
Guest to login with a return destination and the provider's sign-in reason;
**confines a flagged session** (`passwordChangeRequired: true` on `/me`) to
Settings (`/settings`) from every path, ahead of the Permission check and any
return destination;
renders a Permission-guarded route for a session holding any of its
Permissions; and redirects every other session to Home. An Epic Login is never
confined, since `/me` reports it unflagged. The guards decide what is
_rendered_; Spring Security decides what is _permitted_.

`BrowserRouter` means real paths, so the backend serves `index.html` for any
unmatched path; a deep link 404s without that. Lazy route chunks are unused
until a page is heavy enough to earn one.

## What is deliberately absent

| Concern              | Where it goes                                                                        |
| -------------------- | ------------------------------------------------------------------------------------ |
| Global state         | beside the feature that owns it; hoist to `src/lib/` only when a second one needs it |
| Server-state caching | a query library wrapping `apiFetch`, wired in `App.tsx` beside `AuthProvider`        |
| Environment config   | `VITE_`-prefixed variables, read through `import.meta.env`, documented in README.md  |
| Lazy routes          | `src/App.tsx`, when a page is heavy                                                  |
| PWA / service worker | `vite-plugin-pwa` in `vite.config.ts` + a `.fallowrc.jsonc` `entry` line             |

The top-level error boundary (`src/components/error-boundary.tsx`) wraps the
whole tree in `App.tsx`, outside the shell. Its fallback offers a reload, logs
to `console.error` only, and never puts the error's message or stack in the DOM.
