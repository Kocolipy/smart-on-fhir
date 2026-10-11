import { act, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { AuthContext, type AuthContextState } from "@/auth/auth-context-value";
import type { Permission } from "@/auth/api";
import { apiFetch, type ApiDecoder } from "@/lib/http";

import { Accounts } from "./accounts";
import type { GroupRow, UserRow } from "./accounts-api";

vi.mock("@/lib/http", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/http")>()),
  apiFetch: vi.fn(),
}));

// The connector panel has its own suite; here it stands in as a marker that says
// whether it was rendered and with which actions, rather than adding a third
// listing request to every test.
vi.mock("./connectors", () => ({
  Connectors: ({
    canIssueTokens,
    canManageConnectors,
  }: {
    canIssueTokens: boolean;
    canManageConnectors: boolean;
  }) => (
    <p data-testid="connectors-panel">
      {`manage=${String(canManageConnectors)} issue=${String(canIssueTokens)}`}
    </p>
  ),
}));

/** Every Permission, as a Superuser's session holds them. */
const EVERY_PERMISSION: Permission[] = [
  "audit:read",
  "connector:read",
  "connector:token",
  "connector:write",
  "counter:read",
  "counter:write",
  "group:read",
  "group:write",
  "ops:read",
  "user:read",
  "user:write",
];

const apiFetchMock = vi.mocked(apiFetch);

const auth: AuthContextState = {
  changePassword: vi.fn(),
  expireSession: vi.fn(),
  login: vi.fn(),
  logout: vi.fn(),
  signInReason: null,
  signOutForInactivity: vi.fn(),
  status: "authenticated",
  user: {
    idleTimeoutSeconds: 900,
    passwordChangeRequired: false,
    permissions: EVERY_PERMISSION,
    username: "ada",
  },
};

const GRACE_ID = "00000000-0000-4000-8000-000000000001";

/** A row; `lockCause` follows `locked` (a failure lock) unless the test names one. */
const userRow = (overrides: Partial<UserRow> = {}): UserRow => ({
  active: true,
  admin: false,
  bootstrapAdmin: false,
  createdAt: "2026-01-02T03:04:05Z",
  displayName: null,
  groups: [],
  hasPassword: true,
  id: GRACE_ID,
  lastAuthenticatedAt: null,
  locked: false,
  passwordChangeRequired: false,
  userName: "grace",
  ...overrides,
  lockCause: overrides.lockCause ?? (overrides.locked === true ? "FAILURES" : null),
});

const groupRow = (overrides: Partial<GroupRow> = {}): GroupRow => ({
  adminGroup: false,
  displayName: "Engineering",
  id: "00000000-0000-4000-8000-0000000000e0",
  memberCount: 2,
  ...overrides,
});

type Result = object | Promise<object>;

/**
 * Answers the two listing reads by path and every other request from a queue,
 * in order — so a test states what the listings hold and what each action
 * answers, not the order the page happens to issue its reads in.
 */
function routeApi({
  actions = [],
  groups = { kind: "ok", data: [] },
  users,
}: {
  actions?: Result[];
  groups?: Result;
  users: Result;
}) {
  const queue = [...actions];
  apiFetchMock.mockImplementation(((
    path: string,
    _init: RequestInit,
    decode?: ApiDecoder<unknown>,
  ) => {
    if (path === "/api/admin/accounts") return answer(users, decode);
    if (path === "/api/admin/groups") return answer(groups, decode);
    const next = queue.shift();
    if (next === undefined) throw new Error(`unexpected request to ${path}`);
    return answer(next, decode);
  }) as never);
}

/**
 * What `apiFetch` hands the page for a canned result: an `ok` result's `data`
 * is the backend's JSON body, read through the decoder the page passed, and a
 * body that decoder refuses is a plain `failed`, as `apiFetch` makes it — so a
 * request naming the wrong decoder, or none where its answer has a body, fails
 * the test rather than slipping the fixture through undecoded.
 */
async function answer(pending: Result, decode?: ApiDecoder<unknown>) {
  const result = await pending;
  if (!("kind" in result) || result.kind !== "ok") return result;
  if (decode === undefined) return { kind: "ok", data: undefined };
  try {
    return {
      kind: "ok",
      data: await decode(Response.json("data" in result ? result.data : undefined)),
    };
  } catch {
    return { kind: "failed" };
  }
}

