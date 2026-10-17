---
type: Runbook
title: Epic signing-key promotion
description: Publishing, promoting and removing the Epic client-assertion signing keys by redeploy, and withdrawing a compromised one.
tags: [epic, keys, rotation]
---

# Signing-key promotion

The service signs Epic client assertions with the **active** key
(`AppEpicClientKey` / `AppEpicClientKeyId`) and publishes it, plus the optional
**next** key (`AppEpicClientNextKey` / `AppEpicClientNextKeyId`), at the public
`GET /api/auth/epic/jwks.json`, where Epic fetches it. The next key is published
but never signs. The service has no expiry or rotation logic of its own: a key is
published for exactly as long as configuration names it, and every step below is
an operator's redeploy (`/docs/adr/0012-epic-login.md`, D14).

A redeploy here means changing the values the service starts with and
restarting it. The keys and the `kid`s travel separately:

- **The keys** (`AppEpicClientKey`, `AppEpicClientNextKey`) live in
  `EpicClientSecretArn`. Update the stack's parameters — which rewrites the
  secret and nothing else — and `start.sh` reads the new values at the next
  restart.
- **The `kid`s** (`AppEpicClientKeyId`, `AppEpicClientNextKeyId`) are in
  `/opt/backend/.env`, which UserData writes only when the instance is created.
  Set them in **both** places: the stack parameters (so a replacement instance
  gets them) and `/opt/backend/.env` on the running instance.

Then `sudo systemctl restart backend`. Each restart's startup
record carries `app.epic.client_key_id` and `app.epic.client_next_key_id` (the
`kid`s, never the keys), so every step leaves a record in the log.

1. **Create the next key around mid-period**, under a new `kid` — a date makes a
   good one, e.g. `epic-2027-04`:

   ```bash
   openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-384 -out next.pem
   awk 'NF {printf "%s", $0}' next.pem   # the one-line value for the parameter
   ```

2. **Publish it.** Set `AppEpicClientNextKey` and `AppEpicClientNextKeyId` and
   redeploy. Confirm both `kid`s are served, the active one first:

   ```bash
   curl -s https://<host>/api/auth/epic/jwks.json | jq -r '.keys[].kid'
   ```

   Leave it published long enough for Epic to have fetched the new document
   before the next step, since Epic may cache our JWKS.

3. **Promote it by redeploying with it as the active key**: move the next key's
   values into `AppEpicClientKey` / `AppEpicClientKeyId`, and put the old active
   key into `AppEpicClientNextKey` / `AppEpicClientNextKeyId`, so it stays
   published while assertions Epic may still be checking against it age out.
   From this restart every assertion carries the new `kid`.

4. **Remove the old key from configuration**: clear `AppEpicClientNextKey` and
   `AppEpicClientNextKeyId` (both together — the service refuses to start with
   one set and not the other) and redeploy. The JWKS then lists the active key
   alone. Destroy the old key's PEM.

To withdraw a compromised key, skip the waiting: promote the next key (or a
freshly created one) and remove the compromised key in a single redeploy.

The keys are environment variables for now; the target is an AWS KMS-held key
that never leaves KMS, which is open work (`/docs/adr/0012-epic-login.md`).
