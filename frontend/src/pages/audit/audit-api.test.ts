import { describe, expect, it } from "vitest";

import { DecodeError } from "@/lib/decode";
import { BOOLEAN, describeDecoder, STRING, type FieldCheck } from "@/lib/decode.testHelpers";

import {
  AUDIT_EVENTS,
  AUDIT_EVENTS_PATH,
  AUDIT_OPERATIONS,
  AUDIT_OUTCOMES,
  decodeAuditEvent,
  EMPTY_AUDIT_FILTERS,
  parseAuditFilters,
  UNFILTERED_AUDIT_QUERY,
  type AuditFilters,
} from "./audit-api";

/** One event exactly as the backend serialises it: every field present, `null`s included. */
const AUDIT_EVENT = {
  id: "e-1",
  occurredAt: "2026-01-02T03:04:05Z",
  operation: "LOGIN_SUCCESS",
  outcome: "SUCCESS",
  actorId: "00000000-0000-4000-8000-000000000001",
  subjectId: "00000000-0000-4000-8000-000000000001",
  resourceType: "User",
  resourceId: "00000000-0000-4000-8000-000000000001",
  changedPaths: ["password"],
  statusClass: "ok",
  errorCode: null,
  httpMethod: "POST",
  httpPath: "/api/auth/login",
  requestId: "req-1",
  resultCount: null,
  filterShape: null,
  permissions: [],
  loginMethod: "password",
  permissionsBefore: [],
  permissionsAfter: [],
  superuserChanged: null,
  groupIds: [],
};

const AUDIT_EVENT_PAGE = {
  totalResults: 1,
  startIndex: 1,
  events: [AUDIT_EVENT],
};

/** The first page of the listing, as the Audit page reads it. */
const FIRST_PAGE = { count: 50, startIndex: 1 };

const refusal = (run: () => unknown, message: string) =>
  expect(run).toThrow(new DecodeError(message));

// A page's own fields are the paged read's one page decoder's (`src/lib/paging.test.ts`);
// the events it reads are the audit trail's.
const ARRAY: FieldCheck = ["user:read", "an array"];

// Every field, missing and wrong-typed. A nullable field is refused missing as
// it is wrong-typed: `null` is a value the backend sends, never an omission.
describeDecoder("AuditEvent", decodeAuditEvent, AUDIT_EVENT, {
  id: STRING,
  occurredAt: STRING,
  operation: ["SOMETHING_ELSE", AUDIT_OPERATIONS.join(" | "), "a string"],
  outcome: ["PARTIAL", AUDIT_OUTCOMES.join(" | "), "a string"],
  actorId: STRING,
  subjectId: STRING,
  resourceType: STRING,
  resourceId: STRING,
  changedPaths: ARRAY,
  statusClass: STRING,
  errorCode: STRING,
  httpMethod: STRING,
  httpPath: STRING,
  requestId: STRING,
  resultCount: ["3", "an integer"],
  filterShape: STRING,
  permissions: ARRAY,
  loginMethod: ["saml", "password | sso", "a string"],
  permissionsBefore: ARRAY,
  permissionsAfter: ARRAY,
  superuserChanged: BOOLEAN,
  groupIds: ARRAY,
});

