import { ChevronRightIcon } from "lucide-react";
import { useId, useState } from "react";

import { useStatusReporter } from "@/auth/page-status";
import { useGatedRead } from "@/auth/use-gated-read";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import {
  Table,
  TableBody,
  TableCaption,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { formatInstant } from "@/lib/time";

import {
  AUDIT_EVENTS,
  AUDIT_PAGE_SIZE,
  auditQueryAt,
  EMPTY_AUDIT_FILTERS,
  parseAuditControlChange,
  parseAuditFilters,
  type ParsedAuditFilters,
  type AuditEvent,
  type AuditEventPage,
  type AuditOperation,
  type AuditFilters,
  UNFILTERED_AUDIT_QUERY,
  type AuditQuery,
} from "./audit-api";
import { AuditFilterToolbar } from "./audit-filter-toolbar";
import { AUDIT_OPERATION_LABELS } from "./audit-operation-labels";
import { PagedListing } from "@/pages/paged-listing";
import { StatusBadge } from "@/pages/status-badge";

const eventsOf = (page: AuditEventPage) => page.events;

/**
 * The columns a row shows at a glance. A last column holds each row's detail
 * control, its heading for a screen reader alone; what the control opens is
 * {@link EventDetail}.
 */
const AUDIT_COLUMNS = ["Occurred", "Operation", "Outcome", "Actor", "Resource"];

/** The resource column: its type, and the id, in monospace, when the operation named one. */
function ResourceCell({ event }: { event: AuditEvent }) {
  if (event.resourceId === null) return event.resourceType;
  return (
    <>
      {event.resourceType} <span className="font-mono">{event.resourceId}</span>
    </>
  );
}

/**
 * The Request field: the triggering request's route and its correlation id,
 * either of which is absent for an event no request triggered — a scheduled
 * job's write, or a seeding event recorded with no actor at all.
 */
function requestText(event: AuditEvent): string {
  const route = [event.httpMethod, event.httpPath].filter((part) => part !== null).join(" ");
  if (route !== "" && event.requestId !== null) return `${route} (${event.requestId})`;
  if (route !== "") return route;
  return event.requestId ?? "—";
}

/** The operations that change a User's power through its Group memberships. */
const MEMBERSHIP_OPERATIONS: readonly AuditOperation[] = [
  "PERMISSION_GRANT",
  "PERMISSION_REVOKE",
  "DORMANCY_MEMBERSHIP_REVOCATION",
];

const listed = (values: readonly string[]): string =>
  values.length === 0 ? "none" : values.join(", ");

const permissionChange = (event: AuditEvent): string =>
  `permissions: ${listed(event.permissionsBefore)} → ${listed(event.permissionsAfter)}`;

/**
 * A Group Permissions change's part of Details: the Group's Permissions before
 * and after. A refused one changed nothing and names none — its reason is the
 * Status column's.
 */
function groupPermissionsParts(event: AuditEvent): string[] {
  if (event.operation !== "GROUP_PERMISSIONS_CHANGE" || event.outcome !== "SUCCESS") return [];
  return [permissionChange(event)];
}

/**
 * A membership change's part of Details: the User's effective Permissions
 * before and after, whether it granted or removed the Superuser, and the
 * Groups a dormancy revocation removed. Nothing for any other operation.
 */
function membershipParts(event: AuditEvent): (string | null)[] {
  if (!MEMBERSHIP_OPERATIONS.includes(event.operation)) return [];
  const superuser =
    event.operation === "PERMISSION_GRANT" ? "Superuser granted" : "Superuser removed";
  return [
    permissionChange(event),
    event.superuserChanged === true ? superuser : null,
    event.groupIds.length === 0 ? null : `groups: ${event.groupIds.join(", ")}`,
  ];
}

/**
 * Everything else a reader may need, as plain text joined by " · ": a login's
 * method, a bulk read's result count and filter shape, the Permissions a token
 * event carried, and a membership change's Permissions before and after. Empty
 * when the operation names none of these.
 */
function detailsText(event: AuditEvent): string {
  const parts = [
    event.loginMethod === null ? null : `login method: ${event.loginMethod}`,
    event.resultCount === null ? null : `${event.resultCount} result(s)`,
    event.filterShape,
    event.permissions.length === 0 ? null : event.permissions.join(", "),
    ...membershipParts(event),
    ...groupPermissionsParts(event),
  ].filter((part): part is string => part !== null);
  return parts.length === 0 ? "—" : parts.join(" · ");
}

/**
 * What a row keeps behind its detail control, as named fields: the changed
 * paths, the status and its error code, the triggering request, and everything
 * else as Details. Every value is plain text.
 */
function EventDetail({
  accessibleName,
  event,
  id,
}: {
  accessibleName: string;
  event: AuditEvent;
  id: string;
}) {
  const fields = [
    {
      term: "Changed paths",
      value: event.changedPaths.length === 0 ? "—" : event.changedPaths.join(", "),
    },
    {
      term: "Status",
      value: (
        <>
          {event.statusClass}
          {event.errorCode ? <span className="block">{event.errorCode}</span> : null}
        </>
      ),
    },
    { term: "Request", value: <span className="font-mono">{requestText(event)}</span> },
    { term: "Details", value: detailsText(event) },
  ];
  return (
    <div aria-label={accessibleName} id={id} role="region">
      <dl className="flex flex-col gap-1">
        {fields.map(({ term, value }) => (
          <div className="flex gap-6" key={term}>
            <dt className="w-32 shrink-0 font-medium text-muted-foreground">{term}</dt>
            <dd className="whitespace-normal">{value}</dd>
          </div>
        ))}
      </dl>
    </div>
  );
}

/**
 * One event: its row of columns, and beneath it, while open, a row of its own
 * holding {@link EventDetail}. The control is the chevron button, so Enter and
 * Space work it, and it states whether it is open with `aria-expanded`.
 *
 * A click anywhere on the row, or on its open detail, toggles it too — a mouse
 * convenience only, so neither row is given a role or a Tab stop of its own. A
 * click that ends a text selection, such as dragging over an actor id to copy
 * it, toggles nothing. The chevron's own click stops there, so it toggles once.
 */
function EventRow({ event }: { event: AuditEvent }) {
  const [open, setOpen] = useState(false);
  const toggle = () => setOpen((current) => !current);
  const toggleUnlessSelecting = () => {
    if (!window.getSelection()?.toString()) toggle();
  };
  const detailId = useId();
  const occurred = formatInstant(event.occurredAt);
  const label = AUDIT_OPERATION_LABELS[event.operation];
  const accessibleName = `Details of ${label} at ${occurred}`;
  return (
    <>
      <TableRow className="cursor-pointer align-top" onClick={toggleUnlessSelecting}>
        <TableHead className="tabular-nums" scope="row">
          {occurred}
        </TableHead>
        <TableCell>{label}</TableCell>
        <TableCell>
          <StatusBadge status={event.outcome} />
        </TableCell>
        <TableCell className="text-muted-foreground">
          {event.actorId === null ? "—" : <span className="font-mono">{event.actorId}</span>}
        </TableCell>
        <TableCell className="text-muted-foreground">
          <ResourceCell event={event} />
        </TableCell>
        <TableCell className="text-right">
          <Button
            aria-controls={detailId}
            aria-expanded={open}
            aria-label={accessibleName}
            onClick={(click) => {
              click.stopPropagation();
              toggle();
            }}
            size="icon-sm"
            variant="ghost"
          >
            <ChevronRightIcon
              aria-hidden="true"
              className={open ? "rotate-90 transition-transform" : "transition-transform"}
            />
          </Button>
        </TableCell>
      </TableRow>
      {open ? (
        <TableRow onClick={toggleUnlessSelecting}>
          <TableCell className="pb-3" colSpan={AUDIT_COLUMNS.length + 1}>
            <EventDetail accessibleName={accessibleName} event={event} id={detailId} />
          </TableCell>
        </TableRow>
      ) : null}
    </>
  );
}

function AuditTable({ events }: { events: readonly AuditEvent[] }) {
  return (
    <Table>
      <TableCaption className="sr-only">Audit events</TableCaption>
      <TableHeader>
        <TableRow>
          {AUDIT_COLUMNS.map((heading) => (
            <TableHead className="text-muted-foreground" key={heading} scope="col">
              {heading}
            </TableHead>
          ))}
          <TableHead scope="col">
            <span className="sr-only">Event details</span>
          </TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {events.map((event) => (
          <EventRow event={event} key={event.id} />
        ))}
      </TableBody>
    </Table>
  );
}

/**
 * The listing and the query it was read for, as state. The query is stored as
 * {@link parseAuditFilters} returned it, and a page turn moves it without
 * parsing the filters again. A read happens only on
 * mount, on every apply that parses, on a page change and on an
 * explicit Refresh — never on a timer, because every request to this backend
 * renews the session's idle clock.
 *
 * A filter refused on the page and a read refused by the backend share the
 * page's one error line, which the signed-in layout owns and renders, where
 * the latest failure shows.
 */
function useAuditEvents() {
  const [draft, setDraft] = useState<AuditFilters>(EMPTY_AUDIT_FILTERS);
  const [applied, setApplied] = useState<AuditFilters>(EMPTY_AUDIT_FILTERS);
  const [query, setQuery] = useState<AuditQuery>(UNFILTERED_AUDIT_QUERY);
  const filters = useStatusReporter();

  const reading = useGatedRead(AUDIT_EVENTS, {
    args: [query],
    failureMessage: "Unable to load the audit trail. Please try again.",
  });

  /**
   * The one way filters reach the listing: a set either refused on the page's
   * line, sending nothing, or read from its first page. Says which.
   */
  const read = (next: AuditFilters, parsed: ParsedAuditFilters): boolean => {
    setDraft(next);
    if (!parsed.ok) {
      filters.report(parsed.message);
      return false;
    }
    filters.report(null);
    // An apply always reads, even of the filters already shown: the re-read
    // is for the query set beside it, and is its one read either way.
    void reading.reload();
    setQuery(parsed.query);
    return true;
  };

  return {
    /** The toolbar's Apply and Reset: the whole set, typed fields included. */
    apply: (next: AuditFilters) => {
      if (read(next, parseAuditFilters(next))) setApplied(next);
    },
    /** An Operation or Outcome change, which leaves the typed fields to Apply. */
    applyControl: (next: AuditFilters) => {
      read(next, parseAuditControlChange(next, applied));
    },
    draft,
    move: (startIndex: number) => setQuery((current) => auditQueryAt(current, startIndex)),
    reload: reading.reload,
    setDraft,
    view: reading.view,
  };
}

/**
 * The read-only audit trail: every recorded administrative, authentication
 * and provisioning event, filtered and paged. Rendered only for a session
 * holding `audit:read` — the route guard keeps out any other — and the
 * listing is read only with it in any case, so the hook refuses nothing a
 * session without the Permission could not have asked for anyway.
 *
 * Every field is rendered as plain text, in its row or its expanded detail:
 * `httpPath` and `filterShape` are never turned into a link, and nothing here reaches `dangerouslySetInnerHTML`.
 * There is no polling and no interval — a read happens only on mount, on a
 * valid filter apply, on paging, and on the explicit Refresh button, because
 * every request to this backend renews the session's idle timeout.
 */
export function Audit() {
  const { apply, applyControl, draft, move, reload, setDraft, view } = useAuditEvents();

  return (
    <div className="flex flex-col gap-6">
      <Card>
        <CardHeader>
          <CardTitle>Audit trail</CardTitle>
          <CardDescription>
            Every recorded administrative, authentication and provisioning event, newest first.
          </CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-4">
          <AuditFilterToolbar
            draft={draft}
            onApply={apply}
            onControlChange={applyControl}
            onDraft={setDraft}
          />

          {/* A refresh that failed hides the events last read, as every paged
              listing does: the trail is evidence, and rows it could not
              confirm are not shown as it. Refresh is always offered, since
              the trail grows with nothing on the page to say so. */}
          <PagedListing
            copy={{
              empty: "No audit events match the current filters.",
              loading: "Loading audit events…",
              noun: "Events",
              pagerLabel: "Audit trail pages",
            }}
            onMove={move}
            onReadAgain={() => void reload()}
            pageSize={AUDIT_PAGE_SIZE}
            readAgain="always"
            rows={eventsOf}
            view={view}
          >
            {({ rows: events }) => <AuditTable events={events} />}
          </PagedListing>
        </CardContent>
      </Card>
    </div>
  );
}
