import type { ReactNode } from "react";
import { Link } from "react-router-dom";

import { useAuth } from "@/auth/auth-context-value";
import { holds, VIEW_PERMISSIONS, WRITE_PERMISSIONS } from "@/auth/permissions";
import { useGatedRead } from "@/auth/use-gated-read";
import { type RefusalMessages, useGatedWrite } from "@/auth/use-gated-write";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { jsonDecoder } from "@/lib/decode";

import {
  decodeGroupRows,
  decodeUserRow,
  decodeUserRows,
  formatDate,
  formatInstant,
  GROUPS_PATH,
  namesSameUser,
  TOKEN_PERMISSIONS,
  userActionPath,
  USERS_PATH,
  type GroupRow,
  type UserAction,
  type UserRow,
} from "./accounts-api";
import { Connectors } from "./connectors";

/** The sentence the Users view exists to make true: a lockout ends in exactly one way. */
const UNLOCK_EXPLANATION =
  "A lockout never expires: Unlock is the only way it ends, and Unlock also requires the User to change their password before they can do anything else.";

/** What was being attempted, as the refusal copy names it. */
const ATTEMPTED: Record<UserAction, (userName: string) => string> = {
  unlock: (userName) => `unlock ${userName}`,
  "force-password-change": (userName) => `force a password change for ${userName}`,
};

/** Copy for a refused action, keyed on what the backend refused. */
function actionMessages(action: UserAction, userName: string): RefusalMessages {
  return {
    404: `${userName} no longer exists. Reload the page for the current list.`,
    409: `Refused: ${userName} has no password to replace.`,
    default: `Unable to ${ATTEMPTED[action](userName)}. Please try again.`,
  };
}

/**
 * The lockout cell. The Bootstrap Admin gets no state at all — it cannot be
 * locked, so "not locked" would describe a condition that could change — and
 * nobody gets an expiry, because a lockout has none. A lock says its cause, so
 * an operator can tell a forgotten password from an abandoned account before
 * unlocking.
 */
function LockoutCell({ user }: { user: UserRow }) {
  if (user.bootstrapAdmin) {
    return (
      <span className="text-muted-foreground" title="The Bootstrap Admin cannot be locked">
        —
      </span>
    );
  }
  if (!user.locked) return <span className="text-muted-foreground">Not locked</span>;
  return (
    <span className="font-medium text-destructive">
      {user.lockCause === "DORMANCY" ? "Locked: dormant" : "Locked: failed logins"}
    </span>
  );
}

/**
 * Whether the signed-in administrator may force this row's password change:
 * never on their own account, never on the Bootstrap Admin, and only for a
 * User with a password not already flagged. The backend refuses every excluded
 * case independently.
 */
function offersForcedChange(user: UserRow, self: boolean): boolean {
  if (!user.hasPassword || user.passwordChangeRequired) return false;
  return !self && !user.bootstrapAdmin;
}

/** Unlock only while a lockout is in force, and never on the caller's own account. */
const offersUnlock = (user: UserRow, self: boolean): boolean =>
  user.locked && !user.bootstrapAdmin && !self;

/** The role column: the recovery identity first, then the derived Admin flag. */
function roleOf(user: UserRow): string {
  if (user.bootstrapAdmin) return "Bootstrap Admin";
  return user.admin ? "Admin" : "User";
}

type OnAction = (user: UserRow, action: UserAction) => void;

function UserActions({
  onAction,
  pending,
  self,
  user,
}: {
  /** `null` when the session lacks `user:write`, which both actions require: none is offered. */
  onAction: OnAction | null;
  pending: boolean;
  self: boolean;
  user: UserRow;
}) {
  if (onAction === null) return null;
  return (
    <span className="flex flex-wrap gap-2">
      {offersUnlock(user, self) ? (
        <Button
          disabled={pending}
          onClick={() => onAction(user, "unlock")}
          size="sm"
          title={UNLOCK_EXPLANATION}
          variant="outline"
        >
          Unlock {user.userName}
        </Button>
      ) : null}
      {offersForcedChange(user, self) ? (
        <Button
          disabled={pending}
          onClick={() => onAction(user, "force-password-change")}
          size="sm"
          variant="outline"
        >
          Force password change for {user.userName}
        </Button>
      ) : null}
    </span>
  );
}

const MUTED_CELL = "py-3 pr-4 text-muted-foreground";

