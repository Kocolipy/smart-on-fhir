import { expect, test } from "@playwright/test";

import { recordConsoleErrors } from "./console.helpers";
import { ROUTE_READY } from "./route.helpers";

/**
 * Every authenticated route loads with an empty console, as an Admin and as a
 * User. The smoke suite checks the login page; these are the pages that decode
 * API bodies (#60) and could trip the error boundary, so a contract that
 * drifted between the backend and the SPA shows up here as a console error or
 * an alert before it shows up as a wrong screen.
 *
 * In the `routes` project, after every project whose specs create and delete
 * connectors: Accounts reads each listed connector's tokens, and one deleted
 * between the listing and that read would show an alert here that is no
 * fault of the route. It runs after `user` and ahead of `guest`, so
 * `user.json` and `admin.json` still replay live sessions here.
 */

const IDENTITIES = [
  { role: "admin", routes: ["/", "/accounts", "/audit", "/change-password"] },
  {
    role: "user",
    routes: ["/", "/change-password"],
    storageState: "test/e2e/.auth/user.json",
  },
] as const;

for (const identity of IDENTITIES) {
  test(`every ${identity.role} route loads without console errors`, async ({ browser, page }) => {
    // The project's own page replays the Admin; the User gets a context of its own.
    const target =
      "storageState" in identity
        ? await (
            await browser.newContext({
              baseURL: test.info().project.use.baseURL,
              storageState: identity.storageState,
            })
          ).newPage()
        : page;
    try {
      const errors = recordConsoleErrors(target);
      for (const route of identity.routes) {
        await target.goto(route);
        // Landing where asked proves the route rendered for this role rather
        // than redirecting; no alert means neither a refused load nor the
        // error boundary's fallback.
        await expect(target).toHaveURL(route);
        await ROUTE_READY[route]!(target);
        await expect(target.getByRole("alert")).toHaveCount(0);
      }
      expect(errors).toEqual([]);
    } finally {
      if (target !== page) await target.context().close();
    }
  });
}
