import { useState, type FormEvent } from "react";

import { useAuth } from "@/auth/auth-context-value";
import { holds, type Requirement } from "@/auth/permissions";
import { useGatedRead } from "@/auth/use-gated-read";
import { type RefusalMessages, useGatedWrite } from "@/auth/use-gated-write";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from "@/components/ui/alert-dialog";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Separator } from "@/components/ui/separator";
import {
  Table,
  TableBody,
  TableCaption,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { formatDate } from "@/lib/time";

import {
  CONNECTOR_TOKENS,
  CONNECTOR_TOKENS_PAGE_SIZE,
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
import { ListingBody, ReadAgain } from "@/pages/listing-body";
import { PagedListing } from "@/pages/paged-listing";
import { usePagedRead } from "@/pages/paged-read";

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
 * The order the issue form offers the token Permissions in: Users before
 * Groups, read before write. Display only — the request still lists them in
 * `TOKEN_PERMISSIONS`' order, the backend's.
 */
const OFFERED_TOKEN_PERMISSIONS = [
  "user:read",
  "user:write",
  "group:read",
  "group:write",
] as const satisfies readonly TokenPermission[];

/**
 * Whether the session may send a declared request, and so whether the control
 * that sends it is offered at all. Each control asks about the declaration it
 * sends, never a neighbour's, so a Permission that moves on the backend moves
 * one declaration and every control follows it.
 */
type May = (request: { permission: Requirement }) => boolean;

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

/**
 * A listed token's status. Only active tokens are listed — a revoked or expired
 * one never is — so a token is active, or rotated: inside its overlap window,
 * still authenticating until it expires.
 */
const tokenStatus = (token: ConnectorToken): string => (token.rotated ? "Rotated" : "Active");

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

/**
 * The delete control, behind a confirmation: deleting a connector cannot be
 * undone from here — it revokes every token the connector holds, so the
 * directory behind it stops provisioning at once — and the button sits beside
 * the issue form's, where a slip is easy. Nothing is sent until the dialog's
 * own delete is chosen; cancelling, Escape or the overlay sends nothing.
 */
function DeleteConnectorConfirmation({
  connector,
  onDelete,
  pending,
}: {
  connector: Connector;
  onDelete: () => void;
  pending: boolean;
}) {
  return (
    <AlertDialog>
      <AlertDialogTrigger asChild>
        {/* Reads "Delete"; the label keeps the connector's name for a screen
            reader, where every connector's button would otherwise sound alike. */}
        <Button
          aria-label={`Delete ${connector.displayName}`}
          disabled={pending}
          size="sm"
          variant="destructive"
        >
          Delete
        </Button>
      </AlertDialogTrigger>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Delete {connector.displayName}?</AlertDialogTitle>
          <AlertDialogDescription>
            Every token it holds is revoked and it stops provisioning at once. Users and Groups it
            provisioned are kept. This cannot be undone.
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel>Cancel</AlertDialogCancel>
          <AlertDialogAction onClick={onDelete} variant="destructive">
            Delete
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}

/**
 * One connector, and its active tokens read a page at a time — its own read,
 * beside every other section's. Issuing, rotating and revoking re-read the page
 * shown, so a revoked token leaves it at once; deleting re-reads the listing.
 */
function ConnectorSection({
  actionsFor,
  connector,
  may,
  pending,
}: {
  actionsFor: (connector: Connector, rereadTokens: () => Promise<void>) => ConnectorActions;
  connector: Connector;
  may: May;
  pending: boolean;
}) {
  const tokens = usePagedRead(CONNECTOR_TOKENS, {
    args: [connector.id],
    failureMessage: `Unable to load the tokens of ${connector.displayName}. Please try again.`,
    pageSize: CONNECTOR_TOKENS_PAGE_SIZE,
  });
  const { onDelete, onIssue, onRevoke, onRotate } = actionsFor(connector, tokens.reload);
  return (
    <section aria-label={`Connector ${connector.displayName}`} className="flex flex-col gap-3">
      <Separator />
      <div className="flex items-center justify-between gap-4">
        <div>
          <h3 className="font-medium">{connector.displayName}</h3>
          <p className="text-sm text-muted-foreground">Created {formatDate(connector.createdAt)}</p>
        </div>
        {may(DELETE_CONNECTOR) ? (
          <DeleteConnectorConfirmation
            connector={connector}
            onDelete={onDelete}
            pending={pending}
          />
        ) : null}
      </div>
      {may(ISSUE_TOKEN) ? (
        <IssueTokenForm connector={connector} onIssue={onIssue} pending={pending} />
      ) : null}

      <PagedListing
        copy={{
          empty: "No active tokens.",
          loading: "Loading tokens…",
          noun: "tokens",
          pagerLabel: `Pages of the tokens of ${connector.displayName}`,
        }}
        disabled={pending}
        read={tokens}
      >
        {({ rows }) => (
          <TokenTable
            connector={connector}
            may={may}
            onRevoke={onRevoke}
            onRotate={onRotate}
            pending={pending}
            tokens={rows}
          />
        )}
      </PagedListing>
    </section>
  );
}

function TokenTable({
  connector,
  may,
  onRevoke,
  onRotate,
  pending,
  tokens,
}: {
  connector: Connector;
  may: May;
  onRevoke: (token: ConnectorToken) => void;
  onRotate: (token: ConnectorToken) => void;
  pending: boolean;
  /** The page's tokens, at least one, every one active. */
  tokens: readonly ConnectorToken[];
}) {
  return (
    <Table>
      <TableCaption className="sr-only">Tokens of {connector.displayName}</TableCaption>
      <TableHeader>
        <TableRow>
          {["Token", "Permissions", "Issued", "Expires", "Status", "Actions"].map((heading) => (
            <TableHead className="text-muted-foreground" key={heading} scope="col">
              {heading}
            </TableHead>
          ))}
        </TableRow>
      </TableHeader>
      <TableBody>
        {tokens.map((token) => (
          <TableRow key={token.id}>
            <TableHead className="font-mono font-normal" scope="row">
              {token.id.slice(0, 8)}
            </TableHead>
            <TableCell>{permissionList(token.permissions)}</TableCell>
            <TableCell className="text-muted-foreground">{formatDate(token.issuedAt)}</TableCell>
            <TableCell className="text-muted-foreground">{formatDate(token.expiresAt)}</TableCell>
            <TableCell>{tokenStatus(token)}</TableCell>
            <TableCell>
              {/* Every listed token still authenticates, so each may be revoked;
                  a rotated one has its replacement already and is never rotated twice. */}
              <span className="flex gap-2">
                {may(ROTATE_TOKEN) && !token.rotated ? (
                  <Tooltip>
                    <TooltipTrigger asChild>
                      <Button
                        disabled={pending}
                        onClick={() => onRotate(token)}
                        size="sm"
                        variant="outline"
                      >
                        Rotate token {token.id.slice(0, 8)}
                      </Button>
                    </TooltipTrigger>
                    <TooltipContent>
                      Issues a replacement and ends this token immediately
                    </TooltipContent>
                  </Tooltip>
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
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}

function IssueTokenForm({
  connector,
  onIssue,
  pending,
}: {
  connector: Connector;
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
    <form className="flex flex-wrap items-start justify-between gap-3 py-2" onSubmit={issue}>
      <fieldset className="flex flex-col gap-1">
        <legend className="text-sm font-medium">Permissions</legend>
        {OFFERED_TOKEN_PERMISSIONS.map((permission) => (
          <Label className="font-normal" key={permission}>
            <Checkbox
              checked={chosen.includes(permission)}
              onCheckedChange={(checked) => toggle(permission, checked === true)}
            />
            {/* As wide as the longest name, so every hint starts in one column. */}
            <span className="w-[11ch] shrink-0 font-mono">{permission}</span>
            <span className="text-muted-foreground">{TOKEN_PERMISSION_HINTS[permission]}</span>
          </Label>
        ))}
      </fieldset>
      <div className="ml-auto flex flex-col items-end gap-3">
        <div className="flex flex-col gap-1">
          <Label htmlFor={lifetimeId}>Lifetime in days (default 365)</Label>
          <Input
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
          Issue token
        </Button>
      </div>
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
 * Every change re-reads rather than patching locally. A change to a connector's
 * tokens re-reads that connector's page of active tokens: a rotation ends the
 * old token and adds a replacement, a revocation ends one, and only the page
 * read again says what is active now. Creating and deleting a connector
 * re-read the listing of connectors.
 *
 * The listing, every section's token read and every mutation report to the page's one error line — the
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

  /**
   * What every mutation shares: report a refusal naming `what`, then `reread` —
   * still inside the pending window.
   */
  const mutationOptions = (what: string, reread: () => Promise<void> = listing.reload) => ({
    after: reread,
    messages: failureMessages(what),
  });

  const create = (displayName: string, outcome: CreateOutcome) =>
    void write.run(CREATE_CONNECTOR, {
      args: [displayName],
      ...mutationOptions(`Creating ${displayName}`),
      onOk: outcome.onCreated,
      // The one refusal shown where it applies, beside the name field.
      onRefused: outcome.onNameTaken,
    });

  /** The operations on one connector; a token change re-reads its tokens through `rereadTokens`. */
  const actionsFor = (
    connector: Connector,
    rereadTokens: () => Promise<void>,
  ): ConnectorActions => {
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
          ...mutationOptions(`Issuing a token for ${connector.displayName}`, rereadTokens),
          onOk: disclose,
        }),
      onRevoke: (token) =>
        void write.run(REVOKE_TOKEN, {
          args: [connector.id, token.id],
          ...mutationOptions("Revoking the token", rereadTokens),
        }),
      onRotate: (token) => {
        const options = mutationOptions("Rotating the token", rereadTokens);
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
    reload: listing.reload,
    view: listing.view,
  };
}

/**
 * A create's `409` when a live connector already holds the name, compared
 * exactly once trimmed. A deleted connector holds none, so the name it had is
 * free.
 */
const NAME_TAKEN_MESSAGE = "A connector with this name already exists";

/** What becomes of a create, as its form reacts: cleared, or told the name is taken. */
interface CreateOutcome {
  onCreated: () => void;
  onNameTaken: () => void;
}

function CreateConnectorForm({
  onCreate,
  pending,
}: {
  onCreate: (displayName: string, outcome: CreateOutcome) => void;
  pending: boolean;
}) {
  const [name, setName] = useState("");
  // Shown under the field until the name is changed: the name entered stays,
  // so it can be corrected rather than retyped.
  const [nameTaken, setNameTaken] = useState(false);

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const displayName = name.trim();
    if (displayName === "") return;
    setNameTaken(false);
    onCreate(displayName, {
      onCreated: () => setName(""),
      onNameTaken: () => setNameTaken(true),
    });
  };

  return (
    <form className="flex flex-wrap items-end gap-3" onSubmit={submit}>
      <div className="flex flex-col gap-1">
        <Label htmlFor="new-connector-name">New connector name</Label>
        <Input
          aria-describedby={nameTaken ? "new-connector-name-error" : undefined}
          aria-invalid={nameTaken ? true : undefined}
          id="new-connector-name"
          maxLength={200}
          onChange={(event) => {
            setName(event.target.value);
            setNameTaken(false);
          }}
          required
          value={name}
        />
        {nameTaken ? (
          <p className="text-sm text-destructive" id="new-connector-name-error">
            {NAME_TAKEN_MESSAGE}
          </p>
        ) : null}
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
 * never asked for. Creating and deleting a connector and issuing, rotating and
 * revoking a token are the Superuser's alone: each control is offered only to a
 * session the backend reported as one (a User holding every Permission is not),
 * and the backend refuses each independently. A token may carry any Permission
 * a token can carry, so the form offers them all.
 *
 * Renders no error line of its own: it is a panel of the Accounts page, and its
 * listing and mutations report to the page's one line, which the signed-in
 * layout owns (`page-status.ts`).
 */
export function Connectors() {
  const { user } = useAuth();
  const { actionsFor, create, disclosure, dismiss, pending, reload, view } = useConnectors();
  const may: May = (request) => holds(user, request.permission);

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
            nothing new about connectors the page has just shown. Try again,
            the one every listing offers, reads them again. */}
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
                actionsFor={actionsFor}
                connector={connector}
                key={connector.id}
                may={may}
                pending={pending}
              />
            ))
          }
        </ListingBody>
        <ReadAgain disabled={pending} onReadAgain={() => void reload()} view={view} />
      </CardContent>
    </Card>
  );
}
