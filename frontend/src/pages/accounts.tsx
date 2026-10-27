import { useId, useState, type ReactNode } from "react";

import type { Permission } from "@/auth/api";
import { useAuth } from "@/auth/auth-context-value";
import { holds } from "@/auth/permissions";
import { type ReadView, useGatedRead } from "@/auth/use-gated-read";
import { type RefusalMessages, useGatedWrite } from "@/auth/use-gated-write";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { formatInstant } from "@/lib/time";

import {
  GROUP_MEMBERS,
  GROUP_MEMBERS_PAGE_SIZE,
  GROUPS,
  SET_GROUP_PERMISSIONS,
  UNLOCK_USER,
  USERS,
  type GroupMember,
  type GroupMemberPage,
  type GroupRow,
  type UserRow,
} from "./accounts-api";
import { Connectors } from "./connectors";
import { GroupPermissionsDialog } from "./group-permissions-dialog";
import { ListingBody } from "./listing-body";

/** The sentence the Users view exists to make true: a lockout ends in exactly one way. */
const UNLOCK_EXPLANATION =
  "A lockout never expires: Unlock is the only way it ends, and Unlock also requires the User to change their password before they can do anything else.";

/**
 * Copy for a refused Unlock, keyed on what the backend refused. A `409` is a
 * User serving no lockout — most often one another administrator unlocked
 * after this listing was read — which an Unlock has no effect on.
 */
function unlockMessages(userName: string): RefusalMessages {
  return {
    404: `${userName} no longer exists. Reload the page for the current list.`,
    409: `${userName} is no longer locked. Reload the page for the current list.`,
    default: `Unable to unlock ${userName}. Please try again.`,
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
 * Unlock only while a lockout is in force — the backend refuses it `409`
 * otherwise — and never on the caller's own account.
 */
const offersUnlock = (user: UserRow, self: boolean): boolean =>
  user.locked && !user.bootstrapAdmin && !self;

type OnUnlock = (user: UserRow) => void;

function UnlockButton({
  onUnlock,
  pending,
  self,
  user,
}: {
  /** `null` when the session lacks `account:unlock`, which Unlock requires: it is not offered. */
  onUnlock: OnUnlock | null;
  pending: boolean;
  self: boolean;
  user: UserRow;
}) {
  if (onUnlock === null || !offersUnlock(user, self)) return null;
  return (
    <Button
      disabled={pending}
      onClick={() => onUnlock(user)}
      size="sm"
      title={UNLOCK_EXPLANATION}
      variant="outline"
    >
      Unlock {user.userName}
    </Button>
  );
}

const MUTED_CELL = "py-3 pr-4 text-muted-foreground";

function UserRowView({
  onUnlock,
  pending,
  sessionUserId,
  user,
}: {
  onUnlock: OnUnlock | null;
  pending: boolean;
  /**
   * The stable id the session is signed in to. The row carrying it is the
   * caller's own — compared by id, as the backend compares, never by
   * `userName`, which a connector can rename mid-session.
   */
  sessionUserId: string | undefined;
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
      <td className={MUTED_CELL}>
        {user.groups.map((group) => group.displayName).join(", ") || "—"}
      </td>
      <td className="py-3 pr-4">
        {user.active ? (
          <span className="text-muted-foreground">Active</span>
        ) : (
          <span className="font-medium text-destructive">Inactive</span>
        )}
      </td>
      <td className="py-3 pr-4">
        <LockoutCell user={user} />
      </td>
      <td className={MUTED_CELL}>{user.hasPassword ? "Configured" : "None"}</td>
      <td className={MUTED_CELL}>{user.passwordChangeRequired ? "Required" : "No"}</td>
      <td className={MUTED_CELL}>
        {user.lastAuthenticatedAt ? formatInstant(user.lastAuthenticatedAt) : "Never"}
      </td>
      <td className="py-3">
        <UnlockButton
          onUnlock={onUnlock}
          pending={pending}
          self={user.id === sessionUserId}
          user={user}
        />
      </td>
    </tr>
  );
}

const USER_COLUMNS = [
  "User",
  "Groups",
  "Active",
  "Lockout",
  "Password",
  "Password change required",
  "Last sign-in",
  "Actions",
];

function UsersTable({
  onUnlock,
  pending,
  sessionUserId,
  users,
}: {
  onUnlock: OnUnlock | null;
  pending: boolean;
  sessionUserId: string | undefined;
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
            onUnlock={onUnlock}
            pending={pending}
            sessionUserId={sessionUserId}
            user={user}
          />
        ))}
      </tbody>
    </table>
  );
}

/**
 * What a Group confers, as its Permissions column shows it: the Admin group
 * confers the Superuser, which is not a Permission and so is read from the
 * reservation flag, never from the list; any other Group its Permissions, or
 * nothing.
 */
function confersOf(group: GroupRow): string {
  if (group.adminGroup) return "Superuser";
  return group.permissions.join(", ") || "—";
}

