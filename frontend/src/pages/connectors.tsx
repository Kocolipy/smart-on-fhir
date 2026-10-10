import { useState, type FormEvent, type ReactNode } from "react";

import { VIEW_PERMISSIONS, WRITE_PERMISSIONS } from "@/auth/permissions";
import { useGatedRead } from "@/auth/use-gated-read";
import { type RefusalMessages, useGatedWrite } from "@/auth/use-gated-write";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { jsonDecoder } from "@/lib/decode";

import {
  connectorPath,
  CONNECTORS_PATH,
  decodeConnector,
  decodeConnectors,
  decodeIssuedToken,
  formatDate,
  tokenActionPath,
  tokensPath,
  type Connector,
  type ConnectorToken,
  type IssuedToken,
  type TokenPermission,
  TOKEN_PERMISSIONS,
} from "./accounts-api";

/** Module-level, so each is one stable function across renders and hook dependencies. */
const readConnectors = jsonDecoder(decodeConnectors);
const readConnector = jsonDecoder(decodeConnector);
const readIssuedToken = jsonDecoder(decodeIssuedToken);

const INPUT_CLASS =
  "flex h-9 rounded-md border border-input bg-background px-3 py-1 text-sm outline-none focus-visible:ring-2 focus-visible:ring-ring";

/**
 * A plaintext the backend has just disclosed, held in component state and
 * nowhere else — not storage, not the URL, not a cache — so navigating away or
 * reloading loses it for good, which is the point: the backend stored a digest
 * and cannot produce it again either.
 */
interface Disclosure {
  connectorName: string;
  token: IssuedToken;
}

function tokenStatus(token: ConnectorToken): string {
  if (token.revokedAt !== null) return `Revoked ${formatDate(token.revokedAt)}`;
  return token.active ? "Active" : "Expired";
}

/** A token's Permissions as one line; a token from before Permissions carries none. */
function permissionList(permissions: readonly TokenPermission[]): string {
  return permissions.length === 0 ? "No permissions" : permissions.join(", ");
}

/** What each token Permission lets a connector do, shown beside its checkbox. */
const PERMISSION_HINTS: Record<TokenPermission, string> = {
  "group:read": "read Groups",
  "group:write": "create, change and delete Groups",
  "user:read": "read Users",
  "user:write": "create, change and delete Users",
};

/** Copy for a refused request, keyed on what the backend refused. */
function failureMessages(what: string): RefusalMessages {
  return {
    400: `Refused: ${what} — check the values and try again.`,
    404: `${what} failed: it no longer exists. Reload the page.`,
    default: `${what} failed. Please try again.`,
  };
}

function TokenDisclosure({
  disclosure,
  onDismiss,
}: {
  disclosure: Disclosure;
  onDismiss: () => void;
}) {
  return (
    <section
      aria-labelledby="token-disclosure-heading"
      className="flex flex-col gap-2 rounded-md border border-primary p-4"
    >
      <h3 className="font-medium" id="token-disclosure-heading">
        New token for {disclosure.connectorName}
      </h3>
      <p className="text-sm text-muted-foreground">
        Copy it now. It is shown only this once and cannot be retrieved again — not after you
        dismiss this, leave the page or reload it.
      </p>
      <code
        aria-label="New token value"
        className="break-all rounded-md bg-muted px-3 py-2 font-mono text-sm"
      >
        {disclosure.token.presentedValue}
      </code>
      <p className="text-sm text-muted-foreground">
        {permissionList(disclosure.token.permissions)} · expires{" "}
        {formatDate(disclosure.token.expiresAt)}
      </p>
      <Button className="self-start" onClick={onDismiss} size="sm" variant="outline">
        Dismiss token
      </Button>
    </section>
  );
}

