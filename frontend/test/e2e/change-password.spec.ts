import { expect, test, type Page } from "@playwright/test";

import { LOCKOUT_MAX_ATTEMPTS, submitLogin, submitLoginViaApi } from "./auth.helpers";
import {
  anonymousApi,
  deprovisionUser,
  E2E_PREFIX,
  freshBrowser,
  OWN_PASSWORD as OWN,
  provisionUser,
  rowOf,
  runId,
  settlePassword,
  workerConnector,
} from "./scim.helpers";

/**
 * The `/change-password` route driven end to end: a forced change confined to
 * the route, a voluntary one from the showcase, and a lockout reached through
 * the route itself.
 *
 * Runs in the `admin` project because the forced change is made from the
 * Accounts page. Every User here is PROVISIONED by the test over SCIM — never a
 * seeded identity, which a forced change or a lockout would leave unusable for
 * the other specs — and is deleted in a `finally`. One connector serves every
 * test a worker runs from this file (`workerConnector`). Each User signs in
 * through a browser context of its own, so nothing touches the admin session
 * this project replays.
 */

/** The backend's lockout threshold; see `LOCKOUT_MAX_ATTEMPTS`. */
const REFUSALS_BEFORE_LOCKOUT = LOCKOUT_MAX_ATTEMPTS;

const RUN = runId();
/** What each User changes to through the page. */
const REPLACEMENT = "Replacement-Secret-3k";

interface Provisioned {
  userId: string;
  userName: string;
}

const connector = workerConnector("cpw");

/** A User of the test's own, settled on a password it chose, so its change flag starts clear. */
async function provision(label: string): Promise<Provisioned> {
  const userName = `${E2E_PREFIX}cp-${label}-${RUN}`;
  const userId = await provisionUser(connector.scim, userName);
  await settlePassword(userName);
  return { userId, userName };
}

async function deprovision(provisioned: Provisioned | undefined) {
  if (provisioned !== undefined) await deprovisionUser(connector.scim, provisioned.userId);
}

const heading = (page: Page) => page.getByRole("heading", { name: "Change your password" });

async function submitChange(page: Page, current: string, next: string, confirm: string = next) {
  await page.getByLabel("Current password").fill(current);
  await page.getByLabel("New password", { exact: true }).fill(next);
  await page.getByLabel("Confirm new password").fill(confirm);
  await page.getByRole("button", { name: "Change password" }).click();
}

async function expectFieldsCleared(page: Page) {
  for (const label of ["Current password", "Confirm new password"]) {
    await expect(page.getByLabel(label)).toHaveValue("");
  }
  await expect(page.getByLabel("New password", { exact: true })).toHaveValue("");
}

/** After a successful change: on login, told why, and the new password works. */
async function expectSignedOutThenSignIn(page: Page, userName: string, password: string) {
  await expect(page.getByRole("heading", { name: "Welcome back" })).toBeVisible();
  await expect(page).toHaveURL(/\/$/);
  await expect(page.getByRole("status")).toHaveText(
    "Your password was changed. Sign in with your new password.",
  );

  await submitLogin(page, userName, password);
  await expect(page).toHaveURL(/\/showcase$/);
  await expect(page.getByText(`Signed in as ${userName}`)).toBeVisible();
  const me = (await (await page.request.get("/api/auth/me")).json()) as {
    passwordChangeRequired: boolean;
  };
  expect(me.passwordChangeRequired).toBe(false);
}

test("an Admin's forced change confines the User to /change-password until it is made", async ({
  browser,
  page,
}) => {
  test.setTimeout(120_000);
  let user: Provisioned | undefined;
  const context = await freshBrowser(browser);
  try {
    user = await provision("forced");

    // The Admin forces the change from the Accounts page.
    await page.goto("/accounts");
    const row = rowOf(page, page.getByRole("table", { name: "Users" }), user.userName);
    await row.getByRole("button", { name: `Force password change for ${user.userName}` }).click();
    await expect(row.getByText("Required")).toBeVisible();

    // The User signs in and lands on the change, not the showcase.
    const own = await context.newPage();
    await submitLogin(own, user.userName, OWN);
    await expect(own).toHaveURL(/\/change-password$/);
    await expect(heading(own)).toBeVisible();
    await expect(
      own.getByText("Your password must be replaced before you can continue.", { exact: false }),
    ).toBeVisible();
    await expect(own.getByRole("button", { name: "Sign out" })).toBeVisible();

    // Confined: a direct navigation elsewhere is sent back.
    for (const elsewhere of ["/showcase", "/accounts", "/", "/no-such-page"]) {
      await own.goto(elsewhere);
      await expect(own).toHaveURL(/\/change-password$/);
      await expect(heading(own)).toBeVisible();
    }

    // A wrong current password is refused, and the fields are cleared.
    await submitChange(own, "Not-The-Current-One-1", REPLACEMENT);
    await expect(own.getByRole("alert")).toHaveText("The current password is incorrect.");
    await expectFieldsCleared(own);
    await expect(own).toHaveURL(/\/change-password$/);

    // A policy-violating password is refused by the rule the backend names.
    // Reuse, not length: the browser's `minLength` now holds back a short one
    // before it is sent (see the policy test below).
    await submitChange(own, OWN, OWN);
    await expect(own.getByRole("alert")).toHaveText(
      /^The new password must differ from the current password and the \d+ most recent ones$/,
    );
    await expectFieldsCleared(own);
    expect(await own.content()).not.toContain(OWN);

    // The change itself: back to login, then in with the new password, unflagged.
    await submitChange(own, OWN, REPLACEMENT);
    await expectSignedOutThenSignIn(own, user.userName, REPLACEMENT);
  } finally {
    await context.close();
    await deprovision(user);
  }
});