/**
 * Whether the Superuser may set this Group's Permissions: every Group but the
 * Admin group, whose direct members' power is the Superuser, which is not a
 * Permission — the backend refuses it `409`.
 */
const offersPermissionEdit = (group: GroupRow): boolean => !group.adminGroup;

/** One member as its line in an expanded Group shows it. */
function MemberItem({ member }: { member: GroupMember }) {
  return (
    <li className="py-1">
      <span className="font-medium">{member.userName}</span>
      {member.displayName ? (
        <span className="ml-2 text-muted-foreground">{member.displayName}</span>
      ) : null}
      {member.active ? null : <span className="ml-2 font-medium text-destructive">Inactive</span>}
    </li>
  );
}

/** A page's members: the read's data, as the listing body counts rows. */
const membersOf = (page: GroupMemberPage) => page.members;

/**
 * Where the page shown sits among the Group's members, and the moves that
 * remain: back while it is not the first page, on while members lie past it.
 */
function MembersPager({
  onMove,
  page,
}: {
  onMove: (startIndex: number) => void;
  page: GroupMemberPage;
}) {
  const last = page.startIndex + page.members.length - 1;
  return (
    <div className="mt-2 flex items-center gap-2 text-sm text-muted-foreground">
      <span>
        Members {page.startIndex}–{last} of {page.totalResults}
      </span>
      {page.startIndex > 1 ? (
        <Button
          onClick={() => onMove(Math.max(1, page.startIndex - GROUP_MEMBERS_PAGE_SIZE))}
          size="sm"
          variant="outline"
        >
          Previous
        </Button>
      ) : null}
      {last < page.totalResults ? (
        <Button onClick={() => onMove(last + 1)} size="sm" variant="outline">
          Next
        </Button>
      ) : null}
    </div>
  );
}

/**
 * An expanded Group's direct members, read a page at a time — only while the
 * row is expanded, so a Group nobody opens costs no request. A failed read is
 * reported to the page's one error line, as the listings' are.
 */
function GroupMembers({ group, id }: { group: GroupRow; id: string }) {
  const [startIndex, setStartIndex] = useState(1);
  const { view } = useGatedRead(GROUP_MEMBERS, {
    args: [group.id, startIndex],
    failureMessage: `Unable to load the members of ${group.displayName}. Please try again.`,
  });
  return (
    <div aria-label={`Members of ${group.displayName}`} id={id} role="region">
      <ListingBody
        empty={`${group.displayName} has no members.`}
        loading="Loading members…"
        rows={membersOf}
        staleRows="hide"
        view={view}
      >
        {(members) => (
          <ul className="text-sm">
            {members.map((member) => (
              <MemberItem key={member.id} member={member} />
            ))}
          </ul>
        )}
      </ListingBody>
      {view.kind === "ready" && view.data.members.length > 0 ? (
        <MembersPager onMove={setStartIndex} page={view.data} />
      ) : null}
    </div>
  );
}

/**
 * One Group's row. For a session that may read who a Group's members are,
 * its member count is a disclosure: expanding it shows the members, in a row
 * of their own beneath. Any other session sees the count alone.
 */
function GroupRowView({
  columns,
  group,
  mayReadMembers,
  onEdit,
  pending,
}: {
  columns: number;
  group: GroupRow;
  mayReadMembers: boolean;
  onEdit: ((group: GroupRow) => void) | null;
  pending: boolean;
}) {
  const [expanded, setExpanded] = useState(false);
  const panelId = useId();
  return (
    <>
      <tr className="border-b last:border-0">
        <th className="py-3 pr-4 font-medium" scope="row">
          {group.displayName}
        </th>
        <td className="py-3 pr-4 text-muted-foreground">
          {mayReadMembers ? (
            <Button
              aria-controls={panelId}
              aria-expanded={expanded}
              aria-label={`Members of ${group.displayName}`}
              onClick={() => setExpanded((open) => !open)}
              size="sm"
              variant="ghost"
            >
              {group.memberCount}
            </Button>
          ) : (
            group.memberCount
          )}
        </td>
        <td className="py-3 pr-4 text-muted-foreground">{confersOf(group)}</td>
        {onEdit === null ? null : (
          <td className="py-3">
            {offersPermissionEdit(group) ? (
              <Button disabled={pending} onClick={() => onEdit(group)} size="sm" variant="outline">
                Edit permissions for {group.displayName}
              </Button>
            ) : null}
          </td>
        )}
      </tr>
      {expanded ? (
        <tr className="border-b last:border-0">
          <td className="pb-3" colSpan={columns}>
            <GroupMembers group={group} id={panelId} />
          </td>
        </tr>
      ) : null}
    </>
  );
}

/** The Groups table's columns; a Superuser's session adds "Actions". */
const GROUP_COLUMNS = ["Group", "Members", "Permissions"];

