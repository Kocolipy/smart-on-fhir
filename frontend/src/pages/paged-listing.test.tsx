import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";

import type { ReadView } from "@/auth/use-gated-read";

import { PagedListing } from "./paged-listing";

interface WidgetPage {
  startIndex: number;
  totalResults: number;
  widgets: string[];
}

const COPY = {
  empty: "No widgets exist.",
  loading: "Loading widgets…",
  noun: "Widgets",
  pagerLabel: "Widget pages",
};

/** A page of widgets: these, from `startIndex`, of `totalResults` in all. */
const page = (widgets: string[], startIndex = 1, totalResults = 5): WidgetPage => ({
  startIndex,
  totalResults,
  widgets,
});

/** The listing over `view`; `onReadAgain: null` leaves reading again to the page, as the audit trail does. */
function renderListing(
  view: ReadView<WidgetPage>,
  {
    disabled,
    onMove = () => undefined,
    onReadAgain = () => undefined,
  }: {
    disabled?: boolean;
    onMove?: (startIndex: number) => void;
    onReadAgain?: (() => void) | null;
  } = {},
) {
  return render(
    <div data-testid="listing">
      <PagedListing
        copy={COPY}
        disabled={disabled}
        onMove={onMove}
        onReadAgain={onReadAgain ?? undefined}
        pageSize={2}
        rows={(data) => data.widgets}
        view={view}
      >
        {({ rows, startIndex, totalResults }) => (
          <p data-testid="rows">
            {rows.join(", ")} from {startIndex} of {totalResults}
          </p>
        )}
      </PagedListing>
    </div>,
  );
}

const pager = () => screen.queryByRole("navigation", { name: "Widget pages" });

describe("PagedListing", () => {
  it("hands its rows the page they came from", () => {
    renderListing({ data: page(["anvil", "bolt"], 3), kind: "ready" });

    expect(screen.getByTestId("rows").textContent).toBe("anvil, bolt from 3 of 5");
  });

  it("shows its loading copy while the first read is in flight", () => {
    renderListing({ kind: "loading" });

    expect(screen.getByRole("status")).toHaveTextContent(/^Loading widgets…$/);
  });

  it("renders nothing at all for a session without the read's Permission", () => {
    renderListing({ kind: "not-permitted" });

    expect(screen.getByTestId("listing")).toBeEmptyDOMElement();
  });

  // The rows a re-read could not confirm are neither acted on nor held as evidence.
  it("hides the rows last read once a re-read fails", () => {
    renderListing({ data: page(["anvil"]), kind: "stale" });

    expect(screen.queryByTestId("rows")).toBeNull();
  });

  describe("its empty copy", () => {
    it("is the listing's own on an empty first page", () => {
      renderListing({ data: page([], 1, 0), kind: "ready" });

      expect(screen.getByTestId("listing-empty")).toHaveTextContent(/^No widgets exist\.$/);
    });

    it("says only that a later page emptied", () => {
      renderListing({ data: page([], 3, 2), kind: "ready" });

      expect(screen.getByTestId("listing-empty")).toHaveTextContent(
        /^This page is now empty\. Use Previous to go back\.$/,
      );
    });
  });

  describe("its pager", () => {
    // Where the page sat is known once a read of the current path stated it, and kept through a failed re-read.
    it.each(["ready", "stale"] as const)("names the range a %s read stated", (kind) => {
      renderListing({ data: page(["anvil", "bolt"], 3), kind });

      expect(within(pager()!).getByText(/^Widgets /).textContent).toBe("Widgets 3–4 of 5");
    });

    it.each([
      ["while the first read is in flight", { kind: "loading" }],
      ["once the read failed with nothing read", { kind: "failed" }],
    ] as const)("is not shown %s", (_, view) => {
      renderListing(view);

      expect(pager()).toBeNull();
    });

    it("moves back by the page size", async () => {
      const onMove = vi.fn();
      renderListing({ data: page(["anvil", "bolt"], 3), kind: "ready" }, { onMove });

      await userEvent.click(within(pager()!).getByRole("button", { name: "Previous" }));

      expect(onMove.mock.calls).toEqual([[1]]);
    });

    it("holds still while the listing is disabled", () => {
      renderListing({ data: page(["anvil", "bolt"], 3), kind: "ready" }, { disabled: true });

      expect(
        within(pager()!)
          .getAllByRole("button")
          .map((move) => (move as HTMLButtonElement).disabled),
      ).toEqual([true, true]);
    });
  });

  describe("reading again after a failure", () => {
    it.each([
      ["failed", { kind: "failed" }],
      ["stale", { data: page(["anvil"]), kind: "stale" }],
    ] as const)("offers Try again once the read is %s", (_, view) => {
      renderListing(view);

      expect(screen.getByRole("button", { name: "Try again" })).toBeEnabled();
    });

    it.each([
      ["loading", { kind: "loading" }],
      ["ready", { data: page(["anvil"]), kind: "ready" }],
    ] as const)("offers nothing while the read is %s", (_, view) => {
      renderListing(view);

      expect(screen.queryByRole("button", { name: /Try again|Refresh/ })).toBeNull();
    });

    it("reads again on Try again", async () => {
      const onReadAgain = vi.fn();
      renderListing({ kind: "failed" }, { onReadAgain });

      await userEvent.click(screen.getByRole("button", { name: "Try again" }));

      expect(onReadAgain).toHaveBeenCalledTimes(1);
    });

    it("holds Try again still while the listing is disabled", () => {
      renderListing({ kind: "failed" }, { disabled: true });

      expect(screen.getByRole("button", { name: "Try again" })).toBeDisabled();
    });
  });

  // A page that offers its own read-again control, as the audit trail's
  // Refresh in its card header, hands the listing none.
  it.each([
    ["failed", { kind: "failed" }],
    ["stale", { data: page(["anvil"]), kind: "stale" }],
  ] as const)(
    "offers no Try again, once the read is %s, when the page reads again itself",
    (_, view) => {
      renderListing(view, { onReadAgain: null });

      expect(screen.queryByRole("button", { name: "Try again" })).toBeNull();
    },
  );
});
