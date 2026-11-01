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

import { AUDIT_EVENTS_PATH, AUDIT_OPERATIONS } from "./audit-api";
import { renderSignedIn } from "@/shell/signed-in-shell.testHelpers";

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
    totalResults: events.length,
    startIndex: 1,
    events,
    ...overrides,
  };
}

/** The read starting at `startIndex` with no filter, as the page names it. */
const at = (startIndex: number) => `GET ${AUDIT_EVENTS_PATH}?startIndex=${startIndex}&count=50`;

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

/** The pager's position line, "Events 51–100 of 120", or `null` while it names none. */
const pager = () => screen.queryByText(/^Events \d+/);

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

type User = ReturnType<typeof userEvent.setup>;

/** The Operation picker's trigger, whatever it says is selected. */
const pickerTrigger = () => screen.getByRole("button", { name: /^Operation: / });

const openPicker = (user: User) => user.click(pickerTrigger());

/** Opens the picker and toggles each of `operations` in the one visit. */
async function pick(user: User, ...operations: string[]) {
  await openPicker(user);
  for (const name of operations) await user.click(screen.getByRole("option", { name }));
}

const lastRead = (requests: () => readonly { path: string }[]) => readPaths(requests).slice(-1)[0];

const unfiltered = `${AUDIT_EVENTS_PATH}?startIndex=1&count=50`;
const applyButton = () => screen.getByRole("button", { name: "Apply" });
const ACTOR_A = "00000000-0000-4000-8000-00000000000a";
const ACTOR_B = "00000000-0000-4000-8000-00000000000b";
const RESOURCE_A = "00000000-0000-4000-8000-00000000000c";
const outcome = (name: "Any" | "Success" | "Failure") => screen.getByRole("radio", { name });