function UserRowView({
  onAction,
  pending,
  signedIn,
  user,
}: {
  onAction: OnAction | null;
  pending: boolean;
  signedIn: string | undefined;
  user: UserRow;
}) {
  return (
    <tr className="border-b align-top last:border-0">
      <th className="py-3 pr-4 font-medium" scope="row">
        {user.userName}
        {user.displayName ? (
          <span className="block font-normal text-muted-foreground">{user.displayName}</span>
        ) : null}
      </th>
      <td className={MUTED_CELL}>{roleOf(user)}</td>
      <td className="py-3 pr-4">
        {user.active ? (
          <span className="text-muted-foreground">Active</span>
        ) : (
          <span className="font-medium text-destructive">Inactive</span>
        )}
      </td>
      <td className={MUTED_CELL}>{user.hasPassword ? "Configured" : "None"}</td>
      <td className="py-3 pr-4">
        <LockoutCell user={user} />
      </td>
      <td className={MUTED_CELL}>{user.passwordChangeRequired ? "Required" : "No"}</td>
      <td className={MUTED_CELL}>
        {user.lastAuthenticatedAt ? formatInstant(user.lastAuthenticatedAt) : "Never"}
      </td>
      <td className={MUTED_CELL}>{formatDate(user.createdAt)}</td>
      <td className={MUTED_CELL}>
        {user.groups.map((group) => group.displayName).join(", ") || "—"}
      </td>
      <td className="py-3">
        <UserActions
          onAction={onAction}
          pending={pending}
          self={namesSameUser(user.userName, signedIn)}
          user={user}
        />
      </td>
    </tr>
  );
}

const USER_COLUMNS = [
  "User",
  "Role",
  "Active",
  "Password",
  "Lockout",
  "Change required",
  "Last sign-in",
  "Created",
  "Groups",
  "Actions",
];

function UsersTable({
  onAction,
  pending,
  signedIn,
  users,
}: {
  onAction: OnAction | null;
  pending: boolean;
  signedIn: string | undefined;
  users: UserRow[];
}) {
  return (
    <table className="w-full border-collapse text-left text-sm">
      <caption className="sr-only">Users</caption>
      <thead>
        <tr className="border-b text-muted-foreground">
          {USER_COLUMNS.map((heading) => (
            <th className="py-2 pr-4 font-medium" key={heading} scope="col">
              {heading}
            </th>
          ))}
        </tr>
      </thead>
      <tbody>
        {users.map((user) => (
          <UserRowView
            key={user.id}
            onAction={onAction}
            pending={pending}
            signedIn={signedIn}
            user={user}
          />
        ))}
      </tbody>
    </table>
  );
}

