import { useState, type FormEvent } from "react";

import type { Permission } from "@/auth/api";
import { useAuth } from "@/auth/auth-context-value";
import { holds } from "@/auth/permissions";
import { useGatedRead } from "@/auth/use-gated-read";
import { type RefusalMessages, useGatedWrite } from "@/auth/use-gated-write";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { formatDate } from "@/lib/time";

import {
  CONNECTORS,
  CREATE_CONNECTOR,
  DELETE_CONNECTOR,
  ISSUE_TOKEN,
  REVOKE_TOKEN,
  ROTATE_TOKEN,
  type Connector,
  type ConnectorToken,
  type IssuedToken,
  type TokenPermission,
  TOKEN_PERMISSIONS,
} from "./accounts-api";
import { ListingBody } from "./listing-body";

/** The listing's rows: the connectors themselves. */
const everyConnector = (connectors: Connector[]) => connectors;

/** What each token Permission lets a connector do, as the issue form explains it. */
const TOKEN_PERMISSION_HINTS: Record<TokenPermission, string> = {
  "group:read": "read Groups",
  "group:write": "create, change and delete Groups",
  "user:read": "read Users",
  "user:write": "create, change and delete Users",
};

/**
 * Whether the session may send a declared request, and so whether the control
 * that sends it is offered at all. Each control asks about the declaration it
 * sends, never a neighbour's, so a Permission that moves on the backend moves
 * one declaration and every control follows it.
 */
type May = (request: { permission: Permission }) => boolean;

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

/** Copy for a refused request, keyed on what the backend refused. */
function failureMessages(what: string): RefusalMessages {
  return {
    400: `Refused: ${what} — check the values and try again.`,
    404: `${what} failed: it no longer exists. Reload the page.`,
    default: `${what} failed. Please try again.`,
  };
}

/**
 * A rotation's `409`: the token is no longer one a rotation may replace. The
 * re-read after it shows which — revoked, expired, or replaced already.
 */
