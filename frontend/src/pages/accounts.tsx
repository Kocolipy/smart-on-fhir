import type { ReactNode } from "react";

import { useAuth } from "@/auth/auth-context-value";
import { holds } from "@/auth/permissions";
import { type ReadView, useGatedRead } from "@/auth/use-gated-read";
import { type RefusalMessages, useGatedWrite } from "@/auth/use-gated-write";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { formatDate, formatInstant } from "@/lib/time";

import {
  GROUPS,
  namesSameUser,
  USER_ACTION,
  USERS,
  type GroupRow,
  type UserAction,
  type UserRow,
} from "./accounts-api";
import { Connectors } from "./connectors";
import { ListingBody } from "./listing-body";

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
  /** `null` when the session lacks `account:unlock`, which both actions require: none is offered. */
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
  users: readonly UserRow[];
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

function GroupsTable({ groups }: { groups: readonly GroupRow[] }) {
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
 * A projection's card around the shared listing body, or nothing for a
 * session without the projection's read Permission.
 *
 * A failed re-read hides the rows: a row's actions act on what it states, so
 * rows the listing could not confirm are not offered. The page re-reads
 * neither listing today — a row action replaces its row from the response —
 * so this decides only what a future re-read would show.
 */
function ProjectionCard<R>({
  children,
  description,
  empty,
  title,
  view,
}: {
  children: (rows: readonly R[]) => ReactNode;
  description: string;
  empty: string;
  title: string;
  view: ReadView<R[]>;
}) {
  if (view.kind === "not-permitted") return null;
  return (
    <Card>
      <CardHeader>
        <CardTitle>{title}</CardTitle>
        <CardDescription>{description}</CardDescription>
      </CardHeader>
      <CardContent className="overflow-x-auto">
        <ListingBody
          empty={empty}
          loading={`Loading ${title.toLowerCase()}…`}
          rows={everyRow}
          staleRows="hide"
          view={view}
        >
          {children}
        </ListingBody>
      </CardContent>
    </Card>
  );
}

/** A listing's rows: the read's data itself. */
const everyRow = <R,>(rows: R[]) => rows;

/**
 * The two listing reads and the row actions, as state: what the page shows and
 * the one function that changes it. Each listing is read only when the session
 * holds its Permission.
 *
 * Both listings, every action and the connector panel report to the page's
 * one error line, which the signed-in layout owns and renders, and it shows
 * the latest failure. Starting an action withdraws whatever was shown, as the
 * action supersedes it.
 */
function useDirectory() {
  const users = useGatedRead(USERS, {
    failureMessage: "Unable to load the users. Please try again.",
  });
  const groups = useGatedRead(GROUPS, {
    failureMessage: "Unable to load the groups. Please try again.",
  });
  const write = useGatedWrite();

  /**
   * Runs one action and replaces just that row from the response, rather than
   * reloading the listing: the response *is* the User's new state, so a refetch
   * would only add a request that could disagree with it.
   */
  const runAction = (target: UserRow, action: UserAction) =>
    write.run(USER_ACTION, {
      args: [target.id, action],
      messages: actionMessages(action, target.userName),
      onOk: (updated) => {
        users.update((current) =>
          (current ?? []).map((row) => (row.id === updated.id ? updated : row)),
        );
      },
    });

  return { groups, pending: write.pending, runAction, users };
}

/**
 * The Accounts page: the directory's Users and Groups as read-only
 * projections, the two operations an administrator performs on a User, and
 * connector and token management.
 *
 * Each view is shown only to a session holding the Permission its listing
 * requires — Users `user:read`, Groups `group:read`, Connectors
 * `connector:read` — and Unlock and the forced change only with `account:unlock`.
 * The route guard keeps out a User who may see none of them. All of that is a
 * rendering decision and never the authorization: the backend enforces every
 * operation on its own.
 *
 * Nothing the directory owns is editable here, and that is enforced twice: the
 * page renders no control that could change it, and the backend has no
 * endpoint that would accept the change.
 */
export function Accounts() {
  const { user } = useAuth();
  const { groups, pending, runAction, users } = useDirectory();
  const onAction: OnAction | null = holds(user, USER_ACTION.permission)
    ? (target, action) => void runAction(target, action)
    : null;

  return (
    <div className="flex flex-col gap-6">
      <ProjectionCard
        description={`Identity, active status and Group membership come from the directory and are read-only here. ${UNLOCK_EXPLANATION}`}
        empty="No users are provisioned."
        title="Users"
        view={users.view}
      >
        {(rows) => (
          <UsersTable
            onAction={onAction}
            pending={pending}
            signedIn={user?.username}
            users={rows}
          />
        )}
      </ProjectionCard>

      <ProjectionCard
        description="Groups and their membership come from the directory and are read-only here. A Group mapped to a Role grants that Role's Permissions to its direct members from their next sign-in."
        empty="No groups are provisioned."
        title="Groups"
        view={groups.view}
      >
        {(rows) => <GroupsTable groups={rows} />}
      </ProjectionCard>

      {/* The connector panel reports to the page's one error line, the layout's. */}
      <Connectors />
    </div>
  );
}
