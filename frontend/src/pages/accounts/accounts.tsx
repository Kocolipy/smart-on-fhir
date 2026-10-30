import { useId, useState, type ReactNode } from "react";

import type { Permission } from "@/auth/api";
import { useAuth } from "@/auth/auth-context-value";
import type { ReadDeclaration } from "@/auth/declarations";
import { holds } from "@/auth/permissions";
import { type ReadView, useGatedRead } from "@/auth/use-gated-read";
import { type RefusalMessages, useGatedWrite } from "@/auth/use-gated-write";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
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
import { formatInstant } from "@/lib/time";

import {
  GROUP_MEMBERS,
  GROUP_MEMBERS_PAGE_SIZE,
  GROUPS,
  LISTING_PAGE_SIZE,
  SET_GROUP_PERMISSIONS,
  UNLOCK_USER,
  USERS,
  type GroupMember,
  type GroupMemberPage,
  type GroupPage,
  type GroupRow,
  type ListingQuery,
  type UserPage,
  type UserRow,
} from "./accounts-api";
import { Connectors } from "./connectors";
import { GroupPermissionsDialog } from "./group-permissions-dialog";
import { ListingBody } from "@/pages/listing-body";

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
      <Tooltip>
        <TooltipTrigger asChild>
          <span className="text-muted-foreground" tabIndex={0}>
            —
          </span>
        </TooltipTrigger>
        <TooltipContent>The Bootstrap Admin cannot be locked</TooltipContent>
      </Tooltip>
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
    <Tooltip>
      <TooltipTrigger asChild>
        <Button disabled={pending} onClick={() => onUnlock(user)} size="sm" variant="outline">
          Unlock {user.userName}
        </Button>
      </TooltipTrigger>
      <TooltipContent>{UNLOCK_EXPLANATION}</TooltipContent>
    </Tooltip>
  );
}

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
    <TableRow className="align-top">
      <TableHead scope="row">
        {user.userName}
        {user.displayName ? (
          <span className="block font-normal text-muted-foreground">{user.displayName}</span>
        ) : null}
      </TableHead>
      <TableCell className="text-muted-foreground">
        {user.groups.map((group) => group.displayName).join(", ") || "—"}
      </TableCell>
      <TableCell>
        {user.active ? (
          <span className="text-muted-foreground">Active</span>
        ) : (
          <span className="font-medium text-destructive">Inactive</span>
        )}
      </TableCell>
      <TableCell>
        <LockoutCell user={user} />
      </TableCell>
      <TableCell className="text-muted-foreground">
        {user.hasPassword ? "Configured" : "None"}
      </TableCell>
      <TableCell className="text-muted-foreground">
        {user.passwordChangeRequired ? "Required" : "No"}
      </TableCell>
      <TableCell className="text-muted-foreground">
        {user.lastAuthenticatedAt ? formatInstant(user.lastAuthenticatedAt) : "Never"}
      </TableCell>
      <TableCell>
        <UnlockButton
          onUnlock={onUnlock}
          pending={pending}
          self={user.id === sessionUserId}
          user={user}
        />
      </TableCell>
    </TableRow>
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
    <Table>
      <TableCaption className="sr-only">Users</TableCaption>
      <TableHeader>
        <TableRow>
          {USER_COLUMNS.map((heading) => (
            <TableHead className="text-muted-foreground" key={heading} scope="col">
              {heading}
            </TableHead>
          ))}
        </TableRow>
      </TableHeader>
      <TableBody>
        {users.map((user) => (
          <UserRowView
            key={user.id}
            onUnlock={onUnlock}
            pending={pending}
            sessionUserId={sessionUserId}
            user={user}
          />
        ))}
      </TableBody>
    </Table>
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

/** A page of a paged read's rows, as the page shows it: its rows, where it starts, and the total. */
interface ProjectionPage<R> {
  rows: readonly R[];
  startIndex: number;
  totalResults: number;
}

/** A page's members: the read's data, as the listing body counts rows. */
const membersOf = (page: GroupMemberPage) => page.members;

/**
 * Where the page shown sits among every row its read matched, and the moves
 * that remain: back while it is not the first page — by one page, never before
 * the first — and on while rows lie past it, from where this page ended. Every
 * paged read on the page moves through this one control.
 *
 * Renders nothing for an empty first page — there is no page to move from or
 * to, and the body's own empty copy already says so. An empty page that is
 * *not* the first still renders: a row removed after the read that showed the
 * page before it can leave the page the operator moved to empty while the
 * directory is not, and Previous is how they step back (as the audit page's
 * `Pager` keeps Previous enabled past the end).
 */
