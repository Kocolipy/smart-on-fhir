import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";

import { Pager } from "./pager";

const PAGE_SIZE = 20;

/** A pager over `rows` rows starting at `startIndex`, of `totalResults` in all. */
function renderPager(
  { rows, startIndex, totalResults }: { rows: number; startIndex: number; totalResults: number },
  disabled?: boolean,
) {
  const onMove = vi.fn<(startIndex: number) => void>();
  const { container } = render(
    <Pager
      disabled={disabled}
      label="Widget pages"
      noun="Widgets"
      onMove={onMove}
      page={{ rows: Array.from({ length: rows }), startIndex, totalResults }}
      pageSize={PAGE_SIZE}
    />,
  );
  return { container, onMove };
}

const previous = () => screen.getByRole("button", { name: "Previous" });
const next = () => screen.getByRole("button", { name: "Next" });

describe("Pager", () => {
  it("is named by its label", () => {
    renderPager({ rows: 20, startIndex: 1, totalResults: 45 });

    expect(screen.getByRole("navigation", { name: "Widget pages" })).toBeInTheDocument();
  });

  it("names the range shown and the total", () => {
    renderPager({ rows: 20, startIndex: 21, totalResults: 45 });

    expect(screen.getByText(/^Widgets 21–40 of 45$/)).toBeInTheDocument();
  });

  it("disables Previous on the first page", () => {
    renderPager({ rows: 20, startIndex: 1, totalResults: 45 });
    expect(previous()).toBeDisabled();
  });

  it("enables Previous from the second row on", () => {
    renderPager({ rows: 20, startIndex: 2, totalResults: 45 });
    expect(previous()).toBeEnabled();
  });

  it("enables Next while rows lie past this page", () => {
    renderPager({ rows: 20, startIndex: 1, totalResults: 21 });
    expect(next()).toBeEnabled();
  });

  it("disables Next once this page reaches the last row", () => {
    renderPager({ rows: 20, startIndex: 1, totalResults: 20 });
    expect(next()).toBeDisabled();
  });

  it("moves back by one page", async () => {
    const { onMove } = renderPager({ rows: 20, startIndex: 41, totalResults: 80 });

    await userEvent.click(previous());

    expect(onMove).toHaveBeenCalledExactlyOnceWith(21);
  });

  it("never moves back before the first row", async () => {
    const { onMove } = renderPager({ rows: 20, startIndex: 5, totalResults: 80 });

    await userEvent.click(previous());

    expect(onMove).toHaveBeenCalledExactlyOnceWith(1);
  });

  it("moves on from where a short page ended", async () => {
    const { onMove } = renderPager({ rows: 7, startIndex: 21, totalResults: 80 });

    await userEvent.click(next());

    expect(onMove).toHaveBeenCalledExactlyOnceWith(28);
  });

  it("renders nothing for an empty first page", () => {
    const { container } = renderPager({ rows: 0, startIndex: 1, totalResults: 0 });

    expect(container).toBeEmptyDOMElement();
  });

  it("keeps an empty later page's Previous, naming the count instead of a backwards range", () => {
    renderPager({ rows: 0, startIndex: 21, totalResults: 3 });

    expect(screen.getByText(/^Widgets 0 of 3$/)).toBeInTheDocument();
    expect(previous()).toBeEnabled();
    expect(next()).toBeDisabled();
  });

  it("holds both moves still while disabled", () => {
    renderPager({ rows: 20, startIndex: 21, totalResults: 80 }, true);

    expect(previous()).toBeDisabled();
    expect(next()).toBeDisabled();
  });

  it("is not held still by default", () => {
    renderPager({ rows: 20, startIndex: 21, totalResults: 80 });

    expect(previous()).toBeEnabled();
    expect(next()).toBeEnabled();
  });
});