describe("Audit", () => {
  it("lists every recorded event on mount, newest first as the backend ordered them", async () => {
    const { requests } = await renderAudit(
      everyRead(answerPage([EVENT_1, EVENT_2], { totalResults: 2 })),
    );

    await findTable();
    const rows = within(table()).getAllByRole("row");
    // Header row, then the two events in the order the backend sent them.
    expect(within(rows[1]).getByText("LOGIN_FAILURE")).toBeInTheDocument();
    expect(within(rows[2]).getByText("SCIM_USER_LIST")).toBeInTheDocument();
    expect(requests()).toEqual([
      { method: "GET", path: `${AUDIT_EVENTS_PATH}?startIndex=1&count=50` },
    ]);
  });

  it("shows when each event occurred as its date and minute in Singapore", async () => {
    await renderAudit(everyRead(answerPage([EVENT_1, EVENT_2], { totalResults: 2 })));

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
    await renderAudit(everyRead(answerPage([EVENT_2], { totalResults: 1 })));

    await findTable();
    expect(within(table()).getByText("userName eq ?", { exact: false })).toBeInTheDocument();
    expect(within(table()).getByText("/Users", { exact: false })).toBeInTheDocument();
    expect(within(table()).queryAllByRole("link")).toHaveLength(0);
  });

  it("names every column exactly, in a fixed order", async () => {
    await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));

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
    await renderAudit(everyRead(answerPage([EVENT_1, EVENT_2], { totalResults: 2 })));

    await findTable();
    const rows = within(table()).getAllByRole("row");
    expect(
      within(rows[1]).getByText("User 00000000-0000-4000-8000-000000000001"),
    ).toBeInTheDocument();
    expect(within(rows[2]).getByText("User")).toBeInTheDocument();
  });

  it("shows an actor's id, and an em dash for an event with no actor", async () => {
    await renderAudit(everyRead(answerPage([EVENT_1, EVENT_2], { totalResults: 2 })));

    await findTable();
    const rows = within(table()).getAllByRole("row");
    const actorColumn = 2;
    expect(within(rows[1]).getAllByRole("cell")[actorColumn]).toHaveTextContent(/^—$/);
    expect(within(rows[2]).getAllByRole("cell")[actorColumn]).toHaveTextContent(
      "00000000-0000-4000-8000-0000000000c1",
    );
  });

  it("joins the triggering request's method, route and correlation id", async () => {
    await renderAudit(everyRead(answerPage([EVENT_2], { totalResults: 1 })));

    await findTable();
    expect(within(table()).getByText("GET /Users (req-2)")).toBeInTheDocument();
  });

  it("names only the correlation id when the event names no triggering request", async () => {
    await renderAudit(everyRead(answerPage([EVENT_PERMISSION_GRANT], { totalResults: 1 })));

    await findTable();
    expect(within(table()).getByText("req-3")).toBeInTheDocument();
    expect(within(table()).queryByText(/req-3 \(/)).not.toBeInTheDocument();
  });

  it("names only the route when the triggering request carried no correlation id", async () => {
    await renderAudit(
      everyRead(answerPage([{ ...EVENT_2, requestId: null }], { totalResults: 1 })),
    );

    await findTable();
    const requestColumn = 6;
    expect(within(table()).getAllByRole("cell")[requestColumn]).toHaveTextContent(/^GET \/Users$/);
  });

  it("joins changed paths, and shows an em dash when there are none", async () => {
    await renderAudit(
      everyRead(answerPage([EVENT_1, EVENT_PERMISSION_GRANT], { totalResults: 2 })),
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
    await renderAudit(everyRead(answerPage([EVENT_2], { totalResults: 1 })));

    await findTable();
    expect(within(table()).getByText("3 result(s) · userName eq ?")).toBeInTheDocument();
  });

  it("shows a membership change's Permissions before and after in Details", async () => {
    await renderAudit(everyRead(answerPage([EVENT_PERMISSION_GRANT], { totalResults: 1 })));

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
    await renderAudit(everyRead(answerPage([EVENT_BARE], { totalResults: 1 })));

    await findTable();
    const rows = within(table()).getAllByRole("row");
    // The actor, resource (type-only is not a dash), changed-paths, request
    // and details columns: actor, changed paths, request and details each
    // fall back to the same em dash here, so four cells carry it.
    expect(within(rows[1]).getAllByText("—")).toHaveLength(4);
  });

  describe("paging", () => {
    it("disables Previous on the first page and Next with no further page", async () => {
      await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));

      await findTable();
      const pages = within(screen.getByRole("navigation", { name: "Audit trail pages" }));
      expect(pages.getByRole("button", { name: "Previous" })).toBeDisabled();
      expect(pages.getByRole("button", { name: "Next" })).toBeDisabled();
      expect(pages.getByText("Events 1–1 of 1")).toBeInTheDocument();
    });

    it("requests the next page and shows it", async () => {
      const { requests } = await renderAudit({
        [at(1)]: answerPage([EVENT_1], { totalResults: 51 }),
        [at(2)]: answerPage([EVENT_2], { startIndex: 2, totalResults: 51 }),
      });
      const user = userEvent.setup();

      await findTable();
      await user.click(screen.getByRole("button", { name: "Next" }));

      await screen.findByText("Events 2–2 of 51");
      expect(within(table()).getByText("SCIM_USER_LIST")).toBeInTheDocument();
      expect(readPaths(requests).slice(-1)[0]).toBe(`${AUDIT_EVENTS_PATH}?startIndex=2&count=50`);
    });

    it("enables Previous once past the first page, and returns to it", async () => {
      const { requests } = await renderAudit({
        [at(1)]: answerPage([EVENT_1], { totalResults: 51 }),
        [at(2)]: answerPage([EVENT_2], { startIndex: 2, totalResults: 51 }),
      });
      const user = userEvent.setup();

      await findTable();
      expect(screen.getByRole("button", { name: "Previous" })).toBeDisabled();
      await user.click(screen.getByRole("button", { name: "Next" }));

      await screen.findByText("Events 2–2 of 51");
      const previous = screen.getByRole("button", { name: "Previous" });
      expect(previous).toBeEnabled();
      await user.click(previous);

      await screen.findByText("Events 1–1 of 51");
      expect(readPaths(requests).slice(-1)[0]).toBe(`${AUDIT_EVENTS_PATH}?startIndex=1&count=50`);
    });

    // The range belongs to a page read; until the next one is read it is unknown.
    it("names no range while the next page loads", async () => {
      await renderAudit({
        [at(1)]: answerPage([EVENT_1], { totalResults: 52 }),
        [at(2)]: deferred().answer,
      });
      const user = userEvent.setup();

      await findTable();
      expect(pager()).toHaveTextContent(/^Events 1–1 of 52$/);
      await user.click(screen.getByRole("button", { name: "Next" }));

      expect(pager()).not.toBeInTheDocument();
    });

    it("names no range once the next page fails", async () => {
      await renderAudit({
        [at(1)]: answerPage([EVENT_1], { totalResults: 2 }),
        [at(2)]: bare(503),
      });
      const user = userEvent.setup();

      await findTable();
      await user.click(screen.getByRole("button", { name: "Next" }));
      await screen.findByRole("alert");

      expect(pager()).not.toBeInTheDocument();
    });

    it("shows no pager for a session lacking audit:read", async () => {
      await renderAudit({}, holding([]));

      expect(screen.queryByRole("navigation", { name: "Audit trail pages" })).toBeNull();
    });

    it("shows no pager when no events match at all", async () => {
      await renderAudit(everyRead(answerPage([])));

      await screen.findByText("No audit events match the current filters.");
      expect(screen.queryByRole("navigation", { name: "Audit trail pages" })).toBeNull();
    });

    it("keeps the applied filters across Next and Previous", async () => {
      const { requests } = await renderAudit(
        everyRead(answerPage([EVENT_1], { totalResults: 51 })),
      );
      const user = userEvent.setup();

      await findTable();
      await pick(user, "LOGOUT", "LOCKOUT_SET");
      await user.keyboard("{Escape}");
      await user.click(outcome("Failure"));
      await findTable();
      await user.click(screen.getByRole("button", { name: "Next" }));
      await vi.waitFor(() => expect(requests()).toHaveLength(5));

      expect(lastRead(requests)).toBe(
        `${AUDIT_EVENTS_PATH}?operation=LOGOUT&operation=LOCKOUT_SET&outcome=FAILURE&startIndex=2&count=50`,
      );
    });

    it("moves back by the page size it asks for", async () => {
      const { requests } = await renderAudit({
        [at(1)]: answerPage(
          Array.from({ length: 50 }, (_, index) => ({ ...EVENT_1, id: `e-${index}` })),
          { totalResults: 51 },
        ),
        [at(51)]: answerPage([EVENT_2], { startIndex: 51, totalResults: 51 }),
      });
      const user = userEvent.setup();

      await findTable();
      await user.click(screen.getByRole("button", { name: "Next" }));
      await screen.findByText("Events 51–51 of 51");
      expect(screen.getByRole("button", { name: "Next" })).toBeDisabled();
      await user.click(screen.getByRole("button", { name: "Previous" }));
      await screen.findByText("Events 1–50 of 51");

      expect(readPaths(requests)).toEqual([
        `${AUDIT_EVENTS_PATH}?startIndex=1&count=50`,
        `${AUDIT_EVENTS_PATH}?startIndex=51&count=50`,
        `${AUDIT_EVENTS_PATH}?startIndex=1&count=50`,
      ]);
    });

    it("keeps the range a page stated when its Refresh fails", async () => {
      await renderAudit({
        [at(1)]: [answerPage([EVENT_1], { totalResults: 51 }), bare(503)],
      });
      const user = userEvent.setup();

      await findTable();
      await user.click(screen.getByRole("button", { name: "Refresh" }));
      await screen.findByRole("alert");

      expect(pager()).toHaveTextContent(/^Events 1–1 of 51$/);
    });

    it("names the count, not a backwards range, for a page emptied under it", async () => {
      await renderAudit({
        [at(1)]: answerPage([EVENT_1], { totalResults: 2 }),
        // The trail shrank under the filters between the two reads.
        [at(2)]: answerPage([], { startIndex: 2, totalResults: 1 }),
      });
      const user = userEvent.setup();

      await findTable();
      await user.click(screen.getByRole("button", { name: "Next" }));
      await screen.findByText("This page is now empty. Use Previous to go back.");

      expect(pager()).toHaveTextContent(/^Events 0 of 1$/);
      expect(screen.getByRole("button", { name: "Previous" })).toBeEnabled();
    });
  });

  // The audit trail is evidence: rows a refresh could not confirm are not shown as the trail.
  it("hides the events last read once a Refresh fails", async () => {
    await renderAudit({ [at(1)]: [answerPage([EVENT_1]), bare(503)] });
    const user = userEvent.setup();

    await findTable();
    await user.click(screen.getByRole("button", { name: "Refresh" }));
    await screen.findByRole("alert");

    expect(screen.queryByRole("table", { name: "Audit events" })).not.toBeInTheDocument();
  });

  it("reloads on Refresh without changing the filters", async () => {
    const { requests } = await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
    const user = userEvent.setup();

    await findTable();
    await user.click(screen.getByRole("button", { name: "Refresh" }));

    await vi.waitFor(() =>
      expect(readPaths(requests)).toEqual([
        `${AUDIT_EVENTS_PATH}?startIndex=1&count=50`,
        `${AUDIT_EVENTS_PATH}?startIndex=1&count=50`,
      ]),
    );
  });

  describe("filters", () => {
    it("sends no request and shows a local message for a malformed actor id", async () => {
      const { requests } = await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup();

      await findTable();
      await user.type(screen.getByLabelText("Actor id"), "not-a-uuid");
      await user.click(applyButton());

      expect(await screen.findByText("Actor id must be a valid UUID.")).toBeInTheDocument();
      expect(requests()).toHaveLength(1);
    });

    it("builds the request from a resource id and a date range", async () => {
      const { requests } = await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup();

      await findTable();
      await user.type(screen.getByLabelText("Resource id"), "00000000-0000-4000-8000-000000000002");
      fireEvent.change(screen.getByLabelText("From"), { target: { value: "2026-01-01T00:00" } });
      fireEvent.change(screen.getByLabelText("To"), { target: { value: "2026-01-02T00:00" } });
      await user.click(applyButton());

      // Each bound is read as Singapore time, eight hours ahead of UTC.
      expect(readPaths(requests).slice(-1)[0]).toBe(
        `${AUDIT_EVENTS_PATH}?resourceId=00000000-0000-4000-8000-000000000002&from=2025-12-31T16%3A00%3A00.000Z&to=2026-01-01T16%3A00%3A00.000Z&startIndex=1&count=50`,
      );
    });

    it("bounds the listing at the events' shown times when they are typed into From and To", async () => {
      const { requests } = await renderAudit(
        everyRead(answerPage([EVENT_1, EVENT_2], { totalResults: 2 })),
      );
      const user = userEvent.setup();

      await findTable();
      const [first, second] = within(table())
        .getAllByRole("rowheader")
        .map((header) => header.textContent.replace(" ", "T"));
      fireEvent.change(screen.getByLabelText("From"), { target: { value: first } });
      fireEvent.change(screen.getByLabelText("To"), { target: { value: second } });
      await user.click(applyButton());

      // From is the first event's minute, so it is included; To is the second's.
      expect(readPaths(requests).slice(-1)[0]).toBe(
        `${AUDIT_EVENTS_PATH}?from=2026-01-02T19%3A04%3A00.000Z&to=2026-01-03T10%3A11%3A00.000Z&startIndex=1&count=50`,
      );
    });

    it("refuses a year-275760 date with a message and keeps the listing on screen", async () => {
      await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup();

      await findTable();
      enterYear275760("To");
      await user.click(applyButton());

      expect(await screen.findByRole("alert")).toHaveTextContent(
        /^To date must be a valid date\.$/,
      );
      expect(within(table()).getByText("LOGIN_FAILURE")).toBeInTheDocument();
    });

    it("sends no request for a refused date", async () => {
      const { requests } = await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup();

      await findTable();
      enterYear275760("From");
      await user.click(applyButton());

      await screen.findByRole("alert");
      expect(requests()).toHaveLength(1);
    });

    it("reads again when the same filters are applied twice", async () => {
      const { requests } = await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup();

      await findTable();
      await user.click(outcome("Failure"));
      await user.click(applyButton());
      await user.click(applyButton());

      await vi.waitFor(() =>
        expect(readPaths(requests)).toEqual([
          unfiltered,
          `${AUDIT_EVENTS_PATH}?outcome=FAILURE&startIndex=1&count=50`,
          `${AUDIT_EVENTS_PATH}?outcome=FAILURE&startIndex=1&count=50`,
          `${AUDIT_EVENTS_PATH}?outcome=FAILURE&startIndex=1&count=50`,
        ]),
      );
    });

    it("clears a refusal once the filters are applied valid", async () => {
      await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup();

      await findTable();
      const actorId = screen.getByLabelText("Actor id");
      await user.type(actorId, "not-a-uuid");
      await user.click(applyButton());
      await screen.findByRole("alert");
      await user.clear(actorId);
      await user.click(applyButton());

      expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    });

    it("shows a read failure that lands after a refused filter, as the latest", async () => {
      const { answer } = await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup();

      await findTable();
      await user.type(screen.getByLabelText("Actor id"), "not-a-uuid");
      await user.click(applyButton());
      await screen.findByText("Actor id must be a valid UUID.");
      answer(everyRead(bare(503)));
      await user.click(screen.getByRole("button", { name: "Refresh" }));

      expect(await screen.findByRole("alert")).toHaveTextContent(
        "Unable to load the audit trail. Please try again.",
      );
    });

    it("keeps a refused filter on the error line when a later read succeeds", async () => {
      const { requests } = await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup();

      await findTable();
      await user.type(screen.getByLabelText("Actor id"), "not-a-uuid");
      await user.click(applyButton());
      await screen.findByRole("alert");
      await user.click(screen.getByRole("button", { name: "Refresh" }));
      await vi.waitFor(() => expect(requests()).toHaveLength(2));
      // Let the Refresh's answer land before reading the line it might have changed.
      await findTable();

      expect(screen.getByRole("alert")).toHaveTextContent(/^Actor id must be a valid UUID\.$/);
    });

    it("returns to the first page on a valid Apply", async () => {
      await renderAudit({
        [at(1)]: answerPage([EVENT_1], { totalResults: 51 }),
        [at(2)]: answerPage([EVENT_2], { startIndex: 2, totalResults: 51 }),
      });
      const user = userEvent.setup();

      await findTable();
      await user.click(screen.getByRole("button", { name: "Next" }));
      await screen.findByText("Events 2–2 of 51");
      await user.click(applyButton());

      expect(await screen.findByText("Events 1–1 of 51")).toBeInTheDocument();
    });

    it("applies a typed field only on Apply", async () => {
      const { requests } = await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup();
      const id = "00000000-0000-4000-8000-000000000002";

      await findTable();
      await user.type(screen.getByLabelText("Actor id"), id);
      expect(readPaths(requests)).toEqual([unfiltered]);
      await user.click(applyButton());

      await vi.waitFor(() =>
        expect(readPaths(requests)).toEqual([
          unfiltered,
          `${AUDIT_EVENTS_PATH}?actorId=${id}&startIndex=1&count=50`,
        ]),
      );
    });

    it("applies on Enter in a typed field", async () => {
      const { requests } = await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup();
      const id = "00000000-0000-4000-8000-000000000002";

      await findTable();
      await user.type(screen.getByLabelText("Resource id"), `${id}{Enter}`);

      await vi.waitFor(() =>
        expect(lastRead(requests)).toBe(
          `${AUDIT_EVENTS_PATH}?resourceId=${id}&startIndex=1&count=50`,
        ),
      );
    });
  });

  describe("the Operation picker", () => {
    const shownOptions = () => screen.getAllByRole("option").map((option) => option.textContent);

    it("lists every operation by name alone, with no count beside it", async () => {
      await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup();

      await findTable();
      await openPicker(user);

      expect(shownOptions()).toEqual(AUDIT_OPERATIONS);
    });

    it("narrows the list as the search is typed", async () => {
      await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup();

      await findTable();
      await openPicker(user);
      await user.type(screen.getByRole("combobox"), "lock");

      expect(shownOptions().sort()).toEqual(["DORMANCY_LOCKOUT", "LOCKOUT_LIFT", "LOCKOUT_SET"]);
    });

    it("says so when the search matches no operation", async () => {
      await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup();

      await findTable();
      await openPicker(user);
      await user.type(screen.getByRole("combobox"), "zzz");

      expect(screen.getByText("No operation matches.")).toBeInTheDocument();
    });

    it("names its filter and how many are selected", async () => {
      await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup();

      await findTable();
      expect(pickerTrigger()).toHaveAccessibleName("Operation: Any");
      await pick(user, "LOGOUT", "LOCKOUT_SET");

      expect(pickerTrigger()).toHaveAccessibleName("Operation: 2 selected");
    });

    it("gives a checkbox to each operation, checked once it is selected", async () => {
      await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup();

      await findTable();
      await pick(user, "LOGOUT");

      const checkedOf = (name: string) => {
        const option = screen.getByRole("option", { name });
        return [
          option.getAttribute("aria-checked"),
          option.querySelector('[data-slot="checkbox"]')?.getAttribute("data-state"),
        ];
      };
      expect([checkedOf("LOGOUT"), checkedOf("LOCKOUT_SET")]).toEqual([
        ["true", "checked"],
        ["false", "unchecked"],
      ]);
    });

    // The row is the control: its checkbox only shows the state, so it is no stop of its own.
    it("keeps each checkbox out of the Tab order", async () => {
      await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup();

      await findTable();
      await openPicker(user);

      const stops = screen
        .getAllByRole("option")
        .map((option) => option.querySelector('[data-slot="checkbox"]')?.getAttribute("tabindex"));
      expect(new Set(stops)).toEqual(new Set(["-1"]));
    });

    it("reads at once on each change, and only for what is selected then", async () => {
      const { requests } = await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup();

      await findTable();
      await pick(user, "LOGOUT", "LOCKOUT_SET", "LOGOUT");

      await vi.waitFor(() =>
        expect(readPaths(requests)).toEqual([
          unfiltered,
          `${AUDIT_EVENTS_PATH}?operation=LOGOUT&startIndex=1&count=50`,
          `${AUDIT_EVENTS_PATH}?operation=LOGOUT&operation=LOCKOUT_SET&startIndex=1&count=50`,
          `${AUDIT_EVENTS_PATH}?operation=LOCKOUT_SET&startIndex=1&count=50`,
        ]),
      );
    });

    it("clears every selection in one step, and reads every operation", async () => {
      const { requests } = await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup();

      await findTable();
      await pick(user, "LOGOUT", "LOCKOUT_SET");
      await vi.waitFor(() => expect(requests()).toHaveLength(3));
      await user.click(screen.getByRole("option", { name: "Clear selection" }));

      await vi.waitFor(() => expect(requests()).toHaveLength(4));
      expect(lastRead(requests)).toBe(unfiltered);
      expect(pickerTrigger()).toHaveAccessibleName("Operation: Any");
    });

    it("offers no clearing with nothing selected", async () => {
      await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup();

      await findTable();
      await openPicker(user);

      expect(screen.queryByRole("option", { name: "Clear selection" })).toBeNull();
    });

    it("offers clearing still while a search hides every operation", async () => {
      await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup();

      await findTable();
      await pick(user, "LOGOUT");
      await user.type(screen.getByRole("combobox"), "zzz");

      expect(shownOptions()).toEqual(["Clear selection"]);
    });

    it("refuses a change while a typed field is invalid, sending nothing", async () => {
      const { requests } = await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup();

      await findTable();
      await user.type(screen.getByLabelText("Actor id"), "not-a-uuid");
      await pick(user, "LOGOUT");

      expect(screen.getByRole("alert")).toHaveTextContent(/^Actor id must be a valid UUID\.$/);
      expect(pickerTrigger()).toHaveAccessibleName("Operation: 1 selected");
      expect(readPaths(requests)).toEqual([unfiltered]);
    });

    it("leaves a valid typed field unsent until Apply", async () => {
      const { requests } = await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup();

      await findTable();
      await user.type(screen.getByLabelText("Actor id"), ACTOR_A);
      await pick(user, "LOGOUT");
      await vi.waitFor(() => expect(requests()).toHaveLength(2));
      await user.keyboard("{Escape}");
      await user.click(applyButton());

      await vi.waitFor(() =>
        expect(readPaths(requests)).toEqual([
          unfiltered,
          `${AUDIT_EVENTS_PATH}?operation=LOGOUT&startIndex=1&count=50`,
          `${AUDIT_EVENTS_PATH}?operation=LOGOUT&actorId=${ACTOR_A}&startIndex=1&count=50`,
        ]),
      );
    });

    it("sends every typed filter last applied, not the fields as they now stand", async () => {
      const { requests } = await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup();
      const from = "2026-01-01T00:00";
      const to = "2026-01-02T00:00";

      await findTable();
      await user.type(screen.getByLabelText("Actor id"), ACTOR_A);
      await user.type(screen.getByLabelText("Resource id"), RESOURCE_A);
      fireEvent.change(screen.getByLabelText("From"), { target: { value: from } });
      fireEvent.change(screen.getByLabelText("To"), { target: { value: to } });
      await user.click(applyButton());
      await vi.waitFor(() => expect(requests()).toHaveLength(2));

      await user.clear(screen.getByLabelText("Actor id"));
      await user.clear(screen.getByLabelText("Resource id"));
      fireEvent.change(screen.getByLabelText("From"), { target: { value: "" } });
      fireEvent.change(screen.getByLabelText("To"), { target: { value: "" } });
      await pick(user, "LOGOUT");

      await vi.waitFor(() => expect(requests()).toHaveLength(3));
      expect(lastRead(requests)).toBe(
        `${AUDIT_EVENTS_PATH}?operation=LOGOUT&actorId=${ACTOR_A}&resourceId=${RESOURCE_A}` +
          "&from=2025-12-31T16%3A00%3A00.000Z&to=2026-01-01T16%3A00%3A00.000Z&startIndex=1&count=50",
      );
    });

    it("leaves the typed field on screen as it stands through a change", async () => {
      await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup();

      await findTable();
      await user.type(screen.getByLabelText("Actor id"), ACTOR_A);
      await pick(user, "LOGOUT");

      expect(screen.getByLabelText("Actor id")).toHaveValue(ACTOR_A);
    });

    it("keeps the last accepted typed value past a refused Apply", async () => {
      const { requests } = await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup();

      await findTable();
      await user.type(screen.getByLabelText("Actor id"), ACTOR_A);
      await user.click(applyButton());
      await vi.waitFor(() => expect(requests()).toHaveLength(2));
      await user.clear(screen.getByLabelText("Actor id"));
      await user.type(screen.getByLabelText("Actor id"), "not-a-uuid");
      await user.click(applyButton());
      await screen.findByRole("alert");
      await user.clear(screen.getByLabelText("Actor id"));
      await user.type(screen.getByLabelText("Actor id"), ACTOR_B);
      await pick(user, "LOGOUT");

      await vi.waitFor(() =>
        expect(lastRead(requests)).toBe(
          `${AUDIT_EVENTS_PATH}?operation=LOGOUT&actorId=${ACTOR_A}&startIndex=1&count=50`,
        ),
      );
    });

    it("carries nothing typed past a Reset", async () => {
      const { requests } = await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup();

      await findTable();
      await user.type(screen.getByLabelText("Actor id"), ACTOR_A);
      await user.click(applyButton());
      await vi.waitFor(() => expect(requests()).toHaveLength(2));
      await user.click(screen.getByRole("button", { name: "Reset" }));
      await vi.waitFor(() => expect(requests()).toHaveLength(3));
      await pick(user, "LOGOUT");

      await vi.waitFor(() =>
        expect(lastRead(requests)).toBe(
          `${AUDIT_EVENTS_PATH}?operation=LOGOUT&startIndex=1&count=50`,
        ),
      );
    });

    it("sends a selection a refusal held back with the next change", async () => {
      const { requests } = await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup();

      await findTable();
      await user.type(screen.getByLabelText("Actor id"), "not-a-uuid");
      await pick(user, "LOGOUT");
      await screen.findByRole("alert");
      await user.keyboard("{Escape}");
      await user.clear(screen.getByLabelText("Actor id"));
      await user.click(outcome("Failure"));

      await vi.waitFor(() =>
        expect(lastRead(requests)).toBe(
          `${AUDIT_EVENTS_PATH}?operation=LOGOUT&outcome=FAILURE&startIndex=1&count=50`,
        ),
      );
    });

    it("sends the last applied typed value with an Outcome change too", async () => {
      const { requests } = await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup();

      await findTable();
      await user.type(screen.getByLabelText("Actor id"), ACTOR_A);
      await user.click(applyButton());
      await vi.waitFor(() => expect(requests()).toHaveLength(2));
      await user.clear(screen.getByLabelText("Actor id"));
      await user.type(screen.getByLabelText("Actor id"), ACTOR_B);
      await user.click(outcome("Success"));

      await vi.waitFor(() =>
        expect(lastRead(requests)).toBe(
          `${AUDIT_EVENTS_PATH}?outcome=SUCCESS&actorId=${ACTOR_A}&startIndex=1&count=50`,
        ),
      );
    });

    it("returns to the first page on a change", async () => {
      await renderAudit({
        [at(1)]: answerPage([EVENT_1], { totalResults: 51 }),
        [at(2)]: answerPage([EVENT_2], { startIndex: 2, totalResults: 51 }),
        [`GET ${AUDIT_EVENTS_PATH}?operation=LOGOUT&startIndex=1&count=50`]: answerPage([EVENT_1], {
          totalResults: 1,
        }),
      });
      const user = userEvent.setup();

      await findTable();
      await user.click(screen.getByRole("button", { name: "Next" }));
      await screen.findByText("Events 2–2 of 51");
      await pick(user, "LOGOUT");

      expect(await screen.findByText("Events 1–1 of 1")).toBeInTheDocument();
    });

    it("works from the keyboard alone: open, search, pick, close", async () => {
      const { requests } = await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup();

      await findTable();
      pickerTrigger().focus();
      await user.keyboard("{Enter}");
      await vi.waitFor(() => expect(screen.getByRole("combobox")).toHaveFocus());
      await user.keyboard("lockout_l{Enter}");
      await vi.waitFor(() =>
        expect(lastRead(requests)).toBe(
          `${AUDIT_EVENTS_PATH}?operation=LOCKOUT_LIFT&startIndex=1&count=50`,
        ),
      );
      await user.keyboard("{Escape}");

      await vi.waitFor(() => expect(pickerTrigger()).toHaveFocus());
      expect(screen.queryByRole("combobox")).toBeNull();
    });

    it("names its search and its list for a screen reader", async () => {
      await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup();

      await findTable();
      expect(pickerTrigger()).toHaveAttribute("aria-haspopup", "dialog");
      await openPicker(user);

      expect(screen.getByRole("combobox")).toHaveAccessibleName("Operations");
      expect(screen.getByRole("listbox")).toHaveAttribute("aria-multiselectable", "true");
    });
  });

  describe("the Outcome toggle", () => {
    it("offers Any, Success and Failure under its label, Any chosen first", async () => {
      await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));

      await findTable();
      const toggle = within(screen.getByRole("radiogroup", { name: "Outcome" }));
      expect(
        toggle
          .getAllByRole("radio")
          .map((radio) => [radio.textContent, radio.getAttribute("aria-checked")]),
      ).toEqual([
        ["Any", "true"],
        ["Success", "false"],
        ["Failure", "false"],
      ]);
    });

    it.each([
      ["Success", "SUCCESS"],
      ["Failure", "FAILURE"],
    ] as const)("reads at once when %s is chosen", async (name, value) => {
      const { requests } = await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup();

      await findTable();
      await user.click(outcome(name));

      await vi.waitFor(() =>
        expect(readPaths(requests)).toEqual([
          unfiltered,
          `${AUDIT_EVENTS_PATH}?outcome=${value}&startIndex=1&count=50`,
        ]),
      );
    });

    it("reads every outcome again when Any is chosen", async () => {
      const { requests } = await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup();

      await findTable();
      await user.click(outcome("Failure"));
      await user.click(outcome("Any"));

      await vi.waitFor(() => expect(requests()).toHaveLength(3));
      expect(lastRead(requests)).toBe(unfiltered);
    });

    it("stays where it is, and reads nothing, when the position chosen is pressed", async () => {
      const { requests } = await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup();

      await findTable();
      await user.click(outcome("Any"));

      expect(outcome("Any")).toHaveAttribute("aria-checked", "true");
      expect(readPaths(requests)).toEqual([unfiltered]);
    });

    it("refuses a change while a typed field is invalid, sending nothing", async () => {
      const { requests } = await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup();

      await findTable();
      await user.type(screen.getByLabelText("Resource id"), "not-a-uuid");
      await user.click(outcome("Failure"));

      expect(screen.getByRole("alert")).toHaveTextContent(/^Resource id must be a valid UUID\.$/);
      expect(readPaths(requests)).toEqual([unfiltered]);
    });

    it("moves with the arrow keys and chooses with Space", async () => {
      const { requests } = await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup();

      await findTable();
      outcome("Any").focus();
      await user.keyboard("{ArrowRight}");
      await vi.waitFor(() => expect(outcome("Success")).toHaveFocus());
      await user.keyboard(" ");

      await vi.waitFor(() =>
        expect(lastRead(requests)).toBe(
          `${AUDIT_EVENTS_PATH}?outcome=SUCCESS&startIndex=1&count=50`,
        ),
      );
    });
  });

  describe("Reset", () => {
    it("clears every control and reads the unfiltered first page", async () => {
      const { requests } = await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup();

      await findTable();
      await user.type(screen.getByLabelText("Actor id"), "00000000-0000-4000-8000-000000000001");
      await user.type(screen.getByLabelText("Resource id"), "00000000-0000-4000-8000-000000000002");
      fireEvent.change(screen.getByLabelText("From"), { target: { value: "2026-01-01T00:00" } });
      fireEvent.change(screen.getByLabelText("To"), { target: { value: "2026-01-02T00:00" } });
      await user.click(outcome("Failure"));
      await pick(user, "LOGOUT");
      await user.keyboard("{Escape}");
      const applied = requests().length;
      await user.click(screen.getByRole("button", { name: "Reset" }));

      await vi.waitFor(() => expect(requests()).toHaveLength(applied + 1));
      expect(lastRead(requests)).toBe(unfiltered);
      expect({
        typed: ["Actor id", "Resource id", "From", "To"].map(
          (label) => screen.getByLabelText<HTMLInputElement>(label).value,
        ),
        operations: pickerTrigger().textContent,
        any: outcome("Any").getAttribute("aria-checked"),
      }).toEqual({ typed: ["", "", "", ""], operations: "Operation: Any", any: "true" });
    });

    it("clears a refusal, whatever was typed", async () => {
      await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup();

      await findTable();
      await user.type(screen.getByLabelText("Actor id"), "not-a-uuid");
      await user.click(applyButton());
      await screen.findByRole("alert");
      await user.click(screen.getByRole("button", { name: "Reset" }));

      expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    });

    it("returns from a later page to the first", async () => {
      await renderAudit({
        [at(1)]: answerPage([EVENT_1], { totalResults: 51 }),
        [at(2)]: answerPage([EVENT_2], { startIndex: 2, totalResults: 51 }),
      });
      const user = userEvent.setup();

      await findTable();
      await user.click(screen.getByRole("button", { name: "Next" }));
      await screen.findByText("Events 2–2 of 51");
      await user.click(screen.getByRole("button", { name: "Reset" }));

      expect(await screen.findByText("Events 1–1 of 51")).toBeInTheDocument();
    });
  });

  describe("the toolbar", () => {
    it("is one named form, every typed field labelled", async () => {
      await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));

      await findTable();
      const toolbar = within(screen.getByRole("form", { name: "Audit filters" }));
      expect(
        ["Actor id", "Resource id", "From", "To"].map(
          (label) => toolbar.getByLabelText(label).tagName,
        ),
      ).toEqual(["INPUT", "INPUT", "INPUT", "INPUT"]);
    });

    it("reaches every control by Tab, in order", async () => {
      await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup();
      const toolbar = within(screen.getByRole("form", { name: "Audit filters" }));

      await findTable();
      pickerTrigger().focus();
      const expected = [
        outcome("Any"),
        toolbar.getByLabelText("Actor id"),
        toolbar.getByLabelText("Resource id"),
        toolbar.getByLabelText("From"),
        toolbar.getByLabelText("To"),
        applyButton(),
        toolbar.getByRole("button", { name: "Reset" }),
      ];
      const reached: (Element | null)[] = [];
      while (reached.length < expected.length) {
        await user.tab();
        reached.push(document.activeElement);
      }

      expect(reached).toEqual(expected);
    });
  });
});
