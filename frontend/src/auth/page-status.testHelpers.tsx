// Test support only: excluded from the production project, so never hot-reloaded.
/* eslint-disable react-refresh/only-export-components */
import { screen } from "@testing-library/react";
import type { ReactNode } from "react";

import { PageStatusScope } from "./page-status";

// A hook suite under `src/auth/` reports into the real page status, as a page
// inside the signed-in layout does, and reads the line back off the DOM — the
// only way anything reads it. The layout's own markup is the page suites'.

const LINE = "page-status-line";

/** The real page status around `children`, its line rendered for {@link shownLine} to read. */
export function InPageStatus({
  children,
  resetKey = "/page",
}: {
  children: ReactNode;
  resetKey?: string;
}) {
  return (
    <PageStatusScope resetKey={resetKey}>
      {(error) => (
        <>
          {children}
          {error === null ? null : <output data-testid={LINE}>{error}</output>}
        </>
      )}
    </PageStatusScope>
  );
}

/** What the page status's line shows, or `null` when it shows nothing. */
export function shownLine(): string | null {
  return screen.queryByTestId(LINE)?.textContent ?? null;
}
