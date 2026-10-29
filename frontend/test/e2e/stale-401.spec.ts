import { expect, test, type Browser, type Cookie, type Page, type Route } from "@playwright/test";

import { captureSessionCookie, expireSession, SESSION_COOKIE } from "./auth.helpers";
import { ROUTE_READY } from "./route.helpers";
import {
  deprovisionUser,
  E2E_PREFIX,
  FIXTURE_GROUPS,
  freshBrowser,
  OWN_PASSWORD,
  provisionUser,
  runId,
  scimPatch,
  settlePassword,
  workerConnector,
} from "./scim.helpers";

/**
 * A `401` ends only the session its request was sent under
 * (`frontend/docs/BACKEND_CONTRACT.md`). A read still in flight when the User
 * signs out belongs to the session that ended: its `401`, landing later, says
 * nothing about why that session ended, and nothing at all about a session
 * signed in since.
 *
 * Each test holds the Audit page's read in flight with route interception,
 * changes the session under it, then sends it on to the backend, which
 * refuses it `401` for real. The browser attaches the session cookie as a held
 * request goes on, not as the page sent it, so the jar holds the cookie of the
 * session the read was sent under — captured before the sign-out ended it —
 * for as long as the read takes, and is then given back whatever it held:
 * after a fresh sign-in, the new session's, which would otherwise answer the
 * read.
 *
 * The Users are provisioned here, in the Auditors Group for the Audit page,
 * and deleted in a `finally`; the seeded identities, whose sessions the other
 * projects replay, are never touched.
 */

const RUN = runId();
const connector = workerConnector("stale-401");

const EXPIRED_NOTICE = "Your session ended. Please sign in again.";

const AUDIT_READ_PATH = "/api/admin/audit-events";

/** The Audit page's read, its query whatever the page asks. */
const AUDIT_READ = (url: URL) => url.pathname === AUDIT_READ_PATH;

/** Set on the page's `window` once the page has handled the held read's answer. */
const HANDLED = "staleReadHandled";

/** A provisioned Auditor, settled on {@link OWN_PASSWORD}, signed in through a browser of its own. */
async function withSignedInAuditor(
  browser: Browser,
  label: string,
  body: (page: Page, userName: string) => Promise<void>,
) {
  const userName = `${E2E_PREFIX}stale-${label}-${RUN}`;
  const id = await provisionUser(connector.scim, userName);
  const context = await freshBrowser(browser);
  try {
    const added = await scimPatch(connector.scim, `/scim/v2/Groups/${FIXTURE_GROUPS.auditors}`, [
      { op: "add", path: "members", value: [{ value: id }] },
    ]);
    expect(added.status(), "adding the User to the Auditors Group").toBe(200);
    await settlePassword(userName);

    const page = await context.newPage();
    await page.goto("/login");
    await signInHere(page, userName);
    await body(page, userName);
  } finally {
    await context.close();
    await deprovisionUser(connector.scim, id);
  }
}

/**
 * Signs in through the login page already shown, with no navigation of its
 * own: a reload would end the page, and the read held in it with it.
 */
async function signInHere(page: Page, userName: string) {
  await page.getByLabel("Username").fill(userName);
  await page.getByLabel("Password").fill(OWN_PASSWORD);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByText(`Signed in as ${userName}`)).toBeVisible();
}

/**
 * Opens the Audit page from Home, holding its read; resolves to the read, held.
 *
 * The page's own handling of that read's answer changes nothing on screen
 * when the answer is stale, so the page marks when it has run ({@link HANDLED}):
 * every continuation awaiting the answer — the session request seam's,
 * through to its expiry — runs in the microtasks of the task that settles it,
 * and a task posted from there runs only after all of them.
 */
