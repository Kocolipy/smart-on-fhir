import { ArrowDownIcon, ArrowUpDownIcon, ArrowUpIcon } from "lucide-react";
import { Fragment, useId, useState, type ReactNode } from "react";

import type { Permission } from "@/auth/api";
import { useAuth } from "@/auth/auth-context-value";
import { holds } from "@/auth/permissions";
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
  type GroupRow,
  type ListingQuery,
  type ListingSort,
  type UserRow,
} from "./accounts-api";
import { Connectors } from "./connectors";
import { GroupPermissionsDialog } from "./group-permissions-dialog";
import { PagedListing } from "@/pages/paged-listing";
import type { Page } from "@/lib/paging";
import { type PagedDeclaration, type PagedRead, usePagedRead } from "@/pages/paged-read";
import { UserStatusBadge } from "@/pages/status-badge";

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

/**
 * The Directory attributes a projection's heading sorts by, as their SCIM
 * paths: the Users' and Groups' `displayName`, the Users' `active`.
 */
type SortAttribute = "active" | "displayName";

/** How a projection is sorted, by the card that holds the sort. */
interface ProjectionSorting {
  onSort: (sort: ListingSort | null) => void;
  sort: ListingSort | null;
}

/**
 * The sort a press of the heading for `by` asks for next: ascending first,
 * then descending, then the projection's default order. A heading other than
 * the sorted one starts ascending and replaces the sort, since the backend
 * sorts by one attribute only.
 */
function nextSort(sort: ListingSort | null, by: SortAttribute): ListingSort | null {
  if (sort?.by !== by) return { by, order: "ascending" };
  return sort.order === "ascending" ? { by, order: "descending" } : null;
}

/**
 * A sortable column's heading, its `by` the SCIM attribute the backend sorts
 * the directory by. It states its direction with `aria-sort`, and its label is
 * a ghost icon button, as the design's icon buttons are, showing the direction
 * — up ascending, down descending, both while unsorted. Each press moves
 * ascending → descending → the projection's default order.
 */
function SortHeading({
  by,
  disabled,
  label,
  sorting: { onSort, sort },
}: {
  by: SortAttribute;
  /** Holds the sort still, as the pager and the search are held. */
  disabled: boolean;
  label: string;
  sorting: ProjectionSorting;
}) {
  const direction = sort?.by === by ? sort.order : "none";
  const Icon =
    direction === "ascending"
      ? ArrowUpIcon
      : direction === "descending"
        ? ArrowDownIcon
        : ArrowUpDownIcon;
  return (
    <TableHead aria-sort={direction} className="text-muted-foreground" scope="col">
      <Button
        className="-ml-2 h-8 px-2 text-muted-foreground"
        disabled={disabled}
        onClick={() => onSort(nextSort(sort, by))}
        size="sm"
        variant="ghost"
      >
        {label}
        <Icon aria-hidden="true" />
      </Button>
    </TableHead>
  );
}

/** A column's heading that cannot sort: its text alone. */
function PlainHeading({ children }: { children: string }) {
  return (
    <TableHead className="text-muted-foreground" scope="col">
      {children}
    </TableHead>
  );
}

/**
 * The page of Users shown, in the order the backend read it. "User" sorts by
 * the display name it shows first and "Active" by `active`; every other column
 * is login or credential state, or has no single sort key, and cannot sort.
 * The row's heading is its User.
 */
