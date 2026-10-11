import { useState, type FormEvent } from "react";

import { useGatedRead } from "@/auth/use-gated-read";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { jsonDecoder } from "@/lib/decode";

import {
  AUDIT_OPERATIONS,
  AUDIT_OUTCOMES,
  buildAuditQuery,
  decodeAuditEventPage,
  DEFAULT_PAGE_SIZE,
  EMPTY_AUDIT_FILTERS,
  formatInstant,
  validateAuditFilters,
  type AuditEvent,
  type AuditFilters,
} from "./audit-api";

const INPUT_CLASS =
  "flex h-9 rounded-md border border-input bg-background px-3 py-1 text-sm outline-none focus-visible:ring-2 focus-visible:ring-ring";

/** Module-level, so it is one stable function across renders and hook dependencies. */
const readAuditPage = jsonDecoder(decodeAuditEventPage);

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

/**
 * Everything else a reader may need, as plain text joined by " · ": a login's
 * method, a bulk read's result count and filter shape, a membership change's
 * Role, and the Permissions a token event carried. Empty when the operation
 * names none of these.
 */
function detailsCell(event: AuditEvent): string {
  const parts = [
    event.loginMethod === null ? null : `login method: ${event.loginMethod}`,
    event.resultCount === null ? null : `${event.resultCount} result(s)`,
    event.filterShape,
    event.role,
    event.permissions.length === 0 ? null : event.permissions.join(", "),
  ].filter((part): part is string => part !== null);
  return parts.length === 0 ? "—" : parts.join(" · ");
}

function EventRow({ event }: { event: AuditEvent }) {
  return (
    <tr className="border-b align-top last:border-0">
      <th className="py-3 pr-4 font-medium" scope="row">
        {formatInstant(event.occurredAt)}
      </th>
      <td className="py-3 pr-4">{event.operation}</td>
      <td className="py-3 pr-4">{event.outcome}</td>
      <td className="py-3 pr-4 text-muted-foreground">{event.actorId ?? "—"}</td>
      <td className="py-3 pr-4 text-muted-foreground">{resourceCell(event)}</td>
      <td className="py-3 pr-4 text-muted-foreground">
        {event.changedPaths.length === 0 ? "—" : event.changedPaths.join(", ")}
      </td>
      <td className="py-3 pr-4 text-muted-foreground">
        {event.statusClass}
        {event.errorCode ? <span className="block">{event.errorCode}</span> : null}
      </td>
      <td className="py-3 pr-4 text-muted-foreground">{requestCell(event)}</td>
      <td className="py-3 text-muted-foreground">{detailsCell(event)}</td>
    </tr>
  );
}