/** Every request the page sent, as path and init — the operation it named. */
const sent = () => apiFetchMock.mock.calls.map(([path, init]) => [path, init]);

function renderAccounts(value: AuthContextState = auth) {
  return render(
    <AuthContext.Provider value={value}>
      <MemoryRouter>
        <Accounts />
      </MemoryRouter>
    </AuthContext.Provider>,
  );
}

const usersTable = () => screen.getByRole("table", { name: "Users" });
const groupsTable = () => screen.getByRole("table", { name: "Groups" });

/**
 * The row for a named User or Group, addressed by its row header. A prefix match
 * on the accessible name, written as a predicate rather than a RegExp built
 * from `name`, so no regex metacharacter in a fixture name can change it.
 */
const row = (name: string, table = usersTable()) =>
  within(
    within(table)
      .getByRole("rowheader", { name: (accessibleName) => accessibleName.startsWith(name) })
      .closest("tr")!,
  );

describe("Accounts", () => {
  beforeEach(() => {
    apiFetchMock.mockReset();
    vi.mocked(auth.expireSession).mockReset();
    vi.mocked(auth.logout).mockReset();
  });

  it("lists every User with its identity, standing, credential and history", async () => {
    routeApi({
      users: {
        kind: "ok",
        data: [
          userRow({
            displayName: "Grace Hopper",
            groups: [
              { displayName: "Engineering", id: "g1" },
              { displayName: "Operators", id: "g2" },
            ],
            lastAuthenticatedAt: "2026-03-04T05:06:07Z",
          }),
          userRow({ admin: true, id: "ada-id", userName: "ada" }),
        ],
      },
    });
    renderAccounts();

    expect(await screen.findByRole("rowheader", { name: /^grace/ })).toBeInTheDocument();
    expect(
      within(usersTable())
        .getAllByRole("columnheader")
        .map((header) => header.textContent),
    ).toEqual([
      "User",
      "Role",
      "Active",
      "Password",
      "Lockout",
      "Change required",
      "Last sign-in",
      "Created",
      "Groups",
      "Actions",
    ]);
    const grace = row("grace");
    expect(grace.getByText("Grace Hopper")).toBeInTheDocument();
    expect(grace.getByText("User")).toBeInTheDocument();
    expect(grace.getByText("Active")).toBeInTheDocument();
    expect(grace.getByText("Configured")).toBeInTheDocument();
    expect(grace.getByText("Not locked")).toBeInTheDocument();
    expect(grace.getByText("No")).toBeInTheDocument();
    expect(grace.getByText("2026-03-04 05:06")).toBeInTheDocument();
    expect(grace.getByText("2026-01-02")).toBeInTheDocument();
    expect(grace.getByText("Engineering, Operators")).toBeInTheDocument();

    expect(row("ada").getByText("Admin")).toBeInTheDocument();
    expect(sent()).toContainEqual(["/api/admin/accounts", {}]);
  });

  it("reports the states a directory and the login path can leave a User in", async () => {
    routeApi({
      users: {
        kind: "ok",
        data: [
          userRow({ active: false, hasPassword: false, userName: "closed" }),
          userRow({ id: "p", locked: true, passwordChangeRequired: true, userName: "penalised" }),
        ],
      },
    });
    renderAccounts();

    expect(await screen.findByRole("rowheader", { name: /^closed/ })).toBeInTheDocument();
    expect(row("closed").getByText("Inactive")).toBeInTheDocument();
    expect(row("closed").getByText("None")).toBeInTheDocument();
    expect(row("closed").getByText("Never")).toBeInTheDocument();
    expect(row("closed").getByText("—")).toBeInTheDocument();
    expect(row("penalised").getByText("Locked: failed logins")).toBeInTheDocument();
    expect(row("penalised").getByText("Required")).toBeInTheDocument();
  });

  it("says why each User is locked, so a forgotten password and an abandoned account differ", async () => {
    routeApi({
      users: {
        kind: "ok",
        data: [
          userRow({ id: "f", locked: true, lockCause: "FAILURES", userName: "guessed" }),
          userRow({ id: "d", locked: true, lockCause: "DORMANCY", userName: "dormant" }),
          userRow({ id: "o", userName: "open" }),
        ],
      },
    });
    renderAccounts();

    await screen.findByRole("rowheader", { name: /^dormant/ });
    expect(row("guessed").getByText("Locked: failed logins")).toBeInTheDocument();
    expect(row("guessed").queryByText("Locked: dormant")).not.toBeInTheDocument();
    expect(row("dormant").getByText("Locked: dormant")).toBeInTheDocument();
    expect(row("dormant").queryByText("Locked: failed logins")).not.toBeInTheDocument();
    expect(row("open").getByText("Not locked")).toBeInTheDocument();
  });

  it("lists every Group with its member count and marks the protected Admin group", async () => {
    routeApi({
      groups: {
        kind: "ok",
        data: [
          groupRow({ adminGroup: true, displayName: "Admins", id: "a", memberCount: 1 }),
          groupRow(),
        ],
      },
      users: { kind: "ok", data: [userRow()] },
    });
    renderAccounts();

    expect(await screen.findByRole("rowheader", { name: "Admins" })).toBeInTheDocument();
    expect(row("Admins", groupsTable()).getByText("1")).toBeInTheDocument();
    expect(row("Admins", groupsTable()).getByText("Protected Admin group")).toBeInTheDocument();
    expect(row("Engineering", groupsTable()).getByText("2")).toBeInTheDocument();
    expect(row("Engineering", groupsTable()).getByText("—")).toBeInTheDocument();
    expect(
      row("Engineering", groupsTable()).queryByText("Protected Admin group"),
    ).not.toBeInTheDocument();
    expect(sent()).toContainEqual(["/api/admin/groups", {}]);
  });

  /**
   * The read-only criterion as the page renders it: no form control anywhere in
   * either projection, and no button but the two application-owned actions —
   * nothing that could change a name, the active flag or a membership.
   */
  it("offers no control that could change what the directory owns", async () => {
    routeApi({
      groups: { kind: "ok", data: [groupRow({ adminGroup: true, displayName: "Admins" })] },
      users: {
        kind: "ok",
        data: [
          userRow({ locked: true }),
          userRow({ active: false, id: "h", userName: "hopper" }),
          userRow({ bootstrapAdmin: true, id: "r", userName: "root" }),
        ],
      },
    });
    renderAccounts();

    await screen.findByRole("rowheader", { name: /^grace/ });
    for (const table of [usersTable(), groupsTable()]) {
      expect(within(table).queryAllByRole("textbox")).toHaveLength(0);
      expect(within(table).queryAllByRole("checkbox")).toHaveLength(0);
      expect(within(table).queryAllByRole("combobox")).toHaveLength(0);
    }
    expect(within(groupsTable()).queryAllByRole("button")).toHaveLength(0);
    const labels = within(usersTable())
      .getAllByRole("button")
      .map((button) => button.textContent ?? "");
    expect(labels.length).toBeGreaterThan(0);
    expect(labels.every((label) => /^(Unlock|Force password change for) /.test(label))).toBe(true);
    expect(screen.queryByRole("button", { name: /Disable|Enable|Deactivate|Activate/ })).toBeNull();
  });

  it("says Unlock is the only way a lockout ends and that it requires a password change", async () => {
    routeApi({ users: { kind: "ok", data: [userRow({ locked: true })] } });
    renderAccounts();

    const unlock = await screen.findByRole("button", { name: "Unlock grace" });
    const explanation =
      "A lockout never expires: Unlock is the only way it ends, and Unlock also requires the User to change their password before they can do anything else.";
    expect(screen.getByText(explanation, { exact: false })).toBeInTheDocument();
    expect(unlock).toHaveAttribute("title", explanation);
    // No expiry anywhere: there is nothing to count down to.
    expect(screen.queryByText(/until|expires in|remaining/i)).not.toBeInTheDocument();
  });

  it("ends a lockout by the User's stable id and shows the change it now requires", async () => {
    routeApi({
      actions: [{ kind: "ok", data: userRow({ locked: false, passwordChangeRequired: true }) }],
      users: { kind: "ok", data: [userRow({ locked: true })] },
    });
    const user = userEvent.setup();
    renderAccounts();

    await user.click(await screen.findByRole("button", { name: "Unlock grace" }));

    expect(sent()).toContainEqual([`/api/admin/accounts/${GRACE_ID}/unlock`, { method: "POST" }]);
    expect(row("grace").getByText("Not locked")).toBeInTheDocument();
    expect(row("grace").getByText("Required")).toBeInTheDocument();
    expect(row("grace").queryByRole("button", { name: "Unlock grace" })).not.toBeInTheDocument();
  });

  it("forces a password change by the User's stable id", async () => {
    routeApi({
      actions: [{ kind: "ok", data: userRow({ passwordChangeRequired: true }) }],
      users: { kind: "ok", data: [userRow()] },
    });
    const user = userEvent.setup();
    renderAccounts();

    await user.click(
      await screen.findByRole("button", { name: "Force password change for grace" }),
    );

    expect(sent()).toContainEqual([
      `/api/admin/accounts/${GRACE_ID}/force-password-change`,
      { method: "POST" },
    ]);
    expect(row("grace").getByText("Required")).toBeInTheDocument();
    expect(
      row("grace").queryByRole("button", { name: "Force password change for grace" }),
    ).not.toBeInTheDocument();
  });

  it("offers Unlock only while a lockout is in force", async () => {
    routeApi({ users: { kind: "ok", data: [userRow()] } });
    renderAccounts();

    await screen.findByRole("rowheader", { name: /^grace/ });
    expect(row("grace").queryByRole("button", { name: "Unlock grace" })).not.toBeInTheDocument();
  });

  it("offers no forced change to a User with no password or one already flagged", async () => {
    routeApi({
      users: {
        kind: "ok",
        data: [
          userRow({ hasPassword: false, userName: "nopass" }),
          userRow({ id: "f", passwordChangeRequired: true, userName: "flagged" }),
        ],
      },
    });
    renderAccounts();

    await screen.findByRole("rowheader", { name: /^nopass/ });
    expect(within(usersTable()).queryAllByRole("button")).toHaveLength(0);
  });

  /**
   * The backend refuses both with a 403; hiding them makes the refusal visible
   * before the click. The session name is compared the way the backend compares
   * it, so a differently-cased spelling still recognises its own row.
   */
  it("offers the signed-in Admin neither action on their own account", async () => {
    routeApi({
      users: { kind: "ok", data: [userRow({ admin: true, locked: true, userName: "ADA" })] },
    });
    renderAccounts();

    await screen.findByRole("rowheader", { name: /^ADA/ });
    expect(within(usersTable()).queryAllByRole("button")).toHaveLength(0);
  });

  it("shows the Bootstrap Admin with no lockout state and no Unlock", async () => {
    routeApi({
      users: {
        kind: "ok",
        // Locked is impossible for it; even a row claiming it renders no state.
        data: [userRow({ admin: true, bootstrapAdmin: true, locked: true, userName: "root" })],
      },
    });
    renderAccounts();

    await screen.findByRole("rowheader", { name: /^root/ });
    const root = row("root");
    expect(root.getByText("Bootstrap Admin")).toBeInTheDocument();
    expect(root.getByTitle("The Bootstrap Admin cannot be locked")).toHaveTextContent("—");
    expect(root.queryByText(/^Locked/)).not.toBeInTheDocument();
    expect(root.queryByText("Not locked")).not.toBeInTheDocument();
    expect(root.queryByRole("button", { name: /Unlock/ })).not.toBeInTheDocument();
    // Only the Bootstrap Admin may force its own change, so another Admin is not offered it.
    expect(root.queryByRole("button", { name: /Force password change/ })).not.toBeInTheDocument();
  });

  it("does not offer the Bootstrap Admin a forced change on its own account", async () => {
    routeApi({
      users: { kind: "ok", data: [userRow({ bootstrapAdmin: true, userName: "root" })] },
    });
    renderAccounts({
      ...auth,
      user: {
        idleTimeoutSeconds: 900,
        passwordChangeRequired: false,
        permissions: EVERY_PERMISSION,
        username: "root",
      },
    });

    expect(await screen.findByRole("rowheader", { name: /^root/ })).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Force password change for root" }),
    ).not.toBeInTheDocument();
  });

  it("disables every control while an action is in flight", async () => {
    let finishAction: ((result: object) => void) | undefined;
    routeApi({
      actions: [
        new Promise<object>((resolve) => {
          finishAction = resolve;
        }),
      ],
      users: {
        kind: "ok",
        data: [userRow({ locked: true }), userRow({ id: "h", locked: true, userName: "hopper" })],
      },
    });
    const user = userEvent.setup();
    renderAccounts();

    await user.click(await screen.findByRole("button", { name: "Unlock grace" }));

    expect(screen.getByRole("button", { name: "Unlock grace" })).toBeDisabled();
    // The other row too: two actions in flight could each answer with a row
    // built from a listing the other one has already changed.
    expect(screen.getByRole("button", { name: "Unlock hopper" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Force password change for hopper" })).toBeDisabled();

    await act(async () => {
      finishAction?.({ kind: "ok", data: userRow() });
    });
    expect(screen.getByRole("button", { name: "Unlock hopper" })).toBeEnabled();
  });

  it("reports a forced change refused for a User with no password to replace", async () => {
    routeApi({
      actions: [{ kind: "failed", status: 409 }],
      users: { kind: "ok", data: [userRow()] },
    });
    const user = userEvent.setup();
    renderAccounts();

    await user.click(
      await screen.findByRole("button", { name: "Force password change for grace" }),
    );

    expect(screen.getByRole("alert")).toHaveTextContent(
      "Refused: grace has no password to replace.",
    );
    // The listing is untouched: the backend refused, so nothing changed.
    expect(row("grace").getByText("No")).toBeInTheDocument();
  });

  it("reports a User that has since been removed", async () => {
    routeApi({
      actions: [{ kind: "failed", status: 404 }],
      users: { kind: "ok", data: [userRow({ locked: true })] },
    });
    const user = userEvent.setup();
    renderAccounts();

    await user.click(await screen.findByRole("button", { name: "Unlock grace" }));

    expect(screen.getByRole("alert")).toHaveTextContent(
      "grace no longer exists. Reload the page for the current list.",
    );
  });

  it("reports a failed action with action-specific copy", async () => {
    routeApi({
      actions: [{ kind: "failed", status: 503 }, { kind: "failed" }],
      users: { kind: "ok", data: [userRow({ locked: true })] },
    });
    const user = userEvent.setup();
    renderAccounts();

    await user.click(await screen.findByRole("button", { name: "Unlock grace" }));
    expect(screen.getByRole("alert")).toHaveTextContent(
      "Unable to unlock grace. Please try again.",
    );

    await user.click(screen.getByRole("button", { name: "Force password change for grace" }));
    expect(screen.getByRole("alert")).toHaveTextContent(
      "Unable to force a password change for grace. Please try again.",
    );
  });

  // The page names no 400 of its own, so this pins its own `default` over the
  // hook's generic 400 copy — the status the page's action-specific sentence
  // is written for.
  it("reports a 400 refusal with the action's own copy, not the hook's generic one", async () => {
    routeApi({
      actions: [{ kind: "failed", status: 400 }],
      users: { kind: "ok", data: [userRow({ locked: true })] },
    });
    const user = userEvent.setup();
    renderAccounts();

    await user.click(await screen.findByRole("button", { name: "Unlock grace" }));

    expect(screen.getByRole("alert")).toHaveTextContent(
      "Unable to unlock grace. Please try again.",
    );
  });

  // The csrf-expired mapping is the seam's own, exercised generically by
  // use-gated-write.test.tsx; this page proves only its own copy and wiring.

  it("clears the previous error when the next action succeeds", async () => {
    routeApi({
      actions: [
        { kind: "failed", status: 503 },
        { kind: "ok", data: userRow() },
      ],
      users: { kind: "ok", data: [userRow({ locked: true })] },
    });
    const user = userEvent.setup();
    renderAccounts();

    await user.click(await screen.findByRole("button", { name: "Unlock grace" }));
    expect(screen.getByRole("alert")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Unlock grace" }));
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("reports an expired security token on a listing read with transport-specific copy", async () => {
    routeApi({ users: { kind: "csrf-expired" } });
    renderAccounts();

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Your security token expired. Please try again.",
    );
  });

  it("reports a refused action as permission denied and keeps the session", async () => {
    routeApi({
      actions: [{ kind: "forbidden" }],
      users: { kind: "ok", data: [userRow({ locked: true })] },
    });
    const user = userEvent.setup();
    renderAccounts();

    await user.click(await screen.findByRole("button", { name: "Unlock grace" }));

    expect(screen.getByRole("alert")).toHaveTextContent(/^You don't have permission to do this\.$/);
    // The refused row is left as it was, and nothing ends the session.
    expect(screen.getByRole("button", { name: "Unlock grace" })).toBeEnabled();
    expect(auth.expireSession).not.toHaveBeenCalled();
    expect(auth.logout).not.toHaveBeenCalled();
  });

  it("reports a refused listing read as permission denied and keeps the session", async () => {
    routeApi({ users: { kind: "forbidden" } });
    renderAccounts();

    expect(await screen.findByRole("alert")).toHaveTextContent(
      /^You don't have permission to do this\.$/,
    );
    expect(auth.expireSession).not.toHaveBeenCalled();
    expect(auth.logout).not.toHaveBeenCalled();
  });

  /**
   * The route guard means a signed-in user is always present, but the context
   * types it as optional; the page must not throw while it is absent, and with
   * no name to compare it recognises no row as the caller's own.
   */
  it("treats no row as the caller's own when the session names no user", async () => {
    routeApi({ users: { kind: "ok", data: [userRow({ locked: true })] } });
    renderAccounts({
      ...auth,
      user: { ...auth.user!, username: "" },
    });

    expect(await screen.findByRole("button", { name: "Unlock grace" })).toBeEnabled();
  });

  it("shows no view at all without a signed-in user, whose Permissions are unknown", () => {
    routeApi({ users: { kind: "ok", data: [userRow({ locked: true })] } });
    renderAccounts({ ...auth, user: null });

    expect(screen.queryByRole("heading", { name: "Users" })).not.toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Groups" })).not.toBeInTheDocument();
    expect(apiFetchMock).not.toHaveBeenCalled();
  });

  it("reports a failed Users read", async () => {
    routeApi({ groups: { kind: "ok", data: [] }, users: { kind: "failed", status: 503 } });
    renderAccounts();

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Unable to load the users. Please try again.",
    );
    expect(screen.queryByText("No users are provisioned.")).not.toBeInTheDocument();
    expect(screen.queryByText("Loading users…")).not.toBeInTheDocument();
  });

  it("reports a failed Groups read", async () => {
    routeApi({ groups: { kind: "failed", status: 503 }, users: { kind: "ok", data: [userRow()] } });
    renderAccounts();

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Unable to load the groups. Please try again.",
    );
    expect(screen.queryByText("No groups are provisioned.")).not.toBeInTheDocument();
    expect(screen.queryByText("Loading groups…")).not.toBeInTheDocument();
  });

  // Groups is listed first among the page's `supersedes`, so when both reads
  // fail the Groups error is the one shown.
  it("shows the Groups error, not the Users one, when both reads fail", async () => {
    routeApi({
      groups: { kind: "failed", status: 503 },
      users: { kind: "failed", status: 503 },
    });
    renderAccounts();

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Unable to load the groups. Please try again.",
    );
  });

  // Through the real transport and decoders, against a backend whose Users body
  // has drifted from the contract. The Groups listing answers a valid body, so
  // the same pipeline is shown rendering what does decode.
  it.each([
    ["a renamed field", [{ ...userRow(), userName: undefined, login: "grace" }]],
    ["null where a string was promised", [userRow({ createdAt: null as unknown as string })]],
    ["a string where a boolean was promised", [{ ...userRow(), locked: "false" }]],
    ["an object where the list was promised", { users: [userRow()] }],
  ])("reports a Users body with %s and renders none of it", async (_, usersBody) => {
    const actual = await vi.importActual<typeof import("@/lib/http")>("@/lib/http");
    apiFetchMock.mockImplementation(actual.apiFetch as never);
    vi.stubGlobal(
      "fetch",
      vi.fn((input: string) =>
        Promise.resolve(
          input === "/api/admin/accounts" ? Response.json(usersBody) : Response.json([groupRow()]),
        ),
      ),
    );
    try {
      renderAccounts();

      expect(await screen.findByRole("alert")).toHaveTextContent(
        /^Unable to load the users\. Please try again\.$/,
      );
      expect(await screen.findByRole("rowheader", { name: "Engineering" })).toBeInTheDocument();
      expect(row("Engineering", groupsTable()).getByText("2")).toBeInTheDocument();
      expect(screen.queryByText("grace")).not.toBeInTheDocument();
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it("reports a Users body that is not JSON at all, as a proxy's error page", async () => {
    const actual = await vi.importActual<typeof import("@/lib/http")>("@/lib/http");
    apiFetchMock.mockImplementation(actual.apiFetch as never);
    vi.stubGlobal(
      "fetch",
      vi.fn((input: string) =>
        Promise.resolve(
          input === "/api/admin/accounts"
            ? new Response("<html><body>502 Bad Gateway</body></html>", {
                headers: { "Content-Type": "text/html" },
              })
            : Response.json([groupRow()]),
        ),
      ),
    );
    try {
      renderAccounts();

      expect(await screen.findByRole("alert")).toHaveTextContent(
        /^Unable to load the users\. Please try again\.$/,
      );
      expect(screen.queryByText(/Bad Gateway/)).not.toBeInTheDocument();
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it("says so when no User or Group is provisioned", async () => {
    routeApi({ users: { kind: "ok", data: [] } });
    renderAccounts();

    expect(await screen.findByText("No users are provisioned.")).toBeInTheDocument();
    expect(await screen.findByText("No groups are provisioned.")).toBeInTheDocument();
  });

  it("expires the auth state when the listing read is unauthenticated", async () => {
    let finishLoading: ((result: object) => void) | undefined;
    routeApi({
      users: new Promise<object>((resolve) => {
        finishLoading = resolve;
      }),
    });
    renderAccounts();

    expect(screen.getByText("Loading users…")).toBeInTheDocument();
    expect(screen.getByText("Loading groups…")).toBeInTheDocument();

    await act(async () => {
      finishLoading?.({ kind: "unauthenticated" });
    });

    // The page never renders this case: the session seam ends the session and
    // the route guard replaces this page on the same update.
    expect(auth.expireSession).toHaveBeenCalledOnce();
  });
});

describe("Accounts by Permission", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  /** The page as a session holding exactly these Permissions sees it. */
  const holding = (permissions: Permission[]): AuthContextState => ({
    ...auth,
    user: { idleTimeoutSeconds: 900, passwordChangeRequired: false, permissions, username: "ada" },
  });

  /** Every request the page issued, by path. */
  const requested = () => apiFetchMock.mock.calls.map(([path]) => path);

  it("shows an Account admin the Users and Groups and no connector panel", async () => {
    routeApi({ users: { kind: "ok", data: [userRow({ locked: true, userName: "grace" })] } });
    renderAccounts(holding(["group:read", "user:read", "user:write"]));

    expect(await screen.findByRole("table", { name: "Users" })).toBeInTheDocument();
    expect(await screen.findByRole("heading", { name: "Groups" })).toBeInTheDocument();
    expect(screen.queryByTestId("connectors-panel")).not.toBeInTheDocument();
    // The actions its user:write allows.
    expect(row("grace").getByRole("button", { name: "Unlock grace" })).toBeEnabled();
  });

  it("offers no Unlock or forced change without user:write", async () => {
    routeApi({ users: { kind: "ok", data: [userRow({ locked: true, userName: "grace" })] } });
    renderAccounts(holding(["user:read"]));

    expect(await screen.findByRole("rowheader", { name: /^grace/ })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Unlock/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Force password change/ })).not.toBeInTheDocument();
  });

  it("neither shows nor requests a view the session lacks the Permission for", async () => {
    routeApi({ users: { kind: "ok", data: [] } });
    renderAccounts(holding(["group:read"]));

    expect(await screen.findByRole("heading", { name: "Groups" })).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Users" })).not.toBeInTheDocument();
    expect(requested()).toEqual(["/api/admin/groups"]);
  });

  it("shows only the connector panel to a connector reader, with neither action", () => {
    routeApi({ users: { kind: "ok", data: [] } });
    renderAccounts(holding(["connector:read"]));

    expect(screen.getByTestId("connectors-panel")).toHaveTextContent("manage=false issue=false");
    expect(screen.queryByRole("heading", { name: "Users" })).not.toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Groups" })).not.toBeInTheDocument();
    expect(requested()).toEqual([]);
  });

  it("passes the connector panel each of its actions by its own Permission", () => {
    routeApi({ users: { kind: "ok", data: [] } });
    const { unmount } = renderAccounts(holding(["connector:read", "connector:write"]));
    expect(screen.getByTestId("connectors-panel")).toHaveTextContent("manage=true issue=false");
    unmount();

    renderAccounts(holding(["connector:read", "connector:token"]));
    expect(screen.getByTestId("connectors-panel")).toHaveTextContent("manage=false issue=true");
  });
});
