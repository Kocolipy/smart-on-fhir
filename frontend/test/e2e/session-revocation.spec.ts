import { expect, test, type Browser, type Page } from "@playwright/test";

import { submitLogin, submitLoginViaApi } from "./auth.helpers";
import {
  anonymousApi,
  deprovisionUser,
  E2E_PREFIX,
  freshBrowser,
  OWN_PASSWORD,
  provisionUser,
  runId,
  scimPatch,
  settlePassword,
  workerConnector,
} from "./scim.helpers";

/**
 * Sessions ended from somewhere other than the browser holding them, seen from
 * that browser: a second sign-in as the same User (one session per User, #64),
 * and SCIM writes the backend revokes sessions for after commit — deactivation,
 * deletion, a password replace, a rename, and removal from a mapped Group (the
 * Admin group, which is the Superuser Group, and the Account admin Role's Group).
 *
 * The backend's integration tests prove the sessions are deleted. What only a
 * browser can show is the SPA's reaction: the next request the page makes is
 * refused, and the User lands on the login page TOLD their session ended,
 * rather than on a broken page or a silent sign-in form.
 *
 * Every User is provisioned here and deleted in a `finally`; the seeded
 * identities, whose sessions the other projects replay, are never touched.
 */

const RUN = runId();
const connector = workerConnector("revocation");

const EXPIRED_NOTICE = "Your session ended. Please sign in again.";
const REPLACED_PASSWORD = "Directory-Replaced-6t";

/** A provisioned User, settled on {@link OWN_PASSWORD}; deleted again by `withUser`. */
async function withUser(label: string, body: (userName: string, id: string) => Promise<void>) {
  const userName = `${E2E_PREFIX}rev-${label}-${RUN}`;
  const id = await provisionUser(connector.scim, userName);
  try {
    await settlePassword(userName);
    await body(userName, id);
  } finally {
    await deprovisionUser(connector.scim, id);
  }
}

/** Signs `userName` in through the login page of a browser of its own. */
async function signedInBrowser(browser: Browser, userName: string) {
  const context = await freshBrowser(browser);
  const page = await context.newPage();
  await submitLogin(page, userName, OWN_PASSWORD);
  await expect(page).toHaveURL(/\/showcase$/);
  await expect(page.getByText(`Signed in as ${userName}`)).toBeVisible();
  return page;
}

/**
 * Park the page on `/change-password`, reached by an in-app link so the SPA
 * keeps its authenticated state, and nothing there writes anything.
 */
async function park(page: Page) {
  await page.getByRole("link", { name: "Change password" }).click();
  await expect(page.getByRole("heading", { name: "Change your password" })).toBeVisible();
}

/**
 * The page's next request, after the session was ended elsewhere: `Back`
 * remounts the showcase, whose counter read is refused, and the SPA sends the
 * User to sign in with the expired-session notice. A read, never the counter's
 * increment — `showcase.spec.ts` asserts exact counts on the shared counter.
 * Every User holds the baseline `counter:read`, so the remount always reads.
 */
async function expectNextRequestEndsSession(page: Page) {
  await page.getByRole("link", { name: "Back" }).click();
  await expect(page.getByRole("heading", { name: "Welcome back" })).toBeVisible();
  await expect(page.getByRole("status")).toHaveText(EXPIRED_NOTICE);
  expect((await page.request.get("/api/auth/me")).status()).toBe(401);
}

test("a second sign-in as the same User ends the first browser's session", async ({ browser }) => {
  test.setTimeout(90_000);
  await withUser("single", async (userName) => {
    const first = await signedInBrowser(browser, userName);
    try {
      await park(first);

      const second = await signedInBrowser(browser, userName);
      try {
        await expectNextRequestEndsSession(first);

        // The session kept is the newer one: it goes on working.
        await second.reload();
        await expect(second.getByText(`Signed in as ${userName}`)).toBeVisible();
        expect((await second.request.get("/api/auth/me")).status()).toBe(200);
      } finally {
        await second.context().close();
      }
    } finally {
      await first.context().close();
    }
  });
});

