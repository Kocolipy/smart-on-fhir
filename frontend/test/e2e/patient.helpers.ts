import { expect, type Page } from "@playwright/test";

import { cardTitled } from "./card.helpers";

/**
 * Starts recording every request the page sends for a patient, and returns
 * what it has recorded so far. Call it before navigating, so the first read
 * is caught.
 */
export function recordPatientRequests(page: Page): () => string[] {
  const sent: string[] = [];
  page.on("request", (request) => {
    const { pathname } = new URL(request.url());
    if (pathname.startsWith("/api/patient")) sent.push(`${request.method()} ${pathname}`);
  });
  return () => [...sent];
}

/**
 * Home's Patient card as a password login sees it — or an EHR launch that put
 * no patient in context: nothing is in context, so it says so.
 */
export async function expectNoPatientInContext(page: Page): Promise<void> {
  await expect(cardTitled(page, "Patient").getByText("No patient in context")).toBeVisible();
}

/** Home's Patient card showing the patient in context, by the name it lists. */
export async function expectPatientInContext(page: Page, name: string): Promise<void> {
  await expect(cardTitled(page, "Patient").getByText(name, { exact: true })).toBeVisible();
}
