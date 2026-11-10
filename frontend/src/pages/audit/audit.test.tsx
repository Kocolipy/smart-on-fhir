import { fireEvent, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";

import {
  bare,
  deferred,
  holding,
  json,
  renderAsSession,
  type Answers,
  type StartingSession,
  type WireAnswer,
} from "@/auth/session-adapter.testHelpers";

import { InPageStatus } from "@/auth/page-status.testHelpers";

import { Audit } from "./audit";
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

/** Each event's own row, in order: not the header row, and not an opened detail row. */
const eventRows = () =>
  within(table())
    .getAllByRole("row")
    .filter((row) => within(row).queryByRole("rowheader") !== null);

/** `row`'s cell under the column headed `heading`, found by the headings' order. */
function cellOf(row: HTMLElement, heading: string): Element {
  const headings = within(table())
    .getAllByRole("columnheader")
    .map((header) => header.textContent);
  const cell = row.children[headings.indexOf(heading)];
  if (cell === undefined) throw new Error(`no ${heading} column`);
  return cell;
}

/** Every event's detail control, in row order. */
const detailControls = () => within(table()).getAllByRole("button", { name: /^Details of / });

/**
 * Opens every event's detail and returns each one's fields as name → text, in
 * row order, as a reader of its expanded row sees them.
 */
async function openDetails(user: User): Promise<Record<string, string>[]> {
  for (const control of detailControls()) await user.click(control);
  return screen.getAllByRole("region", { name: /^Details of / }).map((region) =>
    Object.fromEntries(
      within(region)
        .getAllByRole("term")
        .map((term) => [term.textContent, term.nextElementSibling?.textContent ?? ""]),
    ),
  );
}

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
    // The two events in the order the backend sent them, each by its operation's label.
    expect(eventRows().map((row) => cellOf(row, "Operation").textContent)).toEqual([
      "Login failed",
      "Users listed",
    ]);
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

  it("says the trail is loading while its first read is in flight", async () => {
    await renderAudit({ [at(1)]: deferred().answer });

    expect(screen.getByRole("status")).toHaveTextContent(/^Loading audit events…$/);
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
    const user = userEvent.setup();

    await findTable();
    await openDetails(user);
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
    ).toEqual(["Occurred", "Operation", "Outcome", "Actor", "Resource", "Event details"]);
  });

  it("shows each outcome as its status badge", async () => {
    await renderAudit(everyRead(answerPage([EVENT_1, EVENT_2], { totalResults: 2 })));

    await findTable();
    expect(
      eventRows().map((row) => {
        const badge = cellOf(row, "Outcome").querySelector('[data-slot="badge"]');
        return badge?.textContent;
      }),
    ).toEqual(["Failure", "Success"]);
  });

  it("tints the header row, sets identifiers in monospace and times in tabular figures", async () => {
    await renderAudit(everyRead(answerPage([EVENT_PERMISSION_GRANT], { totalResults: 1 })));
    const user = userEvent.setup();

    await findTable();
    const [row] = eventRows();
    await openDetails(user);
    expect({
      header: table().querySelector("thead")?.classList.contains("bg-accent"),
      occurred: cellOf(row!, "Occurred").classList.contains("tabular-nums"),
      actor: within(row!).getByText("00000000-0000-4000-8000-0000000000c1").className,
      resource: within(row!).getByText("00000000-0000-4000-8000-000000000009").className,
      request: screen.getByText("req-3").className,
    }).toEqual({
      header: true,
      occurred: true,
      actor: "font-mono",
      resource: "font-mono",
      request: "font-mono",
    });
  });

  describe("an event's detail", () => {
    it("keeps changed paths, status, request and details behind a disclosure on its row", async () => {
      await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup();

      await findTable();
      const control = within(table()).getByRole("button", {
        name: "Details of Login failed at 2026-01-03 03:04",
      });
      expect(control).toHaveAttribute("aria-expanded", "false");
      expect(screen.queryByRole("region", { name: /^Details of / })).toBeNull();

      await user.click(control);

      expect(control).toHaveAttribute("aria-expanded", "true");
      const region = screen.getByRole("region", {
        name: "Details of Login failed at 2026-01-03 03:04",
      });
      expect(document.getElementById(control.getAttribute("aria-controls") ?? "")).toBe(region);
      expect(
        within(region)
          .getAllByRole("term")
          .map((term) => [term.textContent, term.nextElementSibling?.textContent]),
      ).toEqual([
        ["Changed paths", "—"],
        ["Status", "client_errorBAD_CREDENTIALS"],
        ["Request", "POST /api/auth/login (req-1)"],
        ["Details", "login method: password"],
      ]);
    });

    it("opens beneath its row, across every column", async () => {
      await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup();

      await findTable();
      await user.click(detailControls()[0]!);

      const cell = screen.getByRole("region", { name: /^Details of / }).closest("td");
      expect(cell?.colSpan).toBe(6);
    });

    it("closes again on a second press", async () => {
      await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup();

      await findTable();
      const [control] = detailControls();
      await user.click(control!);
      await user.click(control!);

      expect([
        control!.getAttribute("aria-expanded"),
        screen.queryByRole("region", { name: /^Details of / }),
      ]).toEqual(["false", null]);
    });

    it("turns its chevron while open, animating the turn both ways", async () => {
      await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup();

      await findTable();
      const [control] = detailControls();
      const chevron = () => {
        const icon = control!.querySelector("svg")!;
        return {
          turned: icon.classList.contains("rotate-90"),
          animated: icon.classList.contains("transition-transform"),
        };
      };
      const closed = chevron();
      await user.click(control!);

      expect({ closed, open: chevron() }).toEqual({
        closed: { turned: false, animated: true },
        open: { turned: true, animated: true },
      });
    });

    it("opens each row's detail alone", async () => {
      await renderAudit(everyRead(answerPage([EVENT_1, EVENT_2], { totalResults: 2 })));
      const user = userEvent.setup();

      await findTable();
      await user.click(detailControls()[1]!);

      expect(
        screen.getAllByRole("region", { name: /^Details of / }).map((region) => region.id),
      ).toEqual([detailControls()[1]!.getAttribute("aria-controls")]);
      expect(detailControls().map((control) => control.getAttribute("aria-expanded"))).toEqual([
        "false",
        "true",
      ]);
    });

    it("opens with Enter and closes with Space from the keyboard", async () => {
      await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup();

      await findTable();
      const [control] = detailControls();
      control!.focus();
      await user.keyboard("{Enter}");
      const opened = control!.getAttribute("aria-expanded");
      await user.keyboard(" ");

      expect([opened, control!.getAttribute("aria-expanded")]).toEqual(["true", "false"]);
    });

    it("opens on a click anywhere on its row, and closes on a second", async () => {
      await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup();

      await findTable();
      const [row] = eventRows();
      await user.click(cellOf(row!, "Operation"));
      const opened = detailControls()[0]!.getAttribute("aria-expanded");
      await user.click(cellOf(row!, "Outcome"));

      expect([opened, detailControls()[0]!.getAttribute("aria-expanded")]).toEqual([
        "true",
        "false",
      ]);
    });

    it("closes on a click anywhere on the open detail", async () => {
      await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup();

      await findTable();
      await user.click(detailControls()[0]!);
      await user.click(within(screen.getByRole("region", { name: /^Details of / })).getByText("—"));

      expect([
        detailControls()[0]!.getAttribute("aria-expanded"),
        screen.queryByRole("region", { name: /^Details of / }),
      ]).toEqual(["false", null]);
    });

    it("stays as it is on a click that ends selecting text, on its row or its detail", async () => {
      await renderAudit(everyRead(answerPage([EVENT_2], { totalResults: 1 })));
      const user = userEvent.setup();

      await findTable();
      const [row] = eventRows();
      /** A click that lands as a drag-selection of `target`'s text ends. */
      const selectAndClick = (target: Element) => {
        window.getSelection()!.selectAllChildren(target);
        fireEvent.click(target);
        window.getSelection()!.removeAllRanges();
      };
      selectAndClick(cellOf(row!, "Actor"));
      const afterRowSelection = detailControls()[0]!.getAttribute("aria-expanded");
      await user.click(detailControls()[0]!);
      selectAndClick(
        within(screen.getByRole("region", { name: /^Details of / })).getByText(
          "GET /Users (req-2)",
        ),
      );

      expect([afterRowSelection, detailControls()[0]!.getAttribute("aria-expanded")]).toEqual([
        "false",
        "true",
      ]);
    });

    it("shows its row as clickable, with a pointer over it", async () => {
      await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));

      await findTable();
      const [row] = eventRows();

      expect(row).toHaveClass("cursor-pointer");
    });

    it("opens from its control even while text elsewhere on the page stays selected", async () => {
      await renderAudit(everyRead(answerPage([EVENT_1, EVENT_2], { totalResults: 2 })));
      const user = userEvent.setup();

      await findTable();
      window.getSelection()!.selectAllChildren(cellOf(eventRows()[1]!, "Actor"));
      const [control] = detailControls();
      control!.focus();
      await user.keyboard("{Enter}");
      window.getSelection()!.removeAllRanges();

      expect(control).toHaveAttribute("aria-expanded", "true");
    });
  });

  it("renders a resource id beside its type, and the type alone when there is none", async () => {
    await renderAudit(everyRead(answerPage([EVENT_1, EVENT_2], { totalResults: 2 })));

    await findTable();
    expect(eventRows().map((row) => cellOf(row, "Resource").textContent)).toEqual([
      "User 00000000-0000-4000-8000-000000000001",
      "User",
    ]);
  });

  it("shows an actor's id, and an em dash for an event with no actor", async () => {
    await renderAudit(everyRead(answerPage([EVENT_1, EVENT_2], { totalResults: 2 })));

    await findTable();
    expect(eventRows().map((row) => cellOf(row, "Actor").textContent)).toEqual([
      "—",
      "00000000-0000-4000-8000-0000000000c1",
    ]);
  });

  it("joins the triggering request's method, route and correlation id", async () => {
    await renderAudit(everyRead(answerPage([EVENT_2], { totalResults: 1 })));
    const user = userEvent.setup();

    await findTable();
    const [details] = await openDetails(user);
    expect(details!.Request).toBe("GET /Users (req-2)");
  });

  it("names only the correlation id when the event names no triggering request", async () => {
    await renderAudit(everyRead(answerPage([EVENT_PERMISSION_GRANT], { totalResults: 1 })));
    const user = userEvent.setup();

    await findTable();
    const [details] = await openDetails(user);
    expect(details!.Request).toBe("req-3");
  });

  it("names only the route when the triggering request carried no correlation id", async () => {
    await renderAudit(
      everyRead(answerPage([{ ...EVENT_2, requestId: null }], { totalResults: 1 })),
    );
    const user = userEvent.setup();

    await findTable();
    const [details] = await openDetails(user);
    expect(details!.Request).toBe("GET /Users");
  });

  it("joins changed paths, and shows an em dash when there are none", async () => {
    await renderAudit(
      everyRead(answerPage([EVENT_1, EVENT_PERMISSION_GRANT], { totalResults: 2 })),
    );
    const user = userEvent.setup();

    await findTable();
    const details = await openDetails(user);
    expect(details.map((fields) => fields["Changed paths"])).toEqual(["—", "groups, active"]);
  });

  it("joins a bulk read's result count and filter shape in Details", async () => {
    await renderAudit(everyRead(answerPage([EVENT_2], { totalResults: 1 })));
    const user = userEvent.setup();

    await findTable();
    const [details] = await openDetails(user);
    expect(details!.Details).toBe("3 result(s) · userName eq ?");
  });

  it("shows a membership change's Permissions before and after in Details", async () => {
    await renderAudit(everyRead(answerPage([EVENT_PERMISSION_GRANT], { totalResults: 1 })));
    const user = userEvent.setup();

    await findTable();
    const [details] = await openDetails(user);
    expect(details!.Details).toBe("permissions: none → group:read, user:read");
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

    const user = userEvent.setup();

    await findTable();
    const details = await openDetails(user);
    expect(details.map((fields) => [fields.Details, fields.Status])).toEqual([
      ["permissions: audit:read, user:read → audit:read", "ok"],
      ["—", "client_errorINVALID_PERMISSIONS"],
    ]);
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

    const user = userEvent.setup();

    await findTable();
    const details = await openDetails(user);
    expect(details.map((fields) => fields.Details)).toEqual([
      "permissions: none → ops:read · Superuser granted",
      "permissions: ops:read → none · Superuser removed",
    ]);
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

    const user = userEvent.setup();

    await findTable();
    const [details] = await openDetails(user);
    expect(details!.Details).toBe(
      "permissions: audit:read → none · groups: 00000000-0000-4000-8000-0000000000a1, " +
        "00000000-0000-4000-8000-0000000000a2",
    );
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

    const user = userEvent.setup();

    await findTable();
    const [details] = await openDetails(user);
    expect(details!.Details).toBe("group:read, user:read");
  });

  it("names a login's method in Details", async () => {
    await renderAudit(
      everyRead(answerPage([EVENT_1, { ...EVENT_1, id: "e-5", loginMethod: "sso" }])),
    );

    const user = userEvent.setup();

    await findTable();
    const details = await openDetails(user);
    expect(details.map((fields) => fields.Details)).toEqual([
      "login method: password",
      "login method: sso",
    ]);
  });

  it("shows an em dash in every optional column for an event naming none of them", async () => {
    await renderAudit(everyRead(answerPage([EVENT_BARE], { totalResults: 1 })));

    const user = userEvent.setup();

    await findTable();
    const [row] = eventRows();
    const [details] = await openDetails(user);
    // A type-only resource is not a dash; the actor and every optional detail are.
    expect({
      actor: cellOf(row!, "Actor").textContent,
      resource: cellOf(row!, "Resource").textContent,
      details,
    }).toEqual({
      actor: "—",
      resource: "User",
      details: { "Changed paths": "—", Status: "ok", Request: "—", Details: "—" },
    });
  });

  describe("paging", () => {
    it("requests the next page and shows it", async () => {
      const { requests } = await renderAudit({
        [at(1)]: answerPage([EVENT_1], { totalResults: 51 }),
        [at(2)]: answerPage([EVENT_2], { startIndex: 2, totalResults: 51 }),
      });
      const user = userEvent.setup();

      await findTable();
      await user.click(
        within(screen.getByRole("navigation", { name: "Audit trail pages" })).getByRole("button", {
          name: "Next",
        }),
      );

      await screen.findByText("Showing 2 to 2 of 51 events");
      expect(within(table()).getByText("Users listed")).toBeInTheDocument();
      expect(readPaths(requests).slice(-1)[0]).toBe(`${AUDIT_EVENTS_PATH}?startIndex=2&count=50`);
    });

    it("shows no pager, and sends no request, for a session lacking audit:read", async () => {
      const { requests } = await renderAudit({}, holding([]));

      expect(screen.queryByRole("navigation", { name: "Audit trail pages" })).toBeNull();
      expect(requests()).toEqual([]);
    });

    it("keeps the applied filters across Next and Previous", async () => {
      const { requests } = await renderAudit(
        everyRead(answerPage([EVENT_1], { totalResults: 51 })),
      );
      const user = userEvent.setup();

      await findTable();
      await pick(user, "Logged out", "Lockout set");
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
      await screen.findByText("Showing 51 to 51 of 51 events");
      expect(screen.getByRole("button", { name: "Next" })).toBeDisabled();
      await user.click(screen.getByRole("button", { name: "Previous" }));
      await screen.findByText("Showing 1 to 50 of 51 events");

      expect(readPaths(requests)).toEqual([
        `${AUDIT_EVENTS_PATH}?startIndex=1&count=50`,
        `${AUDIT_EVENTS_PATH}?startIndex=51&count=50`,
        `${AUDIT_EVENTS_PATH}?startIndex=1&count=50`,
      ]);
    });
  });

  describe("Refresh", () => {
    /** Every read-again control on the page, each with whether it sits in the trail's card header. */
    const readAgainControls = () =>
      screen
        .queryAllByRole("button", { name: /^(Refresh|Try again)$/ })
        .map((control) => [
          control.textContent,
          control.closest('[data-slot="card-header"]') !== null,
        ]);

    // The trail grows with nothing on the page to say so, so Refresh is
    // offered whatever the read came to, in the card header's top right.
    it.each([
      ["loading", () => deferred().answer],
      ["ready", () => answerPage([EVENT_1])],
      ["failed", () => bare(503)],
    ] as const)(
      "is the one read-again control, in the card header, while the read is %s",
      async (kind, answer) => {
        await renderAudit({ [at(1)]: answer() });
        if (kind === "ready") await findTable();
        if (kind === "failed") await screen.findByRole("alert");

        expect(readAgainControls()).toEqual([["Refresh", true]]);
      },
    );

    // Rendered directly: through the shell, the route guard turns this session away first.
    it("is not offered to a session lacking audit:read", async () => {
      await renderAsSession(
        <InPageStatus>
          <Audit />
        </InPageStatus>,
        holding([]),
        { at: "/audit" },
      );
      expect(screen.getByText("Audit trail")).toBeInTheDocument();

      expect(readAgainControls()).toEqual([]);
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
      expect(within(table()).getByText("Login failed")).toBeInTheDocument();
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
      await screen.findByText("Showing 2 to 2 of 51 events");
      await user.click(applyButton());

      expect(await screen.findByText("Showing 1 to 1 of 51 events")).toBeInTheDocument();
    });

    it("reads only the first page on an Apply from a later page", async () => {
      const { requests } = await renderAudit({
        [at(1)]: answerPage([EVENT_1], { totalResults: 51 }),
        [at(2)]: answerPage([EVENT_2], { startIndex: 2, totalResults: 51 }),
      });
      const user = userEvent.setup();

      await findTable();
      await user.click(screen.getByRole("button", { name: "Next" }));
      await screen.findByText("Showing 2 to 2 of 51 events");
      await user.click(applyButton());
      await screen.findByText("Showing 1 to 1 of 51 events");

      expect(readPaths(requests)).toEqual([
        `${AUDIT_EVENTS_PATH}?startIndex=1&count=50`,
        `${AUDIT_EVENTS_PATH}?startIndex=2&count=50`,
        `${AUDIT_EVENTS_PATH}?startIndex=1&count=50`,
      ]);
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

    it("lists every operation by its label alone, with no count beside it", async () => {
      await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup();

      await findTable();
      await openPicker(user);

      expect(shownOptions()).toEqual([
        "Login succeeded",
        "Login failed",
        "Logged out",
        "Lockout set",
        "Lockout lifted",
        "Connector created",
        "Connector deleted",
        "Connector token issued",
        "Connector token rotated",
        "Connector token revoked",
        "User created",
        "Users listed",
        "User replaced",
        "User deleted",
        "User sessions revoked",
        "Group created",
        "Group replaced",
        "Group deleted",
        "Groups listed",
        "Users and Groups searched",
        "Recovery resource seeded",
        "Dormancy lockout",
        "Dormancy membership revocation",
        "Password change required",
        "Password changed",
        "Access denied",
        "Permission granted",
        "Permission revoked",
        "Group Permissions changed",
        "FHIRcast token released",
      ]);
    });

    it("sends each operation's own value for its label", async () => {
      const { requests } = await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup();

      await findTable();
      await openPicker(user);
      for (const option of screen.getAllByRole("option")) await user.click(option);

      await vi.waitFor(() =>
        expect(lastRead(requests)).toBe(
          `${AUDIT_EVENTS_PATH}?` +
            AUDIT_OPERATIONS.map((operation) => `operation=${operation}`).join("&") +
            "&startIndex=1&count=50",
        ),
      );
    });

    it("narrows the list as the search is typed", async () => {
      await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup();

      await findTable();
      await openPicker(user);
      await user.type(screen.getByRole("combobox"), "lock");

      expect(shownOptions().sort()).toEqual(["Dormancy lockout", "Lockout lifted", "Lockout set"]);
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
      await pick(user, "Logged out", "Lockout set");

      expect(pickerTrigger()).toHaveAccessibleName("Operation: 2 selected");
    });

    it("gives a checkbox to each operation, checked once it is selected", async () => {
      await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup();

      await findTable();
      await pick(user, "Logged out");

      const checkedOf = (name: string) => {
        const option = screen.getByRole("option", { name });
        return [
          option.getAttribute("aria-checked"),
          option.querySelector('[data-slot="checkbox"]')?.getAttribute("data-state"),
        ];
      };
      expect([checkedOf("Logged out"), checkedOf("Lockout set")]).toEqual([
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
      await pick(user, "Logged out", "Lockout set", "Logged out");

      await vi.waitFor(() =>
        expect(readPaths(requests)).toEqual([
          unfiltered,
          `${AUDIT_EVENTS_PATH}?operation=LOGOUT&startIndex=1&count=50`,
          `${AUDIT_EVENTS_PATH}?operation=LOGOUT&operation=LOCKOUT_SET&startIndex=1&count=50`,
          `${AUDIT_EVENTS_PATH}?operation=LOCKOUT_SET&startIndex=1&count=50`,
        ]),
      );
    });

    const clearings = () => screen.queryAllByRole("option", { name: "Clear selection" });

    it("offers clearing first and last in the list, each set apart, once something is selected", async () => {
      await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup();

      await findTable();
      await pick(user, "Logged out");
      const shown = shownOptions();

      expect({
        first: shown[0],
        last: shown[shown.length - 1],
        clearings: clearings().length,
        separators: within(screen.getByRole("listbox")).getAllByRole("separator").length,
      }).toEqual({
        first: "Clear selection",
        last: "Clear selection",
        clearings: 2,
        separators: 2,
      });
    });

    it.each([
      ["first", 0],
      ["last", 1],
    ])(
      "clears every selection in one step from the %s clearing, and reads every operation",
      async (_where, index) => {
        const { requests } = await renderAudit(
          everyRead(answerPage([EVENT_1], { totalResults: 1 })),
        );
        const user = userEvent.setup();

        await findTable();
        await pick(user, "Logged out", "Lockout set");
        await vi.waitFor(() => expect(requests()).toHaveLength(3));
        await user.click(clearings()[index]!);

        await vi.waitFor(() => expect(requests()).toHaveLength(4));
        expect(lastRead(requests)).toBe(unfiltered);
        expect(pickerTrigger()).toHaveAccessibleName("Operation: Any");
      },
    );

    it("offers no clearing with nothing selected", async () => {
      await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup();

      await findTable();
      await openPicker(user);

      expect(clearings()).toEqual([]);
    });

    it("offers both clearings still while a search hides every operation", async () => {
      await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup();

      await findTable();
      await pick(user, "Logged out");
      await user.type(screen.getByRole("combobox"), "zzz");

      expect(shownOptions()).toEqual(["Clear selection", "Clear selection"]);
    });

    it("refuses a change while a typed field is invalid, sending nothing", async () => {
      const { requests } = await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup();

      await findTable();
      await user.type(screen.getByLabelText("Actor id"), "not-a-uuid");
      await pick(user, "Logged out");

      expect(screen.getByRole("alert")).toHaveTextContent(/^Actor id must be a valid UUID\.$/);
      expect(pickerTrigger()).toHaveAccessibleName("Operation: 1 selected");
      expect(readPaths(requests)).toEqual([unfiltered]);
    });

    it("leaves a valid typed field unsent until Apply", async () => {
      const { requests } = await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup();

      await findTable();
      await user.type(screen.getByLabelText("Actor id"), ACTOR_A);
      await pick(user, "Logged out");
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
      await pick(user, "Logged out");

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
      await pick(user, "Logged out");

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
      await pick(user, "Logged out");

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
      await pick(user, "Logged out");

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
      await pick(user, "Logged out");
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
          totalResults: 51,
        }),
      });
      const user = userEvent.setup();

      await findTable();
      await user.click(screen.getByRole("button", { name: "Next" }));
      await screen.findByText("Showing 2 to 2 of 51 events");
      await pick(user, "Logged out");

      expect(await screen.findByText("Showing 1 to 1 of 51 events")).toBeInTheDocument();
    });

    it("works from the keyboard alone: open, search, pick, close", async () => {
      const { requests } = await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup();

      await findTable();
      pickerTrigger().focus();
      await user.keyboard("{Enter}");
      await vi.waitFor(() => expect(screen.getByRole("combobox")).toHaveFocus());
      await user.keyboard("lockout l{Enter}");
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
      await pick(user, "Logged out");
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
      await screen.findByText("Showing 2 to 2 of 51 events");
      await user.click(screen.getByRole("button", { name: "Reset" }));

      expect(await screen.findByText("Showing 1 to 1 of 51 events")).toBeInTheDocument();
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
