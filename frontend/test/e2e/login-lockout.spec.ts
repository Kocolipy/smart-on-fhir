import { expect, test } from "@playwright/test";

import { LOCKOUT_MAX_ATTEMPTS, submitLogin, submitLoginViaApi } from "./auth.helpers";
import {
  anonymousApi,
  deprovisionUser,
  E2E_PREFIX,
  freshBrowser,
  OWN_PASSWORD,
  provisionUser,
  runId,
  settlePassword,
  workerConnector,
} from "./scim.helpers";

/**
 * A lockout reached where it usually is — at the login page — and what that
 * page then says. `change-password.spec.ts` reaches a lockout through the
 * password change instead, and `accounts-admin.spec.ts` through the API.
 *
 * In the `admin` project only because the User is provisioned through an
 * Admin's connector; the sign-ins themselves run in a browser of their own.
 */

const RUN = runId();
const connector = workerConnector("lockout");
const INCORRECT = "The username or password is incorrect.";

test("a lockout at the login page refuses the right password exactly as a wrong one", async ({
  browser,
}) => {
  test.setTimeout(90_000);
  const userName = `${E2E_PREFIX}login-locked-${RUN}`;
  const id = await provisionUser(connector.scim, userName);
  const context = await freshBrowser(browser);
  try {
    await settlePassword(userName);
    const page = await context.newPage();

    for (let attempt = 1; attempt <= LOCKOUT_MAX_ATTEMPTS; attempt += 1) {
      await submitLogin(page, userName, `Wrong-Guess-${attempt}-xyz`);
      await expect(page.getByRole("alert")).toHaveText(INCORRECT);
    }

    // The right password, now: refused, with the same words and on the same
    // page. Saying "locked" here would tell a guesser the name is real and
    // that the guessing worked.
    await submitLogin(page, userName, OWN_PASSWORD);
    await expect(page.getByRole("alert")).toHaveText(INCORRECT);
    await expect(page).toHaveURL(/\/login$/);
    await expect(page.getByRole("button", { name: "Sign in" })).toBeEnabled();

    // The same holds on the wire: the locked User's right password and a
    // name that does not exist are answered alike.
    const api = await anonymousApi();
    try {
      const locked = await submitLoginViaApi(api, userName, OWN_PASSWORD);
      const unknown = await submitLoginViaApi(api, `${E2E_PREFIX}nobody-${RUN}`, OWN_PASSWORD);
      expect(locked.status()).toBe(401);
      expect(unknown.status()).toBe(401);
      expect(await locked.text()).toBe(await unknown.text());
    } finally {
      await api.dispose();
    }
  } finally {
    await context.close();
    await deprovisionUser(connector.scim, id);
  }
});