describe("decodeAuditEvent", () => {
  describe("the event itself", () => {
    it("decodes an event with no triggering request at all", () => {
      // A dormancy or revocation event the scheduled job records: no actor,
      // no HTTP method or path, and no correlation id — nothing ties it to a
      // request because none triggered it.
      expect(
        decodeAuditEvent({
          ...AUDIT_EVENT,
          actorId: null,
          httpMethod: null,
          httpPath: null,
          requestId: null,
        }),
      ).toMatchObject({ httpMethod: null, httpPath: null, requestId: null });
    });

    it("decodes each login method, and none for an event that is not a login", () => {
      expect(
        ["password", "sso", null].map(
          (loginMethod) => decodeAuditEvent({ ...AUDIT_EVENT, loginMethod }).loginMethod,
        ),
      ).toEqual(["password", "sso", null]);
    });

    it("decodes a resultCount when it is present", () => {
      expect(decodeAuditEvent({ ...AUDIT_EVENT, resultCount: 3 })).toMatchObject({
        resultCount: 3,
      });
    });

    it("decodes every operation the backend's closed set holds", () => {
      for (const operation of AUDIT_OPERATIONS) {
        expect(decodeAuditEvent({ ...AUDIT_EVENT, operation })).toMatchObject({ operation });
      }
    });

    it("decodes every outcome the backend's closed set holds", () => {
      for (const outcome of AUDIT_OUTCOMES) {
        expect(decodeAuditEvent({ ...AUDIT_EVENT, outcome })).toMatchObject({ outcome });
      }
    });

    it("decodes a membership change's Permissions before and after, its marker and its Groups", () => {
      const change = {
        ...AUDIT_EVENT,
        operation: "DORMANCY_MEMBERSHIP_REVOCATION",
        permissionsBefore: ["audit:read", "user:read"],
        permissionsAfter: ["user:read"],
        superuserChanged: true,
        groupIds: ["00000000-0000-4000-8000-0000000000a1"],
      };

      expect(decodeAuditEvent(change)).toStrictEqual(change);
    });

    it("decodes a Group Permissions change, the Group's Permissions before and after", () => {
      const change = {
        ...AUDIT_EVENT,
        operation: "GROUP_PERMISSIONS_CHANGE",
        resourceType: "Group",
        changedPaths: ["permissions"],
        permissionsBefore: ["audit:read", "user:read"],
        permissionsAfter: ["audit:read"],
      };

      expect(decodeAuditEvent(change)).toStrictEqual(change);
    });

    it("decodes each Superuser marker, and none for an event that is no membership change", () => {
      expect(
        [true, false, null].map(
          (superuserChanged) =>
            decodeAuditEvent({ ...AUDIT_EVENT, superuserChanged }).superuserChanged,
        ),
      ).toEqual([true, false, null]);
    });

    it.each(["changedPaths", "permissions", "permissionsBefore", "permissionsAfter", "groupIds"])(
      "refuses a %s entry that is not a string",
      (key) => {
        refusal(
          () => decodeAuditEvent({ ...AUDIT_EVENT, [key]: ["user:read", 7] }),
          "is not a string",
        );
      },
    );
  });
});