function Pager({
  disabled = false,
  label,
  noun,
  onMove,
  page: { rows, startIndex, totalResults },
  pageSize,
}: {
  /** Holds the page still, as while an action that answers onto it is in flight. */
  disabled?: boolean;
  /** The control's accessible name. */
  label: string;
  /** What the rows are, as the position line names them: "Users 1–20 of 45". */
  noun: string;
  onMove: (startIndex: number) => void;
  /** The page shown, which the position line and the moves are counted from. */
  page: ProjectionPage<unknown>;
  pageSize: number;
}) {
  if (rows.length === 0 && startIndex === 1) return null;
  const last = startIndex + rows.length - 1;
  return (
    <nav aria-label={label} className="mt-2 flex items-center gap-2 text-sm text-muted-foreground">
      <span>
        {/* An empty page's own range (`startIndex`–`last`) would read
            backwards, since `last` falls one short of `startIndex`: say how
            many rows there are in all instead of a range that contradicts itself. */}
        {rows.length === 0 ? (
          <>
            {noun} 0 of {totalResults}
          </>
        ) : (
          <>
            {noun} {startIndex}–{last} of {totalResults}
          </>
        )}
      </span>
      {startIndex > 1 ? (
        <Button
          disabled={disabled}
          onClick={() => onMove(Math.max(1, startIndex - pageSize))}
          size="sm"
          variant="outline"
        >
          Previous
        </Button>
      ) : null}
      {last < totalResults ? (
        <Button disabled={disabled} onClick={() => onMove(last + 1)} size="sm" variant="outline">
          Next
        </Button>
      ) : null}
    </nav>
  );
}

/**
 * The empty line for a page that is not the first: a connector removing a row
 * between the read that counted the directory and the read that fetched this
 * page can leave the page shown with none, while the directory or Group is
 * not empty at all. Distinct from "no rows are provisioned" and "no rows
 * match the search", which both claim an absence this is careful not to.
 */