test("an unflagged User changes its password voluntarily from the showcase", async ({
  browser,
}) => {
  test.setTimeout(120_000);
  let user: Provisioned | undefined;
  const context = await freshBrowser(browser);
  try {
    user = await provision("voluntary");

    const own = await context.newPage();
    await submitLogin(own, user.userName, OWN);
    await expect(own).toHaveURL(/\/showcase$/);

    await own.getByRole("link", { name: "Change password" }).click();
    await expect(own).toHaveURL(/\/change-password$/);
    await expect(
      own.getByText("Choose a new password for your account.", { exact: false }),
    ).toBeVisible();
    await expect(own.getByRole("link", { name: "Back" })).toBeVisible();

    await submitChange(own, OWN, REPLACEMENT);
    await expectSignedOutThenSignIn(own, user.userName, REPLACEMENT);
  } finally {
    await context.close();
    await deprovision(user);
  }
});

test("the form states the policy, and the browser holds back a too-short password", async ({
  browser,
}) => {
  test.setTimeout(120_000);
  let user: Provisioned | undefined;
  const context = await freshBrowser(browser);
  try {
    user = await provision("policy");

    const own = await context.newPage();
    await submitLogin(own, user.userName, OWN);
    await expect(own).toHaveURL(/\/showcase$/);
    await own.getByRole("link", { name: "Change password" }).click();
    await expect(heading(own)).toBeVisible();

    // The rules are stated before anything is typed, and are the new field's description.
    const newPassword = own.getByLabel("New password", { exact: true });
    await expect(newPassword).toHaveAccessibleDescription(
      [
        "12 to 256 characters long",
        "Must not contain your user name",
        "Must not reuse your current or recent passwords",
      ].join(" "),
    );

    // Too short: the browser's own constraint validation stops the submit, so
    // nothing reaches the backend and no refusal is rendered.
    const changes: string[] = [];
    own.on("request", (sent) => {
      if (sent.url().endsWith("/api/auth/change-password")) changes.push(sent.method());
    });
    await submitChange(own, OWN, "short-1");
    expect(await newPassword.evaluate((input: HTMLInputElement) => input.validity.tooShort)).toBe(
      true,
    );
    await expect(own.getByRole("alert")).toHaveCount(0);
    await expect(heading(own)).toBeVisible();
    expect(changes).toEqual([]);

    // Long enough but containing the user name: the browser lets it through,
    // and the backend's rule — one the page stated — is shown verbatim.
    await submitChange(own, OWN, `${user.userName}-Secret-5w`);
    await expect(own.getByRole("alert")).toHaveText(
      "The new password must not contain the user name",
    );
    expect(changes).toEqual(["POST"]);
    await expectFieldsCleared(own);
  } finally {
    await context.close();
    await deprovision(user);
  }
});

test("wrong current passwords lock the account, ending the session, and login says an Admin must Unlock it", async ({
  browser,
}) => {
  test.setTimeout(120_000);
  let user: Provisioned | undefined;
  const context = await freshBrowser(browser);
  try {
    user = await provision("locked");

    const own = await context.newPage();
    await submitLogin(own, user.userName, OWN);
    await expect(own).toHaveURL(/\/showcase$/);
    await own.getByRole("link", { name: "Change password" }).click();
    await expect(heading(own)).toBeVisible();

    for (let attempt = 1; attempt < REFUSALS_BEFORE_LOCKOUT; attempt += 1) {
      await submitChange(own, `Wrong-Guess-${attempt}-xyz`, REPLACEMENT);
      await expect(own.getByRole("alert")).toHaveText("The current password is incorrect.");
    }
    await submitChange(own, "Wrong-Guess-last-xyz", REPLACEMENT);

    // The lockout ended the session, so the SPA's does too: the visitor lands
    // on login, which says why, with no return destination to the change page.
    await expect(own.getByRole("heading", { name: "Welcome back" })).toBeVisible();
    await expect(own).toHaveURL(/\/$/);
    await expect(own.getByRole("status")).toHaveText(
      "Too many incorrect passwords: the account is now locked and your session has ended. An Admin must Unlock the account before you can sign in again.",
    );

    // The backend's side of it: this session is gone and the password no longer signs in.
    expect((await own.request.get("/api/auth/me")).status()).toBe(401);
    const api = await anonymousApi();
    try {
      expect((await submitLoginViaApi(api, user.userName, OWN)).status()).toBe(401);
    } finally {
      await api.dispose();
    }
  } finally {
    await context.close();
    await deprovision(user);
  }
});
