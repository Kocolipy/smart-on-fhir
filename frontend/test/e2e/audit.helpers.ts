import { expect, type Locator, type Page } from "@playwright/test";

/** The Audit page's listing. */
export const eventsTable = (page: Page) => page.getByRole("table", { name: "Audit events" });

/**
 * The listing's event rows, one per event: each is headed by when it occurred, so neither the
 * header row nor an opened detail row beneath an event is one.
 */
export const eventRows = (page: Page) =>
  eventsTable(page)
    .getByRole("row")
    .filter({ has: page.getByRole("rowheader") });

/**
 * A column an event row shows at a glance. Written out by hand rather than imported from the
 * page's `AUDIT_COLUMNS`, so the journeys hold the page to the headings the issue named instead
 * of agreeing with whatever it renders.
 */
type AuditColumn = "Occurred" | "Operation" | "Outcome" | "Actor" | "Resource";

/**
 * `row`'s cell under the column headed `heading`, found by the table's own headings, so a spec
 * reads a column by its name and never by where it sits.
 */
export async function eventCell(row: Locator, heading: AuditColumn): Promise<Locator> {
  const headings = await eventsTable(row.page()).getByRole("columnheader").allTextContents();
  const index = headings.indexOf(heading);
  expect(index, `the ${heading} column`).toBeGreaterThanOrEqual(0);
  return row.getByRole("rowheader").or(row.getByRole("cell")).nth(index);
}

/** The status badge in `row`'s Outcome cell: "Success" or "Failure". */
export const outcomeBadge = async (row: Locator) =>
  (await eventCell(row, "Outcome")).locator('[data-slot="badge"]');

/**
 * A field an event's detail names. Like {@link AuditColumn}, a hand-written oracle for the terms
 * the page's `EventDetail` lists, not a copy imported from it.
 */
type AuditDetail = "Changed paths" | "Status" | "Request" | "Details";

/**
 * Opens `row`'s detail through its disclosure button and hands back the region it opened, whose
 * fields {@link detailField} reads. The region is the one the button's `aria-controls` names: two
 * events of one operation in one minute share a name, but never an id.
 */
export async function openEventDetail(row: Locator): Promise<Locator> {
  const control = row.getByRole("button", { name: /^Details of / });
  await control.click();
  await expect(control).toHaveAttribute("aria-expanded", "true");
  const id = await control.getAttribute("aria-controls");
  const region = row.page().locator(`[id="${id}"]`);
  await expect(region).toHaveRole("region");
  return region;
}

/**
 * The value of `term` in an opened detail `region`: the definition paired with that term, found by
 * the term's place among the region's own terms, as {@link eventCell} finds a column.
 */
export async function detailField(region: Locator, term: AuditDetail): Promise<Locator> {
  const terms = await region.getByRole("term").allTextContents();
  const index = terms.indexOf(term);
  expect(index, `the ${term} field`).toBeGreaterThanOrEqual(0);
  return region.getByRole("definition").nth(index);
}

/** The toolbar's Operation picker, opened from its trigger, whose name states the selection. */
export const operationPicker = (page: Page) => page.getByRole("button", { name: /^Operation: / });

/**
 * Selects exactly the operations labelled `labels` in the Operation picker, finding each through
 * the search by its label, and closes it. Each change applies at once, as the page's own, over the
 * typed fields last applied.
 */
export async function selectAuditOperations(page: Page, labels: readonly string[]) {
  await operationPicker(page).click();
  const search = page.getByRole("combobox", { name: "Operations" });
  await expect(search).toBeFocused();
  const clear = page.getByRole("option", { name: "Clear selection" });
  if ((await clear.count()) > 0) await clear.click();
  for (const label of labels) {
    await search.fill(label);
    await page.getByRole("option", { name: label, exact: true }).click();
  }
  await page.keyboard.press("Escape");
}

/**
 * Sets every Audit filter but the date range — an omitted one to "any", or empty — and applies
 * them; `operation` is the operation's label, as the picker shows it. The typed fields go first,
 * so a stale invalid one cannot refuse a change of the picker or the toggle, and Apply reads the
 * whole set last.
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