describe("parseAuditFilters", () => {
  const valid: AuditFilters = {
    ...EMPTY_AUDIT_FILTERS,
    actorId: "00000000-0000-4000-8000-000000000001",
    resourceId: "00000000-0000-4000-8000-000000000002",
    from: "2026-01-01T00:00",
    to: "2026-01-02T00:00",
  };

  /** The path a filter set's query is read at, or its refusal message — whichever the step returned. */
  const outcome = (filters: AuditFilters) => {
    const parsed = parseAuditFilters(filters);
    return parsed.ok ? AUDIT_EVENTS.path(parsed.query, FIRST_PAGE) : parsed.message;
  };

  it("parses empty filters to exactly the unfiltered query, read with its paging alone", () => {
    expect(parseAuditFilters(EMPTY_AUDIT_FILTERS)).toEqual({
      ok: true,
      query: UNFILTERED_AUDIT_QUERY,
    });
    expect(outcome(EMPTY_AUDIT_FILTERS)).toBe(`${AUDIT_EVENTS_PATH}?startIndex=1&count=50`);
  });

  it("parses one operation and an outcome into the query", () => {
    expect(
      outcome({ ...EMPTY_AUDIT_FILTERS, operations: ["LOGIN_FAILURE"], outcome: "FAILURE" }),
    ).toBe(`${AUDIT_EVENTS_PATH}?operation=LOGIN_FAILURE&outcome=FAILURE&startIndex=1&count=50`);
  });

  it("repeats the parameter for several operations", () => {
    expect(outcome({ ...EMPTY_AUDIT_FILTERS, operations: ["LOCKOUT_SET", "LOCKOUT_LIFT"] })).toBe(
      `${AUDIT_EVENTS_PATH}?operation=LOCKOUT_SET&operation=LOCKOUT_LIFT&startIndex=1&count=50`,
    );
  });

  it("refuses an invalid typed field whatever operations are selected", () => {
    expect(outcome({ ...valid, operations: ["LOGOUT"], actorId: "not-a-uuid" })).toBe(
      "Actor id must be a valid UUID.",
    );
  });

  it("parses trimmed actor and resource ids into the query", () => {
    expect(
      outcome({
        ...EMPTY_AUDIT_FILTERS,
        actorId: "  00000000-0000-4000-8000-000000000001  ",
        resourceId: "  00000000-0000-4000-8000-000000000002  ",
      }),
    ).toBe(
      `${AUDIT_EVENTS_PATH}?actorId=00000000-0000-4000-8000-000000000001&resourceId=00000000-0000-4000-8000-000000000002&startIndex=1&count=50`,
    );
  });

  it("reads from/to datetime-local values as Singapore time, sent as ISO instants", () => {
    const path = outcome({
      ...EMPTY_AUDIT_FILTERS,
      from: "2026-01-01T00:00",
      to: "2026-01-02T00:00",
    });
    const params = new URLSearchParams(path.slice(AUDIT_EVENTS_PATH.length + 1));
    // Whatever the browser's zone, eight hours ahead of UTC.
    expect([params.get("from"), params.get("to")]).toEqual([
      "2025-12-31T16:00:00.000Z",
      "2026-01-01T16:00:00.000Z",
    ]);
  });

  it("refuses a date bound that names a day the calendar lacks", () => {
    expect(outcome({ ...EMPTY_AUDIT_FILTERS, from: "2026-02-30T00:00" })).toBe(
      "From date must be a valid date.",
    );
  });

  it.each([
    ["actorId", "Actor id must be a valid UUID."],
    ["resourceId", "Resource id must be a valid UUID."],
  ] as const)("refuses a malformed %s that is not a UUID", (key, message) => {
    expect(outcome({ ...valid, [key]: "not-a-uuid" })).toBe(message);
  });

  it.each([
    ["a prefix", "x00000000-0000-4000-8000-000000000001"],
    ["a suffix", "00000000-0000-4000-8000-000000000001x"],
  ])("refuses an id that wraps a well-formed UUID in %s", (_, actorId) => {
    expect(outcome({ ...valid, actorId })).toBe("Actor id must be a valid UUID.");
  });

  it("refuses a from that is not before to", () => {
    expect(outcome({ ...valid, from: "2026-01-02T00:00", to: "2026-01-01T00:00" })).toBe(
      "The from date must be before the to date.",
    );
  });

  it("refuses a from equal to to", () => {
    expect(outcome({ ...valid, from: valid.to })).toBe("The from date must be before the to date.");
  });

  it.each([
    ["to", { to: "" }],
    ["from", { from: "" }],
  ])("parses one date bound with %s left empty", (_, change) => {
    expect(parseAuditFilters({ ...valid, ...change }).ok).toBe(true);
  });

  it.each([
    ["from", "From date must be a valid date."],
    ["to", "To date must be a valid date."],
  ] as const)("refuses a %s bound in year 275760, past what a Date can hold", (key, message) => {
    // `datetime-local` accepts year 275760, but JavaScript cannot represent
    // this moment of it: the bound is refused, never turned into a query.
    expect(outcome({ ...EMPTY_AUDIT_FILTERS, [key]: "275760-12-31T23:59" })).toBe(message);
  });

  it("refuses an unparsable date bound", () => {
    expect(outcome({ ...EMPTY_AUDIT_FILTERS, to: "not-a-date" })).toBe(
      "To date must be a valid date.",
    );
  });
});

describe("AUDIT_EVENTS", () => {
  it("reads the page its query addresses, its filters before the page, under audit:read", () => {
    const parsed = parseAuditFilters({
      ...EMPTY_AUDIT_FILTERS,
      operations: ["LOGOUT", "LOCKOUT_SET"],
      outcome: "FAILURE",
    });
    if (!parsed.ok) throw new Error("the filters should parse");
    expect({
      path: AUDIT_EVENTS.path(parsed.query, { count: 50, startIndex: 51 }),
      permission: AUDIT_EVENTS.permission,
    }).toEqual({
      path: "/api/admin/audit-events?operation=LOGOUT&operation=LOCKOUT_SET&outcome=FAILURE&startIndex=51&count=50",
      permission: "audit:read",
    });
  });

  it("reads its answer as one page of the audit trail", async () => {
    await expect(AUDIT_EVENTS.decode(Response.json(AUDIT_EVENT_PAGE))).resolves.toStrictEqual({
      rows: [AUDIT_EVENT],
      startIndex: 1,
      totalResults: 1,
    });
  });

  it("refuses an answer that is a bare list of events rather than a page", async () => {
    await expect(AUDIT_EVENTS.decode(Response.json([AUDIT_EVENT]))).rejects.toThrow(
      new DecodeError("AuditEventPage is not an object"),
    );
  });
});