function AuditTable({ events }: { events: AuditEvent[] }) {
  return (
    <table className="w-full border-collapse text-left text-sm">
      <caption className="sr-only">Audit events</caption>
      <thead>
        <tr className="border-b text-muted-foreground">
          {AUDIT_COLUMNS.map((heading) => (
            <th className="py-2 pr-4 font-medium" key={heading} scope="col">
              {heading}
            </th>
          ))}
        </tr>
      </thead>
      <tbody>
        {events.map((event) => (
          <EventRow event={event} key={event.id} />
        ))}
      </tbody>
    </table>
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
        <label className="text-sm font-medium" htmlFor="audit-operation">
          Operation
        </label>
        <select
          className={INPUT_CLASS}
          id="audit-operation"
          onChange={(event) => set("operation", event.target.value as AuditFilters["operation"])}
          value={draft.operation}
        >
          <option value="">Any operation</option>
          {AUDIT_OPERATIONS.map((operation) => (
            <option key={operation} value={operation}>
              {operation}
            </option>
          ))}
        </select>
      </div>
      <div className="flex flex-col gap-1">
        <label className="text-sm font-medium" htmlFor="audit-outcome">
          Outcome
        </label>
        <select
          className={INPUT_CLASS}
          id="audit-outcome"
          onChange={(event) => set("outcome", event.target.value as AuditFilters["outcome"])}
          value={draft.outcome}
        >
          <option value="">Any outcome</option>
          {AUDIT_OUTCOMES.map((outcome) => (
            <option key={outcome} value={outcome}>
              {outcome}
            </option>
          ))}
        </select>
      </div>
      <div className="flex flex-col gap-1">
        <label className="text-sm font-medium" htmlFor="audit-actor-id">
          Actor id
        </label>
        <input
          className={INPUT_CLASS}
          id="audit-actor-id"
          onChange={(event) => set("actorId", event.target.value)}
          value={draft.actorId}
        />
      </div>
      <div className="flex flex-col gap-1">
        <label className="text-sm font-medium" htmlFor="audit-resource-id">
          Resource id
        </label>
        <input
          className={INPUT_CLASS}
          id="audit-resource-id"
          onChange={(event) => set("resourceId", event.target.value)}
          value={draft.resourceId}
        />
      </div>
      <div className="flex flex-col gap-1">
        <label className="text-sm font-medium" htmlFor="audit-from">
          From
        </label>
        <input
          className={INPUT_CLASS}
          id="audit-from"
          onChange={(event) => set("from", event.target.value)}
          type="datetime-local"
          value={draft.from}
        />
      </div>
      <div className="flex flex-col gap-1">
        <label className="text-sm font-medium" htmlFor="audit-to">
          To
        </label>
        <input
          className={INPUT_CLASS}
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
 * The listing, its committed filters and its page, as state. A read happens
 * only on mount, on a filter submit that validates, on a page change and on
 * an explicit Refresh — never on a timer, because every request to this
 * backend renews the session's idle clock.
 */
function useAuditEvents() {
  const [draft, setDraft] = useState<AuditFilters>(EMPTY_AUDIT_FILTERS);
  const [committed, setCommitted] = useState<AuditFilters>(EMPTY_AUDIT_FILTERS);
  const [page, setPage] = useState(0);
  const [validationError, setValidationError] = useState<string | null>(null);

  const path = buildAuditQuery(committed, page, DEFAULT_PAGE_SIZE);
  const reading = useGatedRead({
    decode: readAuditPage,
    failureMessage: "Unable to load the audit trail. Please try again.",
    path,
    permission: "audit:read",
  });

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const result = validateAuditFilters(draft);
    if (!result.ok) {
      setValidationError(result.message);
      return;
    }
    setValidationError(null);
    setCommitted(draft);
    setPage(0);
  };

  const totalPages = reading.data?.totalPages ?? 0;

  return {
    draft,
    error: validationError ?? reading.error,
    events: reading.data?.events ?? null,
    failed: reading.failed,
    nextPage: () => setPage((current) => current + 1),
    page,
    // No clamp: the Previous button that calls this is disabled at page zero,
    // so `current` is never zero when this runs.
    previousPage: () => setPage((current) => current - 1),
    reload: reading.reload,
    setDraft,
    submit,
    totalPages,
  };
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
  const {
    draft,
    error,
    events,
    failed,
    nextPage,
    page,
    previousPage,
    reload,
    setDraft,
    submit,
    totalPages,
  } = useAuditEvents();

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

          {error ? (
            <p className="text-sm text-destructive" role="alert">
              {error}
            </p>
          ) : null}

          <div className="overflow-x-auto">
            {failed ? null : events === null ? (
              <p className="text-sm text-muted-foreground">Loading audit events…</p>
            ) : events.length === 0 ? (
              <p className="text-sm text-muted-foreground">
                No audit events match the current filters.
              </p>
            ) : (
              <AuditTable events={events} />
            )}
          </div>

          <div className="flex items-center justify-between gap-4">
            <Button disabled={page === 0} onClick={previousPage} size="sm" variant="outline">
              Previous
            </Button>
            <p className="text-sm text-muted-foreground">
              Page {page + 1} of {Math.max(totalPages, 1)}
            </p>
            <Button
              disabled={page + 1 >= totalPages}
              onClick={nextPage}
              size="sm"
              variant="outline"
            >
              Next
            </Button>
            <Button onClick={() => void reload()} size="sm" variant="outline">
              Refresh
            </Button>
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
