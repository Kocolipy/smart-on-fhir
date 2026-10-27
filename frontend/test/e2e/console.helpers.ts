import type { ConsoleMessage, Page } from "@playwright/test";

/**
 * Collects every console error and uncaught exception `page` raises from here
 * on, each prefixed with the URL the page was on, except the console errors
 * `expected` accepts. Shared by the two specs that load pages with an empty
 * console as their subject: `smoke.spec.ts` owns the signed-out login page,
 * `console-errors.spec.ts` every signed-in route.
 */
export function recordConsoleErrors(
  page: Page,
  expected: (message: ConsoleMessage) => boolean = () => false,
): string[] {
  const errors: string[] = [];
  page.on("console", (message) => {
    if (message.type() === "error" && !expected(message)) {
      errors.push(`${page.url()}: ${message.text()}`);
    }
  });
  page.on("pageerror", (error) => errors.push(`${page.url()}: ${error.message}`));
  return errors;
}
