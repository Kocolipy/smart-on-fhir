import { describe, expect, it } from "vitest";

import { DecodeError } from "@/lib/decode";

import {
  AUDIT_EVENTS_PATH,
  AUDIT_OPERATIONS,
  AUDIT_OUTCOMES,
  auditQueryAtPage,
  decodeAuditEventPage,
  EMPTY_AUDIT_FILTERS,
  formatInstant,
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
  role: null,
  permissions: [],
  loginMethod: "password",
};

const AUDIT_EVENT_PAGE = {
  events: [AUDIT_EVENT],
  page: 0,
  size: 50,
  totalElements: 1,
  totalPages: 1,
};

const without = (body: object, key: string) =>
  Object.fromEntries(Object.entries(body).filter(([field]) => field !== key));

const refusal = (run: () => unknown, message: string) =>
  expect(run).toThrow(new DecodeError(message));

describe("decodeAuditEventPage", () => {
  it("decodes a valid page to the same value", () => {
    expect(decodeAuditEventPage(AUDIT_EVENT_PAGE)).toStrictEqual(AUDIT_EVENT_PAGE);
  });

  it("decodes an empty page", () => {
    expect(decodeAuditEventPage({ ...AUDIT_EVENT_PAGE, events: [], totalElements: 0 })).toEqual({
      ...AUDIT_EVENT_PAGE,
      events: [],
      totalElements: 0,
    });
  });

  it.each([
    ["events", "an array"],
    ["page", "an integer"],
    ["size", "an integer"],
    ["totalElements", "an integer"],
    ["totalPages", "an integer"],
  ])("refuses a page missing %s", (key, expected) => {
    refusal(
      () => decodeAuditEventPage(without(AUDIT_EVENT_PAGE, key)),
      `AuditEventPage.${key} is not ${expected}`,
    );
  });

  it.each([
    ["an array", [AUDIT_EVENT_PAGE]],
    ["null", null],
    ["a string", "body"],
  ])("refuses %s as a page", (_, value) => {
    refusal(() => decodeAuditEventPage(value), "AuditEventPage is not an object");
  });

  describe("the event itself", () => {
    const decodeOneEvent = (event: unknown) =>
      decodeAuditEventPage({ ...AUDIT_EVENT_PAGE, events: [event] }).events[0];

    it("decodes a valid event to the same value", () => {
      expect(decodeOneEvent(AUDIT_EVENT)).toStrictEqual(AUDIT_EVENT);
    });

    it("drops nothing the wire did not send and adds nothing", () => {
      expect(
        Object.keys(decodeOneEvent({ ...AUDIT_EVENT, extra: "ignored" }) as object).sort(),
      ).toStrictEqual(Object.keys(AUDIT_EVENT).sort());
    });

    it.each([
      ["id", "a string"],
      ["occurredAt", "a string"],
      ["resourceType", "a string"],
      ["statusClass", "a string"],
      ["changedPaths", "an array"],
      ["permissions", "an array"],
    ])("refuses an event missing %s", (key, expected) => {
      refusal(
        () => decodeOneEvent(without(AUDIT_EVENT, key)),
        `AuditEvent.${key} is not ${expected}`,
      );
    });

    it.each([
      ["actorId", "a string"],
      ["subjectId", "a string"],
      ["resourceId", "a string"],
      ["errorCode", "a string"],
      ["httpMethod", "a string"],
      ["httpPath", "a string"],
      ["filterShape", "a string"],
      ["role", "a string"],
      ["requestId", "a string"],
      ["loginMethod", "a string"],
    ])("refuses an event missing the nullable field %s", (key, expected) => {
      refusal(
        () => decodeOneEvent(without(AUDIT_EVENT, key)),
        `AuditEvent.${key} is not ${expected}`,
      );
    });

    it("decodes an event with no triggering request at all", () => {
      // A dormancy or revocation event the scheduled job records: no actor,
      // no HTTP method or path, and no correlation id — nothing ties it to a
      // request because none triggered it.
      expect(
        decodeOneEvent({
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
          (loginMethod) => decodeOneEvent({ ...AUDIT_EVENT, loginMethod }).loginMethod,
        ),
      ).toEqual(["password", "sso", null]);
    });

    it("refuses a login method outside the closed set", () => {
      refusal(
        () => decodeOneEvent({ ...AUDIT_EVENT, loginMethod: "saml" }),
        "AuditEvent.loginMethod is not password | sso",
      );
    });

    it("refuses an event missing resultCount", () => {
      refusal(
        () => decodeOneEvent(without(AUDIT_EVENT, "resultCount")),
        "AuditEvent.resultCount is not an integer",
      );
    });

    it.each([
      ["id", 7, "a string"],
      ["occurredAt", 7, "a string"],
      ["resourceType", 7, "a string"],
      ["statusClass", 7, "a string"],
      ["requestId", 7, "a string"],
      ["actorId", 7, "a string"],
      ["subjectId", 7, "a string"],
      ["resourceId", 7, "a string"],
      ["errorCode", 7, "a string"],
      ["httpMethod", 7, "a string"],
      ["httpPath", 7, "a string"],
      ["filterShape", 7, "a string"],
      ["role", 7, "a string"],
      ["changedPaths", "password", "an array"],
      ["permissions", "user:read", "an array"],
    ])("refuses a wrong-typed %s", (key, badValue, expected) => {
      refusal(
        () => decodeOneEvent({ ...AUDIT_EVENT, [key]: badValue }),
        `AuditEvent.${key} is not ${expected}`,
      );
    });

    it("refuses a wrong-typed resultCount", () => {
      refusal(
        () => decodeOneEvent({ ...AUDIT_EVENT, resultCount: "3" }),
        "AuditEvent.resultCount is not an integer",
      );
    });

    it("decodes a resultCount when it is present", () => {
      expect(decodeOneEvent({ ...AUDIT_EVENT, resultCount: 3 })).toMatchObject({ resultCount: 3 });
    });

    it("decodes every operation the backend's closed set holds", () => {
      for (const operation of AUDIT_OPERATIONS) {
        expect(decodeOneEvent({ ...AUDIT_EVENT, operation })).toMatchObject({ operation });
      }
    });

    it("refuses an operation outside the closed set", () => {
      refusal(
        () => decodeOneEvent({ ...AUDIT_EVENT, operation: "SOMETHING_ELSE" }),
        `AuditEvent.operation is not ${AUDIT_OPERATIONS.join(" | ")}`,
      );
    });

    it("decodes every outcome the backend's closed set holds", () => {
      for (const outcome of AUDIT_OUTCOMES) {
        expect(decodeOneEvent({ ...AUDIT_EVENT, outcome })).toMatchObject({ outcome });
      }
    });

    it("refuses an outcome outside the closed set", () => {
      refusal(
        () => decodeOneEvent({ ...AUDIT_EVENT, outcome: "PARTIAL" }),
        `AuditEvent.outcome is not ${AUDIT_OUTCOMES.join(" | ")}`,
      );
    });

    it("refuses a changedPaths entry that is not a string", () => {
      refusal(
        () => decodeOneEvent({ ...AUDIT_EVENT, changedPaths: ["password", 7] }),
        "is not a string",
      );
    });

    it("refuses a permissions entry that is not a string", () => {
      refusal(
        () => decodeOneEvent({ ...AUDIT_EVENT, permissions: ["user:read", 7] }),
        "is not a string",
      );
    });
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

  /** The path a filter set parses to, or its refusal message — whichever the step returned. */
  const outcome = (filters: AuditFilters) => {
    const parsed = parseAuditFilters(filters);
    return parsed.ok ? parsed.query.path : parsed.message;
  };

  it("parses empty filters to page zero, carrying only page and size", () => {
    expect(outcome(EMPTY_AUDIT_FILTERS)).toBe(`${AUDIT_EVENTS_PATH}?page=0&size=50`);
  });

  it("parses empty filters to exactly the unfiltered query", () => {
    expect(parseAuditFilters(EMPTY_AUDIT_FILTERS)).toEqual({
      ok: true,
      query: UNFILTERED_AUDIT_QUERY,
    });
  });

  it("parses a fully specified filter set to a query that starts on page zero", () => {
    const parsed = parseAuditFilters(valid);
    expect(parsed.ok ? parsed.query.page : parsed.message).toBe(0);
  });

  it("parses a non-empty operation and outcome into the query", () => {
    expect(
      outcome({ ...EMPTY_AUDIT_FILTERS, operation: "LOGIN_FAILURE", outcome: "FAILURE" }),
    ).toBe(`${AUDIT_EVENTS_PATH}?operation=LOGIN_FAILURE&outcome=FAILURE&page=0&size=50`);
  });

  it("parses trimmed actor and resource ids into the query", () => {
    expect(
      outcome({
        ...EMPTY_AUDIT_FILTERS,
        actorId: "  00000000-0000-4000-8000-000000000001  ",
        resourceId: "  00000000-0000-4000-8000-000000000002  ",
      }),
    ).toBe(
      `${AUDIT_EVENTS_PATH}?actorId=00000000-0000-4000-8000-000000000001&resourceId=00000000-0000-4000-8000-000000000002&page=0&size=50`,
    );
  });

  it("parses from/to datetime-local values into ISO instants", () => {
    const path = outcome({
      ...EMPTY_AUDIT_FILTERS,
      from: "2026-01-01T00:00",
      to: "2026-01-02T00:00",
    });
    const params = new URLSearchParams(path.slice(AUDIT_EVENTS_PATH.length + 1));
    // A datetime-local value is wall-clock time in the browser's zone, so the
    // expected instant is that same moment built from its parts here.
    expect([params.get("from"), params.get("to")]).toEqual([
      new Date(2026, 0, 1).toISOString(),
      new Date(2026, 0, 2).toISOString(),
    ]);
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

describe("auditQueryAtPage", () => {
  it("moves a parsed query to another page, keeping its filters", () => {
    const parsed = parseAuditFilters({ ...EMPTY_AUDIT_FILTERS, outcome: "FAILURE" });
    if (!parsed.ok) throw new Error("the filters should parse");
    expect(auditQueryAtPage(parsed.query, 3)).toMatchObject({
      page: 3,
      path: `${AUDIT_EVENTS_PATH}?outcome=FAILURE&page=3&size=50`,
    });
  });
});

describe("formatInstant", () => {
  it("renders the date and minute, dropping seconds and the zone", () => {
    expect(formatInstant("2026-03-04T05:06:07Z")).toBe("2026-03-04 05:06");
  });
});
