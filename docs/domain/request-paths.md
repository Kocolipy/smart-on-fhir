---
type: Domain Rules
title: Request paths
description: Which request paths the backend answers itself, which become the SPA shell, and which stay a 404.
tags: [routing, spa]
---

# Request paths

**Reserved server path** — a request path the backend answers for itself:
`/api`, `/actuator` and `/scim`, each reserving both the exact path and everything
beneath it. Reserved paths are authenticated by the filter chain and keep their
own error responses. `SpaRoutes.isReservedServerPath` is the only place the list
lives. `/scim` is reserved because otherwise a mistyped SCIM path would be read as
a client-side route and answered with the SPA shell and a `200`, which a
provisioning client would parse as a successful empty response rather than as an
error.

**SPA shell** — `index.html`, the single document the single-page application
boots from. A path with no server-side handler is _forwarded to the shell_ when
it belongs to the client-side router, which is how a deep link such as
`/accounts` or `/login` survives a page reload.

**File request** — a path whose **last** segment contains a dot
(`/assets/index-a1b2c3.js`, `/favicon.ico`). A missing file request stays a 404;
it is never answered with the SPA shell, or a broken asset URL would return HTML
with a 200. A dot in an earlier segment (`/v1.0/settings`) does not make a path
one. Whether a path may be served without authentication (true of file requests)
and whether a missing path becomes the shell (false of them) are two different
questions; "frontend route" blurred them.
