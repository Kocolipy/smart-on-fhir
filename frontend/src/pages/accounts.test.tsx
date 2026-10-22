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

import type { GroupRow, UserRow } from "./accounts-api";
import { renderSignedIn } from "./signed-in-shell.testHelpers";

const USERS = "GET /api/admin/accounts";
const GROUPS = "GET /api/admin/groups";
const CONNECTORS = "GET /api/admin/connectors";

/** A Superuser's session, which sees every listing and every action on the page. */
const SUPERUSER = holding(EVERY_PERMISSION);

const GRACE_ID = "00000000-0000-4000-8000-000000000001";

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

const unlockGrace = `POST /api/admin/accounts/${GRACE_ID}/unlock`;
const forceGrace = `POST /api/admin/accounts/${GRACE_ID}/force-password-change`;

/**
 * The page as `session` sees it, inside the signed-in layout that renders its
 * one error line — every listing it may read answered empty unless `answers`
 * says otherwise, the Connectors panel's included, since the page renders it
 * for real.
 */
const renderAccounts = (answers: Answers, session: StartingSession = SUPERUSER) =>
  renderSignedIn("/accounts", session, {
    [CONNECTORS]: json([]),
    [GROUPS]: json([]),
    [USERS]: json([]),
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
      [USERS]: json([
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
      "Access",
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
    expect(grace.getByText("2026-03-05 01:06")).toBeInTheDocument();
    expect(grace.getByText("2026-01-03")).toBeInTheDocument();
    expect(grace.getByText("Engineering, Operators")).toBeInTheDocument();

    expect(row("ada").getByText("Superuser")).toBeInTheDocument();
    expect(requests()).toContainEqual({ method: "GET", path: "/api/admin/accounts" });
  });

  it("reports the states a directory and the login path can leave a User in", async () => {
    await renderAccounts({
      [USERS]: json([
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
      [USERS]: json([
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
      [GROUPS]: json([
        groupRow({ adminGroup: true, displayName: "Admins", id: "a", memberCount: 1 }),
        groupRow({ displayName: "Auditors", id: "b", permissions: ["audit:read", "user:read"] }),
        groupRow(),
      ]),
      [USERS]: json([userRow()]),
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
    expect(requests()).toContainEqual({ method: "GET", path: "/api/admin/groups" });
  });

  it("shows the Admin group as conferring the Superuser, whatever Permissions it reports", async () => {
    await renderAccounts({
      [GROUPS]: json([
        groupRow({ adminGroup: true, displayName: "Admins", id: "a" }),
        groupRow({ displayName: "Superusers", id: "b", permissions: ["ops:read"] }),
      ]),
      [USERS]: json([userRow()]),
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
      [GROUPS]: json([groupRow({ adminGroup: true, displayName: "Admins" })]),
      [USERS]: json([
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
    expect(within(groupsTable()).queryAllByRole("button")).toHaveLength(0);
    const labels = within(usersTable())
      .getAllByRole("button")
      .map((button) => button.textContent ?? "");
    expect(labels.length).toBeGreaterThan(0);
    expect(labels.every((label) => /^(Unlock|Force password change for) /.test(label))).toBe(true);
    expect(screen.queryByRole("button", { name: /Disable|Enable|Deactivate|Activate/ })).toBeNull();
  });

  it("says Unlock is the only way a lockout ends and that it requires a password change", async () => {
    await renderAccounts({ [USERS]: json([userRow({ locked: true })]) });

    const unlock = await screen.findByRole("button", { name: "Unlock grace" });
    const explanation =
      "A lockout never expires: Unlock is the only way it ends, and Unlock also requires the User to change their password before they can do anything else.";
    expect(screen.getByText(explanation, { exact: false })).toBeInTheDocument();
    expect(unlock).toHaveAttribute("title", explanation);
    // No expiry anywhere: there is nothing to count down to.
    expect(screen.queryByText(/until|expires in|remaining/i)).not.toBeInTheDocument();
  });

  it("ends a lockout by the User's stable id and shows the change it now requires", async () => {
    const { requests } = await renderAccounts({
      [unlockGrace]: json(userRow({ locked: false, passwordChangeRequired: true })),
      [USERS]: json([userRow({ locked: true })]),
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

  it("forces a password change by the User's stable id", async () => {
    const { requests } = await renderAccounts({
      [forceGrace]: json(userRow({ passwordChangeRequired: true })),
      [USERS]: json([userRow()]),
    });
    const user = userEvent.setup();

    await user.click(
      await screen.findByRole("button", { name: "Force password change for grace" }),
    );

    expect(requests()).toContainEqual({
      method: "POST",
      path: `/api/admin/accounts/${GRACE_ID}/force-password-change`,
    });
    expect(row("grace").getByText("Required")).toBeInTheDocument();
    expect(
      row("grace").queryByRole("button", { name: "Force password change for grace" }),
    ).not.toBeInTheDocument();
  });

  // The answer's row replaces the listed one only once it decodes: a row whose
  // lock state contradicts itself is a failure, and the listing stands.
  it("reports an action answered with a row that does not decode, and keeps the row", async () => {
    await renderAccounts({
      [unlockGrace]: json({ ...userRow(), lockCause: "DORMANCY" }),
      [USERS]: json([userRow({ locked: true })]),
    });
    const user = userEvent.setup();

    await user.click(await screen.findByRole("button", { name: "Unlock grace" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      /^Unable to unlock grace\. Please try again\.$/,
    );
    expect(row("grace").getByText("Locked: failed logins")).toBeInTheDocument();
  });

  it("offers Unlock only while a lockout is in force", async () => {
    await renderAccounts({ [USERS]: json([userRow()]) });

    await screen.findByRole("rowheader", { name: /^grace/ });
    expect(row("grace").queryByRole("button", { name: "Unlock grace" })).not.toBeInTheDocument();
  });

  it("offers no forced change to a User with no password or one already flagged", async () => {
    await renderAccounts({
      [USERS]: json([
        userRow({ hasPassword: false, userName: "nopass" }),
        userRow({ id: "f", passwordChangeRequired: true, userName: "flagged" }),
      ]),
    });

    await screen.findByRole("rowheader", { name: /^nopass/ });
    expect(within(usersTable()).queryAllByRole("button")).toHaveLength(0);
  });

  /**
   * The backend refuses both with a 403; hiding them makes the refusal visible
   * before the click. The session name is compared the way the backend compares
   * it, so a differently-cased spelling still recognises its own row.
   */
  it("offers the signed-in Admin neither action on their own account", async () => {
    await renderAccounts({
      [USERS]: json([userRow({ admin: true, locked: true, userName: "ADA" })]),
    });

    await screen.findByRole("rowheader", { name: /^ADA/ });
    expect(within(usersTable()).queryAllByRole("button")).toHaveLength(0);
  });

  it("shows the Bootstrap Admin with no lockout state and no Unlock", async () => {
    await renderAccounts({
      // Locked is impossible for it; even a row claiming it renders no state.
      [USERS]: json([
        userRow({ admin: true, bootstrapAdmin: true, locked: true, userName: "root" }),
      ]),
    });

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
    await renderAccounts(
      { [USERS]: json([userRow({ bootstrapAdmin: true, userName: "root" })]) },
      holding(EVERY_PERMISSION, { username: "root" }),
    );

    expect(await screen.findByRole("rowheader", { name: /^root/ })).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Force password change for root" }),
    ).not.toBeInTheDocument();
  });

  it("disables every control while an action is in flight", async () => {
    const unlocking = deferred();
    await renderAccounts({
      [unlockGrace]: unlocking.answer,
      [USERS]: json([
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
    expect(screen.getByRole("button", { name: "Force password change for hopper" })).toBeDisabled();

    await unlocking.settle(json(userRow()));
    expect(screen.getByRole("button", { name: "Unlock hopper" })).toBeEnabled();
  });

  it("reports a forced change refused for a User with no password to replace", async () => {
    await renderAccounts({ [forceGrace]: bare(409), [USERS]: json([userRow()]) });
    const user = userEvent.setup();

    await user.click(
      await screen.findByRole("button", { name: "Force password change for grace" }),
    );

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Refused: grace has no password to replace.",
    );
    // The listing is untouched: the backend refused, so nothing changed.
    expect(row("grace").getByText("No")).toBeInTheDocument();
  });

  it("reports a User that has since been removed", async () => {
    await renderAccounts({ [unlockGrace]: bare(404), [USERS]: json([userRow({ locked: true })]) });
    const user = userEvent.setup();

    await user.click(await screen.findByRole("button", { name: "Unlock grace" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "grace no longer exists. Reload the page for the current list.",
    );
  });

  it("reports a failed action with action-specific copy", async () => {
    await renderAccounts({
      [forceGrace]: UNREACHABLE,
      [unlockGrace]: bare(503),
      [USERS]: json([userRow({ locked: true })]),
    });
    const user = userEvent.setup();

    await user.click(await screen.findByRole("button", { name: "Unlock grace" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Unable to unlock grace. Please try again.",
    );

    await user.click(screen.getByRole("button", { name: "Force password change for grace" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Unable to force a password change for grace. Please try again.",
    );
  });

  // The page names no 400 of its own, so this pins its own `default` over the
  // hook's generic 400 copy — the status the page's action-specific sentence
  // is written for.
  it("reports a 400 refusal with the action's own copy, not the hook's generic one", async () => {
    await renderAccounts({ [unlockGrace]: bare(400), [USERS]: json([userRow({ locked: true })]) });
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
      [USERS]: json([userRow({ locked: true })]),
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
      [USERS]: json([userRow({ locked: true })]),
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

  /**
   * The route guard means a signed-in user is always present, but the context
   * types it as optional; the page must not throw while it is absent, and with
   * no name to compare it recognises no row as the caller's own.
   */
  it("treats no row as the caller's own when the session names no user", async () => {
    await renderAccounts(
      { [USERS]: json([userRow({ locked: true })]) },
      holding(EVERY_PERMISSION, { username: "" }),
    );

    expect(await screen.findByRole("button", { name: "Unlock grace" })).toBeEnabled();
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
    await renderAccounts({ [GROUPS]: bare(503), [USERS]: json([userRow()]) });

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
      [GROUPS]: json([groupRow()]),
      [USERS]: json([{ ...userRow(), userName: undefined, login: "grace" }]),
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
      [USERS]: json([userRow({ locked: true })]),
    });
    const user = userEvent.setup();
    expect(await screen.findByRole("alert")).toHaveTextContent(/connectors/);

    await user.click(await screen.findByRole("button", { name: "Unlock grace" }));

    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });
});

describe("Accounts by Permission", () => {
  /** Every request the page issued, by path. */
  const paths = (requests: () => readonly { path: string }[]) => requests().map(({ path }) => path);

  // Without connector:read the panel renders nothing of its own; its suite pins that.
  it("shows an Account admin the Users and Groups", async () => {
    await renderAccounts(
      { [USERS]: json([userRow({ locked: true, userName: "grace" })]) },
      holding(["group:read", "user:read", "account:unlock"]),
    );

    expect(await screen.findByRole("table", { name: "Users" })).toBeInTheDocument();
    expect(await screen.findByRole("heading", { name: "Groups" })).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Connectors" })).not.toBeInTheDocument();
    // The actions its account:unlock allows.
    expect(row("grace").getByRole("button", { name: "Unlock grace" })).toBeEnabled();
  });

  it("shows a helpdesk the Users with Unlock and the forced change, and no Groups", async () => {
    await renderAccounts(
      { [USERS]: json([userRow({ locked: true, userName: "grace" })]) },
      holding(["user:read", "account:unlock"]),
    );

    expect(await screen.findByRole("table", { name: "Users" })).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Groups" })).not.toBeInTheDocument();
    expect([
      row("grace").getByRole("button", { name: "Unlock grace" }),
      row("grace").getByRole("button", { name: "Force password change for grace" }),
    ]).toHaveLength(2);
  });

  it("offers no Unlock or forced change without account:unlock", async () => {
    await renderAccounts(
      { [USERS]: json([userRow({ locked: true, userName: "grace" })]) },
      holding(["user:read"]),
    );

    expect(await screen.findByRole("rowheader", { name: /^grace/ })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Unlock/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Force password change/ })).not.toBeInTheDocument();
  });

  it("neither shows nor requests a view the session lacks the Permission for", async () => {
    const { requests } = await renderAccounts({}, holding(["group:read"]));

    expect(await screen.findByRole("heading", { name: "Groups" })).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Users" })).not.toBeInTheDocument();
    expect(paths(requests)).toEqual(["/api/admin/groups"]);
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

  const threeGroups = json([
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

  const ticks = (dialog: HTMLElement) =>
    within(dialog)
      .getAllByRole("checkbox")
      .map((box) => [box.getAttribute("name"), (box as HTMLInputElement).checked]);

  const puts = <T extends { method: string }>(requests: () => readonly T[]) =>
    requests().filter(({ method }) => method === "PUT");

  it("offers the Superuser Edit permissions on every Group but the Admin group", async () => {
    await renderAccounts({ [GROUPS]: threeGroups }, THE_SUPERUSER);

    expect(await screen.findByRole("rowheader", { name: "Admins" })).toBeInTheDocument();
    expect(row("Auditors", groupsTable()).getByRole("button")).toHaveTextContent(
      "Edit permissions for Auditors",
    );
    expect(row("Engineering", groupsTable()).getByRole("button")).toHaveTextContent(
      "Edit permissions for Engineering",
    );
    expect(row("Admins", groupsTable()).queryByRole("button")).toBeNull();
    expect(columns()).toEqual(["Group", "Members", "Permissions", "Actions"]);
  });

  it("offers no Edit permissions to a session holding every Permission but not the Superuser", async () => {
    await renderAccounts({ [GROUPS]: threeGroups }, holding(EVERY_PERMISSION));

    expect(await screen.findByRole("rowheader", { name: "Auditors" })).toBeInTheDocument();
    expect(within(groupsTable()).queryAllByRole("button")).toHaveLength(0);
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
    const { dialog } = await openEditor();
    await within(dialog).findByRole("checkbox", { name: "group:read" });

    const escape = new Event("cancel", { cancelable: true });
    fireEvent(dialog, escape);

    expect(escape.defaultPrevented).toBe(true);
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

    fireEvent(dialog, new Event("cancel", { cancelable: true }));

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

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Unable to load the Permissions. Please try again.",
    );
    expect(within(dialog).getByText("The Permissions could not be loaded.")).toBeInTheDocument();
    expect(within(dialog).queryAllByRole("checkbox")).toHaveLength(0);
    expect(within(dialog).getByRole("button", { name: "Save" })).toBeDisabled();
  });
});