function GroupsTable({ groups }: { groups: GroupRow[] }) {
  return (
    <table className="w-full border-collapse text-left text-sm">
      <caption className="sr-only">Groups</caption>
      <thead>
        <tr className="border-b text-muted-foreground">
          <th className="py-2 pr-4 font-medium" scope="col">
            Group
          </th>
          <th className="py-2 pr-4 font-medium" scope="col">
            Members
          </th>
          <th className="py-2 font-medium" scope="col">
            Authority
          </th>
        </tr>
      </thead>
      <tbody>
        {groups.map((group) => (
          <tr className="border-b last:border-0" key={group.id}>
            <th className="py-3 pr-4 font-medium" scope="row">
              {group.displayName}
            </th>
            <td className="py-3 pr-4 text-muted-foreground">{group.memberCount}</td>
            <td className="py-3 text-muted-foreground">
              {group.adminGroup ? "Protected Admin group" : "—"}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

/**
 * A projection's card: its loading state, its empty state — both withheld when
 * the read failed, so a failure is never also reported as an empty directory or
 * as a load still under way — and otherwise its table.
 */
function ProjectionCard({
  children,
  description,
  empty,
  failed,
  rows,
  title,
}: {
  children: ReactNode;
  description: string;
  empty: string;
  failed: boolean;
  rows: unknown[] | null;
  title: string;
}) {
  // A failed read is never followed by a successful one on this page, so a
  // failure always means there are no rows to show.
  let body: ReactNode = children;
  if (failed) body = null;
  else if (rows === null)
    body = <p className="text-sm text-muted-foreground">Loading {title.toLowerCase()}…</p>;
  else if (rows.length === 0) body = <p className="text-sm text-muted-foreground">{empty}</p>;
  return (
    <Card>
      <CardHeader>
        <CardTitle>{title}</CardTitle>
        <CardDescription>{description}</CardDescription>
      </CardHeader>
      <CardContent className="overflow-x-auto">{body}</CardContent>
    </Card>
  );
}

/** Module-level, so each is one stable function across renders and hook dependencies. */
const readUserRows = jsonDecoder(decodeUserRows);
const readGroupRows = jsonDecoder(decodeGroupRows);
const readUserRow = jsonDecoder(decodeUserRow);

/**
 * The two listing reads and the row actions, as state: what the page shows and
 * the one function that changes it. Each listing is read only when the session
 * holds its Permission.
 *
 * The page has one error line, showing the latest thing to go wrong: an
 * action's refusal while one is showing, otherwise a listing's. Starting an
 * action withdraws whatever was shown, as the action supersedes it.
 */
function useDirectory() {
  const users = useGatedRead({
    decode: readUserRows,
    failureMessage: "Unable to load the users. Please try again.",
    path: USERS_PATH,
    permission: VIEW_PERMISSIONS.users,
  });
  const groups = useGatedRead({
    decode: readGroupRows,
    failureMessage: "Unable to load the groups. Please try again.",
    path: GROUPS_PATH,
    permission: VIEW_PERMISSIONS.groups,
  });
  const write = useGatedWrite({ supersedes: [groups, users] });

  /**
   * Runs one action and replaces just that row from the response, rather than
   * reloading the listing: the response *is* the User's new state, so a refetch
   * would only add a request that could disagree with it.
   */
  const runAction = (target: UserRow, action: UserAction) =>
    write.run(
      {
        decode: readUserRow,
        method: "POST",
        path: userActionPath(target.id, action),
        permission: WRITE_PERMISSIONS.users,
      },
      {
        messages: actionMessages(action, target.userName),
        onOk: (updated) => {
          users.update((current) =>
            (current ?? []).map((row) => (row.id === updated.id ? updated : row)),
          );
        },
      },
    );

  return { error: write.error, groups, pending: write.pending, runAction, users };
}

/**
 * The Accounts page: the directory's Users and Groups as read-only
 * projections, the two operations an administrator performs on a User, and
 * connector and token management.
 *
 * Each view is shown only to a session holding the Permission its listing
 * requires — Users `user:read`, Groups `group:read`, Connectors
 * `connector:read` — and Unlock and the forced change only with `user:write`.
 * The route guard keeps out a User who may see none of them. All of that is a
 * rendering decision and never the authorization: the backend enforces every
 * operation on its own.
 *
 * Nothing the directory owns is editable here, and that is enforced twice: the
 * page renders no control that could change it, and the backend has no
 * endpoint that would accept the change.
 */
export function Accounts() {
  const { logout, user } = useAuth();
  const readUsers = holds(user, VIEW_PERMISSIONS.users);
  const readGroups = holds(user, VIEW_PERMISSIONS.groups);
  const { error, groups, pending, runAction, users } = useDirectory();
  const onAction: OnAction | null = holds(user, WRITE_PERMISSIONS.users)
    ? (target, action) => void runAction(target, action)
    : null;

  return (
    <main className="mx-auto flex min-h-svh max-w-6xl flex-col gap-6 p-8">
      <div className="flex items-center justify-between gap-4">
        <div>
          <p className="text-sm text-muted-foreground">Signed in as {user?.username}</p>
          <h1 className="text-3xl font-semibold tracking-tight">Accounts</h1>
        </div>
        <Button variant="outline" onClick={() => void logout()}>
          Sign out
        </Button>
      </div>

      {error ? (
        <p className="text-sm text-destructive" role="alert">
          {error}
        </p>
      ) : null}

      {readUsers ? (
        <ProjectionCard
          description={`Identity, active status and Group membership come from the directory and are read-only here. ${UNLOCK_EXPLANATION}`}
          empty="No users are provisioned."
          failed={users.failed}
          rows={users.data}
          title="Users"
        >
          <UsersTable
            onAction={onAction}
            pending={pending}
            signedIn={user?.username}
            users={users.data ?? []}
          />
        </ProjectionCard>
      ) : null}

      {readGroups ? (
        <ProjectionCard
          description="Groups and their membership come from the directory and are read-only here. A Group mapped to a Role grants that Role's Permissions to its direct members from their next sign-in."
          empty="No groups are provisioned."
          failed={groups.failed}
          rows={groups.data}
          title="Groups"
        >
          <GroupsTable groups={groups.data ?? []} />
        </ProjectionCard>
      ) : null}

      {holds(user, VIEW_PERMISSIONS.connectors) ? (
        <Connectors
          canIssueTokens={holds(user, WRITE_PERMISSIONS.tokens)}
          canManageConnectors={holds(user, WRITE_PERMISSIONS.connectors)}
          grantablePermissions={TOKEN_PERMISSIONS.filter((permission) => holds(user, permission))}
        />
      ) : null}

      <Link className="text-sm font-medium underline underline-offset-4" to="/showcase">
        Back to counter
      </Link>
    </main>
  );
}
