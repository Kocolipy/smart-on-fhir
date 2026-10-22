import { expect, test } from "@playwright/test";

import { LOCKOUT_MAX_ATTEMPTS, loginAsFixture, submitLoginViaApi } from "./auth.helpers";
import {
  anonymousApi,
  deprovisionUser,
  E2E_PREFIX,
  freshBrowser,
  OWN_PASSWORD,
  provisionUser,
  rowOf,
  runId,
  settlePassword,
  workerConnector,
} from "./scim.helpers";

/**
 * The helpdesk power delegated through a Group: the `helpdesk` development
 * fixture is a member of a Group that carries `account:unlock` (and
 * `user:read`), and nothing else — it is not the Superuser. It unlocks a locked
 * User from Manage accounts, as a helpdesk operator does for someone who
 * forgot their password.
 *
 * In the `admin` project: the replayed Admin session issues the connector that
 * provisions the locked User, and the helpdesk signs in from a browser of its
 * own, so the Admin session is untouched. Only `dev-roles.spec.ts` signs in as
 * the same fixture, in the `guest` project, which runs after this one.
 */

const directory = workerConnector("helpdesk");
const LOCKED_USER = `${E2E_PREFIX}helpdesk-locked-${runId()}`;

test("a helpdesk User unlocks a locked User from Manage accounts", async ({ browser }) => {
  // A provision, a self-service change, a lockout's worth of refused logins and
  // two sign-ins, each a password hash on the backend.
  test.setTimeout(90_000);
  const id = await provisionUser(directory.scim, LOCKED_USER);
  const helpdesk = await freshBrowser(browser);
  try {
    await settlePassword(LOCKED_USER);
    const guesser = await anonymousApi();
    try {
      for (let attempt = 0; attempt < LOCKOUT_MAX_ATTEMPTS; attempt += 1) {
        expect((await submitLoginViaApi(guesser, LOCKED_USER, "not-the-password")).status()).toBe(
          401,
        );
      }
    } finally {
      await guesser.dispose();
    }

    const page = await helpdesk.newPage();
    await loginAsFixture(page, "helpdesk");
    await page.getByRole("link", { name: "Manage accounts" }).click();
    await expect(page.getByRole("heading", { name: "Accounts" })).toBeVisible();
    const row = rowOf(page, page.getByRole("table", { name: "Users" }), LOCKED_USER);
    await expect(row.getByText("Locked: failed logins", { exact: true })).toBeVisible();

    await row.getByRole("button", { name: `Unlock ${LOCKED_USER}` }).click();
    await expect(row.getByText("Not locked")).toBeVisible();
    await expect(row.getByText("Required")).toBeVisible();
    await expect(row.getByRole("button", { name: /^Unlock/ })).toHaveCount(0);

    // The backend's doing: the password works again, into the change flow only.
    const own = await anonymousApi();
    try {
      expect((await submitLoginViaApi(own, LOCKED_USER, OWN_PASSWORD)).status()).toBe(200);
      const me = (await (await own.get("/api/auth/me")).json()) as {
        passwordChangeRequired?: boolean;
      };
      expect(me.passwordChangeRequired).toBe(true);
    } finally {
      await own.dispose();
    }
  } finally {
    await helpdesk.close();
    await deprovisionUser(directory.scim, id);
  }
});