function UsersTable({
  onUnlock,
  page,
  pending,
  sessionUserId,
  sorting,
}: {
  /** `null` when the session lacks `account:unlock`, which Unlock requires: it is not offered. */
  onUnlock: OnUnlock | null;
  page: Page<UserRow>;
  pending: boolean;
  /**
   * The stable id the session is signed in to. The row carrying it is the
   * caller's own — compared by id, as the backend compares, never by
   * `userName`, which a connector can rename mid-session.
   */
  sessionUserId: string | undefined;
  sorting: ProjectionSorting;
}) {
  return (
    <Table>
      <TableCaption className="sr-only">Users</TableCaption>
      <TableHeader>
        <TableRow>
          <SortHeading by="displayName" disabled={pending} label="User" sorting={sorting} />
          <PlainHeading>Groups</PlainHeading>
          <SortHeading by="active" disabled={pending} label="Active" sorting={sorting} />
          <PlainHeading>Lockout</PlainHeading>
          <PlainHeading>Password</PlainHeading>
          <PlainHeading>Password change required</PlainHeading>
          <PlainHeading>Last sign-in</PlainHeading>
          <PlainHeading>Actions</PlainHeading>
        </TableRow>
      </TableHeader>
      <TableBody>
        {page.rows.map((user) => (
          <TableRow className="align-top" key={user.id}>
            <TableHead scope="row">
              {user.displayName ? (
                <>
                  {user.displayName}
                  <span className="block font-normal text-muted-foreground">{user.userName}</span>
                </>
              ) : (
                user.userName
              )}
            </TableHead>
            <TableCell className="text-muted-foreground">
              {user.groups.map((group) => group.displayName).join(", ") || "—"}
            </TableCell>
            <TableCell>
              <UserStatusBadge active={user.active} />
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

/**
 * One member as its line in an expanded Group shows it: the display name
 * first, then the `userName`, as the Users projection names a User — or the
 * `userName` alone for a member with no display name.
 */
function MemberItem({ member }: { member: GroupMember }) {
  return (
    <li className="py-1">
      {member.displayName ? (
        <>
          <span className="font-medium">{member.displayName}</span>
          <span className="ml-2 text-muted-foreground">{member.userName}</span>
        </>
      ) : (
        <span className="font-medium">{member.userName}</span>
      )}
      {member.active ? null : (
        <span className="ml-2">
          <UserStatusBadge active={member.active} />
        </span>
      )}
    </li>
  );
}

/**
 * An expanded Group's direct members, read a page at a time — only while the
 * row is expanded, so a Group nobody opens costs no request. A failed read is
 * reported to the page's one error line, as the listings' are, and offers
 * Try again in the row itself, which stays expanded.
 */
function GroupMembers({ group, id }: { group: GroupRow; id: string }) {
  const members = usePagedRead(GROUP_MEMBERS, {
    args: [group.id],
    failureMessage: `Unable to load the members of ${group.displayName}. Please try again.`,
    pageSize: GROUP_MEMBERS_PAGE_SIZE,
  });
  return (
    <div aria-label={`Members of ${group.displayName}`} id={id} role="region">
      <PagedListing
        copy={{
          empty: `${group.displayName} has no members.`,
          loading: "Loading members…",
          noun: "members",
          pagerLabel: `Pages of the members of ${group.displayName}`,
        }}
        read={members}
      >
        {({ rows }) => (
          <ul className="text-sm">
            {rows.map((member) => (
              <MemberItem key={member.id} member={member} />
            ))}
          </ul>
        )}
      </PagedListing>
    </div>
  );
}

/**
 * The page of Groups shown, in the order the backend read it; "Group" alone
 * sorts, by `displayName`, as the Users' "User" does — members and Permissions
 * have no single sort key.
 *
 * For a session that may read who a Group's members are, the member count is a
 * disclosure: expanding it shows the members, in a row of their own beneath,
 * each Group's opening and closing on its own. The open Groups are held by
 * their ids, so a row an action's answer replaces stays open. Any other
 * session sees the count alone.
 */
function GroupsTable({
  mayEdit,
  mayReadMembers,
  onEdit,
  page,
  pending,
  sorting,
}: {
  /** Whether the session is the Superuser's, which alone sets Group Permissions: the "Actions" column. */
  mayEdit: boolean;
  /** Whether the session may read who a Group's members are: `group:read` and `user:read`. */
  mayReadMembers: boolean;
  onEdit: (group: GroupRow) => void;
  page: Page<GroupRow>;
  pending: boolean;
  sorting: ProjectionSorting;
}) {
  const panelIds = useId();
  const [expanded, setExpanded] = useState<ReadonlySet<string>>(new Set());
  // The headings after the sortable "Group" one; a Group's members row spans them all.
  const plainHeadings = ["Members", "Permissions", ...(mayEdit ? ["Actions"] : [])];
  /** Opens the Group's members, or closes them while they are open, leaving every other Group's as they are. */
  const toggle = (id: string) =>
    setExpanded((open) => {
      const next = new Set(open);
      if (!next.delete(id)) next.add(id);
      return next;
    });
  return (
    <Table>
      <TableCaption className="sr-only">Groups</TableCaption>
      <TableHeader>
        <TableRow>
          <SortHeading by="displayName" disabled={pending} label="Group" sorting={sorting} />
          {plainHeadings.map((heading) => (
            <PlainHeading key={heading}>{heading}</PlainHeading>
          ))}
        </TableRow>
      </TableHeader>
      <TableBody>
        {page.rows.map((group) => {
          // The id of the Group's members panel, which its Members control names as the region it controls.
          const panelId = `${panelIds}${group.id}`;
          const open = mayReadMembers && expanded.has(group.id);
          return (
            <Fragment key={group.id}>
              <TableRow>
                <TableHead scope="row">{group.displayName}</TableHead>
                <TableCell className="text-muted-foreground">
                  {mayReadMembers ? (
                    <Button
                      aria-controls={panelId}
                      aria-expanded={open}
                      aria-label={`Members of ${group.displayName}`}
                      onClick={() => toggle(group.id)}
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
                {mayEdit ? (
                  <TableCell>
                    {offersPermissionEdit(group) ? (
                      <Button
                        disabled={pending}
                        onClick={() => onEdit(group)}
                        size="sm"
                        variant="outline"
                      >
                        Edit permissions for {group.displayName}
                      </Button>
                    ) : null}
                  </TableCell>
                ) : null}
              </TableRow>
              {open ? (
                <TableRow>
                  <TableCell className="whitespace-normal pb-3" colSpan={1 + plainHeadings.length}>
                    <GroupMembers group={group} id={panelId} />
                  </TableCell>
                </TableRow>
              ) : null}
            </Fragment>
          );
        })}
      </TableBody>
    </Table>
  );
}

/**
 * A projection's search: the text a name must contain, sent when the form is
 * submitted rather than on every keystroke. Submitting it reads the first page
 * of what it matches afresh, the search shown included; an emptied box reads
 * every row again.
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
 * A projection's card — its search and the shared paged listing — or nothing
 * for a session without the projection's read Permission. The body says which
 * empty it is: nothing matches the search, or nothing is provisioned at all.
 *
 * A failed re-read hides the rows: a row's actions act on what it states, so
 * rows the listing could not confirm are not offered. The page re-reads
 * neither listing on an action — a row action replaces its row from the
 * response — so the rows shown are read again by the listing's Try again,
 * the page they are on, or by searching again, from the first page.
 */
function ProjectionCard<R>({
  children,
  description,
  noun,
  onSearch,
  pending,
  read,
  search,
  title,
}: {
  /** The page shown, which has at least one row. */
  children: (page: Page<R>) => ReactNode;
  description: string;
  /** The rows' name, lower-case and plural: "users". */
  noun: string;
  onSearch: (search: string) => void;
  /**
   * Whether an action is in flight. Its answer replaces a row of the page
   * shown, so the search and the pager hold still until it lands.
   */
  pending: boolean;
  read: PagedRead<R>;
  /** The search the shown page was read for; empty for none. */
  search: string;
  title: string;
}) {
  if (read.view.kind === "not-permitted") return null;
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
        <PagedListing
          copy={{
            empty: search === "" ? `No ${noun} are provisioned.` : `No ${noun} match "${search}".`,
            loading: `Loading ${noun}…`,
            noun,
            pagerLabel: `${title} pages`,
          }}
          disabled={pending}
          read={read}
        >
          {children}
        </PagedListing>
      </CardContent>
    </Card>
  );
}

/** Unsearched and in the default order: what a projection reads on opening. */
const UNSEARCHED: ListingQuery = { search: "", sort: null };

/**
 * One projection's read, for the search and sort the card has set and the
 * page it has moved to: a new search or a new sort starts again from the first
 * page — the paged read's rule for any new arguments — each keeping the other.
 */
function useProjection<R>(
  declaration: PagedDeclaration<R, [query: ListingQuery]>,
  failure: string,
) {
  const [query, setQuery] = useState(UNSEARCHED);
  const read = usePagedRead(declaration, {
    args: [query],
    failureMessage: failure,
    pageSize: LISTING_PAGE_SIZE,
  });
  // A search or sort reads its first page afresh, once, even when it names the
  // query shown: submitting the search shown again is a request to read it again.
  const requery = (next: (current: ListingQuery) => ListingQuery) => {
    setQuery(next);
    read.restart();
  };
  return {
    query,
    read,
    search: (search: string) => requery((current) => ({ ...current, search })),
    sorting: {
      onSort: (sort: ListingSort | null) => requery((current) => ({ ...current, sort })),
      sort: query.sort,
    },
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
        users.read.update((page) => page && { ...page, rows: replaceRow(page.rows, updated) });
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
        groups.read.update((page) => page && { ...page, rows: replaceRow(page.rows, updated) });
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
  const mayEdit = holds(user, SET_GROUP_PERMISSIONS.permission);
  const mayReadMembers = holds(user, GROUP_MEMBERS.permission);

  return (
    <div className="flex flex-col gap-6">
      <ProjectionCard
        description={`Identity, active status and Group membership come from the directory and are read-only here. ${UNLOCK_EXPLANATION}`}
        noun="users"
        onSearch={users.search}
        pending={pending}
        read={users.read}
        search={users.query.search}
        title="Users"
      >
        {(page) => (
          <UsersTable
            onUnlock={onUnlock}
            page={page}
            pending={pending}
            // Stryker disable next-line OptionalChaining: equivalent — a page of Users is read only for a session holding user:read, so `user` is set wherever this renders.
            sessionUserId={user?.id}
            sorting={users.sorting}
          />
        )}
      </ProjectionCard>

      <ProjectionCard
        description="Groups and their membership come from the directory and are read-only here. A Group grants its Permissions to its direct members from their next sign-in, and a Group with none grants nothing. The Admin group's direct members are the Superuser."
        noun="groups"
        onSearch={groups.search}
        pending={pending}
        read={groups.read}
        search={groups.query.search}
        title="Groups"
      >
        {(page) => (
          <GroupsTable
            mayEdit={mayEdit}
            mayReadMembers={mayReadMembers}
            onEdit={setEditing}
            page={page}
            pending={pending}
            sorting={groups.sorting}
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
