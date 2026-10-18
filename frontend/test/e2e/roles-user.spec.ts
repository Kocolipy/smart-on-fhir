import { expect, test } from "@playwright/test";

import { csrfHeaderFor, postAdminAction, submitLoginViaApi } from "./auth.helpers";
import {
  anonymousApi,
  BACKEND_URL,
  createConnector,
  deleteConnector,
  deprovisionUser,
  E2E_PREFIX,
  OWN_PASSWORD,
  provisionUser,
  runId,
  scimApi,
  settlePassword,
} from "./scim.helpers";

// The seeded `user`: a baseline User, in no mapped Group, so holding only the
// baseline Permissions every User holds — the counter's. What it sees and what
// it is refused are both asserted.

test.describe("baseline User pages", () => {
  test("lands on Home with the counter and self-service, and no administration", async ({
    page,
  }) => {
    await page.goto("/");

    await expect(page.getByRole("heading", { name: "Home" })).toBeVisible();
    await expect(page).toHaveURL("/");
    await expect(page.getByTestId("count")).toBeVisible();
    await expect(page.getByRole("button", { name: "Increment" })).toBeVisible();
    await expect(page.getByRole("button", { name: "Reset" })).toBeVisible();
    await expect(page.getByRole("link", { name: "Manage accounts" })).toHaveCount(0);
    await expect(page.getByRole("link", { name: "Change password" })).toBeVisible();
    await expect(page.getByRole("button", { name: "Sign out" })).toBeVisible();
  });

  test("is routed away from the accounts page by a deep link", async ({ page }) => {
    await page.goto("/accounts");

    await expect(page.getByRole("heading", { name: "Home" })).toBeVisible();
    await expect(page).toHaveURL("/");
  });
});

test.describe("baseline User API", () => {
  /**
   * The server-side half: the SPA hiding a page proves only that the page is
   * unreachable; this proves the data is, which is what matters when the
   * caller is not a browser at all.
   *
   * 403 and not 401: the session is valid and the backend accepted it, the
   * missing Permission is what was refused. A 401 here would mean the request
   * arrived unauthenticated, and the test would be passing for the wrong reason.
   */
  test("holds only the baseline Permissions and no role", async ({ page }) => {
    const me = (await (await page.request.get("/api/auth/me")).json()) as Record<string, unknown>;

    expect(me.permissions).toEqual(["counter:read", "counter:write", "patient:read"]);
    expect(me).not.toHaveProperty("role");
  });

  test("is refused every administrative read", async ({ page }) => {
    for (const path of [
      "/api/admin/accounts",
      "/api/admin/groups",
      "/api/admin/audit-events",
      "/api/admin/connectors",
      `${BACKEND_URL}/actuator/prometheus`,
    ]) {
      const response = await page.request.get(path);

      expect(response.status(), `GET ${path}`).toBe(403);
      expect(await response.text()).not.toContain("@");
    }
  });

  /**
   * The control endpoints matter more than the listings: a User who could
   * reach them could unlock or flag an administrator. The CSRF token is sent
   * deliberately — without it the chain answers 403 from the CSRF filter first,
   * and the test would pass without ever exercising the Permission check. Any
   * id will do: the Permission is refused before the target is looked up.
   */
  test("is refused unlocking or flagging a User", async ({ page }) => {
    for (const action of ["unlock", "force-password-change"]) {
      const response = await postAdminAction(page, crypto.randomUUID(), action);

      expect(response.status(), `a baseline User must not reach ${action}`).toBe(403);
    }
  });

  test("keeps its self-service and reads its counter", async ({ page }) => {
    expect((await page.request.get("/api/self")).status()).toBe(200);
    expect((await page.request.get("/api/count")).status()).toBe(200);
  });

  /**
   * Logout is how a caller ends its own session, and the session it ended is
   * refused afterwards. The User signed out is a baseline User this test
   * provisions, not the seeded `user`: ending the session `user.json` holds
   * would sign out every spec that replays it, `console-errors.spec.ts` in the
   * `admin` project included. The CSRF token is sent, because Logout is not
   * exempt from CSRF: without it the CSRF filter answers 403 and nobody is
   * signed out.
   */
  test("signs out through Logout, after which its session is refused", async ({ browser }) => {
    const admin = await browser.newContext({
      baseURL: test.info().project.use.baseURL,
      storageState: "test/e2e/.auth/admin.json",
    });
    const adminPage = await admin.newPage();
    const connector = await createConnector(adminPage, `${E2E_PREFIX}logout-${runId()}`);
    const scim = await scimApi(connector.token);
    const api = await anonymousApi();
    let userId: string | undefined;
    try {
      const userName = `${E2E_PREFIX}logout-${runId()}`;
      userId = await provisionUser(scim, userName);
      await settlePassword(userName);
      expect((await submitLoginViaApi(api, userName, OWN_PASSWORD)).status()).toBe(200);
      expect((await api.get("/api/auth/me")).status(), "signed in").toBe(200);

      const logout = await api.delete("/api/auth/logout", { headers: await csrfHeaderFor(api) });

      expect(logout.status()).toBe(204);
      expect(logout.headers()["clear-site-data"]).toBe('"cache","cookies","storage"');
      expect((await api.get("/api/auth/me")).status()).toBe(401);
    } finally {
      await api.dispose();
      if (userId !== undefined) await deprovisionUser(scim, userId);
      await scim.dispose();
      await deleteConnector(adminPage, connector.id);
      await admin.close();
    }
  });
});