test("a SCIM deactivation ends the User's session and refuses the next sign-in", async ({
  browser,
}) => {
  test.setTimeout(90_000);
  await withUser("deactivated", async (userName, id) => {
    const page = await signedInBrowser(browser, userName);
    try {
      await park(page);

      const patched = await scimPatch(connector.scim, `/scim/v2/Users/${id}`, [
        { op: "replace", path: "active", value: false },
      ]);
      expect(patched.status(), "deactivating over SCIM").toBe(200);

      await expectNextRequestEndsSession(page);

      // Signing in again is refused with the generic message: the login page
      // does not reveal that the account exists but is inactive.
      await submitLogin(page, userName, OWN_PASSWORD);
      await expect(page.getByRole("alert")).toHaveText("The username or password is incorrect.");
      await expect(page).toHaveURL(/\/$/);
    } finally {
      await page.context().close();
    }
  });
});

test("a SCIM password replace ends the User's session and retires the old password", async ({
  browser,
}) => {
  test.setTimeout(90_000);
  await withUser("password", async (userName, id) => {
    const page = await signedInBrowser(browser, userName);
    try {
      await park(page);

      const patched = await scimPatch(connector.scim, `/scim/v2/Users/${id}`, [
        { op: "replace", path: "password", value: REPLACED_PASSWORD },
      ]);
      expect(patched.status(), "replacing the password over SCIM").toBe(200);

      await expectNextRequestEndsSession(page);

      const api = await anonymousApi();
      try {
        expect((await submitLoginViaApi(api, userName, OWN_PASSWORD)).status()).toBe(401);
      } finally {
        await api.dispose();
      }
      // The directory's password signs in — confined to the change, because a
      // connector-set password is one the User must replace.
      await submitLogin(page, userName, REPLACED_PASSWORD);
      await expect(page).toHaveURL(/\/change-password$/);
    } finally {
      await page.context().close();
    }
  });
});

test("a SCIM deletion ends the User's session and refuses the next sign-in", async ({
  browser,
}) => {
  test.setTimeout(90_000);
  await withUser("deleted", async (userName, id) => {
    const page = await signedInBrowser(browser, userName);
    try {
      await park(page);

      // `withUser`'s own clean-up finds the User gone and leaves it so.
      await deprovisionUser(connector.scim, id);

      await expectNextRequestEndsSession(page);

      await submitLogin(page, userName, OWN_PASSWORD);
      await expect(page.getByRole("alert")).toHaveText("The username or password is incorrect.");
      await expect(page).toHaveURL(/\/$/);
    } finally {
      await page.context().close();
    }
  });
});

test("a SCIM rename ends the User's session and only the new name signs in", async ({
  browser,
}) => {
  test.setTimeout(90_000);
  await withUser("renamed", async (userName, id) => {
    // Still prefixed, so a crashed run's leftover is swept like any other.
    const renamed = `${userName}-new`;
    const page = await signedInBrowser(browser, userName);
    try {
      await park(page);

      const patched = await scimPatch(connector.scim, `/scim/v2/Users/${id}`, [
        { op: "replace", path: "userName", value: renamed },
      ]);
      expect(patched.status(), "renaming over SCIM").toBe(200);

      await expectNextRequestEndsSession(page);

      // The old name names nobody now; the same password under the new name is
      // the same User, unflagged, because a rename is not a password write.
      await submitLogin(page, userName, OWN_PASSWORD);
      await expect(page.getByRole("alert")).toHaveText("The username or password is incorrect.");
      await submitLogin(page, renamed, OWN_PASSWORD);
      await expect(page).toHaveURL(/\/showcase$/);
      await expect(page.getByText(`Signed in as ${renamed}`)).toBeVisible();
    } finally {
      await page.context().close();
    }
  });
});

