import { fireEvent, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

import type { Permission } from "@/auth/api";
import { AuthContext, type AuthContextState } from "@/auth/auth-context-value";
import { apiFetch } from "@/lib/http";

import { AUDIT_EVENTS_PATH } from "./audit-api";
import { Audit } from "./audit";

vi.mock("@/lib/http", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/http")>()),
  apiFetch: vi.fn(),
}));

const apiFetchMock = vi.mocked(apiFetch);

const auth: AuthContextState = {
  changePassword: vi.fn(),
  expireSession: vi.fn(),
  login: vi.fn(),
  logout: vi.fn(),
  passwordChanged: false,
  sessionExpired: false,
  signOutForInactivity: vi.fn(),
  signedOutForInactivity: false,
  status: "authenticated",
  user: {
    idleTimeoutSeconds: 900,
    passwordChangeRequired: false,
    permissions: ["audit:read"] as Permission[],
    username: "ada",
  },
};

const EVENT_1 = {
  id: "e-1",
  occurredAt: "2026-01-02T03:04:05Z",
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
  role: null,
  permissions: [],
  loginMethod: "password",
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
  role: null,
  permissions: [],
  loginMethod: null,
};

/** A membership change: multiple changed paths, a Role and Permissions, no triggering request. */
const EVENT_ROLE_CHANGE = {
  id: "e-3",
  occurredAt: "2026-01-04T09:08:07Z",
  operation: "ROLE_GRANT",
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
  role: "Engineering",
  permissions: ["user:read", "group:read"],
  loginMethod: null,
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
  role: null,
  permissions: [],
  loginMethod: null,
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

function resolveWith(result: object) {
  apiFetchMock.mockResolvedValue(result as never);
}

function renderAudit(value: AuthContextState = auth) {
  return render(
    <AuthContext.Provider value={value}>
      <MemoryRouter>
        <Audit />
      </MemoryRouter>
    </AuthContext.Provider>,
  );
}

const table = () => screen.getByRole("table", { name: "Audit events" });

/** Waits for the table to render, then returns it. */
const findTable = () => screen.findByRole("table", { name: "Audit events" });

describe("Audit", () => {
  beforeEach(() => {
    apiFetchMock.mockReset();
    vi.mocked(auth.expireSession).mockReset();
    vi.mocked(auth.logout).mockReset();
  });

  it("lists every recorded event on mount, newest first as the backend ordered them", async () => {
    resolveWith({ kind: "ok", data: pageOf([EVENT_1, EVENT_2], { totalElements: 2 }) });
    renderAudit();

    await findTable();
    const rows = within(table()).getAllByRole("row");
    // Header row, then the two events in the order the backend sent them.
    expect(within(rows[1]).getByText("LOGIN_FAILURE")).toBeInTheDocument();
    expect(within(rows[2]).getByText("SCIM_USER_LIST")).toBeInTheDocument();
    expect(apiFetchMock).toHaveBeenCalledWith(
      `${AUDIT_EVENTS_PATH}?page=0&size=50`,
      {},
      expect.any(Function),
    );
  });

  it("shows an empty-state message when no events match", async () => {
    resolveWith({ kind: "ok", data: pageOf([]) });
    renderAudit();

    expect(
      await screen.findByText("No audit events match the current filters."),
    ).toBeInTheDocument();
    expect(screen.queryByRole("table", { name: "Audit events" })).not.toBeInTheDocument();
  });

  it("sends no request for a session lacking audit:read", () => {
    renderAudit({
      ...auth,
      user: { ...auth.user!, permissions: [] },
    });

    expect(apiFetchMock).not.toHaveBeenCalled();
  });

  it("reports a failed read with the page's own copy", async () => {
    resolveWith({ kind: "failed", status: 503 });
    renderAudit();

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Unable to load the audit trail. Please try again.",
    );
  });

  it("renders every event field as plain text, never as a link", async () => {
    resolveWith({ kind: "ok", data: pageOf([EVENT_2], { totalElements: 1 }) });
    renderAudit();

    await findTable();
    expect(within(table()).getByText("userName eq ?", { exact: false })).toBeInTheDocument();
    expect(within(table()).getByText("/Users", { exact: false })).toBeInTheDocument();
    expect(within(table()).queryAllByRole("link")).toHaveLength(0);
  });

  it("names every column exactly, in a fixed order", async () => {
    resolveWith({ kind: "ok", data: pageOf([EVENT_1], { totalElements: 1 }) });
    renderAudit();

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
    resolveWith({ kind: "ok", data: pageOf([EVENT_1, EVENT_2], { totalElements: 2 }) });
    renderAudit();

    await findTable();
    const rows = within(table()).getAllByRole("row");
    expect(
      within(rows[1]).getByText("User 00000000-0000-4000-8000-000000000001"),
    ).toBeInTheDocument();
    expect(within(rows[2]).getByText("User")).toBeInTheDocument();
  });

  it("shows an actor's id, and an em dash for an event with no actor", async () => {
    resolveWith({ kind: "ok", data: pageOf([EVENT_1, EVENT_2], { totalElements: 2 }) });
    renderAudit();

    await findTable();
    const rows = within(table()).getAllByRole("row");
    const actorColumn = 2;
    expect(within(rows[1]).getAllByRole("cell")[actorColumn]).toHaveTextContent(/^—$/);
    expect(within(rows[2]).getAllByRole("cell")[actorColumn]).toHaveTextContent(
      "00000000-0000-4000-8000-0000000000c1",
    );
  });

  it("joins the triggering request's method, route and correlation id", async () => {
    resolveWith({ kind: "ok", data: pageOf([EVENT_2], { totalElements: 1 }) });
    renderAudit();

    await findTable();
    expect(within(table()).getByText("GET /Users (req-2)")).toBeInTheDocument();
  });

  it("names only the correlation id when the event names no triggering request", async () => {
    resolveWith({ kind: "ok", data: pageOf([EVENT_ROLE_CHANGE], { totalElements: 1 }) });
    renderAudit();

    await findTable();
    expect(within(table()).getByText("req-3")).toBeInTheDocument();
    expect(within(table()).queryByText(/req-3 \(/)).not.toBeInTheDocument();
  });

  it("names only the route when the triggering request carried no correlation id", async () => {
    resolveWith({
      kind: "ok",
      data: pageOf([{ ...EVENT_2, requestId: null }], { totalElements: 1 }),
    });
    renderAudit();

    await findTable();
    const requestColumn = 6;
    expect(within(table()).getAllByRole("cell")[requestColumn]).toHaveTextContent(/^GET \/Users$/);
  });

  it("joins changed paths, and shows an em dash when there are none", async () => {
    resolveWith({ kind: "ok", data: pageOf([EVENT_1, EVENT_ROLE_CHANGE], { totalElements: 2 }) });
    renderAudit();

    await findTable();
    const rows = within(table()).getAllByRole("row");
    const changedPathsColumn = 4;
    expect(within(rows[1]).getAllByRole("cell")[changedPathsColumn]).toHaveTextContent(/^—$/);
    expect(within(rows[2]).getAllByRole("cell")[changedPathsColumn]).toHaveTextContent(
      "groups, active",
    );
  });

  it("joins a bulk read's result count and filter shape in Details", async () => {
    resolveWith({ kind: "ok", data: pageOf([EVENT_2], { totalElements: 1 }) });
    renderAudit();

    await findTable();
    expect(within(table()).getByText("3 result(s) · userName eq ?")).toBeInTheDocument();
  });

  it("joins a membership change's Role and Permissions in Details", async () => {
    resolveWith({ kind: "ok", data: pageOf([EVENT_ROLE_CHANGE], { totalElements: 1 }) });
    renderAudit();

    await findTable();
    expect(within(table()).getByText("Engineering · user:read, group:read")).toBeInTheDocument();
  });

  it("names a login's method in Details", async () => {
    resolveWith({
      kind: "ok",
      data: pageOf([EVENT_1, { ...EVENT_1, id: "e-5", loginMethod: "sso" }]),
    });
    renderAudit();

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
    resolveWith({ kind: "ok", data: pageOf([EVENT_BARE], { totalElements: 1 }) });
    renderAudit();

    await findTable();
    const rows = within(table()).getAllByRole("row");
    // The actor, resource (type-only is not a dash), changed-paths, request
    // and details columns: actor, changed paths, request and details each
    // fall back to the same em dash here, so four cells carry it.
    expect(within(rows[1]).getAllByText("—")).toHaveLength(4);
  });

  it("renders safely while the signed-in user's details are unavailable", async () => {
    resolveWith({ kind: "ok", data: pageOf([]) });
    renderAudit({ ...auth, user: null });

    expect(await screen.findByText("Signed in as")).toBeInTheDocument();
  });

  describe("paging", () => {
    it("disables Previous on the first page and Next with no further page", async () => {
      resolveWith({ kind: "ok", data: pageOf([EVENT_1], { totalElements: 1, totalPages: 1 }) });
      renderAudit();

      await findTable();
      expect(screen.getByRole("button", { name: "Previous" })).toBeDisabled();
      expect(screen.getByRole("button", { name: "Next" })).toBeDisabled();
      expect(screen.getByText("Page 1 of 1")).toBeInTheDocument();
    });

    it("requests the next page and shows it", async () => {
      apiFetchMock
        .mockResolvedValueOnce({
          kind: "ok",
          data: pageOf([EVENT_1], { totalElements: 2, totalPages: 2 }),
        } as never)
        .mockResolvedValueOnce({
          kind: "ok",
          data: pageOf([EVENT_2], { page: 1, totalElements: 2, totalPages: 2 }),
        } as never);
      const user = userEvent.setup();
      renderAudit();

      await findTable();
      await user.click(screen.getByRole("button", { name: "Next" }));

      await screen.findByText("Page 2 of 2");
      expect(within(table()).getByText("SCIM_USER_LIST")).toBeInTheDocument();
      expect(apiFetchMock).toHaveBeenLastCalledWith(
        `${AUDIT_EVENTS_PATH}?page=1&size=50`,
        {},
        expect.any(Function),
      );
    });

    it("enables Previous once past the first page, and returns to it", async () => {
      apiFetchMock
        .mockResolvedValueOnce({
          kind: "ok",
          data: pageOf([EVENT_1], { totalElements: 2, totalPages: 2 }),
        } as never)
        .mockResolvedValueOnce({
          kind: "ok",
          data: pageOf([EVENT_2], { page: 1, totalElements: 2, totalPages: 2 }),
        } as never)
        .mockResolvedValueOnce({
          kind: "ok",
          data: pageOf([EVENT_1], { totalElements: 2, totalPages: 2 }),
        } as never);
      const user = userEvent.setup();
      renderAudit();

      await findTable();
      expect(screen.getByRole("button", { name: "Previous" })).toBeDisabled();
      await user.click(screen.getByRole("button", { name: "Next" }));

      await screen.findByText("Page 2 of 2");
      const previous = screen.getByRole("button", { name: "Previous" });
      expect(previous).toBeEnabled();
      await user.click(previous);

      await screen.findByText("Page 1 of 2");
      expect(apiFetchMock).toHaveBeenLastCalledWith(
        `${AUDIT_EVENTS_PATH}?page=0&size=50`,
        {},
        expect.any(Function),
      );
    });
  });

  it("reloads on Refresh without changing the filters", async () => {
    resolveWith({ kind: "ok", data: pageOf([EVENT_1], { totalElements: 1 }) });
    const user = userEvent.setup();
    renderAudit();

    await findTable();
    await user.click(screen.getByRole("button", { name: "Refresh" }));

    expect(apiFetchMock).toHaveBeenCalledTimes(2);
    expect(apiFetchMock).toHaveBeenLastCalledWith(
      `${AUDIT_EVENTS_PATH}?page=0&size=50`,
      {},
      expect.any(Function),
    );
  });

  describe("filters", () => {
    it("sends no request and shows a local message for a malformed actor id", async () => {
      resolveWith({ kind: "ok", data: pageOf([EVENT_1], { totalElements: 1 }) });
      const user = userEvent.setup();
      renderAudit();

      await findTable();
      await user.type(screen.getByLabelText("Actor id"), "not-a-uuid");
      await user.click(screen.getByRole("button", { name: "Apply filters" }));

      expect(await screen.findByText("Actor id must be a valid UUID.")).toBeInTheDocument();
      expect(apiFetchMock).toHaveBeenCalledTimes(1);
    });

    it("builds the request from the submitted filters", async () => {
      resolveWith({ kind: "ok", data: pageOf([EVENT_1], { totalElements: 1 }) });
      const user = userEvent.setup();
      renderAudit();

      await findTable();
      await user.selectOptions(screen.getByLabelText("Operation"), "LOGIN_FAILURE");
      await user.selectOptions(screen.getByLabelText("Outcome"), "FAILURE");
      await user.click(screen.getByRole("button", { name: "Apply filters" }));

      expect(apiFetchMock).toHaveBeenLastCalledWith(
        `${AUDIT_EVENTS_PATH}?operation=LOGIN_FAILURE&outcome=FAILURE&page=0&size=50`,
        {},
        expect.any(Function),
      );
    });

    it("builds the request from a resource id and a date range", async () => {
      resolveWith({ kind: "ok", data: pageOf([EVENT_1], { totalElements: 1 }) });
      const user = userEvent.setup();
      renderAudit();

      await findTable();
      await user.type(screen.getByLabelText("Resource id"), "00000000-0000-4000-8000-000000000002");
      fireEvent.change(screen.getByLabelText("From"), { target: { value: "2026-01-01T00:00" } });
      fireEvent.change(screen.getByLabelText("To"), { target: { value: "2026-01-02T00:00" } });
      await user.click(screen.getByRole("button", { name: "Apply filters" }));

      expect(apiFetchMock).toHaveBeenLastCalledWith(
        `${AUDIT_EVENTS_PATH}?resourceId=00000000-0000-4000-8000-000000000002&from=${encodeURIComponent(new Date("2026-01-01T00:00").toISOString())}&to=${encodeURIComponent(new Date("2026-01-02T00:00").toISOString())}&page=0&size=50`,
        {},
        expect.any(Function),
      );
    });
  });

  it("signs out", async () => {
    resolveWith({ kind: "ok", data: pageOf([]) });
    const user = userEvent.setup();
    renderAudit();

    await user.click(screen.getByRole("button", { name: "Sign out" }));
    expect(auth.logout).toHaveBeenCalledOnce();
  });

  it("links back to the showcase", async () => {
    resolveWith({ kind: "ok", data: pageOf([]) });
    renderAudit();

    expect(await screen.findByRole("link", { name: /showcase/i })).toHaveAttribute(
      "href",
      "/showcase",
    );
  });
});