function ConnectorSection({
  canIssue,
  canManage,
  connector,
  grantable,
  onDelete,
  onIssue,
  onRevoke,
  onRotate,
  pending,
}: {
  /** `connector:token`: issue, rotate and revoke are offered. */
  canIssue: boolean;
  /** `connector:write`: delete is offered. */
  canManage: boolean;
  connector: Connector;
  /**
   * The token Permissions this session holds itself — the only ones the backend
   * lets it put on a token. The others are shown but cannot be chosen.
   */
  grantable: readonly TokenPermission[];
  onDelete: () => void;
  onIssue: (permissions: TokenPermission[], lifetimeDays: number | null) => void;
  onRevoke: (token: ConnectorToken) => void;
  onRotate: (token: ConnectorToken) => void;
  pending: boolean;
}) {
  return (
    <section
      aria-label={`Connector ${connector.displayName}`}
      className="flex flex-col gap-3 border-t pt-4"
    >
      <div className="flex items-center justify-between gap-4">
        <div>
          <h3 className="font-medium">{connector.displayName}</h3>
          <p className="text-sm text-muted-foreground">Created {formatDate(connector.createdAt)}</p>
        </div>
        {canManage ? (
          <Button disabled={pending} onClick={onDelete} size="sm" variant="destructive">
            Delete {connector.displayName}
          </Button>
        ) : null}
      </div>

      <TokenTable
        canIssue={canIssue}
        connector={connector}
        onRevoke={onRevoke}
        onRotate={onRotate}
        pending={pending}
      />

      {canIssue ? (
        <IssueTokenForm
          connector={connector}
          grantable={grantable}
          onIssue={onIssue}
          pending={pending}
        />
      ) : null}
    </section>
  );
}

