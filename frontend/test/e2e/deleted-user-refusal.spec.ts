import { expect, test } from "@playwright/test";

import { applyAuditFilters, eventRows } from "./audit.helpers";
import { LOCKOUT_MAX_ATTEMPTS, submitLogin } from "./auth.helpers";
import {
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
 * A User a connector deleted is nobody at the login page: its own, once-right
 * password is refused exactly as a wrong one, and the audit trail records each
 * attempt against nobody — a `LOGIN_FAILURE` with reason `UNKNOWN_ACCOUNT` and
 * no subject — never against the deleted User, so as many attempts as the
 * lockout threshold impose no Lockout on it either
 * (docs/domain/authentication.md, "A Login's refusal belongs to the User its
 * one read found").
 *
 * A browser cannot land the deletion inside a Login's own window — between its
 * read of the User and the login-state lock — which the backend's
 * `PasswordLoginUnderLockTests` stage deterministically. This holds the
 * outcome they guard end to end.
 *
 * In the `admin` project because the User is provisioned through an Admin's
 * connector and the trail is read on the Admin's Audit page; the sign-ins run
 * in a browser of their own.
 */

const RUN = runId();
const connector = workerConnector("deleted-refusal");
const INCORRECT = "The username or password is incorrect.";

test("a deleted User's credentials are refused at the login page and recorded against nobody", async ({
  browser,
  page,
}) => {
  test.setTimeout(120_000);
  const userName = `${E2E_PREFIX}deleted-refusal-${RUN}`;
  const id = await provisionUser(connector.scim, userName);
  try {
    await settlePassword(userName);
  } finally {
    await deprovisionUser(connector.scim, id);
  }

  const context = await freshBrowser(browser);
  try {
    const guesser = await context.newPage();
    for (let attempt = 1; attempt <= LOCKOUT_MAX_ATTEMPTS; attempt += 1) {
      await submitLogin(guesser, userName, OWN_PASSWORD);
      await expect(guesser.getByRole("alert")).toHaveText(INCORRECT);
      await expect(guesser).toHaveURL(/\/login$/);
    }
  } finally {
    await context.close();
  }

  await page.goto("/audit");
  await expect(page.getByRole("heading", { name: "Audit", exact: true })).toBeVisible();

  // The page shows the refusal as a subject-less failure for an unknown account.
  // The trail is shared and such a row names nobody by design, so no row can be
  // told apart as this run's own: what shows this run's attempts were recorded
  // against nobody is the two Resource-id checks after it.
  await applyAuditFilters(page, { operation: "LOGIN_FAILURE" });
  const unknownAccount = eventRows(page)
    .filter({ has: page.getByRole("cell", { name: "User", exact: true }) })
    .filter({ hasText: "UNKNOWN_ACCOUNT" })
    .filter({ hasText: "login method: password" });
  await expect(unknownAccount.first()).toBeVisible();

  // None names the deleted User, and no Lockout was set on it.
  await applyAuditFilters(page, { operation: "LOGIN_FAILURE", resourceId: id });
  await expect(page.getByText("No audit events match the current filters.")).toBeVisible();
  await applyAuditFilters(page, { operation: "LOCKOUT_SET", resourceId: id });
  await expect(page.getByText("No audit events match the current filters.")).toBeVisible();
});
