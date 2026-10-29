import { fireEvent, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";

import { EVERY_PERMISSION } from "@/auth/auth-context.testHelpers";
import {
  bare,
  deferred,
  GUEST,
  holding,
  json,
  UNREACHABLE,
  type Answers,
  type StartingSession,
} from "@/auth/session-adapter.testHelpers";

import type { GroupMember, GroupRow, UserRow } from "./accounts-api";
import { renderSignedIn } from "./signed-in-shell.testHelpers";

const USERS = "GET /api/admin/accounts?startIndex=1&count=20";
const GROUPS = "GET /api/admin/groups?startIndex=1&count=20";
const CONNECTORS = "GET /api/admin/connectors";

/** A Superuser's session, which sees every listing and every action on the page. */
const SUPERUSER = holding(EVERY_PERMISSION);

const GRACE_ID = "00000000-0000-4000-8000-000000000001";
const ALICE_ID = "00000000-0000-4000-8000-0000000000a1";

/** A row; `lockCause` follows `locked` (a failure lock) unless the test names one. */
const userRow = (overrides: Partial<UserRow> = {}): UserRow => ({
  active: true,
  admin: false,
  bootstrapAdmin: false,
  createdAt: "2026-01-02T19:04:05Z",
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
  permissions: [],
  ...overrides,
});

/**
 * A page of the Users projection as the backend answers it: these rows, from
 * `startIndex`, of `totalResults` in all — just these rows unless a test says so.
 */
const usersPage = (users: readonly object[], totalResults = users.length, startIndex = 1) =>
  json({ startIndex, totalResults, users });

/** A page of the Groups projection, as {@link usersPage} is one of Users. */
const groupsPage = (groups: readonly object[], totalResults = groups.length, startIndex = 1) =>
  json({ groups, startIndex, totalResults });

const unlockGrace = `POST /api/admin/accounts/${GRACE_ID}/unlock`;

/**
 * The page as `session` sees it, inside the signed-in layout that renders its
 * one error line — every listing it may read answered empty unless `answers`
 * says otherwise, the Connectors panel's included, since the page renders it
 * for real.
 */
const renderAccounts = (answers: Answers, session: StartingSession = SUPERUSER) =>
  renderSignedIn("/accounts", session, {
    [CONNECTORS]: json([]),
    [GROUPS]: groupsPage([]),
    [USERS]: usersPage([]),
    ...answers,
  });

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
  it("lists every User with its identity, standing, credential and history", async () => {
    const { requests } = await renderAccounts({
      [USERS]: usersPage([
        userRow({
          displayName: "Grace Hopper",
          groups: [
            { displayName: "Engineering", id: "g1" },
            { displayName: "Operators", id: "g2" },
          ],
          lastAuthenticatedAt: "2026-03-04T17:06:07Z",
        }),
        userRow({ admin: true, id: "ada-id", userName: "ada" }),
      ]),
    });

    expect(await screen.findByRole("rowheader", { name: /^grace/ })).toBeInTheDocument();
    expect(
      within(usersTable())
        .getAllByRole("columnheader")
        .map((header) => header.textContent),
    ).toEqual([
      "User",
      "Groups",
      "Active",
      "Lockout",
      "Password",
      "Password change required",
      "Last sign-in",
      "Actions",
    ]);
    const grace = row("grace");
    expect(grace.getByText("Grace Hopper")).toBeInTheDocument();
    expect(
      grace
        .getAllByRole("cell")
        .slice(0, -1)
        .map((cell) => cell.textContent),
    ).toEqual([
      "Engineering, Operators",
      "Active",
      "Not locked",
      "Configured",
      "No",
      "2026-03-05 01:06",
    ]);

    expect(requests()).toContainEqual({
      method: "GET",
      path: "/api/admin/accounts?startIndex=1&count=20",
    });
  });

  it("reports the states a directory and the login path can leave a User in", async () => {
    await renderAccounts({
      [USERS]: usersPage([
        userRow({ active: false, hasPassword: false, userName: "closed" }),
        userRow({ id: "p", locked: true, passwordChangeRequired: true, userName: "penalised" }),
      ]),
    });

    expect(await screen.findByRole("rowheader", { name: /^closed/ })).toBeInTheDocument();
    expect(row("closed").getByText("Inactive")).toBeInTheDocument();
    expect(row("closed").getByText("None")).toBeInTheDocument();
    expect(row("closed").getByText("Never")).toBeInTheDocument();
    expect(row("closed").getByText("—")).toBeInTheDocument();
    expect(row("penalised").getByText("Locked: failed logins")).toBeInTheDocument();
    expect(row("penalised").getByText("Required")).toBeInTheDocument();
  });

  it("says why each User is locked, so a forgotten password and an abandoned account differ", async () => {
    await renderAccounts({
      [USERS]: usersPage([
        userRow({ id: "f", locked: true, lockCause: "FAILURES", userName: "guessed" }),
        userRow({ id: "d", locked: true, lockCause: "DORMANCY", userName: "dormant" }),
        userRow({ id: "o", userName: "open" }),
      ]),
    });

    await screen.findByRole("rowheader", { name: /^dormant/ });
    expect(row("guessed").getByText("Locked: failed logins")).toBeInTheDocument();
    expect(row("guessed").queryByText("Locked: dormant")).not.toBeInTheDocument();
    expect(row("dormant").getByText("Locked: dormant")).toBeInTheDocument();
    expect(row("dormant").queryByText("Locked: failed logins")).not.toBeInTheDocument();
    expect(row("open").getByText("Not locked")).toBeInTheDocument();
  });

  it("lists every Group with its member count and the Permissions it confers", async () => {
    const { requests } = await renderAccounts({
      [GROUPS]: groupsPage([
        groupRow({ adminGroup: true, displayName: "Admins", id: "a", memberCount: 1 }),
        groupRow({ displayName: "Auditors", id: "b", permissions: ["audit:read", "user:read"] }),
        groupRow(),
      ]),
      [USERS]: usersPage([userRow()]),
    });

    expect(await screen.findByRole("rowheader", { name: "Admins" })).toBeInTheDocument();
    expect(
      within(groupsTable()).getByRole("columnheader", { name: "Permissions" }),
    ).toBeInTheDocument();
    expect(within(groupsTable()).queryByRole("columnheader", { name: "Authority" })).toBeNull();
    expect(row("Admins", groupsTable()).getByText("1")).toBeInTheDocument();
    expect(row("Auditors", groupsTable()).getByText("audit:read, user:read")).toBeInTheDocument();
    expect(row("Engineering", groupsTable()).getByText("2")).toBeInTheDocument();
    expect(row("Engineering", groupsTable()).getByText("—")).toBeInTheDocument();
    expect(requests()).toContainEqual({
      method: "GET",
      path: "/api/admin/groups?startIndex=1&count=20",
    });
  });

  it("shows the Admin group as conferring the Superuser, whatever Permissions it reports", async () => {
    await renderAccounts({
      [GROUPS]: groupsPage([
        groupRow({ adminGroup: true, displayName: "Admins", id: "a" }),
        groupRow({ displayName: "Superusers", id: "b", permissions: ["ops:read"] }),
      ]),
      [USERS]: usersPage([userRow()]),
    });

    expect(await screen.findByRole("rowheader", { name: "Admins" })).toBeInTheDocument();
    expect(row("Admins", groupsTable()).getByText("Superuser")).toBeInTheDocument();
    expect(row("Superusers", groupsTable()).queryByText("Superuser")).toBeNull();
    expect(row("Superusers", groupsTable()).getByText("ops:read")).toBeInTheDocument();
  });

  /**
   * The read-only criterion as the page renders it: no form control anywhere in
   * either projection, and no button but the two application-owned actions —
   * nothing that could change a name, the active flag or a membership.
   */
  it("offers no control that could change what the directory owns", async () => {
    await renderAccounts({
      [GROUPS]: groupsPage([groupRow({ adminGroup: true, displayName: "Admins" })]),
      [USERS]: usersPage([
        userRow({ locked: true }),
        userRow({ active: false, id: "h", userName: "hopper" }),
        userRow({ bootstrapAdmin: true, id: "r", userName: "root" }),
      ]),
    });

    await screen.findByRole("rowheader", { name: /^grace/ });
    for (const table of [usersTable(), groupsTable()]) {
      expect(within(table).queryAllByRole("textbox")).toHaveLength(0);
      expect(within(table).queryAllByRole("checkbox")).toHaveLength(0);
      expect(within(table).queryAllByRole("combobox")).toHaveLength(0);
    }
    // The Groups table's only buttons disclose a Group's members, which change nothing.
    expect(
      within(groupsTable())
        .queryAllByRole("button")
        .map((button) => button.getAttribute("aria-expanded")),
    ).toEqual(["false"]);
    const labels = within(usersTable())
      .getAllByRole("button")
      .map((button) => button.textContent ?? "");
    expect(labels.length).toBeGreaterThan(0);
    expect(labels.every((label) => /^Unlock /.test(label))).toBe(true);
    expect(screen.queryByRole("button", { name: /Disable|Enable|Deactivate|Activate/ })).toBeNull();
  });

  it("says Unlock is the only way a lockout ends and that it requires a password change", async () => {
    await renderAccounts({ [USERS]: usersPage([userRow({ locked: true })]) });

    const unlock = await screen.findByRole("button", { name: "Unlock grace" });
    const explanation =
      "A lockout never expires: Unlock is the only way it ends, and Unlock also requires the User to change their password before they can do anything else.";
    expect(screen.getByText(explanation, { exact: false })).toBeInTheDocument();
    fireEvent.focus(unlock);
    expect(await screen.findByRole("tooltip")).toHaveTextContent(explanation);
    // No expiry anywhere: there is nothing to count down to.
    expect(screen.queryByText(/until|expires in|remaining/i)).not.toBeInTheDocument();
  });

  it("ends a lockout by the User's stable id and shows the change it now requires", async () => {
    const { requests } = await renderAccounts({
      [unlockGrace]: json(userRow({ locked: false, passwordChangeRequired: true })),
      [USERS]: usersPage([userRow({ locked: true })]),
    });
    const user = userEvent.setup();

    await user.click(await screen.findByRole("button", { name: "Unlock grace" }));

    expect(requests()).toContainEqual({
      method: "POST",
      path: `/api/admin/accounts/${GRACE_ID}/unlock`,
    });
    expect(row("grace").getByText("Not locked")).toBeInTheDocument();
    expect(row("grace").getByText("Required")).toBeInTheDocument();
    expect(row("grace").queryByRole("button", { name: "Unlock grace" })).not.toBeInTheDocument();
  });

  // The answer's row replaces the listed one only once it decodes: a row whose
  // lock state contradicts itself is a failure, and the listing stands.
  it("reports an action answered with a row that does not decode, and keeps the row", async () => {
    await renderAccounts({
      [unlockGrace]: json({ ...userRow(), lockCause: "DORMANCY" }),
      [USERS]: usersPage([userRow({ locked: true })]),
    });
    const user = userEvent.setup();

    await user.click(await screen.findByRole("button", { name: "Unlock grace" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      /^Unable to unlock grace\. Please try again\.$/,
    );
    expect(row("grace").getByText("Locked: failed logins")).toBeInTheDocument();
  });

  it("offers Unlock only while a lockout is in force", async () => {
    await renderAccounts({ [USERS]: usersPage([userRow()]) });

    await screen.findByRole("rowheader", { name: /^grace/ });
    expect(row("grace").queryByRole("button", { name: "Unlock grace" })).not.toBeInTheDocument();
  });

  // `account:unlock` has no effect on an unlocked User (ADR 0015): no row that is not locked
  // offers anything, whatever its credential.
  it("offers no action on an unlocked User, whatever its credential", async () => {
    await renderAccounts({
      [USERS]: usersPage([
        userRow({ userName: "settled" }),
        userRow({ hasPassword: false, id: "n", userName: "nopass" }),
        userRow({ id: "f", passwordChangeRequired: true, userName: "flagged" }),
      ]),
    });

    await screen.findByRole("rowheader", { name: /^settled/ });
    expect(within(usersTable()).queryAllByRole("button")).toHaveLength(0);
  });

  it("offers Unlock alone on a locked User", async () => {
    await renderAccounts({ [USERS]: usersPage([userRow({ locked: true })]) });

    await screen.findByRole("rowheader", { name: /^grace/ });
    expect(
      row("grace")
        .getAllByRole("button")
        .map((button) => button.textContent),
    ).toEqual(["Unlock grace"]);
  });

  /**
   * The backend refuses Unlock of the caller's own account with a 403, deciding
   * "own" by stable id; hiding it makes the refusal visible before the click.
   * The page decides the same way, so a session whose User a connector renamed
   * after sign-in still recognises its own row.
   */
  it("offers the signed-in Admin no action on their own account after a rename", async () => {
    await renderAccounts(
      {
        [USERS]: usersPage([
          userRow({ admin: true, id: ALICE_ID, locked: true, userName: "alice.tan" }),
        ]),
      },
      holding(EVERY_PERMISSION, { id: ALICE_ID, username: "alice" }),
    );

    await screen.findByRole("rowheader", { name: /^alice\.tan/ });
    expect(within(usersTable()).queryAllByRole("button")).toHaveLength(0);
  });

  /**
   * A User a connector gave the session's name is still another User: the
   * backend allows Unlock on them, so the page offers it.
   */
  it("offers Unlock on another User who now holds the session's name", async () => {
    await renderAccounts(
      { [USERS]: usersPage([userRow({ locked: true, userName: "alice" })]) },
      holding(EVERY_PERMISSION, { id: ALICE_ID, username: "alice" }),
    );

    expect(await screen.findByRole("button", { name: "Unlock alice" })).toBeEnabled();
  });

  it("shows the Bootstrap Admin with no lockout state and no Unlock", async () => {
    await renderAccounts({
      // Locked is impossible for it; even a row claiming it renders no state.
      [USERS]: usersPage([
        userRow({ admin: true, bootstrapAdmin: true, locked: true, userName: "root" }),
      ]),
    });

    await screen.findByRole("rowheader", { name: /^root/ });
    const root = row("root");
    // USER_COLUMNS, less the rowheader: Groups, Active, Lockout, …
    const lockoutCell = root.getAllByRole("cell")[2];
    fireEvent.focus(within(lockoutCell).getByText("—"));
    expect(await screen.findByRole("tooltip")).toHaveTextContent(
      "The Bootstrap Admin cannot be locked",
    );
    expect(root.queryByText(/^Locked/)).not.toBeInTheDocument();
    expect(root.queryByText("Not locked")).not.toBeInTheDocument();
    expect(root.queryAllByRole("button")).toHaveLength(0);
  });

  it("disables every control while an action is in flight", async () => {
    const unlocking = deferred();
    await renderAccounts({
      [unlockGrace]: unlocking.answer,
      [USERS]: usersPage([
        userRow({ locked: true }),
        userRow({ id: "h", locked: true, userName: "hopper" }),
      ]),
    });
    const user = userEvent.setup();

    await user.click(await screen.findByRole("button", { name: "Unlock grace" }));

    expect(screen.getByRole("button", { name: "Unlock grace" })).toBeDisabled();
    // The other row too: two actions in flight could each answer with a row
    // built from a listing the other one has already changed.
    expect(screen.getByRole("button", { name: "Unlock hopper" })).toBeDisabled();

    await unlocking.settle(json(userRow()));
    expect(screen.getByRole("button", { name: "Unlock hopper" })).toBeEnabled();
  });

  // The backend refuses an Unlock of a User serving no lockout with 409 —
  // most often another administrator unlocked it after this listing was read.
  it("reports an Unlock refused because the User is no longer locked", async () => {
    await renderAccounts({
      [unlockGrace]: json({ code: "request-refused", status: 409 }, 409),
      [USERS]: usersPage([userRow({ locked: true })]),
    });
    const user = userEvent.setup();

    await user.click(await screen.findByRole("button", { name: "Unlock grace" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      /^grace is no longer locked\. Reload the page for the current list\.$/,
    );
    // The listing is untouched: the backend refused, so nothing changed here.
    expect(row("grace").getByText("Locked: failed logins")).toBeInTheDocument();
  });

  it("reports a User that has since been removed", async () => {
    await renderAccounts({
      [unlockGrace]: bare(404),
      [USERS]: usersPage([userRow({ locked: true })]),
    });
    const user = userEvent.setup();

    await user.click(await screen.findByRole("button", { name: "Unlock grace" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "grace no longer exists. Reload the page for the current list.",
    );
  });

  it("reports a failed action with action-specific copy", async () => {
    await renderAccounts({
      [unlockGrace]: UNREACHABLE,
      [USERS]: usersPage([userRow({ locked: true })]),
    });
    const user = userEvent.setup();

    await user.click(await screen.findByRole("button", { name: "Unlock grace" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Unable to unlock grace. Please try again.",
    );
  });

  // The page names no 400 of its own, so this pins its own `default` over the
  // hook's generic 400 copy — the status the page's action-specific sentence
  // is written for.
  it("reports a 400 refusal with the action's own copy, not the hook's generic one", async () => {
    await renderAccounts({
      [unlockGrace]: bare(400),
      [USERS]: usersPage([userRow({ locked: true })]),
    });
    const user = userEvent.setup();

    await user.click(await screen.findByRole("button", { name: "Unlock grace" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Unable to unlock grace. Please try again.",
    );
  });

  // The csrf-expired mapping is the seam's own, exercised generically by
  // use-gated-write.test.tsx; this page proves only its own copy and wiring.

  it("clears the previous error when the next action succeeds", async () => {
    await renderAccounts({
      [unlockGrace]: [bare(503), json(userRow())],
      [USERS]: usersPage([userRow({ locked: true })]),
    });
    const user = userEvent.setup();

    await user.click(await screen.findByRole("button", { name: "Unlock grace" }));
    expect(await screen.findByRole("alert")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Unlock grace" }));
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("reports a refused action as permission denied and keeps the session", async () => {
    const { requests, session } = await renderAccounts({
      [unlockGrace]: bare(403),
      [USERS]: usersPage([userRow({ locked: true })]),
    });
    const user = userEvent.setup();

    await user.click(await screen.findByRole("button", { name: "Unlock grace" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      /^You don't have permission to do this\.$/,
    );
    // The refused row is left as it was, and nothing ends the session.
    expect(screen.getByRole("button", { name: "Unlock grace" })).toBeEnabled();
    expect(session().status).toBe("authenticated");
    expect(requests()).not.toContainEqual(expect.objectContaining({ path: "/api/auth/logout" }));
  });

  it("shows no view at all without a signed-in user, whose Permissions are unknown", async () => {
    const { requests } = await renderAccounts({}, GUEST);

    expect(screen.queryByRole("heading", { name: "Users" })).not.toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Groups" })).not.toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Connectors" })).not.toBeInTheDocument();
    expect(requests()).toEqual([]);
  });

  it("reports a failed Users read", async () => {
    await renderAccounts({ [USERS]: bare(503) });

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Unable to load the users. Please try again.",
    );
    expect(screen.queryByText("No users are provisioned.")).not.toBeInTheDocument();
    expect(screen.queryByText("Loading users…")).not.toBeInTheDocument();
  });

  it("reports a failed Groups read", async () => {
    await renderAccounts({ [GROUPS]: bare(503), [USERS]: usersPage([userRow()]) });

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Unable to load the groups. Please try again.",
    );
    expect(screen.queryByText("No groups are provisioned.")).not.toBeInTheDocument();
    expect(screen.queryByText("Loading groups…")).not.toBeInTheDocument();
  });

  // The error line shows the latest failure, so when both reads fail the one
  // that landed last is the one shown, whichever listing it was.
  it.each([
    ["Users", USERS, GROUPS, "Unable to load the users. Please try again."],
    ["Groups", GROUPS, USERS, "Unable to load the groups. Please try again."],
  ] as const)(
    "shows the %s failure when both reads fail and it lands last",
    async (_, last, first, copy) => {
      const lastFailure = deferred();
      await renderAccounts({ [first]: bare(503), [last]: lastFailure.answer });
      await screen.findByRole("alert");

      await lastFailure.settle(bare(503));

      expect(screen.getByRole("alert").textContent).toBe(copy);
    },
  );

  // Against a backend whose Users body has drifted from the contract. The
  // Groups listing answers a valid body, so the same pipeline is shown
  // rendering what does decode. Which drifts the decoder refuses is
  // accounts-api.test.ts's and decode.test.ts's to say.
  it("reports a Users body with a renamed field and renders none of it", async () => {
    await renderAccounts({
      [GROUPS]: groupsPage([groupRow()]),
      [USERS]: usersPage([{ ...userRow(), userName: undefined, login: "grace" }]),
    });

    expect(await screen.findByRole("alert")).toHaveTextContent(
      /^Unable to load the users\. Please try again\.$/,
    );
    expect(await screen.findByRole("rowheader", { name: "Engineering" })).toBeInTheDocument();
    expect(row("Engineering", groupsTable()).getByText("2")).toBeInTheDocument();
    expect(screen.queryByText("grace")).not.toBeInTheDocument();
  });

  it("says so when no User or Group is provisioned", async () => {
    await renderAccounts({});

    expect(await screen.findByText("No users are provisioned.")).toBeInTheDocument();
    expect(await screen.findByText("No groups are provisioned.")).toBeInTheDocument();
  });

  it("expires the auth state when the listing read is unauthenticated", async () => {
    const loading = deferred();
    const { session } = await renderAccounts({
      [GROUPS]: deferred().answer,
      [USERS]: loading.answer,
    });

    expect(screen.getByText("Loading users…")).toBeInTheDocument();
    expect(screen.getByText("Loading groups…")).toBeInTheDocument();

    await loading.settle(bare(401));

    // The page never renders this case: the session seam ends the session and
    // the route guard replaces this page on the same update.
    expect(session()).toEqual({ signInReason: "expired", status: "guest" });
  });
});

/**
 * The page's single error line with every panel present: the Connectors panel
 * reports to the layout's page status as the page does, so a failure of its
 * read and one of another listing share one line, where the latest shows.
 */
describe("Accounts with its Connectors panel", () => {
  it.each([
    ["Connectors", USERS, CONNECTORS, "Unable to load the connectors. Please try again."],
    ["Users", CONNECTORS, USERS, "Unable to load the users. Please try again."],
  ] as const)(
    "shows one error line, the %s failure, when it lands after the other",
    async (_, first, last, copy) => {
      const firstFailure = deferred();
      const lastFailure = deferred();
      await renderAccounts({ [first]: firstFailure.answer, [last]: lastFailure.answer });

      await firstFailure.settle(bare(503));
      await lastFailure.settle(bare(503));

      expect(screen.getAllByRole("alert").map((line) => line.textContent)).toEqual([copy]);
    },
  );

  it("withdraws the Connectors failure from the line once an action starts", async () => {
    await renderAccounts({
      [CONNECTORS]: bare(503),
      [unlockGrace]: deferred().answer,
      [USERS]: usersPage([userRow({ locked: true })]),
    });
    const user = userEvent.setup();
    expect(await screen.findByRole("alert")).toHaveTextContent(/connectors/);

    await user.click(await screen.findByRole("button", { name: "Unlock grace" }));

    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });
});

/** The read of one page of a projection, from `startIndex`, of the rows matching `search`. */
const pageOf = (path: "accounts" | "groups", startIndex: number, search?: string) =>
  `GET /api/admin/${path}?startIndex=${startIndex}&count=20` +
  (search === undefined ? "" : `&search=${encodeURIComponent(search)}`);

/** `count` Users, `user-01` onwards from `first`, each with an id of its own. */
const usersFrom = (first: number, count: number) =>
  Array.from({ length: count }, (_, index) =>
    userRow({
      id: `user-${first + index}`,
      userName: `user-${String(first + index).padStart(2, "0")}`,
    }),
  );

/** `count` Groups, `group-01` onwards from `first`. */
const groupsFrom = (first: number, count: number) =>
  Array.from({ length: count }, (_, index) =>
    groupRow({
      displayName: `group-${String(first + index).padStart(2, "0")}`,
      id: `group-${first + index}`,
    }),
  );

const rowHeaders = (table: HTMLElement) =>
  within(table)
    .getAllByRole("rowheader")
    .map((header) => header.textContent);

const searchFor = async (card: "users" | "groups", text: string) => {
  const box = screen.getByRole("searchbox", { name: `Search ${card}` });
  await userEvent.clear(box);
  if (text !== "") await userEvent.type(box, text);
  await userEvent.click(
    within(
      screen.getByRole("search", { name: `${card === "users" ? "Users" : "Groups"} search` }),
    ).getByRole("button", { name: "Search" }),
  );
};

describe("the Users and Groups projections, a page at a time", () => {
  it("show the Users one page at a time with the total, moving on while more remain and back again", async () => {
    const { requests } = await renderAccounts({
      [pageOf("accounts", 1)]: usersPage(usersFrom(1, 20), 25),
      [pageOf("accounts", 21)]: usersPage(usersFrom(21, 5), 25, 21),
    });
    expect(await screen.findByText("Users 1–20 of 25")).toBeInTheDocument();
    // Re-read on every use: a new page is a new pager.
    const users = () => screen.getByRole("navigation", { name: "Users pages" });
    expect(within(users()).queryByRole("button", { name: "Previous" })).toBeNull();

    await userEvent.click(within(users()).getByRole("button", { name: "Next" }));

    expect(await screen.findByText("Users 21–25 of 25")).toBeInTheDocument();
    expect(rowHeaders(usersTable())).toEqual([
      "user-21",
      "user-22",
      "user-23",
      "user-24",
      "user-25",
    ]);
    expect(within(users()).queryByRole("button", { name: "Next" })).toBeNull();
    expect(requests()).toContainEqual({ method: "GET", path: pageOf("accounts", 21).slice(4) });

    await userEvent.click(within(users()).getByRole("button", { name: "Previous" }));
    expect(await screen.findByText("Users 1–20 of 25")).toBeInTheDocument();
  });

  it("show the Groups one page at a time with the total", async () => {
    await renderAccounts({
      [pageOf("groups", 1)]: groupsPage(groupsFrom(1, 20), 21),
      [pageOf("groups", 21)]: groupsPage(groupsFrom(21, 1), 21, 21),
    });
    expect(await screen.findByText("Groups 1–20 of 21")).toBeInTheDocument();

    await userEvent.click(
      within(screen.getByRole("navigation", { name: "Groups pages" })).getByRole("button", {
        name: "Next",
      }),
    );

    expect(await screen.findByText("Groups 21–21 of 21")).toBeInTheDocument();
    expect(rowHeaders(groupsTable())).toEqual(["group-21"]);
  });

  it("search the Users by name from the first page, whichever page was shown", async () => {
    const { requests } = await renderAccounts({
      [pageOf("accounts", 1)]: usersPage(usersFrom(1, 20), 25),
      [pageOf("accounts", 21)]: usersPage(usersFrom(21, 5), 25, 21),
      [pageOf("accounts", 1, "grace hopper")]: usersPage([
        userRow({ displayName: "Grace Hopper" }),
      ]),
    });
    await userEvent.click(await screen.findByRole("button", { name: "Next" }));
    await screen.findByText("Users 21–25 of 25");

    await searchFor("users", "  grace hopper ");

    expect(await screen.findByText("Users 1–1 of 1")).toBeInTheDocument();
    expect(rowHeaders(usersTable())).toEqual(["graceGrace Hopper"]);
    expect(requests()).toContainEqual({
      method: "GET",
      path: pageOf("accounts", 1, "grace hopper").slice(4),
    });
  });

  it("search the Groups by name, and an emptied search lists every Group again", async () => {
    await renderAccounts({
      [pageOf("groups", 1)]: groupsPage(groupsFrom(1, 2)),
      [pageOf("groups", 1, "nurse")]: groupsPage([groupRow({ displayName: "Nurses" })]),
    });
    await screen.findByText("Groups 1–2 of 2");

    await searchFor("groups", "nurse");
    expect(await screen.findByRole("rowheader", { name: "Nurses" })).toBeInTheDocument();

    await searchFor("groups", "");
    expect(await screen.findByText("Groups 1–2 of 2")).toBeInTheDocument();
    expect(rowHeaders(groupsTable())).toEqual(["group-01", "group-02"]);
  });

  it("say when a search matches nothing, which is not the same as none being provisioned", async () => {
    await renderAccounts({
      [pageOf("accounts", 1, "zz")]: usersPage([]),
      [pageOf("groups", 1, "zz")]: groupsPage([]),
    });
    expect(await screen.findByText("No users are provisioned.")).toBeInTheDocument();
    expect(await screen.findByText("No groups are provisioned.")).toBeInTheDocument();

    await searchFor("users", "zz");
    await searchFor("groups", "zz");

    expect(await screen.findByText('No users match "zz".')).toBeInTheDocument();
    expect(await screen.findByText('No groups match "zz".')).toBeInTheDocument();
    expect(screen.queryByText(/are provisioned/)).toBeNull();
    expect(screen.queryByRole("navigation", { name: /pages$/ })).toBeNull();
  });

  it("replace an unlocked User's row on a later page from the answer, reading nothing again", async () => {
    const later = [...usersFrom(21, 2), userRow({ locked: true })];
    const { requests } = await renderAccounts({
      [pageOf("accounts", 1)]: usersPage(usersFrom(1, 20), 23),
      [pageOf("accounts", 21)]: usersPage(later, 23, 21),
      [unlockGrace]: json(userRow({ passwordChangeRequired: true })),
    });
    await userEvent.click(await screen.findByRole("button", { name: "Next" }));

    await userEvent.click(await screen.findByRole("button", { name: "Unlock grace" }));

    expect(await row("grace").findByText("Not locked")).toBeInTheDocument();
    expect(row("grace").getByText("Required")).toBeInTheDocument();
    expect(screen.getByText("Users 21–23 of 23")).toBeInTheDocument();
    expect(rowHeaders(usersTable())).toEqual(["user-21", "user-22", "grace"]);
    expect(
      requests()
        .filter(({ path }) => path.startsWith("/api/admin/accounts?"))
        .map(({ path }) => path),
    ).toEqual([pageOf("accounts", 1).slice(4), pageOf("accounts", 21).slice(4)]);
  });

  /**
   * An action's answer replaces its row on the page shown, so the page and
   * the search hold still until it lands: moving off the page in between would
   * leave the answer nowhere to go.
   */
  it("hold the page and the search still while an action is in flight", async () => {
    const unlocking = deferred();
    await renderAccounts({
      [pageOf("accounts", 1)]: usersPage([userRow({ locked: true }), ...usersFrom(2, 19)], 21),
      [unlockGrace]: unlocking.answer,
    });
    const box = await screen.findByRole("searchbox", { name: "Search users" });
    const controls = () => [
      box,
      within(screen.getByRole("search", { name: "Users search" })).getByRole("button"),
      within(screen.getByRole("navigation", { name: "Users pages" })).getByRole("button", {
        name: "Next",
      }),
    ];
    expect(box).toHaveValue("");
    expect(controls().map((control) => (control as HTMLButtonElement).disabled)).toEqual([
      false,
      false,
      false,
    ]);

    await userEvent.click(await screen.findByRole("button", { name: "Unlock grace" }));

    expect(controls().map((control) => (control as HTMLButtonElement).disabled)).toEqual([
      true,
      true,
      true,
    ]);
    await unlocking.settle(json(userRow()));
    expect(controls().map((control) => (control as HTMLButtonElement).disabled)).toEqual([
      false,
      false,
      false,
    ]);
  });

  it("replace a Group's row on a later page from a Permissions save's answer", async () => {
    const auditors = groupRow({ displayName: "Auditors", id: "auditors" });
    await renderAccounts(
      {
        [pageOf("groups", 1)]: groupsPage(groupsFrom(1, 20), 22),
        [pageOf("groups", 21)]: groupsPage([...groupsFrom(21, 1), auditors], 22, 21),
        "GET /api/admin/permissions": json(["audit:read", "user:read"]),
        "PUT /api/admin/groups/auditors/permissions": json({
          ...auditors,
          permissions: ["audit:read"],
        }),
      },
      holding(EVERY_PERMISSION, { superuser: true }),
    );
    await userEvent.click(
      within(await screen.findByRole("navigation", { name: "Groups pages" })).getByRole("button", {
        name: "Next",
      }),
    );
    await userEvent.click(
      await screen.findByRole("button", { name: "Edit permissions for Auditors" }),
    );
    const dialog = await screen.findByRole("dialog", { name: "Permissions of Auditors" });

    await userEvent.click(await within(dialog).findByRole("checkbox", { name: "audit:read" }));
    await userEvent.click(within(dialog).getByRole("button", { name: "Save" }));

    expect(await row("Auditors", groupsTable()).findByText("audit:read")).toBeInTheDocument();
    expect(rowHeaders(groupsTable())).toEqual(["group-21", "Auditors"]);
    expect(screen.getByText("Groups 21–22 of 22")).toBeInTheDocument();
  });
});

const ENGINEERING_ID = "00000000-0000-4000-8000-0000000000e0";

/** The read of one page of Engineering's members, from `startIndex`. */
const membersFrom = (startIndex: number) =>
  `GET /api/admin/groups/${ENGINEERING_ID}/members?startIndex=${startIndex}&count=20`;

const member = (index: number, overrides: Partial<GroupMember> = {}): GroupMember => ({
  active: true,
  displayName: null,
  id: `member-${index}`,
  userName: `member-${String(index).padStart(2, "0")}`,
  ...overrides,
});

/** A page of Engineering's members as the backend answers it. */
const memberPage = (totalResults: number, startIndex: number, members: GroupMember[]) =>
  json({ members, startIndex, totalResults });

const membersControl = () =>
  within(groupsTable()).getByRole("button", { name: "Members of Engineering" });

const membersRegion = () => screen.getByRole("region", { name: "Members of Engineering" });

describe("a Group's members", () => {
  it("are a disclosure behind the member count, read on expanding it", async () => {
    const { requests } = await renderAccounts({
      [GROUPS]: groupsPage([groupRow()]),
      [membersFrom(1)]: memberPage(2, 1, [
        member(1, { displayName: "Ada Lovelace", userName: "ada" }),
        member(2, { active: false, userName: "babbage" }),
      ]),
    });

    const control = await screen.findByRole("button", { name: "Members of Engineering" });
    expect([control.textContent, control.getAttribute("aria-expanded")]).toEqual(["2", "false"]);
    expect(requests()).not.toContainEqual({ method: "GET", path: membersFrom(1).slice(4) });

    await userEvent.click(control);

    expect(control.getAttribute("aria-expanded")).toBe("true");
    expect(control.getAttribute("aria-controls")).toBe(membersRegion().id);
    const items = await within(membersRegion()).findAllByRole("listitem");
    expect(items.map((item) => item.textContent)).toEqual(["adaAda Lovelace", "babbageInactive"]);
    expect(
      within(membersRegion()).getByRole("navigation", {
        name: "Pages of the members of Engineering",
      }),
    ).toHaveTextContent(/^Members 1–2 of 2$/);
    expect(within(membersRegion()).queryByRole("button")).toBeNull();
  });

  it("are expanded and collapsed from the keyboard", async () => {
    await renderAccounts({
      [GROUPS]: groupsPage([groupRow()]),
      [membersFrom(1)]: memberPage(1, 1, [member(1)]),
    });
    (await screen.findByRole("button", { name: "Members of Engineering" })).focus();

    await userEvent.keyboard("{Enter}");
    expect(await within(membersRegion()).findByText("member-01")).toBeInTheDocument();
    await userEvent.keyboard(" ");

    expect(membersControl().getAttribute("aria-expanded")).toBe("false");
    expect(screen.queryByRole("region", { name: "Members of Engineering" })).toBeNull();
  });

  it("are read a page at a time, moving on while more remain and back again", async () => {
    const first = Array.from({ length: 20 }, (_, index) => member(index + 1));
    const { requests } = await renderAccounts({
      [GROUPS]: groupsPage([groupRow({ memberCount: 25 })]),
      [membersFrom(1)]: memberPage(25, 1, first),
      [membersFrom(21)]: memberPage(
        25,
        21,
        [21, 22, 23, 24, 25].map((index) => member(index)),
      ),
    });
    await userEvent.click(await screen.findByRole("button", { name: "Members of Engineering" }));
    expect(await within(membersRegion()).findByText("Members 1–20 of 25")).toBeInTheDocument();
    expect(within(membersRegion()).queryByRole("button", { name: "Previous" })).toBeNull();

    await userEvent.click(within(membersRegion()).getByRole("button", { name: "Next" }));

    expect(await within(membersRegion()).findByText("Members 21–25 of 25")).toBeInTheDocument();
    expect(
      within(membersRegion())
        .getAllByRole("listitem")
        .map((item) => item.textContent),
    ).toEqual(["member-21", "member-22", "member-23", "member-24", "member-25"]);
    expect(within(membersRegion()).queryByRole("button", { name: "Next" })).toBeNull();
    expect(requests()).toContainEqual({ method: "GET", path: membersFrom(21).slice(4) });

    await userEvent.click(within(membersRegion()).getByRole("button", { name: "Previous" }));
    expect(await within(membersRegion()).findByText("Members 1–20 of 25")).toBeInTheDocument();
  });

  /**
   * A page can come back short of twenty while members remain — one removed
   * between the backend's count and its read — and the next page starts where
   * that one ended; moving back from there never goes before the first.
   */
  it("move on from where a short page ended, and back no further than the first", async () => {
    await renderAccounts({
      [GROUPS]: groupsPage([groupRow({ memberCount: 30 })]),
      [membersFrom(1)]: memberPage(
        30,
        1,
        Array.from({ length: 14 }, (_, index) => member(index + 1)),
      ),
      [membersFrom(15)]: memberPage(30, 15, [member(15)]),
    });
    await userEvent.click(await screen.findByRole("button", { name: "Members of Engineering" }));
    await within(membersRegion()).findByText("Members 1–14 of 30");

    await userEvent.click(within(membersRegion()).getByRole("button", { name: "Next" }));
    expect(await within(membersRegion()).findByText("Members 15–15 of 30")).toBeInTheDocument();
    await userEvent.click(within(membersRegion()).getByRole("button", { name: "Previous" }));

    expect(await within(membersRegion()).findByText("Members 1–14 of 30")).toBeInTheDocument();
  });

  /** The members' row spans every column the table has, the Superuser's Actions included. */
  it.each([
    ["the Superuser", holding(EVERY_PERMISSION, { superuser: true }), 4],
    ["a session holding every Permission but not the Superuser", holding(EVERY_PERMISSION), 3],
  ] as const)("span the whole table for %s", async (_, session, columns) => {
    await renderAccounts(
      { [GROUPS]: groupsPage([groupRow()]), [membersFrom(1)]: memberPage(1, 1, [member(1)]) },
      session,
    );

    await userEvent.click(await screen.findByRole("button", { name: "Members of Engineering" }));

    expect(within(groupsTable()).getAllByRole("columnheader")).toHaveLength(columns);
    expect(membersRegion().closest("td")?.colSpan).toBe(columns);
  });

  it("of a Group with none say so", async () => {
    await renderAccounts({
      [GROUPS]: groupsPage([groupRow({ memberCount: 0 })]),
      [membersFrom(1)]: memberPage(0, 1, []),
    });

    await userEvent.click(await screen.findByRole("button", { name: "Members of Engineering" }));

    expect(
      await within(membersRegion()).findByText(/^Engineering has no members\.$/),
    ).toBeInTheDocument();
    expect(within(membersRegion()).queryByRole("list")).toBeNull();
    expect(within(membersRegion()).queryByText(/^Members \d/)).toBeNull();
  });

  it("report a failed read to the page's one error line", async () => {
    await renderAccounts({ [GROUPS]: groupsPage([groupRow()]), [membersFrom(1)]: bare(503) });

    await userEvent.click(await screen.findByRole("button", { name: "Members of Engineering" }));

    expect((await screen.findByRole("alert")).textContent).toBe(
      "Unable to load the members of Engineering. Please try again.",
    );
    expect(within(membersRegion()).queryByRole("list")).toBeNull();
    expect(within(membersRegion()).queryByText("Engineering has no members.")).toBeNull();
  });

  it("are shown loading while the first page is read", async () => {
    const page = deferred();
    await renderAccounts({ [GROUPS]: groupsPage([groupRow()]), [membersFrom(1)]: page.answer });

    await userEvent.click(await screen.findByRole("button", { name: "Members of Engineering" }));

    expect(within(membersRegion()).getByText("Loading members…")).toBeInTheDocument();
    await page.settle(memberPage(1, 1, [member(1)]));
    expect(await within(membersRegion()).findByText("member-01")).toBeInTheDocument();
  });

  it("are not offered to a session holding group:read without user:read", async () => {
    const { requests } = await renderAccounts(
      { [GROUPS]: groupsPage([groupRow()]) },
      holding(["group:read"]),
    );

    expect(await screen.findByRole("rowheader", { name: "Engineering" })).toBeInTheDocument();
    expect(row("Engineering", groupsTable()).getByText("2")).toBeInTheDocument();
    expect(within(groupsTable()).queryAllByRole("button")).toHaveLength(0);
    expect(requests().map(({ path }) => path)).toEqual(["/api/admin/groups?startIndex=1&count=20"]);
  });
});

describe("Accounts by Permission", () => {
  /** Every request the page issued, by path. */
  const paths = (requests: () => readonly { path: string }[]) => requests().map(({ path }) => path);

  // Without connector:read the panel renders nothing of its own; its suite pins that.
  it("shows an Account admin the Users and Groups", async () => {
    await renderAccounts(
      { [USERS]: usersPage([userRow({ locked: true, userName: "grace" })]) },
      holding(["group:read", "user:read", "account:unlock"]),
    );

    expect(await screen.findByRole("table", { name: "Users" })).toBeInTheDocument();
    expect(await screen.findByRole("heading", { name: "Groups" })).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Connectors" })).not.toBeInTheDocument();
    // The actions its account:unlock allows.
    expect(row("grace").getByRole("button", { name: "Unlock grace" })).toBeEnabled();
  });

  it("shows a helpdesk the Users with Unlock, and no Groups", async () => {
    await renderAccounts(
      { [USERS]: usersPage([userRow({ locked: true, userName: "grace" })]) },
      holding(["user:read", "account:unlock"]),
    );

    expect(await screen.findByRole("table", { name: "Users" })).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Groups" })).not.toBeInTheDocument();
    expect(row("grace").getByRole("button", { name: "Unlock grace" })).toBeEnabled();
  });

  it("offers no Unlock without account:unlock", async () => {
    await renderAccounts(
      { [USERS]: usersPage([userRow({ locked: true, userName: "grace" })]) },
      holding(["user:read"]),
    );

    expect(await screen.findByRole("rowheader", { name: /^grace/ })).toBeInTheDocument();
    expect(screen.queryAllByRole("button", { name: /Unlock/ })).toHaveLength(0);
  });

  it("neither shows nor requests a view the session lacks the Permission for", async () => {
    const { requests } = await renderAccounts({}, holding(["group:read"]));

    expect(await screen.findByRole("heading", { name: "Groups" })).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Users" })).not.toBeInTheDocument();
    expect(paths(requests)).toEqual(["/api/admin/groups?startIndex=1&count=20"]);
  });

  it("shows only the connector panel to a connector reader", async () => {
    const { requests } = await renderAccounts({}, holding(["connector:read"]));

    expect(await screen.findByRole("heading", { name: "Connectors" })).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Users" })).not.toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Groups" })).not.toBeInTheDocument();
    expect(paths(requests)).toEqual(["/api/admin/connectors"]);
  });
});

/**
 * Group Permissions (ADR 0015): only the Superuser sets them, from the Groups
 * table, and never on the Admin group, whose members' power is the Superuser.
 */
describe("Accounts: a Superuser sets a Group's Permissions", () => {
  /** The Superuser's session: holding every Permission is not being the Superuser. */
  const THE_SUPERUSER = holding(EVERY_PERMISSION, { superuser: true });

  const VOCABULARY = "GET /api/admin/permissions";
  const DELEGATABLE = [
    "account:unlock",
    "audit:read",
    "connector:read",
    "group:read",
    "ops:read",
    "user:read",
  ];

  const AUDITORS_ID = "00000000-0000-4000-8000-0000000000a1";
  const auditors = groupRow({
    displayName: "Auditors",
    id: AUDITORS_ID,
    permissions: ["audit:read", "user:read"],
  });
  const putAuditors = `PUT /api/admin/groups/${AUDITORS_ID}/permissions`;

  const threeGroups = groupsPage([
    groupRow({ adminGroup: true, displayName: "Admins", id: "a" }),
    auditors,
    groupRow(),
  ]);

  const openEditor = async () => {
    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "Edit permissions for Auditors" }));
    return { dialog: await screen.findByRole("dialog", { name: "Permissions of Auditors" }), user };
  };

  const columns = () =>
    within(groupsTable())
      .getAllByRole("columnheader")
      .map((header) => header.textContent);

  // Radix `Checkbox` is not a native input: no `.checked`, and `name` reaches
  // only an internal hidden input, not the queried element. Pairs positionally
  // against the vocabulary's own order, which the dialog renders them in.
  const ticks = (dialog: HTMLElement) =>
    within(dialog)
      .getAllByRole("checkbox")
      .map((box, index) => [DELEGATABLE[index], box.getAttribute("aria-checked") === "true"]);

  const puts = <T extends { method: string }>(requests: () => readonly T[]) =>
    requests().filter(({ method }) => method === "PUT");

  it("offers the Superuser Edit permissions on every Group but the Admin group", async () => {
    await renderAccounts({ [GROUPS]: threeGroups }, THE_SUPERUSER);

    expect(await screen.findByRole("rowheader", { name: "Admins" })).toBeInTheDocument();
    expect(
      row("Auditors", groupsTable()).getByRole("button", { name: /^Edit permissions/ }),
    ).toHaveTextContent("Edit permissions for Auditors");
    expect(
      row("Engineering", groupsTable()).getByRole("button", { name: /^Edit permissions/ }),
    ).toHaveTextContent("Edit permissions for Engineering");
    expect(
      row("Admins", groupsTable()).queryByRole("button", { name: /^Edit permissions/ }),
    ).toBeNull();
    expect(columns()).toEqual(["Group", "Members", "Permissions", "Actions"]);
  });

  it("offers no Edit permissions to a session holding every Permission but not the Superuser", async () => {
    await renderAccounts({ [GROUPS]: threeGroups }, holding(EVERY_PERMISSION));

    expect(await screen.findByRole("rowheader", { name: "Auditors" })).toBeInTheDocument();
    expect(
      within(groupsTable()).queryAllByRole("button", { name: /^Edit permissions/ }),
    ).toHaveLength(0);
    expect(columns()).toEqual(["Group", "Members", "Permissions"]);
  });

  it.each([
    [409, "Refused: the Permissions of Auditors cannot be set."],
    [503, "Unable to set the Permissions of Auditors. Please try again."],
  ])("reports a save refused %i with the Group's own copy", async (status, copy) => {
    await renderAccounts(
      { [GROUPS]: threeGroups, [VOCABULARY]: json(DELEGATABLE), [putAuditors]: bare(status) },
      THE_SUPERUSER,
    );
    const { dialog, user } = await openEditor();

    await within(dialog).findByRole("checkbox", { name: "group:read" });
    await user.click(within(dialog).getByRole("button", { name: "Save" }));

    expect((await screen.findByRole("alert")).textContent).toBe(copy);
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("closes on Escape without a write", async () => {
    const { requests } = await renderAccounts(
      { [GROUPS]: threeGroups, [VOCABULARY]: json(DELEGATABLE) },
      THE_SUPERUSER,
    );
    const { dialog, user } = await openEditor();
    await within(dialog).findByRole("checkbox", { name: "group:read" });

    await user.keyboard("{Escape}");

    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(puts(requests)).toEqual([]);
  });

  it("stays open on Escape while the save is in flight, every control disabled", async () => {
    const saving = deferred();
    await renderAccounts(
      { [GROUPS]: threeGroups, [VOCABULARY]: json(DELEGATABLE), [putAuditors]: saving.answer },
      THE_SUPERUSER,
    );
    const { dialog, user } = await openEditor();
    await user.click(await within(dialog).findByRole("checkbox", { name: "group:read" }));
    await user.click(within(dialog).getByRole("button", { name: "Save" }));

    await user.keyboard("{Escape}");

    expect(screen.getByRole("dialog", { name: "Permissions of Auditors" })).toBeInTheDocument();
    expect(
      [...within(dialog).getAllByRole("checkbox"), ...within(dialog).getAllByRole("button")].every(
        (control) => (control as HTMLInputElement | HTMLButtonElement).disabled,
      ),
    ).toBe(true);
    await saving.settle(
      json({ ...auditors, permissions: ["audit:read", "group:read", "user:read"] }),
    );
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("says the vocabulary is loading until it is read", async () => {
    const vocabulary = deferred();
    await renderAccounts({ [GROUPS]: threeGroups, [VOCABULARY]: vocabulary.answer }, THE_SUPERUSER);
    const { dialog } = await openEditor();

    expect(within(dialog).getByText("Loading Permissions…")).toBeInTheDocument();
    await vocabulary.settle(json(DELEGATABLE));
    expect(within(dialog).queryByText("Loading Permissions…")).not.toBeInTheDocument();
    expect(within(dialog).getAllByRole("checkbox")).toHaveLength(6);
  });

  it("lists every delegatable Permission, ticked with the Group's current set", async () => {
    const { requests } = await renderAccounts(
      { [GROUPS]: threeGroups, [VOCABULARY]: json(DELEGATABLE) },
      THE_SUPERUSER,
    );

    const { dialog } = await openEditor();

    await within(dialog).findByRole("checkbox", { name: "user:read" });
    expect(ticks(dialog)).toEqual([
      ["account:unlock", false],
      ["audit:read", true],
      ["connector:read", false],
      ["group:read", false],
      ["ops:read", false],
      ["user:read", true],
    ]);
    expect(requests()).toContainEqual({ method: "GET", path: "/api/admin/permissions" });
  });

  it("saves the full set with one PUT and shows the Group as the answer states it", async () => {
    const { requests } = await renderAccounts(
      {
        [GROUPS]: threeGroups,
        [VOCABULARY]: json(DELEGATABLE),
        [putAuditors]: json({ ...auditors, permissions: ["audit:read", "group:read"] }),
      },
      THE_SUPERUSER,
    );
    const { dialog, user } = await openEditor();

    await user.click(await within(dialog).findByRole("checkbox", { name: "group:read" }));
    await user.click(within(dialog).getByRole("checkbox", { name: "user:read" }));
    await user.click(within(dialog).getByRole("button", { name: "Save" }));

    expect(
      await row("Auditors", groupsTable()).findByText("audit:read, group:read"),
    ).toBeInTheDocument();
    expect(puts(requests)).toEqual([
      {
        body: ["audit:read", "group:read"],
        method: "PUT",
        path: `/api/admin/groups/${AUDITORS_ID}/permissions`,
      },
    ]);
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("clears every Permission with an empty set", async () => {
    const { requests } = await renderAccounts(
      {
        [GROUPS]: threeGroups,
        [VOCABULARY]: json(DELEGATABLE),
        [putAuditors]: json({ ...auditors, permissions: [] }),
      },
      THE_SUPERUSER,
    );
    const { dialog, user } = await openEditor();

    await user.click(await within(dialog).findByRole("checkbox", { name: "audit:read" }));
    await user.click(within(dialog).getByRole("checkbox", { name: "user:read" }));
    await user.click(within(dialog).getByRole("button", { name: "Save" }));

    expect(await row("Auditors", groupsTable()).findByText("—")).toBeInTheDocument();
    expect(puts(requests)).toEqual([
      { body: [], method: "PUT", path: `/api/admin/groups/${AUDITORS_ID}/permissions` },
    ]);
  });

  it("closes without a write on Cancel", async () => {
    const { requests } = await renderAccounts(
      { [GROUPS]: threeGroups, [VOCABULARY]: json(DELEGATABLE) },
      THE_SUPERUSER,
    );
    const { dialog, user } = await openEditor();

    await user.click(await within(dialog).findByRole("checkbox", { name: "group:read" }));
    await user.click(within(dialog).getByRole("button", { name: "Cancel" }));

    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(puts(requests)).toEqual([]);
    expect(row("Auditors", groupsTable()).getByText("audit:read, user:read")).toBeInTheDocument();
  });

  it("reports a Group that has since been deleted and keeps its row as it was", async () => {
    await renderAccounts(
      { [GROUPS]: threeGroups, [VOCABULARY]: json(DELEGATABLE), [putAuditors]: bare(404) },
      THE_SUPERUSER,
    );
    const { dialog, user } = await openEditor();

    await within(dialog).findByRole("checkbox", { name: "group:read" });
    await user.click(within(dialog).getByRole("button", { name: "Save" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Auditors no longer exists. Reload the page for the current list.",
    );
    expect(row("Auditors", groupsTable()).getByText("audit:read, user:read")).toBeInTheDocument();
  });

  it("offers no Save until the vocabulary is read, and reports a failed read", async () => {
    await renderAccounts({ [GROUPS]: threeGroups, [VOCABULARY]: bare(503) }, THE_SUPERUSER);
    const { dialog } = await openEditor();

    // The page's own error line, correctly `aria-hidden` by Radix while the modal dialog
    // sits over it: still asserted, just not through the accessibility tree.
    expect(await screen.findByRole("alert", { hidden: true })).toHaveTextContent(
      "Unable to load the Permissions. Please try again.",
    );
    expect(within(dialog).getByText("The Permissions could not be loaded.")).toBeInTheDocument();
    expect(within(dialog).queryAllByRole("checkbox")).toHaveLength(0);
    expect(within(dialog).getByRole("button", { name: "Save" })).toBeDisabled();
  });
});
