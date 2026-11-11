import { expect, test, type Locator } from "@playwright/test";

import { recordConsoleErrors } from "./console.helpers";
import { expectNoPatientInContext } from "./patient.helpers";

/**
 * The design's type (frontend/docs/DESIGN.md, Typography) as the packaged
 * bundle renders it: the built stylesheet's named type scale, and the
 * self-hosted faces it needs, fetched under the backend's own CSP. `npm run
 * dev` serves neither the built CSS nor that policy, so a face the policy
 * refuses or a level the build drops shows only here.
 *
 * In the `packaged` project, beside `packaged-csp.spec.ts`; it replays the
 * Admin session `setup` saves and signs no one in.
 */

/** The computed type of `element`, in the properties a type-scale level sets. */
async function typeOf(element: Locator) {
  return element.evaluate((node) => {
    const style = getComputedStyle(node);
    return {
      family: style.fontFamily.split(",")[0],
      size: style.fontSize,
      lineHeight: style.lineHeight,
      weight: style.fontWeight,
      letterSpacing: style.letterSpacing,
    };
  });
}

test("IBM Plex Sans 500 loads from the app's own origin with an empty console", async ({
  page,
}) => {
  const errors = recordConsoleErrors(page);
  await page.goto("/");
  // Home's Sign out button is `font-medium` text, so the page asks for the face.
  await expectNoPatientInContext(page);
  const origin = new URL(page.url()).origin;

  await expect
    .poll(async () => ({
      face: await page.evaluate(
        () =>
          [...document.fonts].find(
            (face) => face.family.replace(/"/g, "") === "IBM Plex Sans" && face.weight === "500",
          )?.status,
      ),
      fetchedFrom: await page.evaluate(() =>
        performance
          .getEntriesByType("resource")
          .map(({ name }) => name)
          .filter((name) => name.endsWith("/ibm-plex-sans-500.woff2")),
      ),
      errors,
    }))
    .toEqual({
      face: "loaded",
      fetchedFrom: [`${origin}/fonts/ibm-plex-sans-500.woff2`],
      errors: [],
    });
});

test("the page title renders on headline-lg and a card title on headline-sm", async ({ page }) => {
  await page.goto("/");
  await expectNoPatientInContext(page);

  expect({
    pageTitle: await typeOf(page.getByRole("heading", { level: 1, name: "Profile" })),
    cardTitle: await typeOf(page.getByRole("heading", { level: 3, name: "Patient" })),
  }).toEqual({
    // DESIGN.md: IBM Plex Sans 28/36, 700, -0.02em (of 28px).
    pageTitle: {
      family: '"IBM Plex Sans"',
      size: "28px",
      lineHeight: "36px",
      weight: "700",
      letterSpacing: "-0.56px",
    },
    // DESIGN.md: IBM Plex Sans 16/24, 600, no tracking.
    cardTitle: {
      family: '"IBM Plex Sans"',
      size: "16px",
      lineHeight: "24px",
      weight: "600",
      letterSpacing: "normal",
    },
  });
});