async function openAuditHoldingItsRead(page: Page): Promise<Route> {
  await page.evaluate(
    ([pathname, flag]) => {
      const send = window.fetch.bind(window);
      let first = true;
      window.fetch = (input, init) => {
        const answer = send(input, init);
        const url = new URL(
          input instanceof Request ? input.url : String(input),
          window.location.href,
        );
        if (first && url.pathname === pathname) {
          first = false;
          const handled = () => setTimeout(() => Reflect.set(window, flag, true));
          answer.then(handled, handled);
        }
        return answer;
      };
    },
    [AUDIT_READ_PATH, HANDLED] as const,
  );
  let hold!: (route: Route) => void;
  const held = new Promise<Route>((resolve) => {
    hold = resolve;
  });
  let first = true;
  await page.route(AUDIT_READ, async (route) => {
    if (first) {
      first = false;
      hold(route);
      return;
    }
    await route.continue();
  });
  await page.getByRole("link", { name: "View audit log" }).click();
  return held;
}

/** Signs out from the signed-in shell, and waits for the login page. */
async function signOut(page: Page) {
  await page.getByRole("button", { name: "Sign out" }).click();
  await expect(page.getByRole("heading", { name: "Welcome back" })).toBeVisible();
}

/**
 * Sends the held read on under the session it was sent under, `ended`, which
 * the backend refuses `401`; puts back the session cookie the jar held; and
 * waits for the page to have handled that answer. Nothing else the page sends
 * may be in flight meanwhile, or it too would go under the ended session.
 */
async function sendOnUnder(page: Page, read: Route, ended: Cookie) {
  const context = page.context();
  const standing = (await context.cookies()).find(({ name }) => name === SESSION_COOKIE);
  await context.addCookies([ended]);
  const answered = page.waitForResponse((response) => response.request() === read.request());
  await read.continue();
  expect((await answered).status(), "the backend refuses the ended session").toBe(401);
  if (standing === undefined) await expireSession(context);
  else await context.addCookies([standing]);
  await page.waitForFunction((flag) => Reflect.get(window, flag) === true, HANDLED);
}

test("a read answering 401 after the User signed out leaves the login page saying nothing", async ({
  browser,
}) => {
  test.setTimeout(90_000);
  await withSignedInAuditor(browser, "signed-out", async (page) => {
    const read = await openAuditHoldingItsRead(page);
    const ended = await captureSessionCookie(page.context());
    await signOut(page);

    await sendOnUnder(page, read, ended);

    await expect(page.getByText(EXPIRED_NOTICE)).toHaveCount(0);
    // The login page reads its notice from the redirect that brought the User
    // there, so the sign-out's own shows nothing whatever the 401 did. A
    // protected route reached now redirects afresh, carrying the reason the
    // session ended with: none, for a sign-out. In-app, so the page lives on.
    await page.evaluate(() => {
      window.history.pushState(null, "", "/audit");
      window.dispatchEvent(new PopStateEvent("popstate"));
    });
    await expect(page).toHaveURL(/\/login$/);
    await expect(page.getByRole("heading", { name: "Welcome back" })).toBeVisible();
    await expect(page.getByText(EXPIRED_NOTICE)).toHaveCount(0);
  });
});

test("a read answering 401 after the User signed back in leaves the new session signed in", async ({
  browser,
}) => {
  test.setTimeout(90_000);
  await withSignedInAuditor(browser, "signed-back-in", async (page, userName) => {
    const read = await openAuditHoldingItsRead(page);
    const ended = await captureSessionCookie(page.context());
    await signOut(page);
    await signInHere(page, userName);
    // The new session's own read of the page, settled, so nothing but the
    // held read is in flight when it goes on.
    await ROUTE_READY["/audit"]!(page);

    await sendOnUnder(page, read, ended);

    // Still on the page the sign-in returned to, the one signed out of.
    await expect(page).toHaveURL(/\/audit$/);
    await expect(page.getByText(`Signed in as ${userName}`)).toBeVisible();
    // And the session goes on working: the next page's read succeeds.
    await page.getByRole("link", { name: "Back to home" }).click();
    await page.getByRole("link", { name: "View audit log" }).click();
    await ROUTE_READY["/audit"]!(page);
    await expect(page.getByText(`Signed in as ${userName}`)).toBeVisible();
  });
});
