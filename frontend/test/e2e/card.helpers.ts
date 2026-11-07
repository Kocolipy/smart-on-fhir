import type { Locator, Page } from "@playwright/test";

/**
 * The card whose title is the heading `name`: the title sits in the card's
 * header, so the card is the heading's grandparent. The one place an e2e spec
 * depends on the `Card` family's markup. `exact` matches the whole title, for
 * a title another card's contains.
 */
export function cardTitled(
  page: Page,
  name: string,
  { exact = false }: { exact?: boolean } = {},
): Locator {
  return page.getByRole("heading", { exact, name }).locator("../..");
}
