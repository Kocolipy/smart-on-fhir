import { useState, type FormEvent } from "react";

import { useStatusReporter } from "@/auth/page-status";
import { type ReadView, useGatedRead } from "@/auth/use-gated-read";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
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
  AUDIT_OPERATIONS,
  AUDIT_OUTCOMES,
  AUDIT_PAGE_SIZE,
  auditQueryAt,
  EMPTY_AUDIT_FILTERS,
  parseAuditFilters,
  type AuditEvent,
  type AuditEventPage,
  type AuditOperation,
  type AuditFilters,
  UNFILTERED_AUDIT_QUERY,
  type AuditQuery,
} from "./audit-api";
import { ListingBody } from "@/pages/listing-body";
import { emptyCopyOf } from "@/pages/page-empty-copy";
import { Pager } from "@/pages/pager";

/** Radix `SelectItem` forbids an empty-string value; this stands in for "any", translated back to "" at the call site. */
const ANY_FILTER_VALUE = "__any__";

const eventsOf = (page: AuditEventPage) => page.events;

const AUDIT_COLUMNS = [
  "Occurred",
  "Operation",
  "Outcome",
  "Actor",
  "Resource",
  "Changed paths",
  "Status",
  "Request",
  "Details",
];

/** The resource column: its type, and the id when the operation named one. */
function resourceCell(event: AuditEvent): string {
  return event.resourceId === null
    ? event.resourceType
    : `${event.resourceType} ${event.resourceId}`;
}

/**
 * The request column: the triggering request's route and its correlation id,
 * either of which is absent for an event no request triggered — a scheduled
 * job's write, or a seeding event recorded with no actor at all.
 */
