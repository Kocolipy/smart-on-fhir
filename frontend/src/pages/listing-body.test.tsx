import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import type { ReadView } from "@/auth/use-gated-read";

import { ListingBody, type StaleRows } from "./listing-body";

const LOADING = "Loading widgets…";
const EMPTY = "No widgets exist.";

function renderBody(view: ReadView<string[]>, staleRows: StaleRows = "keep") {
  return render(
    <div data-testid="body">
      <ListingBody
        empty={EMPTY}
        loading={LOADING}
        rows={(widgets) => widgets}
        staleRows={staleRows}
        view={view}
      >
        {(widgets) => (
          <ul>
            {widgets.map((widget) => (
              <li key={widget}>{widget}</li>
            ))}
          </ul>
        )}
      </ListingBody>
    </div>,
  );
}

const body = () => screen.getByTestId("body");

describe("ListingBody", () => {
  it("renders nothing for a session without the read's Permission", () => {
    renderBody({ kind: "not-permitted" });

    expect(body()).toBeEmptyDOMElement();
  });

  it("says the listing is loading while the first read is in flight", () => {
    renderBody({ kind: "loading" });

    expect(body()).toHaveTextContent(/^Loading widgets…$/);
  });

  it("renders the rows once the read succeeds", () => {
    renderBody({ data: ["anvil", "bolt"], kind: "ready" });

    expect(screen.getAllByRole("listitem").map((item) => item.textContent)).toEqual([
      "anvil",
      "bolt",
    ]);
  });

  // A paged read's rows are one part of what it answered; the rest — its total — is the page's too.
  it.each(["ready", "stale"] as const)(
    "hands its children the %s read their rows came from",
    (kind) => {
      render(
        <ListingBody
          empty={EMPTY}
          loading={LOADING}
          rows={(page: { total: number; widgets: string[] }) => page.widgets}
          staleRows="keep"
          view={{ data: { total: 7, widgets: ["anvil"] }, kind }}
        >
          {(widgets, page) => (
            <p>
              {widgets.length} of {page.total}
            </p>
          )}
        </ListingBody>,
      );

      expect(screen.getByText("1 of 7")).toBeInTheDocument();
    },
  );

  it("says the listing is empty when the read succeeds with no rows", () => {
    renderBody({ data: [], kind: "ready" });

    expect(body()).toHaveTextContent(/^No widgets exist\.$/);
  });

  // The page's error line says what went wrong; the body never claims "loading" or "empty".
  it("renders nothing for a failed first read", () => {
    renderBody({ kind: "failed" });

    expect(body()).toBeEmptyDOMElement();
  });

  it("keeps the last rows after a failed refresh when the page keeps stale rows", () => {
    renderBody({ data: ["anvil"], kind: "stale" }, "keep");

    expect(screen.getByRole("listitem")).toHaveTextContent("anvil");
  });

  it("hides the last rows after a failed refresh when the page hides stale rows", () => {
    renderBody({ data: ["anvil"], kind: "stale" }, "hide");

    expect(body()).toBeEmptyDOMElement();
  });

  it("never reports a failed refresh of an empty listing as empty", () => {
    renderBody({ data: [], kind: "stale" }, "keep");

    expect(body()).toBeEmptyDOMElement();
  });
});
