import { fireEvent, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, onTestFinished, vi } from "vitest";

import {
  bare,
  deferred,
  holding,
  type Answers,
  type StartingSession,
} from "@/auth/session-adapter.testHelpers";

import { AUDIT_EVENTS_PATH } from "./audit-api";
import {
  AUDITOR,
  EVENT_1,
  EVENT_2,
  at,
  everyRead,
  answerPage,
  readPaths,
  table,
  findTable,
  type User,
  pick,
  lastRead,
  apply,
  outcome,
} from "./audit.testHelpers";
import { renderSignedIn } from "@/shell/signed-in-shell.testHelpers";

/** The page as `session` sees it, inside the signed-in layout that renders its one error line. */
function renderAudit(answers: Answers, session: StartingSession = AUDITOR) {
  return renderSignedIn("/audit", session, answers);
}

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
      { method: "GET", path: `${AUDIT_EVENTS_PATH}?startIndex=1&count=20` },
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

    // jsdom runs no animations, so each case names the animation the detail's
    // box computes to, as a stylesheet would with motion allowed or reduced.
    const animatingDetailAs = (animationName: string) => {
      const real = window.getComputedStyle.bind(window);
      const spy = vi.spyOn(window, "getComputedStyle").mockImplementation((element, pseudo) => {
        const style = real(element, pseudo);
        if (!element.classList.contains("grid")) return style;
        return new Proxy(style, {
          get: (target, key) =>
            key === "animationName" ? animationName : Reflect.get(target, key, target),
        });
      });
      onTestFinished(() => spy.mockRestore());
    };
    const detailBox = () =>
      screen.getByRole("region", { name: /^Details of / }).closest("td")!.firstElementChild!;

    it("stays through its closing animation, and leaves once it ends", async () => {
      animatingDetailAs("detail-close");
      await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup();

      await findTable();
      const [control] = detailControls();
      await user.click(control!);
      await user.click(control!);
      const closing = {
        expanded: control!.getAttribute("aria-expanded"),
        animation: detailBox().classList.contains("animate-detail-close"),
      };
      fireEvent.animationEnd(detailBox());

      expect({
        closing,
        after: screen.queryByRole("region", { name: /^Details of / }),
      }).toEqual({ closing: { expanded: "false", animation: true }, after: null });
    });

    it("leaves at once where its closing animation is none, as under reduced motion", async () => {
      animatingDetailAs("none");
      await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup();

      await findTable();
      const [control] = detailControls();
      await user.click(control!);
      await user.click(control!);

      expect(screen.queryByRole("region", { name: /^Details of / })).toBeNull();
    });

    it("opens with its opening animation, even when pressed again while closing", async () => {
      animatingDetailAs("detail-close");
      await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup();

      await findTable();
      const [control] = detailControls();
      await user.click(control!);
      const first = detailBox().classList.contains("animate-detail-open");
      await user.click(control!);
      await user.click(control!);

      expect({
        first,
        again: detailBox().classList.contains("animate-detail-open"),
        expanded: control!.getAttribute("aria-expanded"),
      }).toEqual({ first: true, again: true, expanded: "true" });
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
      expect(readPaths(requests).slice(-1)[0]).toBe(`${AUDIT_EVENTS_PATH}?startIndex=2&count=20`);
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
      await apply(user);
      await findTable();
      await user.click(screen.getByRole("button", { name: "Next" }));
      await vi.waitFor(() => expect(requests()).toHaveLength(3));

      expect(lastRead(requests)).toBe(
        `${AUDIT_EVENTS_PATH}?operation=LOGOUT&operation=LOCKOUT_SET&outcome=FAILURE&startIndex=2&count=20`,
      );
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

    it("is not offered to a session lacking audit:read", async () => {
      await renderAudit({}, holding([]));
      expect(screen.getByText("Audit trail")).toBeInTheDocument();

      expect(readAgainControls()).toEqual([]);
    });
  });

  it("reloads on Refresh without changing the filters", async () => {
    const { requests } = await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
    const user = userEvent.setup();

    await findTable();
    await user.click(screen.getByRole("button", { name: "Refresh" }));

    await vi.waitFor(() =>
      expect(readPaths(requests)).toEqual([
        `${AUDIT_EVENTS_PATH}?startIndex=1&count=20`,
        `${AUDIT_EVENTS_PATH}?startIndex=1&count=20`,
      ]),
    );
  });
});