function requestCell(event: AuditEvent): string {
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
function detailsCell(event: AuditEvent): string {
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

function EventRow({ event }: { event: AuditEvent }) {
  return (
    <TableRow className="align-top">
      <TableHead scope="row">{formatInstant(event.occurredAt)}</TableHead>
      <TableCell>{event.operation}</TableCell>
      <TableCell>{event.outcome}</TableCell>
      <TableCell className="text-muted-foreground">{event.actorId ?? "—"}</TableCell>
      <TableCell className="text-muted-foreground">{resourceCell(event)}</TableCell>
      <TableCell className="text-muted-foreground">
        {event.changedPaths.length === 0 ? "—" : event.changedPaths.join(", ")}
      </TableCell>
      <TableCell className="text-muted-foreground">
        {event.statusClass}
        {event.errorCode ? <span className="block">{event.errorCode}</span> : null}
      </TableCell>
      <TableCell className="text-muted-foreground">{requestCell(event)}</TableCell>
      <TableCell className="text-muted-foreground">{detailsCell(event)}</TableCell>
    </TableRow>
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

function FiltersForm({
  draft,
  onChange,
  onSubmit,
}: {
  draft: AuditFilters;
  onChange: (next: AuditFilters) => void;
  onSubmit: (event: FormEvent) => void;
}) {
  const set = <K extends keyof AuditFilters>(key: K, value: AuditFilters[K]) =>
    onChange({ ...draft, [key]: value });

  return (
    <form className="flex flex-wrap items-end gap-3" onSubmit={onSubmit}>
      <div className="flex flex-col gap-1">
        <Label htmlFor="audit-operation">Operation</Label>
        <Select
          onValueChange={(value) =>
            set("operation", (value === ANY_FILTER_VALUE ? "" : value) as AuditFilters["operation"])
          }
          value={draft.operation === "" ? ANY_FILTER_VALUE : draft.operation}
        >
          <SelectTrigger id="audit-operation">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value={ANY_FILTER_VALUE}>Any operation</SelectItem>
            {AUDIT_OPERATIONS.map((operation) => (
              <SelectItem key={operation} value={operation}>
                {operation}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>
      <div className="flex flex-col gap-1">
        <Label htmlFor="audit-outcome">Outcome</Label>
        <Select
          onValueChange={(value) =>
            set("outcome", (value === ANY_FILTER_VALUE ? "" : value) as AuditFilters["outcome"])
          }
          value={draft.outcome === "" ? ANY_FILTER_VALUE : draft.outcome}
        >
          <SelectTrigger id="audit-outcome">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value={ANY_FILTER_VALUE}>Any outcome</SelectItem>
            {AUDIT_OUTCOMES.map((outcome) => (
              <SelectItem key={outcome} value={outcome}>
                {outcome}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>
      <div className="flex flex-col gap-1">
        <Label htmlFor="audit-actor-id">Actor id</Label>
        <Input
          id="audit-actor-id"
          onChange={(event) => set("actorId", event.target.value)}
          value={draft.actorId}
        />
      </div>
      <div className="flex flex-col gap-1">
        <Label htmlFor="audit-resource-id">Resource id</Label>
        <Input
          id="audit-resource-id"
          onChange={(event) => set("resourceId", event.target.value)}
          value={draft.resourceId}
        />
      </div>
      <div className="flex flex-col gap-1">
        <Label htmlFor="audit-from">From</Label>
        <Input
          id="audit-from"
          onChange={(event) => set("from", event.target.value)}
          type="datetime-local"
          value={draft.from}
        />
      </div>
      <div className="flex flex-col gap-1">
        <Label htmlFor="audit-to">To</Label>
        <Input
          id="audit-to"
          onChange={(event) => set("to", event.target.value)}
          type="datetime-local"
          value={draft.to}
        />
      </div>
      <Button size="sm" type="submit">
        Apply filters
      </Button>
    </form>
  );
}

/**
 * The listing and the query it was read for, as state. The query is stored as
 * {@link parseAuditFilters} returned it, and a page turn moves it without
 * parsing the filters again. A read happens only on
 * mount, on every filter submit that parses, on a page change and on an
 * explicit Refresh — never on a timer, because every request to this backend
 * renews the session's idle clock.
 *
 * A filter refused on the page and a read refused by the backend share the
 * page's one error line, which the signed-in layout owns and renders, where
 * the latest failure shows.
 */
function useAuditEvents() {
  const [draft, setDraft] = useState<AuditFilters>(EMPTY_AUDIT_FILTERS);
  const [query, setQuery] = useState<AuditQuery>(UNFILTERED_AUDIT_QUERY);
  const filters = useStatusReporter();

  const reading = useGatedRead(AUDIT_EVENTS, {
    args: [query],
    failureMessage: "Unable to load the audit trail. Please try again.",
  });

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const parsed = parseAuditFilters(draft);
    if (!parsed.ok) {
      filters.report(parsed.message);
      return;
    }
    filters.report(null);
    // A submit always reads, even of the filters already shown: the re-read
    // is for the query set beside it, and is its one read either way.
    void reading.reload();
    setQuery(parsed.query);
  };

  return {
    draft,
    move: (startIndex: number) => setQuery((current) => auditQueryAt(current, startIndex)),
    reload: reading.reload,
    setDraft,
    submit,
    view: reading.view,
  };
}

/**
 * The page a read answered for the current query, or `null` while none has: a
 * page still loading, or one that failed, names no range it has not seen, so the
 * pager shows only for this.
 */
function readPageOf(view: ReadView<AuditEventPage>): AuditEventPage | null {
  switch (view.kind) {
    case "not-permitted":
    case "loading":
    case "failed":
      return null;
    case "ready":
    case "stale":
      return view.data;
  }
}

/**
 * The read-only audit trail: every recorded administrative, authentication
 * and provisioning event, filtered and paged. Rendered only for a session
 * holding `audit:read` — the route guard keeps out any other — and the
 * listing is read only with it in any case, so the hook refuses nothing a
 * session without the Permission could not have asked for anyway.
 *
 * Every field is rendered as plain text: `httpPath` and `filterShape` are
 * never turned into a link, and nothing here reaches `dangerouslySetInnerHTML`.
 * There is no polling and no interval — a read happens only on mount, on a
 * valid filter submit, on paging, and on the explicit Refresh button, because
 * every request to this backend renews the session's idle timeout.
 */
export function Audit() {
  const { draft, move, reload, setDraft, submit, view } = useAuditEvents();
  const read = readPageOf(view);

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
          <FiltersForm draft={draft} onChange={setDraft} onSubmit={submit} />

          <div className="overflow-x-auto">
            {/* A refresh that failed hides the events last read: the trail is
                evidence, and rows it could not confirm are not shown as it. */}
            <ListingBody
              empty={emptyCopyOf(read, "No audit events match the current filters.")}
              loading="Loading audit events…"
              rows={eventsOf}
              staleRows="hide"
              view={view}
            >
              {(events) => <AuditTable events={events} />}
            </ListingBody>
          </div>

          <div className="flex items-end justify-between gap-4">
            {read !== null ? (
              <Pager
                label="Audit trail pages"
                noun="Events"
                onMove={move}
                page={{ ...read, rows: eventsOf(read) }}
                pageSize={AUDIT_PAGE_SIZE}
              />
            ) : null}
            <Button className="ml-auto" onClick={() => void reload()} size="sm" variant="outline">
              Refresh
            </Button>
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