function GroupsTable({
  groups,
  mayReadMembers,
  onEdit,
  pending,
}: {
  groups: readonly GroupRow[];
  /** Whether the session may read who a Group's members are: `group:read` and `user:read`. */
  mayReadMembers: boolean;
  /** `null` when the session is not the Superuser's, which alone sets Group Permissions. */
  onEdit: ((group: GroupRow) => void) | null;
  pending: boolean;
}) {
  const columns = onEdit === null ? GROUP_COLUMNS : [...GROUP_COLUMNS, "Actions"];
  return (
    <table className="w-full border-collapse text-left text-sm">
      <caption className="sr-only">Groups</caption>
      <thead>
        <tr className="border-b text-muted-foreground">
          {columns.map((heading) => (
            <th className="py-2 pr-4 font-medium" key={heading} scope="col">
              {heading}
            </th>
          ))}
        </tr>
      </thead>
      <tbody>
        {groups.map((group) => (
          <GroupRowView
            columns={columns.length}
            group={group}
            key={group.id}
            mayReadMembers={mayReadMembers}
            onEdit={onEdit}
            pending={pending}
          />
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
   * Runs one Unlock and replaces just that row from the response, rather than
   * reloading the listing: the response *is* the User's new state, so a refetch
   * would only add a request that could disagree with it.
   */
  const runUnlock = (target: UserRow) =>
    write.run(UNLOCK_USER, {
      args: [target.id],
      messages: unlockMessages(target.userName),
      onOk: (updated) => {
        users.update((current) =>
          (current ?? []).map((row) => (row.id === updated.id ? updated : row)),
        );
      },
    });

  /**
   * Replaces the Group's Permissions with the complete set, and its row from
   * the response, which states the Group as the backend now holds it.
   * `settled` runs once the write has answered, whatever it came to.
   */
  const setGroupPermissions = (
    target: GroupRow,
    permissions: readonly Permission[],
    settled: () => void,
  ) =>
    write.run(SET_GROUP_PERMISSIONS, {
      after: async () => settled(),
      args: [target.id, permissions],
      messages: {
        404: `${target.displayName} no longer exists. Reload the page for the current list.`,
        409: `Refused: the Permissions of ${target.displayName} cannot be set.`,
        default: `Unable to set the Permissions of ${target.displayName}. Please try again.`,
      },
      onOk: (updated) => {
        groups.update((current) =>
          (current ?? []).map((row) => (row.id === updated.id ? updated : row)),
        );
      },
    });

  return { groups, pending: write.pending, runUnlock, setGroupPermissions, users };
}

/**
 * The Accounts page: the directory's Users and Groups as read-only
 * projections, the one operation an administrator performs on a User — Unlock
 * of a locked User — and connector and token management.
 *
 * Each view is shown only to a session holding the Permission its listing
 * requires — Users `user:read`, Groups `group:read`, Connectors
 * `connector:read` — and Unlock only with `account:unlock`. A Group's members
 * are offered only to a session holding both `group:read` and `user:read`:
 * who they are is Users-projection data, so `group:read` alone shows the count.
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
  const { groups, pending, runUnlock, setGroupPermissions, users } = useDirectory();
  const [editing, setEditing] = useState<GroupRow | null>(null);
  const onUnlock: OnUnlock | null = holds(user, UNLOCK_USER.permission)
    ? (target) => void runUnlock(target)
    : null;
  const onEdit = holds(user, SET_GROUP_PERMISSIONS.permission) ? setEditing : null;
  const mayReadMembers = holds(user, GROUP_MEMBERS.permission);

  return (
    <div className="flex flex-col gap-6">
      <ProjectionCard
        description={`Identity, active status and Group membership come from the directory and are read-only here. ${UNLOCK_EXPLANATION}`}
        empty="No users are provisioned."
        title="Users"
        view={users.view}
      >
        {(rows) => (
          <UsersTable onUnlock={onUnlock} pending={pending} sessionUserId={user?.id} users={rows} />
        )}
      </ProjectionCard>

      <ProjectionCard
        description="Groups and their membership come from the directory and are read-only here. A Group grants its Permissions to its direct members from their next sign-in, and a Group with none grants nothing. The Admin group's direct members are the Superuser."
        empty="No groups are provisioned."
        title="Groups"
        view={groups.view}
      >
        {(rows) => (
          <GroupsTable
            groups={rows}
            mayReadMembers={mayReadMembers}
            onEdit={onEdit}
            pending={pending}
          />
        )}
      </ProjectionCard>

      {editing === null ? null : (
        <GroupPermissionsDialog
          group={editing}
          onCancel={() => setEditing(null)}
          onSave={(permissions) =>
            void setGroupPermissions(editing, permissions, () => setEditing(null))
          }
          pending={pending}
        />
      )}

      {/* The connector panel reports to the page's one error line, the layout's. */}
      <Connectors />
    </div>
  );
}