const PAGE_EMPTIED_MESSAGE = "This page is now empty. Use Previous to go back.";

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
        empty={
          view.kind === "ready" && view.data.startIndex > 1
            ? PAGE_EMPTIED_MESSAGE
            : `${group.displayName} has no members.`
        }
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
      {view.kind === "ready" ? (
        <Pager
          label={`Pages of the members of ${group.displayName}`}
          noun="Members"
          onMove={setStartIndex}
          page={{ ...view.data, rows: membersOf(view.data) }}
          pageSize={GROUP_MEMBERS_PAGE_SIZE}
        />
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
      <TableRow>
        <TableHead scope="row">{group.displayName}</TableHead>
        <TableCell className="text-muted-foreground">
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
        </TableCell>
        <TableCell className="text-muted-foreground">{confersOf(group)}</TableCell>
        {onEdit === null ? null : (
          <TableCell>
            {offersPermissionEdit(group) ? (
              <Button disabled={pending} onClick={() => onEdit(group)} size="sm" variant="outline">
                Edit permissions for {group.displayName}
              </Button>
            ) : null}
          </TableCell>
        )}
      </TableRow>
      {expanded ? (
        <TableRow>
          <TableCell className="whitespace-normal pb-3" colSpan={columns}>
            <GroupMembers group={group} id={panelId} />
          </TableCell>
        </TableRow>
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
    <Table>
      <TableCaption className="sr-only">Groups</TableCaption>
      <TableHeader>
        <TableRow>
          {columns.map((heading) => (
            <TableHead className="text-muted-foreground" key={heading} scope="col">
              {heading}
            </TableHead>
          ))}
        </TableRow>
      </TableHeader>
      <TableBody>
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
      </TableBody>
    </Table>
  );
}

/**
 * A projection's search: the text a name must contain, sent when the form is
 * submitted rather than on every keystroke. Submitting it reads the first page
 * of what it matches; an emptied box reads every row again.
 */
function SearchBox({
  disabled,
  label,
  name,
  onSearch,
}: {
  /** Holds the search still, as {@link Pager} holds the page. */
  disabled: boolean;
  /** The search landmark's accessible name. */
  label: string;
  /** The box's accessible name. */
  name: string;
  onSearch: (search: string) => void;
}) {
  const [text, setText] = useState("");
  return (
    <form
      aria-label={label}
      className="mb-3 flex items-center gap-2"
      onSubmit={(event) => {
        event.preventDefault();
        onSearch(text.trim());
      }}
      role="search"
    >
      <Input
        aria-label={name}
        className="h-8"
        disabled={disabled}
        onChange={(event) => setText(event.target.value)}
        type="search"
        value={text}
      />
      <Button disabled={disabled} size="sm" type="submit" variant="outline">
        Search
      </Button>
    </form>
  );
}

/**
 * A projection's card — its search, the shared listing body and the pager —
 * or nothing for a session without the projection's read Permission. The
 * body says which empty it is: nothing matches the search, or nothing is
 * provisioned at all.
 *
 * A failed re-read hides the rows: a row's actions act on what it states, so
 * rows the listing could not confirm are not offered. The page re-reads
 * neither listing on an action — a row action replaces its row from the
 * response — so this decides what a failed read of another page or search
 * shows.
 */
function ProjectionCard<T, R>({
  children,
  description,
  noun,
  onMove,
  onSearch,
  pageOf,
  pending,
  search,
  title,
  view,
}: {
  children: (rows: readonly R[]) => ReactNode;
  description: string;
  /** The rows' name, lower-case and plural: "users". */
  noun: string;
  onMove: (startIndex: number) => void;
  onSearch: (search: string) => void;
  pageOf: (data: T) => ProjectionPage<R>;
  /**
   * Whether an action is in flight. Its answer replaces a row of the page
   * shown, so the search and the pager hold still until it lands.
   */
  pending: boolean;
  /** The search the shown page was read for; empty for none. */
  search: string;
  title: string;
  view: ReadView<T>;
}) {
  if (view.kind === "not-permitted") return null;
  const page = view.kind === "ready" ? pageOf(view.data) : null;
  return (
    <Card>
      <CardHeader>
        <CardTitle>{title}</CardTitle>
        <CardDescription>{description}</CardDescription>
      </CardHeader>
      <CardContent>
        <SearchBox
          disabled={pending}
          label={`${title} search`}
          name={`Search ${noun}`}
          onSearch={onSearch}
        />
        {/* Scrolls horizontally on its own, scoped to the table: a
            CardContent-wide overflow-x-auto would make CardContent a scroll
            container with no top padding, clipping the search box's focus
            ring/shadow at its top edge. */}
        <div className="overflow-x-auto">
          <ListingBody
            empty={
              page !== null && page.startIndex > 1
                ? PAGE_EMPTIED_MESSAGE
                : search === ""
                  ? `No ${noun} are provisioned.`
                  : `No ${noun} match "${search}".`
            }
            loading={`Loading ${noun}…`}
            rows={(data: T) => pageOf(data).rows}
            staleRows="hide"
            view={view}
          >
            {children}
          </ListingBody>
        </div>
        {page !== null ? (
          <Pager
            disabled={pending}
            label={`${title} pages`}
            noun={title}
            onMove={onMove}
            page={page}
            pageSize={LISTING_PAGE_SIZE}
          />
        ) : null}
      </CardContent>
    </Card>
  );
}

const usersOf = (page: UserPage): ProjectionPage<UserRow> => ({ ...page, rows: page.users });
const groupsOf = (page: GroupPage): ProjectionPage<GroupRow> => ({ ...page, rows: page.groups });

/** The first page, unsearched: what a projection reads on opening. */
const FIRST_PAGE: ListingQuery = { search: "", startIndex: 1 };

/**
 * One projection's read, from the page and search the card has moved to: a
 * new search starts again from the first page.
 */
function useProjection<T>(declaration: ReadDeclaration<T, [query: ListingQuery]>, failure: string) {
  const [query, setQuery] = useState(FIRST_PAGE);
  const read = useGatedRead(declaration, { args: [query], failureMessage: failure });
  return {
    ...read,
    move: (startIndex: number) => setQuery((current) => ({ ...current, startIndex })),
    query,
    search: (search: string) => setQuery({ search, startIndex: 1 }),
  };
}

/**
 * The page's rows with the one an action answered replaced — matched by id,
 * so an answer for a row no longer on the page changes nothing. With no page
 * read there is no row to replace, so the actions hand the read `null` then.
 */
const replaceRow = <R extends { id: string }>(rows: readonly R[], updated: R): R[] =>
  rows.map((row) => (row.id === updated.id ? updated : row));

/**
 * The two listing reads and the row actions, as state: what the page shows and
 * the one function that changes it. Each listing is read a page at a time, and
 * only when the session holds its Permission.
 *
 * Both listings, every action and the connector panel report to the page's
 * one error line, which the signed-in layout owns and renders, and it shows
 * the latest failure. Starting an action withdraws whatever was shown, as the
 * action supersedes it.
 */
function useDirectory() {
  const users = useProjection(USERS, "Unable to load the users. Please try again.");
  const groups = useProjection(GROUPS, "Unable to load the groups. Please try again.");
  const write = useGatedWrite();

  /**
   * Runs one Unlock and replaces just that row of the page shown from the
   * response, rather than reading the page again: the response *is* the
   * User's new state, so a refetch would only add a request that could
   * disagree with it.
   */
  const runUnlock = (target: UserRow) =>
    write.run(UNLOCK_USER, {
      args: [target.id],
      messages: unlockMessages(target.userName),
      onOk: (updated) => {
        users.update((page) => page && { ...page, users: replaceRow(page.users, updated) });
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
        groups.update((page) => page && { ...page, groups: replaceRow(page.groups, updated) });
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
        noun="users"
        onMove={users.move}
        onSearch={users.search}
        pageOf={usersOf}
        pending={pending}
        search={users.query.search}
        title="Users"
        view={users.view}
      >
        {(rows) => (
          <UsersTable onUnlock={onUnlock} pending={pending} sessionUserId={user?.id} users={rows} />
        )}
      </ProjectionCard>

      <ProjectionCard
        description="Groups and their membership come from the directory and are read-only here. A Group grants its Permissions to its direct members from their next sign-in, and a Group with none grants nothing. The Admin group's direct members are the Superuser."
        noun="groups"
        onMove={groups.move}
        onSearch={groups.search}
        pageOf={groupsOf}
        pending={pending}
        search={groups.query.search}
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
