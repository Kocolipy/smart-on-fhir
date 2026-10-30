import {
  columnVisibilityFeature,
  createColumnHelper,
  FlexRender,
  metaHelper,
  rowExpandingFeature,
  rowPaginationFeature,
  tableFeatures,
  useTable,
  type HeaderGroup,
  type RowData,
  type TableFeatures,
} from "@tanstack/react-table";
import { Fragment, useId, useState, type ReactNode } from "react";

import type { Permission } from "@/auth/api";
import { useAuth } from "@/auth/auth-context-value";
import type { ReadDeclaration } from "@/auth/declarations";
import { holds } from "@/auth/permissions";
import { type ReadView, useGatedRead } from "@/auth/use-gated-read";
import { type RefusalMessages, useGatedWrite } from "@/auth/use-gated-write";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Pagination, PaginationContent, PaginationItem } from "@/components/ui/pagination";
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
import { PagerMove } from "@/pages/pager-move";

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

/** What a Users row's cells act with, beyond the User the row shows. */
interface UsersTableMeta {
  /** `null` when the session lacks `account:unlock`, which Unlock requires: it is not offered. */
  onUnlock: OnUnlock | null;
  pending: boolean;
  /**
   * The stable id the session is signed in to. The row carrying it is the
   * caller's own — compared by id, as the backend compares, never by
   * `userName`, which a connector can rename mid-session.
   */
  sessionUserId: string | undefined;
}

/** The Users table's features: paging, which the backend does (ADR 0017), and the row actions' context. */
const USER_FEATURES = tableFeatures({
  rowPaginationFeature,
  tableMeta: metaHelper<UsersTableMeta>(),
});

const userColumn = createColumnHelper<typeof USER_FEATURES, UserRow>();

/**
 * The Users table's columns. Each cell renders its own `<td>` — the "User"
 * column the row header's `<th>` — so the row is the cells and nothing else.
 */
const USER_COLUMNS = userColumn.columns([
  userColumn.accessor("userName", {
    cell: ({ row: { original: user } }) => (
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
    ),
    header: "User",
  }),
  userColumn.display({
    cell: ({ row: { original: user } }) => (
      <TableCell className="text-muted-foreground">
        {user.groups.map((group) => group.displayName).join(", ") || "—"}
      </TableCell>
    ),
    header: "Groups",
    id: "groups",
  }),
  userColumn.accessor("active", {
    cell: ({ row: { original: user } }) => (
      <TableCell>
        {user.active ? (
          <span className="text-muted-foreground">Active</span>
        ) : (
          <span className="font-medium text-destructive">Inactive</span>
        )}
      </TableCell>
    ),
    header: "Active",
  }),
  userColumn.display({
    cell: ({ row: { original: user } }) => (
      <TableCell>
        <LockoutCell user={user} />
      </TableCell>
    ),
    header: "Lockout",
    id: "lockout",
  }),
  userColumn.display({
    cell: ({ row: { original: user } }) => (
      <TableCell className="text-muted-foreground">
        {user.hasPassword ? "Configured" : "None"}
      </TableCell>
    ),
    header: "Password",
    id: "password",
  }),
  userColumn.display({
    cell: ({ row: { original: user } }) => (
      <TableCell className="text-muted-foreground">
        {user.passwordChangeRequired ? "Required" : "No"}
      </TableCell>
    ),
    header: "Password change required",
    id: "passwordChangeRequired",
  }),
  userColumn.display({
    cell: ({ row: { original: user } }) => (
      <TableCell className="text-muted-foreground">
        {user.lastAuthenticatedAt ? formatInstant(user.lastAuthenticatedAt) : "Never"}
      </TableCell>
    ),
    header: "Last sign-in",
    id: "lastSignIn",
  }),
  userColumn.display({
    cell: ({ row: { original: user }, table }) => {
      const { onUnlock, pending, sessionUserId } = table.options.meta!;
      return (
        <TableCell>
          <UnlockButton
            onUnlock={onUnlock}
            pending={pending}
            self={user.id === sessionUserId}
            user={user}
          />
        </TableCell>
      );
    },
    header: "Actions",
    id: "actions",
  }),
]);

