/**
 * The wire shapes and query of the redacted administrative audit listing —
 * `GET /api/admin/audit-events`, read-only. `/frontend/AGENTS.md`'s
 * page-owned `api.ts`, named for its page.
 *
 * Nothing here requests anything: the Audit page reads through
 * `useSessionRequest` (in practice `useGatedRead`), so a `401` still ends the
 * session in one place. This file only says what a response looks like, how a
 * page of it is addressed, and how a filter is validated before it becomes a
 * query.
 */

import { DecodeError, readObject } from "@/lib/decode";

/**
 * What an audit event says happened, mirroring the backend's closed set
 * (`AuditOperation`). A value outside this list is refused on decode, never
 * passed through.
 */
export const AUDIT_OPERATIONS = [
  "LOGIN_SUCCESS",
  "LOGIN_FAILURE",
  "LOGOUT",
  "LOCKOUT_SET",
  "LOCKOUT_LIFT",
  "ACCOUNT_DISABLE",
  "ACCOUNT_ENABLE",
  "CONNECTOR_CREATE",
  "CONNECTOR_DELETE",
  "CONNECTOR_TOKEN_ISSUE",
  "CONNECTOR_TOKEN_ROTATE",
  "CONNECTOR_TOKEN_REVOKE",
  "SCIM_USER_CREATE",
  "SCIM_USER_LIST",
  "SCIM_USER_REPLACE",
  "SCIM_USER_DELETE",
  "USER_SESSIONS_REVOKE",
  "SCIM_GROUP_CREATE",
  "SCIM_GROUP_REPLACE",
  "SCIM_GROUP_DELETE",
  "SCIM_GROUP_LIST",
  "SCIM_RESOURCE_LIST",
  "SCIM_RESOURCE_SEED",
  "DORMANCY_LOCKOUT",
  "DORMANCY_ROLE_REVOCATION",
  "PASSWORD_CHANGE_REQUIRE",
  "PASSWORD_CHANGE",
  "ACCESS_DENIED",
  "ROLE_GRANT",
  "ROLE_REVOKE",
] as const;

export type AuditOperation = (typeof AUDIT_OPERATIONS)[number];

/** Whether the audited operation worked, mirroring `AuditOutcome`. */
export const AUDIT_OUTCOMES = ["SUCCESS", "FAILURE"] as const;

export type AuditOutcome = (typeof AUDIT_OUTCOMES)[number];

/**
 * How a `LOGIN_SUCCESS` or `LOGIN_FAILURE` was attempted, mirroring
 * `AuditLoginMethod`: password Login, or an EHR launch from Epic (`sso`).
 */
const AUDIT_LOGIN_METHODS = ["password", "sso"] as const;

type AuditLoginMethod = (typeof AUDIT_LOGIN_METHODS)[number];

/**
 * One recorded event, exactly as `AuditEvent` reports it: every reference to a
 * person or a resource is a stable id, never a readable name, and there is no
 * field a password or a bearer value could be written into.
 */
export interface AuditEvent {
  id: string;
  occurredAt: string;
  operation: AuditOperation;
  outcome: AuditOutcome;
  actorId: string | null;
  subjectId: string | null;
  resourceType: string;
  resourceId: string | null;
  changedPaths: string[];
  statusClass: string;
  errorCode: string | null;
  httpMethod: string | null;
  httpPath: string | null;
  /** Correlation id of the triggering request, or `null` for an event no request triggered. */
  requestId: string | null;
  resultCount: number | null;
  filterShape: string | null;
  role: string | null;
  permissions: string[];
  /** How a login was attempted; `null` for every event that is not a login. */
  loginMethod: AuditLoginMethod | null;
}