test("removal from the Admin group over SCIM ends the session and the Admin role", async ({
  browser,
  page: adminPage,
}) => {
  test.setTimeout(90_000);
  const groups = (await (await adminPage.request.get("/api/admin/groups")).json()) as Array<{
    adminGroup: boolean;
    id: string;
  }>;
  const adminGroup = groups.find((group) => group.adminGroup);
  expect(adminGroup, "the Admin group should be listed").toBeTruthy();
  const groupPath = `/scim/v2/Groups/${adminGroup!.id}`;

  await withUser("admin-member", async (userName, id) => {
    const added = await scimPatch(connector.scim, groupPath, [
      { op: "add", path: "members", value: [{ value: id }] },
    ]);
    expect(added.status(), "adding the User to the Admin group").toBe(200);

    const page = await signedInBrowser(browser, userName);
    try {
      // Precondition: the membership made this User an Admin.
      await page.goto("/accounts");
      await expect(page.getByRole("heading", { name: "Accounts" })).toBeVisible();
      await page.goto("/showcase");
      await park(page);

      const removed = await scimPatch(connector.scim, groupPath, [
        { op: "remove", path: `members[value eq "${id}"]` },
      ]);
      expect(removed.status(), "removing the User from the Admin group").toBe(200);

      await expectNextRequestEndsSession(page);

      // Signed in again, the User holds only the baseline Permissions: the page sends them
      // away, and the backend refuses the data behind it.
      await submitLogin(page, userName, OWN_PASSWORD);
      await expect(page).toHaveURL(/\/showcase$/);
      await page.goto("/accounts");
      await expect(page).toHaveURL(/\/showcase$/);
      expect((await page.request.get("/api/admin/accounts")).status()).toBe(403);
    } finally {
      await page.context().close();
    }
  });
});

test("removal from a mapped Group over SCIM signs an Account admin's open SPA session out", async ({
  browser,
  page: adminPage,
}) => {
  test.setTimeout(90_000);
  // The Group conferring the Account admin Role, read from the roles endpoint the
  // way an operator would find it rather than from a hard-coded fixture id.
  const roles = (await (await adminPage.request.get("/api/admin/roles")).json()) as Array<{
    name: string;
    groups: Array<{ id: string; superuser: boolean }>;
  }>;
  const accountAdmin = roles.find((role) => role.name === "Account admin");
  expect(
    accountAdmin?.groups,
    "the Account admin Role should be conferred by one Group",
  ).toHaveLength(1);
  expect(accountAdmin!.groups[0].superuser).toBe(false);
  const groupPath = `/scim/v2/Groups/${accountAdmin!.groups[0].id}`;

  await withUser("account-admin", async (userName, id) => {
    const added = await scimPatch(connector.scim, groupPath, [
      { op: "add", path: "members", value: [{ value: id }] },
    ]);
    expect(added.status(), "adding the User to the Account admin Group").toBe(200);

    const page = await signedInBrowser(browser, userName);
    try {
      // Precondition: the Role reached the session — the Users view is shown, the
      // connectors view (connector:read) is not.
      await page.goto("/accounts");
      await expect(page.getByRole("heading", { name: "Accounts" })).toBeVisible();
      await expect(page.getByRole("heading", { name: "Users", exact: true })).toBeVisible();
      await expect(page.getByRole("heading", { name: "Connectors", exact: true })).toHaveCount(0);
      await page.goto("/showcase");
      await park(page);

      const removed = await scimPatch(connector.scim, groupPath, [
        { op: "remove", path: `members[value eq "${id}"]` },
      ]);
      expect(removed.status(), "removing the User from the Account admin Group").toBe(200);

      await expectNextRequestEndsSession(page);

      // Signed in again, the Role is gone: the Accounts page routes away and the
      // backend refuses the listing.
      await submitLogin(page, userName, OWN_PASSWORD);
      await expect(page).toHaveURL(/\/showcase$/);
      await page.goto("/accounts");
      await expect(page).toHaveURL(/\/showcase$/);
      expect((await page.request.get("/api/admin/accounts")).status()).toBe(403);
    } finally {
      await page.context().close();
    }
  });
});
