import { expect, test } from "@playwright/test";

import { csrfHeaderFor, submitLoginViaApi } from "./auth.helpers";
import { cardTitled } from "./card.helpers";
import { expectNoPatientInContext, recordPatientRequests } from "./patient.helpers";
import {
  anonymousApi,
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

// The seeded `user`: a baseline User, in no Group, so holding only the
// baseline Permissions every User holds. What it sees, and the pages the SPA
// routes it away from, are asserted here. The backend refusing it every
// administrative read and the Unlock needs no browser, and is the backend's
// authorization suite's to prove (`AuthorizationContractTests`).

test.describe("baseline User pages", () => {
  test("lands on Home with no patient in context, self-service, and no administration", async ({
    page,
  }) => {
    const patientRequests = recordPatientRequests(page);
    await page.goto("/");

    await expect(page.getByRole("heading", { name: "Home" })).toBeVisible();
    await expect(page).toHaveURL("/");
    await expectNoPatientInContext(page);
    expect(patientRequests()).toEqual([]);
    // The Pages card links the one page a baseline User may open.
    const pages = cardTitled(page, "Pages");
    await expect(pages.getByRole("link")).toHaveText(["Change password"]);
    await expect(page.getByRole("button")).toHaveText(["Sign out"]);
  });

  test("is routed away from the accounts page by a deep link", async ({ page }) => {
    await page.goto("/accounts");

    await expect(page.getByRole("heading", { name: "Home" })).toBeVisible();
    await expect(page).toHaveURL("/");
  });
});

test.describe("baseline User API", () => {
  test("holds only the baseline Permissions", async ({ page }) => {
    const me = (await (await page.request.get("/api/auth/me")).json()) as Record<string, unknown>;

    expect(me.permissions).toEqual(["patient:read"]);
  });

  test("keeps its self-service", async ({ page }) => {
    expect((await page.request.get("/api/self")).status()).toBe(200);
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