const NOT_ROTATABLE_MESSAGE =
  "Refused: the token can no longer be rotated — it was revoked, has expired or was already rotated.";

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
  connector,
  grantable,
  may,
  onDelete,
  onIssue,
  onRevoke,
  onRotate,
  pending,
}: {
  connector: Connector;
  /**
   * The token Permissions this session holds itself — the only ones the backend
   * lets it put on a token. The others are shown but cannot be chosen.
   */
  grantable: readonly TokenPermission[];
  may: May;
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
        {may(DELETE_CONNECTOR) ? (
          <Button disabled={pending} onClick={onDelete} size="sm" variant="destructive">
            Delete {connector.displayName}
          </Button>
        ) : null}
      </div>

      <TokenTable
        connector={connector}
        may={may}
        onRevoke={onRevoke}
        onRotate={onRotate}
        pending={pending}
      />

      {may(ISSUE_TOKEN) ? (
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
  connector,
  may,
  onRevoke,
  onRotate,
  pending,
}: {
  connector: Connector;
  may: May;
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
              {token.active ? (
                <span className="flex gap-2">
                  {may(ROTATE_TOKEN) ? (
                    <Button
                      disabled={pending}
                      onClick={() => onRotate(token)}
                      size="sm"
                      title="Issues a replacement and ends this token immediately"
                      variant="outline"
                    >
                      Rotate token {token.id.slice(0, 8)}
                    </Button>
                  ) : null}
                  {may(REVOKE_TOKEN) ? (
                    <Button
                      disabled={pending}
                      onClick={() => onRevoke(token)}
                      size="sm"
                      variant="outline"
                    >
                      Revoke token {token.id.slice(0, 8)}
                    </Button>
                  ) : null}
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
                  ? TOKEN_PERMISSION_HINTS[permission]
                  : `${TOKEN_PERMISSION_HINTS[permission]} — you do not hold it`}
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
 *
 * The listing and every mutation report to the page's one error line — the
 * signed-in layout's, which the Accounts page's Users and Groups report to as
 * well — where the latest failure shows: a mutation starting withdraws every standing one,
 * its own refusal replaces it, and the re-read after it — reporting later still
 * — replaces that in turn when it fails too.
 */
function useConnectors() {
  const [disclosure, setDisclosure] = useState<Disclosure | null>(null);
  const listing = useGatedRead(CONNECTORS, {
    failureMessage: "Unable to load the connectors. Please try again.",
  });
  const write = useGatedWrite();

  /** What every mutation shares: report a refusal naming `what`, then re-read — still inside the pending window. */
  const mutationOptions = (what: string) => ({
    after: () => listing.reload(),
    messages: failureMessages(what),
  });

  const create = (displayName: string, onCreated: () => void) =>
    void write.run(CREATE_CONNECTOR, {
      args: [displayName],
      ...mutationOptions(`Creating ${displayName}`),
      onOk: onCreated,
    });

  const actionsFor = (connector: Connector): ConnectorActions => {
    const disclose = (token: IssuedToken) =>
      setDisclosure({ connectorName: connector.displayName, token });
    return {
      onDelete: () =>
        void write.run(DELETE_CONNECTOR, {
          args: [connector.id],
          ...mutationOptions(`Deleting ${connector.displayName}`),
        }),
      onIssue: (permissions, lifetimeDays) =>
        void write.run(ISSUE_TOKEN, {
          args: [connector.id, { lifetimeDays, permissions }],
          ...mutationOptions(`Issuing a token for ${connector.displayName}`),
          onOk: disclose,
        }),
      onRevoke: (token) =>
        void write.run(REVOKE_TOKEN, {
          args: [connector.id, token.id],
          ...mutationOptions("Revoking the token"),
        }),
      onRotate: (token) => {
        const options = mutationOptions("Rotating the token");
        void write.run(ROTATE_TOKEN, {
          args: [connector.id, token.id],
          ...options,
          messages: { ...options.messages, 409: NOT_ROTATABLE_MESSAGE },
          onOk: disclose,
        });
      },
    };
  };

  return {
    actionsFor,
    create,
    disclosure,
    dismiss: () => setDisclosure(null),
    pending: write.pending,
    view: listing.view,
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
 * Renders nothing for a session without `connector:read`, whose listing is
 * never asked for. Every control is offered only to a session holding the
 * Permission its own declaration requires — today `connector:write` for
 * creating and deleting a connector, `connector:token` for issuing, rotating
 * and revoking a token — and the backend refuses each independently. A token
 * may carry only Permissions the session holds itself; the backend refuses
 * anything more, so the form offers nothing more.
 *
 * Renders no error line of its own: it is a panel of the Accounts page, and its
 * listing and mutations report to the page's one line, which the signed-in
 * layout owns (`page-status.ts`).
 */
export function Connectors() {
  const { user } = useAuth();
  const { actionsFor, create, disclosure, dismiss, pending, view } = useConnectors();
  const may: May = (request) => holds(user, request.permission);
  const grantable = TOKEN_PERMISSIONS.filter((permission) => holds(user, permission));

  if (view.kind === "not-permitted") return null;
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
        {disclosure ? <TokenDisclosure disclosure={disclosure} onDismiss={dismiss} /> : null}
        {may(CREATE_CONNECTOR) ? <CreateConnectorForm onCreate={create} pending={pending} /> : null}
        {/* A failed re-read keeps the connectors last read on screen, under the
            error line: every change re-reads, and a refused re-read says
            nothing new about connectors the page has just shown. */}
        <ListingBody
          empty="No connectors exist."
          loading="Loading connectors…"
          rows={everyConnector}
          staleRows="keep"
          view={view}
        >
          {(connectors) =>
            connectors.map((connector) => (
              <ConnectorSection
                connector={connector}
                grantable={grantable}
                key={connector.id}
                may={may}
                pending={pending}
                {...actionsFor(connector)}
              />
            ))
          }
        </ListingBody>
      </CardContent>
    </Card>
  );
}