function TokenTable({
  canIssue,
  connector,
  onRevoke,
  onRotate,
  pending,
}: {
  canIssue: boolean;
  connector: Connector;
  onRevoke: (token: ConnectorToken) => void;
  onRotate: (token: ConnectorToken) => void;
  pending: boolean;
}) {
  if (connector.tokens.length === 0) {
    return <p className="text-sm text-muted-foreground">No tokens issued.</p>;
  }
  return (
    <table className="w-full border-collapse text-left text-sm">
      <caption className="sr-only">Tokens of {connector.displayName}</caption>
      <thead>
        <tr className="border-b text-muted-foreground">
          {["Token", "Permissions", "Issued", "Expires", "Status", "Actions"].map((heading) => (
            <th className="py-2 pr-4 font-medium" key={heading} scope="col">
              {heading}
            </th>
          ))}
        </tr>
      </thead>
      <tbody>
        {connector.tokens.map((token) => (
          <tr className="border-b last:border-0" key={token.id}>
            <th className="py-2 pr-4 font-mono font-normal" scope="row">
              {token.id.slice(0, 8)}
            </th>
            <td className="py-2 pr-4">{permissionList(token.permissions)}</td>
            <td className="py-2 pr-4 text-muted-foreground">{formatDate(token.issuedAt)}</td>
            <td className="py-2 pr-4 text-muted-foreground">{formatDate(token.expiresAt)}</td>
            <td className="py-2 pr-4">{tokenStatus(token)}</td>
            <td className="py-2">
              {token.active && canIssue ? (
                <span className="flex gap-2">
                  <Button
                    disabled={pending}
                    onClick={() => onRotate(token)}
                    size="sm"
                    title="Issues a replacement and ends this token immediately"
                    variant="outline"
                  >
                    Rotate token {token.id.slice(0, 8)}
                  </Button>
                  <Button
                    disabled={pending}
                    onClick={() => onRevoke(token)}
                    size="sm"
                    variant="outline"
                  >
                    Revoke token {token.id.slice(0, 8)}
                  </Button>
                </span>
              ) : null}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function IssueTokenForm({
  connector,
  grantable,
  onIssue,
  pending,
}: {
  connector: Connector;
  /**
   * The token Permissions this session holds itself — the only ones the backend
   * lets it put on a token. The others are shown but cannot be chosen.
   */
  grantable: readonly TokenPermission[];
  onIssue: (permissions: TokenPermission[], lifetimeDays: number | null) => void;
  pending: boolean;
}) {
  const [chosen, setChosen] = useState<readonly TokenPermission[]>([]);
  const [lifetime, setLifetime] = useState("");
  const lifetimeId = `lifetime-${connector.id}`;

  const toggle = (permission: TokenPermission, checked: boolean) =>
    setChosen((current) =>
      checked ? [...current, permission] : current.filter((held) => held !== permission),
    );

  const issue = (event: FormEvent) => {
    event.preventDefault();
    // In the backend's order, so the request reads like the response.
    const permissions = TOKEN_PERMISSIONS.filter((permission) => chosen.includes(permission));
    onIssue(permissions, lifetime === "" ? null : Number(lifetime));
  };

  return (
    <form className="flex flex-wrap items-end gap-3" onSubmit={issue}>
      <fieldset className="flex flex-col gap-1">
        <legend className="text-sm font-medium">Permissions for {connector.displayName}</legend>
        {TOKEN_PERMISSIONS.map((permission) => {
          const allowed = grantable.includes(permission);
          return (
            <label className="flex items-center gap-2 text-sm" key={permission}>
              <input
                checked={chosen.includes(permission)}
                disabled={!allowed}
                onChange={(event) => toggle(permission, event.target.checked)}
                type="checkbox"
              />
              <span className="font-mono">{permission}</span>
              <span className="text-muted-foreground">
                {allowed
                  ? PERMISSION_HINTS[permission]
                  : `${PERMISSION_HINTS[permission]} — you do not hold it`}
              </span>
            </label>
          );
        })}
      </fieldset>
      <div className="flex flex-col gap-1">
        <label className="text-sm font-medium" htmlFor={lifetimeId}>
          Lifetime in days for {connector.displayName} (default 365)
        </label>
        <input
          className={INPUT_CLASS}
          id={lifetimeId}
          inputMode="numeric"
          max={365}
          min={1}
          onChange={(event) => setLifetime(event.target.value)}
          type="number"
          value={lifetime}
        />
      </div>
      <Button disabled={pending || chosen.length === 0} size="sm" type="submit">
        Issue token for {connector.displayName}
      </Button>
    </form>
  );
}

/** The four operations on one connector, as its section invokes them. */
interface ConnectorActions {
  onDelete: () => void;
  onIssue: (permissions: TokenPermission[], lifetimeDays: number | null) => void;
  onRevoke: (token: ConnectorToken) => void;
  onRotate: (token: ConnectorToken) => void;
}

/**
 * The listing, the one-time disclosure and every mutation, as state.
 *
 * Every change reloads the listing rather than patching it locally: a rotation
 * shortens the old token, a revocation stamps it, a delete revokes everything
 * the connector held — and the listing is the only answer that states all of
 * that at once.
 */
function useConnectors() {
  const [disclosure, setDisclosure] = useState<Disclosure | null>(null);
  // A failed read is reported as a failure, never as an empty list.
  const listing = useGatedRead({
    decode: readConnectors,
    failureMessage: "Unable to load the connectors. Please try again.",
    path: CONNECTORS_PATH,
    permission: VIEW_PERMISSIONS.connectors,
  });
  const write = useGatedWrite({ supersedes: [listing] });

  /**
   * What every mutation shares: report a refusal naming `what`, then re-read —
   * still inside the pending window. The re-read's own refusal, when it has
   * one, is the latest thing to go wrong, so it is what the error line shows.
   */
  const mutationOptions = (what: string) => ({
    after: () => listing.reload(),
    messages: failureMessages(what),
  });

  const create = (displayName: string, onCreated: () => void) =>
    void write.run(
      {
        body: { displayName },
        decode: readConnector,
        method: "POST",
        path: CONNECTORS_PATH,
        permission: WRITE_PERMISSIONS.connectors,
      },
      { ...mutationOptions(`Creating ${displayName}`), onOk: onCreated },
    );

  const actionsFor = (connector: Connector): ConnectorActions => {
    const disclose = (token: IssuedToken) =>
      setDisclosure({ connectorName: connector.displayName, token });
    return {
      onDelete: () =>
        void write.run(
          {
            method: "DELETE",
            path: connectorPath(connector.id),
            permission: WRITE_PERMISSIONS.connectors,
          },
          mutationOptions(`Deleting ${connector.displayName}`),
        ),
      onIssue: (permissions, lifetimeDays) =>
        void write.run(
          {
            body: { lifetimeDays, permissions },
            decode: readIssuedToken,
            method: "POST",
            path: tokensPath(connector.id),
            permission: WRITE_PERMISSIONS.tokens,
          },
          { ...mutationOptions(`Issuing a token for ${connector.displayName}`), onOk: disclose },
        ),
      onRevoke: (token) =>
        void write.run(
          {
            method: "POST",
            path: tokenActionPath(connector.id, token.id, "revoke"),
            permission: WRITE_PERMISSIONS.tokens,
          },
          mutationOptions("Revoking the token"),
        ),
      onRotate: (token) =>
        void write.run(
          {
            body: {},
            decode: readIssuedToken,
            method: "POST",
            path: tokenActionPath(connector.id, token.id, "rotate"),
            permission: WRITE_PERMISSIONS.tokens,
          },
          { ...mutationOptions("Rotating the token"), onOk: disclose },
        ),
    };
  };

  return {
    actionsFor,
    connectors: listing.data,
    create,
    disclosure,
    dismiss: () => setDisclosure(null),
    error: write.error,
    pending: write.pending,
    unread: listing.failed,
  };
}

function CreateConnectorForm({
  onCreate,
  pending,
}: {
  onCreate: (displayName: string, onCreated: () => void) => void;
  pending: boolean;
}) {
  const [name, setName] = useState("");

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const displayName = name.trim();
    if (displayName !== "") onCreate(displayName, () => setName(""));
  };

  return (
    <form className="flex flex-wrap items-end gap-3" onSubmit={submit}>
      <div className="flex flex-col gap-1">
        <label className="text-sm font-medium" htmlFor="new-connector-name">
          New connector name
        </label>
        <input
          className={INPUT_CLASS}
          id="new-connector-name"
          maxLength={200}
          onChange={(event) => setName(event.target.value)}
          required
          value={name}
        />
      </div>
      <Button disabled={pending} size="sm" type="submit">
        Create connector
      </Button>
    </form>
  );
}

/**
 * Connector and token management: create, list and delete connectors, and
 * issue, rotate and revoke their tokens. A token's plaintext appears exactly
 * once, in the response to the request that minted it, and is shown in
 * {@link TokenDisclosure}.
 *
 * Rendered only for a session holding `connector:read`. Creating and deleting
 * a connector is offered only with `connector:write`, and issuing, rotating
 * and revoking a token only with `connector:token` — each its own Permission
 * on the backend, which refuses them independently. A token may carry only
 * Permissions the session holds itself (`grantablePermissions`); the backend
 * refuses anything more, so the form offers nothing more.
 */
export function Connectors({
  canIssueTokens,
  canManageConnectors,
  grantablePermissions,
}: {
  canIssueTokens: boolean;
  canManageConnectors: boolean;
  grantablePermissions: readonly TokenPermission[];
}) {
  const { actionsFor, connectors, create, disclosure, dismiss, error, pending, unread } =
    useConnectors();

  // A failed re-read keeps the connectors last read on screen; a failure with
  // none to keep shows neither the loading line nor a claim that none exist.
  let listing: ReactNode = null;
  if (connectors !== null && connectors.length > 0)
    listing = connectors.map((connector) => (
      <ConnectorSection
        canIssue={canIssueTokens}
        canManage={canManageConnectors}
        connector={connector}
        grantable={grantablePermissions}
        key={connector.id}
        pending={pending}
        {...actionsFor(connector)}
      />
    ));
  else if (unread) listing = null;
  else if (connectors === null)
    listing = <p className="text-sm text-muted-foreground">Loading connectors…</p>;
  else listing = <p className="text-sm text-muted-foreground">No connectors exist.</p>;

  return (
    <Card>
      <CardHeader>
        <CardTitle>Connectors</CardTitle>
        <CardDescription>
          The directory integrations allowed to provision over SCIM, and the bearer tokens they
          authenticate with. A token&apos;s value is shown once, when it is issued or rotated, and
          can never be retrieved again.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        {error ? (
          <p className="text-sm text-destructive" role="alert">
            {error}
          </p>
        ) : null}
        {disclosure ? <TokenDisclosure disclosure={disclosure} onDismiss={dismiss} /> : null}
        {canManageConnectors ? <CreateConnectorForm onCreate={create} pending={pending} /> : null}
        {listing}
      </CardContent>
    </Card>
  );
}