/** A row's TanStack id: the directory's id for it, so a replaced row stays the same row. */
const rowIdOf = (row: { id: string }): string => row.id;

/** A projection table's column headings, each its column's own. */
function ProjectionHeader<F extends TableFeatures, R extends RowData>({
  headerGroups,
}: {
  headerGroups: readonly HeaderGroup<F, R>[];
}) {
  return (
    <TableHeader>
      {headerGroups.map((group) => (
        <TableRow key={group.id}>
          {group.headers.map((header) => (
            <TableHead className="text-muted-foreground" key={header.id} scope="col">
              <FlexRender header={header} />
            </TableHead>
          ))}
        </TableRow>
      ))}
    </TableHeader>
  );
}

/**
 * The page of Users shown. Paging is manual: the page is the backend's, read
 * twenty rows at a time, and the table is told only how many rows there are
 * in all — the pager moves from the page's own `startIndex` and row count.
 */
function UsersTable({ meta, page }: { meta: UsersTableMeta; page: ProjectionPage<UserRow> }) {
  const table = useTable({
    columns: USER_COLUMNS,
    data: page.rows,
    features: USER_FEATURES,
    getRowId: rowIdOf,
    manualPagination: true,
    meta,
    rowCount: page.totalResults,
  });
  return (
    <Table>
      <TableCaption className="sr-only">Users</TableCaption>
      <ProjectionHeader headerGroups={table.getHeaderGroups()} />
      <TableBody>
        {table.getRowModel().rows.map((row) => (
          <TableRow className="align-top" key={row.id}>
            {row.getAllCells().map((cell) => (
              <table.FlexRender cell={cell} key={cell.id} />
            ))}
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
 * from it: back by one page, never before the first, and on from where this
 * page ended — counted from the page's own `startIndex` and row count, so a
 * page that came back short is moved on from its last row. Every paged read on
 * the page moves through this one control. A move with nowhere to go —
 * Previous on the first page, Next once no rows lie past this one — is
 * disabled, never hidden.
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
    <Pagination aria-label={label} className="mx-0 mt-2 justify-start">
      <PaginationContent className="gap-4">
        <PaginationItem>
          <PagerMove
            direction="previous"
            disabled={disabled || startIndex <= 1}
            onMove={() => onMove(Math.max(1, startIndex - pageSize))}
          />
        </PaginationItem>
        <PaginationItem>
          {/* An empty page's own range (`startIndex`–`last`) would read
              backwards, since `last` falls one short of `startIndex`: say how
              many rows there are in all instead of a range that contradicts itself. */}
          <p className="text-sm text-muted-foreground">
            {rows.length === 0
              ? `${noun} 0 of ${totalResults}`
              : `${noun} ${startIndex}–${last} of ${totalResults}`}
          </p>
        </PaginationItem>
        <PaginationItem>
          <PagerMove
            direction="next"
            disabled={disabled || last >= totalResults}
            onMove={() => onMove(last + 1)}
          />
        </PaginationItem>
      </PaginationContent>
    </Pagination>
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

/** What a Groups row's cells act with, beyond the Group the row shows. */
interface GroupsTableMeta {
  /** Opens a Group's Permissions editor, from the "Actions" column a Superuser's session alone has. */
  onEdit: (group: GroupRow) => void;
  /** What every members panel's id in this table starts with: see {@link membersPanelId}. */
  panelIds: string;
  pending: boolean;
}

/**
 * The Groups table's features: paging, which the backend does (ADR 0017); the
 * "Actions" column's visibility; a Group's expanded member list; and the row
 * actions' context.
 */
const GROUP_FEATURES = tableFeatures({
  columnVisibilityFeature,
  rowExpandingFeature,
  rowPaginationFeature,
  tableMeta: metaHelper<GroupsTableMeta>(),
});

const groupColumn = createColumnHelper<typeof GROUP_FEATURES, GroupRow>();

/** The id of a Group's members panel, which its Members control names as the region it controls. */
const membersPanelId = (panelIds: string, groupId: string): string => `${panelIds}${groupId}`;

/**
 * The Groups table's columns, each cell rendering its own `<td>` as
 * {@link USER_COLUMNS} do. For a session that may read who a Group's members
 * are, the member count is a disclosure: expanding it shows the members, in a
 * row of their own beneath. Any other session sees the count alone.
 */
const GROUP_COLUMNS = groupColumn.columns([
  groupColumn.accessor("displayName", {
    cell: ({ getValue }) => <TableHead scope="row">{getValue()}</TableHead>,
    header: "Group",
  }),
  groupColumn.display({
    cell: ({ row, table }) => (
      <TableCell className="text-muted-foreground">
        {row.getCanExpand() ? (
          <Button
            aria-controls={membersPanelId(table.options.meta!.panelIds, row.id)}
            aria-expanded={row.getIsExpanded()}
            aria-label={`Members of ${row.original.displayName}`}
            onClick={row.getToggleExpandedHandler()}
            size="sm"
            variant="ghost"
          >
            {row.original.memberCount}
          </Button>
        ) : (
          row.original.memberCount
        )}
      </TableCell>
    ),
    header: "Members",
    id: "members",
  }),
  groupColumn.display({
    cell: ({ row: { original: group } }) => (
      <TableCell className="text-muted-foreground">{confersOf(group)}</TableCell>
    ),
    header: "Permissions",
    id: "permissions",
  }),
  groupColumn.display({
    cell: ({ row: { original: group }, table }) => {
      const { onEdit, pending } = table.options.meta!;
      return (
        <TableCell>
          {offersPermissionEdit(group) ? (
            <Button disabled={pending} onClick={() => onEdit(group)} size="sm" variant="outline">
              Edit permissions for {group.displayName}
            </Button>
          ) : null}
        </TableCell>
      );
    },
    header: "Actions",
    id: "actions",
  }),
]);

/** The page of Groups shown, paged as {@link UsersTable} is. */
function GroupsTable({
  mayEdit,
  mayReadMembers,
  onEdit,
  page,
  pending,
}: {
  /** Whether the session is the Superuser's, which alone sets Group Permissions: the "Actions" column. */
  mayEdit: boolean;
  /** Whether the session may read who a Group's members are: `group:read` and `user:read`. */
  mayReadMembers: boolean;
  onEdit: (group: GroupRow) => void;
  page: ProjectionPage<GroupRow>;
  pending: boolean;
}) {
  const panelIds = useId();
  const table = useTable({
    // A row action's answer replaces its row, which is new data to the table:
    // the Group's open member list stays open across it.
    autoResetExpanded: false,
    columns: GROUP_COLUMNS,
    data: page.rows,
    features: GROUP_FEATURES,
    getRowCanExpand: () => mayReadMembers,
    getRowId: rowIdOf,
    manualPagination: true,
    meta: { onEdit, panelIds, pending },
    rowCount: page.totalResults,
    state: { columnVisibility: { actions: mayEdit } },
  });
  return (
    <Table>
      <TableCaption className="sr-only">Groups</TableCaption>
      <ProjectionHeader headerGroups={table.getHeaderGroups()} />
      <TableBody>
        {table.getRowModel().rows.map((row) => (
          <Fragment key={row.id}>
            <TableRow>
              {row.getVisibleCells().map((cell) => (
                <table.FlexRender cell={cell} key={cell.id} />
              ))}
            </TableRow>
            {row.getIsExpanded() ? (
              <TableRow>
                <TableCell
                  className="whitespace-normal pb-3"
                  colSpan={row.getVisibleCells().length}
                >
                  <GroupMembers group={row.original} id={membersPanelId(panelIds, row.id)} />
                </TableCell>
              </TableRow>
            ) : null}
          </Fragment>
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
  /** The page shown, which has at least one row. */
  children: (page: ProjectionPage<R>) => ReactNode;
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
            {(_rows, data) => children(pageOf(data))}
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
  const mayEdit = holds(user, SET_GROUP_PERMISSIONS.permission);
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
        {(page) => <UsersTable meta={{ onUnlock, pending, sessionUserId: user?.id }} page={page} />}
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
        {(page) => (
          <GroupsTable
            mayEdit={mayEdit}
            mayReadMembers={mayReadMembers}
            onEdit={setEditing}
            page={page}
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
