import { expect, type Page } from "@playwright/test";

/** The Audit page's listing. */
export const eventsTable = (page: Page) => page.getByRole("table", { name: "Audit events" });

/** The listing's body rows: the header row holds column headers, never a cell. */
export const eventRows = (page: Page) =>
  eventsTable(page)
    .getByRole("row")
    .filter({ has: page.getByRole("cell") });

/** The toolbar's Operation picker, opened from its trigger, whose name states the selection. */
export const operationPicker = (page: Page) => page.getByRole("button", { name: /^Operation: / });

/**
 * Selects exactly `operations` in the Operation picker, finding each through the search, and
 * closes it. Each change applies at once, as the page's own, over the typed fields last applied.
 */
export async function selectAuditOperations(page: Page, operations: readonly string[]) {
  await operationPicker(page).click();
  const search = page.getByRole("combobox", { name: "Operations" });
  await expect(search).toBeFocused();
  const clear = page.getByRole("option", { name: "Clear selection" });
  if ((await clear.count()) > 0) await clear.click();
  for (const operation of operations) {
    await search.fill(operation);
    await page.getByRole("option", { name: operation, exact: true }).click();
  }
  await page.keyboard.press("Escape");
}

/**
 * Sets every Audit filter but the date range — an omitted one to "any", or empty — and applies
 * them. The typed fields go first, so a stale invalid one cannot refuse a change of the picker or
 * the toggle, and Apply reads the whole set last.
 */
export async function applyAuditFilters(
  page: Page,
  filters: {
    actorId?: string;
    operation?: string;
    outcome?: "Any" | "Failure" | "Success";
    resourceId?: string;
  },
) {
  await page.getByLabel("Actor id").fill(filters.actorId ?? "");
  await page.getByLabel("Resource id").fill(filters.resourceId ?? "");
  await selectAuditOperations(page, filters.operation === undefined ? [] : [filters.operation]);
  await page.getByRole("radio", { name: filters.outcome ?? "Any", exact: true }).click();
  await page.getByRole("button", { name: "Apply", exact: true }).click();
}