/** One page of the listing, newest first, and where it sits in the whole. */
export interface AuditEventPage {
  events: AuditEvent[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export const AUDIT_EVENTS_PATH = "/api/admin/audit-events";

/** The page size the Audit page asks for; the backend's own default. */
export const DEFAULT_PAGE_SIZE = 50;

// ---- decoders ----------------------------------------------------------------------

const decodeStringElement = (value: unknown): string => {
  if (typeof value !== "string") throw new DecodeError("is not a string");
  return value;
};

const decodeAuditEvent = (value: unknown): AuditEvent => {
  const event = readObject(value, "AuditEvent");
  return {
    id: event.string("id"),
    occurredAt: event.string("occurredAt"),
    operation: event.oneOf("operation", AUDIT_OPERATIONS),
    outcome: event.oneOf("outcome", AUDIT_OUTCOMES),
    actorId: event.nullableString("actorId"),
    subjectId: event.nullableString("subjectId"),
    resourceType: event.string("resourceType"),
    resourceId: event.nullableString("resourceId"),
    changedPaths: event.array("changedPaths", decodeStringElement),
    statusClass: event.string("statusClass"),
    errorCode: event.nullableString("errorCode"),
    httpMethod: event.nullableString("httpMethod"),
    httpPath: event.nullableString("httpPath"),
    requestId: event.nullableString("requestId"),
    resultCount: event.nullableInteger("resultCount"),
    filterShape: event.nullableString("filterShape"),
    role: event.nullableString("role"),
    permissions: event.array("permissions", decodeStringElement),
    loginMethod: event.nullableOneOf("loginMethod", AUDIT_LOGIN_METHODS),
  };
};

export const decodeAuditEventPage = (value: unknown): AuditEventPage => {
  const page = readObject(value, "AuditEventPage");
  return {
    events: page.array("events", decodeAuditEvent),
    page: page.integer("page"),
    size: page.integer("size"),
    totalElements: page.integer("totalElements"),
    totalPages: page.integer("totalPages"),
  };
};

// ---- filters, validation and the query they build ----------------------------------

/**
 * The filters the Audit page offers, each as the raw control value: the two
 * closed-set selects, the two id text inputs, and the two
 * `datetime-local` inputs. `""` means "no filter" for every field.
 */
export interface AuditFilters {
  operation: AuditOperation | "";
  outcome: AuditOutcome | "";
  actorId: string;
  resourceId: string;
  from: string;
  to: string;
}

/** No filter narrows the listing: every event, from page zero. */
export const EMPTY_AUDIT_FILTERS: AuditFilters = {
  operation: "",
  outcome: "",
  actorId: "",
  resourceId: "",
  from: "",
  to: "",
};

export type FilterValidation = { ok: true } | { ok: false; message: string };

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

const ID_FIELDS = [
  { key: "actorId", label: "Actor id" },
  { key: "resourceId", label: "Resource id" },
] as const satisfies readonly { key: keyof AuditFilters; label: string }[];

/**
 * Checks a filter set is well-formed before it becomes a request: an id that
 * is not a UUID, or a `from` not strictly before `to`, is refused locally so
 * the backend's own `400` is never spent on it.
 */
export function validateAuditFilters(filters: AuditFilters): FilterValidation {
  for (const { key, label } of ID_FIELDS) {
    const value = filters[key].trim();
    if (value !== "" && !UUID_PATTERN.test(value)) {
      return { ok: false, message: `${label} must be a valid UUID.` };
    }
  }
  // `Date.parse` on an empty or malformed bound is `NaN`, and a comparison
  // against `NaN` is always `false` either side, so an unset or unparsable
  // bound never triggers a refusal on its own — only two bounds that both
  // parse, with `from` not strictly before `to`, do.
  if (Date.parse(filters.from) >= Date.parse(filters.to)) {
    return { ok: false, message: "The from date must be before the to date." };
  }
  return { ok: true };
}

/**
 * The path and query for one page of the listing: every non-empty filter,
 * plus `page` and `size` always. Assumes `filters` already passed
 * {@link validateAuditFilters} — this never refuses, it only omits what was
 * left empty.
 */
export function buildAuditQuery(filters: AuditFilters, page: number, size: number): string {
  const params = new URLSearchParams();
  if (filters.operation !== "") params.set("operation", filters.operation);
  if (filters.outcome !== "") params.set("outcome", filters.outcome);
  const actorId = filters.actorId.trim();
  if (actorId !== "") params.set("actorId", actorId);
  const resourceId = filters.resourceId.trim();
  if (resourceId !== "") params.set("resourceId", resourceId);
  if (filters.from !== "") params.set("from", new Date(filters.from).toISOString());
  if (filters.to !== "") params.set("to", new Date(filters.to).toISOString());
  params.set("page", String(page));
  params.set("size", String(size));
  return `${AUDIT_EVENTS_PATH}?${params.toString()}`;
}

/** Date and minute from an ISO instant, locale-independent like `accounts-api.ts`'s. */
export const formatInstant = (instant: string): string =>
  `${instant.slice(0, 10)} ${instant.slice(11, 16)}`;
