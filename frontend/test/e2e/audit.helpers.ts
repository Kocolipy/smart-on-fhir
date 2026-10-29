import type { Page } from "@playwright/test";

/** The Audit page's listing. */
export const eventsTable = (page: Page) => page.getByRole("table", { name: "Audit events" });

/** The listing's body rows: the header row holds column headers, never a cell. */
export const eventRows = (page: Page) =>
  eventsTable(page)
    .getByRole("row")
    .filter({ has: page.getByRole("cell") });

/**
 * Chooses `option` in the Audit filter labelled `label`. The Operation and Outcome filters are
 * listboxes, not native `<select>`s, so `selectOption` cannot reach them: each is opened, then
 * chosen from by the option's own text.
 */
export async function chooseAuditFilter(
  page: Page,
  label: "Operation" | "Outcome",
  option: string,
) {
  await page.getByLabel(label).click();
  await page.getByRole("option", { name: option, exact: true }).click();
}

/**
 * Sets every Audit filter but the date range — an omitted one to "any", or empty — and applies
 * them.
 */
export async function applyAuditFilters(
  page: Page,
  filters: { actorId?: string; operation?: string; outcome?: string; resourceId?: string },
) {
  await chooseAuditFilter(page, "Operation", filters.operation ?? "Any operation");
  await chooseAuditFilter(page, "Outcome", filters.outcome ?? "Any outcome");
  await page.getByLabel("Actor id").fill(filters.actorId ?? "");
  await page.getByLabel("Resource id").fill(filters.resourceId ?? "");
  await page.getByRole("button", { name: "Apply filters" }).click();
}
