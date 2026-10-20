import { expect, test, type Page } from "@playwright/test";

import { expectNoPatientInContext, recordPatientRequests } from "./patient.helpers";
import { ROUTE_READY } from "./route.helpers";

/**
 * The production bundle under the backend's own Content Security Policy.
 *
 * Every other spec runs against `npm run dev`, which serves neither the built
 * bundle nor the CSP: Vite injects inline scripts of its own, and the header is
 * the backend's. So a build that adds an inline `<script>`, an `eval` or a
 * third-party origin passes every other gate and breaks only once packaged.
 * This spec loads each route from the JAR that `make integration-test`
 * starts, and fails on any violation the browser reports.
 *
 * In the `packaged` project, declared only when that run's environment names
 * the JAR's origin. It replays the Admin session `setup` saves — a cookie is
 * scoped to the host, not the port, so the JAR honours it — and signs no one in.
 */

declare global {
  interface Window {
    cspViolations: string[];
  }
}

/**
 * Records every CSP violation the page reports, from before its first script
 * runs, and every uncaught exception besides. Console errors are left out: the
 * browser logs any refused request as one, a guest's `401` from
 * `GET /api/auth/me` among them, and reports a violation as the event as well.
 */
async function recordViolations(page: Page): Promise<() => Promise<string[]>> {
  await page.addInitScript(() => {
    window.cspViolations = [];
    document.addEventListener("securitypolicyviolation", (event) => {
      window.cspViolations.push(`${event.violatedDirective} blocked ${event.blockedURI}`);
    });
  });
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(`${page.url()}: ${error.message}`));
  return async () => [...(await page.evaluate(() => window.cspViolations)), ...errors];
}

/** Opens `path` and asserts the document carried a CSP forbidding inline script. */
async function openUnderPolicy(page: Page, path: string) {
  const response = await page.goto(path);
  expect(response?.status(), `GET ${path}`).toBe(200);
  // Without this the spec passes vacuously against a server that sends no policy.
  expect(response?.headers()["content-security-policy"], `the CSP on ${path}`).toMatch(
    /script-src 'self'/,
  );
}

test("the login page loads under the CSP without a violation", async ({ browser }) => {
  const context = await browser.newContext({
    baseURL: test.info().project.use.baseURL,
    storageState: { cookies: [], origins: [] },
  });
  try {
    const page = await context.newPage();
    const violations = await recordViolations(page);

    await openUnderPolicy(page, "/login");
    await expect(page.getByRole("heading", { name: "Welcome back" })).toBeVisible();

    expect(await violations()).toEqual([]);
  } finally {
    await context.close();
  }
});

test("every signed-in route loads under the CSP without a violation", async ({ page }) => {
  const violations = await recordViolations(page);
  const patientRequests = recordPatientRequests(page);

  for (const [path, ready] of Object.entries(ROUTE_READY)) {
    // A deep link, so the backend's SPA fallback serves each route's shell too.
    await openUnderPolicy(page, path);
    await expect(page).toHaveURL(path);
    await ready(page);
    await expect(page.getByRole("alert")).toHaveCount(0);
  }

  expect(await violations()).toEqual([]);
  // Home's Patient card rendered its empty state: no patient is in context to read.
  expect(patientRequests()).toEqual([]);
});

// The detector has to be shown to fire: an inline script, which the policy
// forbids, is reported rather than run.
test("an inline script is refused and reported", async ({ page }) => {
  const violations = await recordViolations(page);
  await openUnderPolicy(page, "/");
  await expectNoPatientInContext(page);

  await page.evaluate(() => {
    const script = document.createElement("script");
    script.textContent = "document.body.dataset.inlineRan = 'yes'";
    document.head.append(script);
  });

  await expect.poll(violations).toContainEqual(expect.stringMatching(/^script-src/));
  expect(await page.evaluate(() => document.body.dataset.inlineRan)).toBeUndefined();
});
