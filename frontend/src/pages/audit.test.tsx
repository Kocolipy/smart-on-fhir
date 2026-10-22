import { fireEvent, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";

import {
  bare,
  deferred,
  holding,
  json,
  type Answers,
  type StartingSession,
  type WireAnswer,
} from "@/auth/session-adapter.testHelpers";

import { AUDIT_EVENTS_PATH } from "./audit-api";
import { renderSignedIn } from "./signed-in-shell.testHelpers";

const AUDITOR = holding(["audit:read"]);

const EVENT_1 = {
  id: "e-1",
  // Late in the UTC day: already the next day in Singapore, where it is shown.
  occurredAt: "2026-01-02T19:04:05Z",
  operation: "LOGIN_FAILURE",
  outcome: "FAILURE",
  actorId: null,
  subjectId: "00000000-0000-4000-8000-000000000001",
  resourceType: "User",
  resourceId: "00000000-0000-4000-8000-000000000001",
  changedPaths: [],
  statusClass: "client_error",
  errorCode: "BAD_CREDENTIALS",
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

const EVENT_2 = {
  id: "e-2",
  occurredAt: "2026-01-03T10:11:12Z",
  operation: "SCIM_USER_LIST",
  outcome: "SUCCESS",
  actorId: "00000000-0000-4000-8000-0000000000c1",
  subjectId: null,
  resourceType: "User",
  resourceId: null,
  changedPaths: [],
  statusClass: "ok",
  errorCode: null,
  httpMethod: "GET",
  httpPath: "/Users",
  requestId: "req-2",
  resultCount: 3,
  filterShape: "userName eq ?",
  permissions: [],
  loginMethod: null,
  permissionsBefore: [],
  permissionsAfter: [],
  superuserChanged: null,
  groupIds: [],
};

/**
 * A membership change: multiple changed paths, the Permissions before and
 * after, no triggering request.
 */
const EVENT_PERMISSION_GRANT = {
  id: "e-3",
  occurredAt: "2026-01-04T09:08:07Z",
  operation: "PERMISSION_GRANT",
  outcome: "SUCCESS",
  actorId: "00000000-0000-4000-8000-0000000000c1",
  subjectId: "00000000-0000-4000-8000-000000000009",
  resourceType: "Group",
  resourceId: "00000000-0000-4000-8000-000000000009",
  changedPaths: ["groups", "active"],
  statusClass: "ok",
  errorCode: null,
  httpMethod: null,
  httpPath: null,
  requestId: "req-3",
  resultCount: null,
  filterShape: null,
  permissions: [],
  loginMethod: null,
  permissionsBefore: [],
  permissionsAfter: ["group:read", "user:read"],
  superuserChanged: false,
  groupIds: [],
};

/**
 * Nothing optional set at all, requestId included: every "—" fallback at
 * once, exactly as the seeding event the backend records with no actor and no
 * triggering request does.
 */
const EVENT_BARE = {
  id: "e-4",
  occurredAt: "2026-01-05T00:00:00Z",
  operation: "SCIM_RESOURCE_SEED",
  outcome: "SUCCESS",
  actorId: null,
  subjectId: null,
  resourceType: "User",
  resourceId: null,
  changedPaths: [],
  statusClass: "ok",
  errorCode: null,
  httpMethod: null,
  httpPath: null,
  requestId: null,
  resultCount: null,
  filterShape: null,
  permissions: [],
  loginMethod: null,
  permissionsBefore: [],
  permissionsAfter: [],
  superuserChanged: null,
  groupIds: [],
};

function pageOf(events: object[], overrides: Partial<Record<string, unknown>> = {}) {
  return {
    events,
    page: 0,
    size: 50,
    totalElements: events.length,
    totalPages: events.length === 0 ? 0 : 1,
    ...overrides,
  };
}

/** The read of page `page` with no filter, as the page names it. */
const atPage = (page: number) => `GET ${AUDIT_EVENTS_PATH}?page=${page}&size=50`;

/** The same answer to every read of the listing, whatever its filters or page. */
const everyRead = (answer: WireAnswer): Answers => ({ [`GET ${AUDIT_EVENTS_PATH}*`]: answer });

/** An `AuditEventPage` holding `events`, as the backend answers a read. */
const answerPage = (events: object[], overrides: Partial<Record<string, unknown>> = {}) =>
  json(pageOf(events, overrides));

/** The read paths the page sent, in order. */
const readPaths = (requests: () => readonly { path: string }[]) =>
  requests().map(({ path }) => path);

/** The page as `session` sees it, inside the signed-in layout that renders its one error line. */
function renderAudit(answers: Answers, session: StartingSession = AUDITOR) {
  return renderSignedIn("/audit", session, answers);
}

const table = () => screen.getByRole("table", { name: "Audit events" });

/** The pager's position line, "Page N" or "Page N of M". */
const pager = () => screen.getByText(/^Page \d+/);

/** Waits for the table to render, then returns it. */
const findTable = () => screen.findByRole("table", { name: "Audit events" });

/**
 * Enters a year-275760 value into a date filter as a browser delivers it. A
 * browser's `datetime-local` accepts years up to 275760, but jsdom sanitizes
 * any year past 9999 to `""`, so the control is read as plain text for this one
 * change — the value reaching the page is the one a real picker would send.
 */
function enterYear275760(label: "From" | "To") {
  const input = screen.getByLabelText(label);
  input.setAttribute("type", "text");
  fireEvent.change(input, { target: { value: "275760-12-31T23:59" } });
}

describe("Audit", () => {
  it("lists every recorded event on mount, newest first as the backend ordered them", async () => {
    const { requests } = await renderAudit(
      everyRead(answerPage([EVENT_1, EVENT_2], { totalElements: 2 })),
    );

    await findTable();
    const rows = within(table()).getAllByRole("row");
    // Header row, then the two events in the order the backend sent them.
    expect(within(rows[1]).getByText("LOGIN_FAILURE")).toBeInTheDocument();
    expect(within(rows[2]).getByText("SCIM_USER_LIST")).toBeInTheDocument();
    expect(requests()).toEqual([{ method: "GET", path: `${AUDIT_EVENTS_PATH}?page=0&size=50` }]);
  });

  it("shows when each event occurred as its date and minute in Singapore", async () => {
    await renderAudit(everyRead(answerPage([EVENT_1, EVENT_2], { totalElements: 2 })));

    await findTable();
    expect(
      within(table())
        .getAllByRole("rowheader")
        .map((header) => header.textContent),
    ).toEqual(["2026-01-03 03:04", "2026-01-03 18:11"]);
  });

  it("shows an empty-state message when no events match", async () => {
    await renderAudit(everyRead(answerPage([])));

    expect(
      await screen.findByText("No audit events match the current filters."),
    ).toBeInTheDocument();
    expect(screen.queryByRole("table", { name: "Audit events" })).not.toBeInTheDocument();
  });

  it("sends no request for a session lacking audit:read", async () => {
    const { requests } = await renderAudit({}, holding([]));

    expect(requests()).toEqual([]);
  });

  it("reports a failed read with the page's own copy", async () => {
    await renderAudit(everyRead(bare(503)));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Unable to load the audit trail. Please try again.",
    );
  });

  // An operation outside the closed set fails the whole page on decode, with
  // the page's own copy, and renders none of it.
  it("reports a page naming an unknown operation, and lists none of it", async () => {
    await renderAudit(everyRead(answerPage([{ ...EVENT_1, operation: "LOGIN_TELEPORT" }])));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      /^Unable to load the audit trail\. Please try again\.$/,
    );
    expect(screen.queryByRole("table", { name: "Audit events" })).not.toBeInTheDocument();
  });

  it("renders every event field as plain text, never as a link", async () => {
    await renderAudit(everyRead(answerPage([EVENT_2], { totalElements: 1 })));

    await findTable();
    expect(within(table()).getByText("userName eq ?", { exact: false })).toBeInTheDocument();
    expect(within(table()).getByText("/Users", { exact: false })).toBeInTheDocument();
    expect(within(table()).queryAllByRole("link")).toHaveLength(0);
  });

  it("names every column exactly, in a fixed order", async () => {
    await renderAudit(everyRead(answerPage([EVENT_1], { totalElements: 1 })));

    await findTable();
    expect(
      within(table())
        .getAllByRole("columnheader")
        .map((header) => header.textContent),
    ).toEqual([
      "Occurred",
      "Operation",
      "Outcome",
      "Actor",
      "Resource",
      "Changed paths",
      "Status",
      "Request",
      "Details",
    ]);
  });

  it("renders a resource id beside its type, and the type alone when there is none", async () => {
    await renderAudit(everyRead(answerPage([EVENT_1, EVENT_2], { totalElements: 2 })));

    await findTable();
    const rows = within(table()).getAllByRole("row");
    expect(
      within(rows[1]).getByText("User 00000000-0000-4000-8000-000000000001"),
    ).toBeInTheDocument();
    expect(within(rows[2]).getByText("User")).toBeInTheDocument();
  });

  it("shows an actor's id, and an em dash for an event with no actor", async () => {
    await renderAudit(everyRead(answerPage([EVENT_1, EVENT_2], { totalElements: 2 })));

    await findTable();
    const rows = within(table()).getAllByRole("row");
    const actorColumn = 2;
    expect(within(rows[1]).getAllByRole("cell")[actorColumn]).toHaveTextContent(/^—$/);
    expect(within(rows[2]).getAllByRole("cell")[actorColumn]).toHaveTextContent(
      "00000000-0000-4000-8000-0000000000c1",
    );
  });

  it("joins the triggering request's method, route and correlation id", async () => {
    await renderAudit(everyRead(answerPage([EVENT_2], { totalElements: 1 })));

    await findTable();
    expect(within(table()).getByText("GET /Users (req-2)")).toBeInTheDocument();
  });

  it("names only the correlation id when the event names no triggering request", async () => {
    await renderAudit(everyRead(answerPage([EVENT_PERMISSION_GRANT], { totalElements: 1 })));

    await findTable();
    expect(within(table()).getByText("req-3")).toBeInTheDocument();
    expect(within(table()).queryByText(/req-3 \(/)).not.toBeInTheDocument();
  });

  it("names only the route when the triggering request carried no correlation id", async () => {
    await renderAudit(
      everyRead(answerPage([{ ...EVENT_2, requestId: null }], { totalElements: 1 })),
    );

    await findTable();
    const requestColumn = 6;
    expect(within(table()).getAllByRole("cell")[requestColumn]).toHaveTextContent(/^GET \/Users$/);
  });

  it("joins changed paths, and shows an em dash when there are none", async () => {
    await renderAudit(
      everyRead(answerPage([EVENT_1, EVENT_PERMISSION_GRANT], { totalElements: 2 })),
    );

    await findTable();
    const rows = within(table()).getAllByRole("row");
    const changedPathsColumn = 4;
    expect(within(rows[1]).getAllByRole("cell")[changedPathsColumn]).toHaveTextContent(/^—$/);
    expect(within(rows[2]).getAllByRole("cell")[changedPathsColumn]).toHaveTextContent(
      "groups, active",
    );
  });

  it("joins a bulk read's result count and filter shape in Details", async () => {
    await renderAudit(everyRead(answerPage([EVENT_2], { totalElements: 1 })));

    await findTable();
    expect(within(table()).getByText("3 result(s) · userName eq ?")).toBeInTheDocument();
  });

  it("shows a membership change's Permissions before and after in Details", async () => {
    await renderAudit(everyRead(answerPage([EVENT_PERMISSION_GRANT], { totalElements: 1 })));

    await findTable();
    expect(
      within(table()).getByText("permissions: none → group:read, user:read"),
    ).toBeInTheDocument();
  });

  it("shows a Group Permissions change's Permissions before and after, and a refusal's none", async () => {
    const changed = {
      ...EVENT_PERMISSION_GRANT,
      operation: "GROUP_PERMISSIONS_CHANGE",
      resourceType: "Group",
      changedPaths: ["permissions"],
      permissionsBefore: ["audit:read", "user:read"],
      permissionsAfter: ["audit:read"],
      superuserChanged: null,
    };
    await renderAudit(
      everyRead(
        answerPage([
          changed,
          {
            ...changed,
            id: "e-7",
            outcome: "FAILURE",
            statusClass: "client_error",
            errorCode: "INVALID_PERMISSIONS",
            changedPaths: [],
            permissionsBefore: [],
            permissionsAfter: [],
          },
        ]),
      ),
    );

    await findTable();
    const rows = within(table()).getAllByRole("row").slice(1);
    expect(
      within(rows[0]!).getByText("permissions: audit:read, user:read → audit:read"),
    ).toBeInTheDocument();
    expect(within(rows[1]!).queryByText(/permissions:/)).toBeNull();
    expect(within(rows[1]!).getByText("INVALID_PERMISSIONS")).toBeInTheDocument();
  });

  it("names the Superuser granted by a grant and removed by a revocation", async () => {
    await renderAudit(
      everyRead(
        answerPage([
          { ...EVENT_PERMISSION_GRANT, permissionsAfter: ["ops:read"], superuserChanged: true },
          {
            ...EVENT_PERMISSION_GRANT,
            id: "e-6",
            operation: "PERMISSION_REVOKE",
            permissionsBefore: ["ops:read"],
            permissionsAfter: [],
            superuserChanged: true,
          },
        ]),
      ),
    );

    await findTable();
    expect(
      within(table()).getByText("permissions: none → ops:read · Superuser granted"),
    ).toBeInTheDocument();
    expect(
      within(table()).getByText("permissions: ops:read → none · Superuser removed"),
    ).toBeInTheDocument();
  });

  it("lists the Groups a dormancy revocation removed in Details", async () => {
    await renderAudit(
      everyRead(
        answerPage([
          {
            ...EVENT_PERMISSION_GRANT,
            operation: "DORMANCY_MEMBERSHIP_REVOCATION",
            permissionsBefore: ["audit:read"],
            permissionsAfter: [],
            groupIds: [
              "00000000-0000-4000-8000-0000000000a1",
              "00000000-0000-4000-8000-0000000000a2",
            ],
          },
        ]),
      ),
    );

    await findTable();
    expect(
      within(table()).getByText(
        "permissions: audit:read → none · groups: 00000000-0000-4000-8000-0000000000a1, " +
          "00000000-0000-4000-8000-0000000000a2",
      ),
    ).toBeInTheDocument();
  });

  it("joins the Permissions a token event carried in Details", async () => {
    await renderAudit(
      everyRead(
        answerPage([
          {
            ...EVENT_BARE,
            operation: "CONNECTOR_TOKEN_ISSUE",
            permissions: ["group:read", "user:read"],
          },
        ]),
      ),
    );

    await findTable();
    expect(within(table()).getByText("group:read, user:read")).toBeInTheDocument();
  });

  it("names a login's method in Details", async () => {
    await renderAudit(
      everyRead(answerPage([EVENT_1, { ...EVENT_1, id: "e-5", loginMethod: "sso" }])),
    );

    await findTable();
    const rows = within(table()).getAllByRole("row");
    const details = (row: HTMLElement) => {
      const cells = within(row).getAllByRole("cell");
      return cells[cells.length - 1].textContent;
    };
    expect([details(rows[1]), details(rows[2])]).toEqual([
      "login method: password",
      "login method: sso",
    ]);
  });

  it("shows an em dash in every optional column for an event naming none of them", async () => {
    await renderAudit(everyRead(answerPage([EVENT_BARE], { totalElements: 1 })));

    await findTable();
    const rows = within(table()).getAllByRole("row");
    // The actor, resource (type-only is not a dash), changed-paths, request
    // and details columns: actor, changed paths, request and details each
    // fall back to the same em dash here, so four cells carry it.
    expect(within(rows[1]).getAllByText("—")).toHaveLength(4);
  });

  describe("paging", () => {
    it("disables Previous on the first page and Next with no further page", async () => {
      await renderAudit(everyRead(answerPage([EVENT_1], { totalElements: 1, totalPages: 1 })));

      await findTable();
      expect(screen.getByRole("button", { name: "Previous" })).toBeDisabled();
      expect(screen.getByRole("button", { name: "Next" })).toBeDisabled();
      expect(screen.getByText("Page 1 of 1")).toBeInTheDocument();
    });

    it("requests the next page and shows it", async () => {
      const { requests } = await renderAudit({
        [atPage(0)]: answerPage([EVENT_1], { totalElements: 2, totalPages: 2 }),
        [atPage(1)]: answerPage([EVENT_2], { page: 1, totalElements: 2, totalPages: 2 }),
      });
      const user = userEvent.setup();

      await findTable();
      await user.click(screen.getByRole("button", { name: "Next" }));

      await screen.findByText("Page 2 of 2");
      expect(within(table()).getByText("SCIM_USER_LIST")).toBeInTheDocument();
      expect(readPaths(requests).slice(-1)[0]).toBe(`${AUDIT_EVENTS_PATH}?page=1&size=50`);
    });

    it("enables Previous once past the first page, and returns to it", async () => {
      const { requests } = await renderAudit({
        [atPage(0)]: answerPage([EVENT_1], { totalElements: 2, totalPages: 2 }),
        [atPage(1)]: answerPage([EVENT_2], { page: 1, totalElements: 2, totalPages: 2 }),
      });
      const user = userEvent.setup();

      await findTable();
      expect(screen.getByRole("button", { name: "Previous" })).toBeDisabled();
      await user.click(screen.getByRole("button", { name: "Next" }));

      await screen.findByText("Page 2 of 2");
      const previous = screen.getByRole("button", { name: "Previous" });
      expect(previous).toBeEnabled();
      await user.click(previous);

      await screen.findByText("Page 1 of 2");
      expect(readPaths(requests).slice(-1)[0]).toBe(`${AUDIT_EVENTS_PATH}?page=0&size=50`);
    });

    // The total belongs to a page read; until the next one is read it is unknown.
    it("names no total while the next page loads", async () => {
      await renderAudit({
        [atPage(0)]: answerPage([EVENT_1], { totalElements: 2, totalPages: 2 }),
        [atPage(1)]: deferred().answer,
      });
      const user = userEvent.setup();

      await findTable();
      await user.click(screen.getByRole("button", { name: "Next" }));

      expect(pager()).toHaveTextContent(/^Page 2$/);
    });

    it("names no total once the next page fails", async () => {
      await renderAudit({
        [atPage(0)]: answerPage([EVENT_1], { totalElements: 2, totalPages: 2 }),
        [atPage(1)]: bare(503),
      });
      const user = userEvent.setup();

      await findTable();
      await user.click(screen.getByRole("button", { name: "Next" }));
      await screen.findByRole("alert");

      expect(pager()).toHaveTextContent(/^Page 2$/);
    });

    it("names no total for a session lacking audit:read", async () => {
      await renderAudit({}, holding([]));

      expect(pager()).toHaveTextContent(/^Page 1$/);
    });

    it("keeps the total a page stated when its Refresh fails", async () => {
      await renderAudit({
        [atPage(0)]: [answerPage([EVENT_1], { totalElements: 51, totalPages: 2 }), bare(503)],
      });
      const user = userEvent.setup();

      await findTable();
      await user.click(screen.getByRole("button", { name: "Refresh" }));
      await screen.findByRole("alert");

      expect(pager()).toHaveTextContent(/^Page 1 of 2$/);
    });

    it("never names a total smaller than the page it is on", async () => {
      await renderAudit({
        [atPage(0)]: answerPage([EVENT_1], { totalElements: 2, totalPages: 2 }),
        // The trail shrank under the filters between the two reads.
        [atPage(1)]: answerPage([], { page: 1, totalElements: 1, totalPages: 1 }),
      });
      const user = userEvent.setup();

      await findTable();
      await user.click(screen.getByRole("button", { name: "Next" }));
      await screen.findByText("No audit events match the current filters.");

      expect(pager()).toHaveTextContent(/^Page 2 of 2$/);
    });
  });

  // The audit trail is evidence: rows a refresh could not confirm are not shown as the trail.
  it("hides the events last read once a Refresh fails", async () => {
    await renderAudit({ [atPage(0)]: [answerPage([EVENT_1]), bare(503)] });
    const user = userEvent.setup();

    await findTable();
    await user.click(screen.getByRole("button", { name: "Refresh" }));
    await screen.findByRole("alert");

    expect(screen.queryByRole("table", { name: "Audit events" })).not.toBeInTheDocument();
  });

  it("reloads on Refresh without changing the filters", async () => {
    const { requests } = await renderAudit(everyRead(answerPage([EVENT_1], { totalElements: 1 })));
    const user = userEvent.setup();

    await findTable();
    await user.click(screen.getByRole("button", { name: "Refresh" }));

    await vi.waitFor(() =>
      expect(readPaths(requests)).toEqual([
        `${AUDIT_EVENTS_PATH}?page=0&size=50`,
        `${AUDIT_EVENTS_PATH}?page=0&size=50`,
      ]),
    );
  });

  describe("filters", () => {
    it("sends no request and shows a local message for a malformed actor id", async () => {
      const { requests } = await renderAudit(
        everyRead(answerPage([EVENT_1], { totalElements: 1 })),
      );
      const user = userEvent.setup();

      await findTable();
      await user.type(screen.getByLabelText("Actor id"), "not-a-uuid");
      await user.click(screen.getByRole("button", { name: "Apply filters" }));

      expect(await screen.findByText("Actor id must be a valid UUID.")).toBeInTheDocument();
      expect(requests()).toHaveLength(1);
    });

    it("builds the request from the submitted filters", async () => {
      const { requests } = await renderAudit(
        everyRead(answerPage([EVENT_1], { totalElements: 1 })),
      );
      const user = userEvent.setup();

      await findTable();
      await user.selectOptions(screen.getByLabelText("Operation"), "LOGIN_FAILURE");
      await user.selectOptions(screen.getByLabelText("Outcome"), "FAILURE");
      await user.click(screen.getByRole("button", { name: "Apply filters" }));

      expect(readPaths(requests).slice(-1)[0]).toBe(
        `${AUDIT_EVENTS_PATH}?operation=LOGIN_FAILURE&outcome=FAILURE&page=0&size=50`,
      );
    });

    it("builds the request from a resource id and a date range", async () => {
      const { requests } = await renderAudit(
        everyRead(answerPage([EVENT_1], { totalElements: 1 })),
      );
      const user = userEvent.setup();

      await findTable();
      await user.type(screen.getByLabelText("Resource id"), "00000000-0000-4000-8000-000000000002");
      fireEvent.change(screen.getByLabelText("From"), { target: { value: "2026-01-01T00:00" } });
      fireEvent.change(screen.getByLabelText("To"), { target: { value: "2026-01-02T00:00" } });
      await user.click(screen.getByRole("button", { name: "Apply filters" }));

      // Each bound is read as Singapore time, eight hours ahead of UTC.
      expect(readPaths(requests).slice(-1)[0]).toBe(
        `${AUDIT_EVENTS_PATH}?resourceId=00000000-0000-4000-8000-000000000002&from=2025-12-31T16%3A00%3A00.000Z&to=2026-01-01T16%3A00%3A00.000Z&page=0&size=50`,
      );
    });

    it("bounds the listing at the events' shown times when they are typed into From and To", async () => {
      const { requests } = await renderAudit(
        everyRead(answerPage([EVENT_1, EVENT_2], { totalElements: 2 })),
      );
      const user = userEvent.setup();

      await findTable();
      const [first, second] = within(table())
        .getAllByRole("rowheader")
        .map((header) => header.textContent.replace(" ", "T"));
      fireEvent.change(screen.getByLabelText("From"), { target: { value: first } });
      fireEvent.change(screen.getByLabelText("To"), { target: { value: second } });
      await user.click(screen.getByRole("button", { name: "Apply filters" }));

      // From is the first event's minute, so it is included; To is the second's.
      expect(readPaths(requests).slice(-1)[0]).toBe(
        `${AUDIT_EVENTS_PATH}?from=2026-01-02T19%3A04%3A00.000Z&to=2026-01-03T10%3A11%3A00.000Z&page=0&size=50`,
      );
    });

    it("refuses a year-275760 date with a message and keeps the listing on screen", async () => {
      await renderAudit(everyRead(answerPage([EVENT_1], { totalElements: 1 })));
      const user = userEvent.setup();

      await findTable();
      enterYear275760("To");
      await user.click(screen.getByRole("button", { name: "Apply filters" }));

      expect(await screen.findByRole("alert")).toHaveTextContent(
        /^To date must be a valid date\.$/,
      );
      expect(within(table()).getByText("LOGIN_FAILURE")).toBeInTheDocument();
    });

    it("sends no request for a refused date", async () => {
      const { requests } = await renderAudit(
        everyRead(answerPage([EVENT_1], { totalElements: 1 })),
      );
      const user = userEvent.setup();

      await findTable();
      enterYear275760("From");
      await user.click(screen.getByRole("button", { name: "Apply filters" }));

      await screen.findByRole("alert");
      expect(requests()).toHaveLength(1);
    });

    it("reads again when the same filters are submitted twice", async () => {
      const { requests } = await renderAudit(
        everyRead(answerPage([EVENT_1], { totalElements: 1 })),
      );
      const user = userEvent.setup();

      await findTable();
      await user.selectOptions(screen.getByLabelText("Outcome"), "FAILURE");
      const apply = screen.getByRole("button", { name: "Apply filters" });
      await user.click(apply);
      await user.click(apply);

      await vi.waitFor(() =>
        expect(readPaths(requests)).toEqual([
          `${AUDIT_EVENTS_PATH}?page=0&size=50`,
          `${AUDIT_EVENTS_PATH}?outcome=FAILURE&page=0&size=50`,
          `${AUDIT_EVENTS_PATH}?outcome=FAILURE&page=0&size=50`,
        ]),
      );
    });

    it("reads once, for the new filters only, when a submit changes them", async () => {
      const { requests } = await renderAudit(
        everyRead(answerPage([EVENT_1], { totalElements: 1 })),
      );
      const user = userEvent.setup();

      await findTable();
      await user.selectOptions(screen.getByLabelText("Outcome"), "FAILURE");
      await user.click(screen.getByRole("button", { name: "Apply filters" }));
      await findTable();

      expect(readPaths(requests)).toEqual([
        `${AUDIT_EVENTS_PATH}?page=0&size=50`,
        `${AUDIT_EVENTS_PATH}?outcome=FAILURE&page=0&size=50`,
      ]);
    });

    it("clears a refusal once the filters are submitted valid", async () => {
      await renderAudit(everyRead(answerPage([EVENT_1], { totalElements: 1 })));
      const user = userEvent.setup();

      await findTable();
      const actorId = screen.getByLabelText("Actor id");
      await user.type(actorId, "not-a-uuid");
      await user.click(screen.getByRole("button", { name: "Apply filters" }));
      await screen.findByRole("alert");
      await user.clear(actorId);
      await user.click(screen.getByRole("button", { name: "Apply filters" }));

      expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    });

    it("shows a read failure that lands after a refused filter, as the latest", async () => {
      const { answer } = await renderAudit(everyRead(answerPage([EVENT_1], { totalElements: 1 })));
      const user = userEvent.setup();

      await findTable();
      await user.type(screen.getByLabelText("Actor id"), "not-a-uuid");
      await user.click(screen.getByRole("button", { name: "Apply filters" }));
      await screen.findByText("Actor id must be a valid UUID.");
      answer(everyRead(bare(503)));
      await user.click(screen.getByRole("button", { name: "Refresh" }));

      expect(
        await screen.findByText("Unable to load the audit trail. Please try again."),
      ).toHaveRole("alert");
    });

    it("keeps a refused filter on the error line when a later read succeeds", async () => {
      const { requests } = await renderAudit(
        everyRead(answerPage([EVENT_1], { totalElements: 1 })),
      );
      const user = userEvent.setup();

      await findTable();
      await user.type(screen.getByLabelText("Actor id"), "not-a-uuid");
      await user.click(screen.getByRole("button", { name: "Apply filters" }));
      await screen.findByRole("alert");
      await user.click(screen.getByRole("button", { name: "Refresh" }));
      await vi.waitFor(() => expect(requests()).toHaveLength(2));
      // Let the Refresh's answer land before reading the line it might have changed.
      await findTable();

      expect(screen.getByRole("alert")).toHaveTextContent(/^Actor id must be a valid UUID\.$/);
    });

    it("returns to the first page on a valid submit", async () => {
      await renderAudit(everyRead(answerPage([EVENT_1], { totalElements: 2, totalPages: 2 })));
      const user = userEvent.setup();

      await findTable();
      await user.click(screen.getByRole("button", { name: "Next" }));
      await screen.findByText("Page 2 of 2");
      await user.click(screen.getByRole("button", { name: "Apply filters" }));

      expect(await screen.findByText("Page 1 of 2")).toBeInTheDocument();
    });
  });
});
