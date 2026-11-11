import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { ReadDeclaration } from "@/auth/declarations";
import { InPageStatus, shownLine } from "@/auth/page-status.testHelpers";
import {
  bare,
  deferred,
  holding,
  json,
  renderAsSession,
  type Answers,
} from "@/auth/session-adapter.testHelpers";
import { jsonDecoder, readObject } from "@/lib/decode";

import { PagedListing } from "./paged-listing";
import { pageDecoder, pagedPath, type Page, type PageAt } from "@/lib/paging";

import { usePagedRead } from "./paged-read";

// The paged read is a hook and the listing it hands everything to, so a suite
// drives both as a page does: a listing of widgets, over the fake backend, for a
// session holding the read's Permission or not.

const decodeWidget = (value: unknown): string => readObject(value, "Widget").string("name");

/** Widgets of one kind, a page at a time, as an API module declares a paged read. */
const WIDGETS: ReadDeclaration<Page<string>, [kind: string, at: PageAt]> = {
  decode: jsonDecoder(pageDecoder("WidgetPage", "widgets", decodeWidget)),
  path: (kind, at) => pagedPath("/api/widgets", at, `kind=${kind}`),
  permission: "user:read",
};

const FAILURE = "Unable to load the widgets. Please try again.";

/** The read of the page of `kind` widgets from `startIndex`, two at a time. */
const widgetsAt = (startIndex: number, kind = "bolt") =>
  `GET /api/widgets?kind=${kind}&startIndex=${startIndex}&count=2`;

/** A page answering these widgets, from `startIndex`, of `totalResults` in all. */
const widgetPage = (names: string[], startIndex = 1, totalResults = 5) =>
  json({ startIndex, totalResults, widgets: names.map((name) => ({ name })) });

/** A listing of widgets of the kind its own control picks, two to a page, as a page renders one. */
function Widgets({ busy = false, readAgain }: { busy?: boolean; readAgain?: boolean }) {
  const [kind, setKind] = useState("bolt");
  const read = usePagedRead(WIDGETS, { args: [kind], failureMessage: FAILURE, pageSize: 2 });
  return (
    <>
      <button onClick={() => setKind("nut")} type="button">
        Show nuts
      </button>
      {/* The page's own read-again control, as the audit trail's Refresh. */}
      <button onClick={() => void read.reload()} type="button">
        Refresh
      </button>
      {/* The page's own fresh start, as a projection's search submitted again. */}
      <button onClick={read.restart} type="button">
        Start over
      </button>
      <div data-testid="listing">
        <PagedListing
          copy={{
            empty: "No widgets exist.",
            loading: "Loading widgets…",
            noun: "widgets",
            pagerLabel: "Widget pages",
          }}
          disabled={busy}
          read={read}
          readAgain={readAgain}
        >
          {({ rows, startIndex, totalResults }) => (
            <p data-testid="rows">
              {rows.join(", ")} from {startIndex} of {totalResults}
            </p>
          )}
        </PagedListing>
      </div>
    </>
  );
}

const PERMITTED = holding(["user:read"]);

const renderWidgets = (
  answers: Answers,
  props: { busy?: boolean; readAgain?: boolean } = {},
  session = PERMITTED,
) =>
  renderAsSession(
    // The page status a page reports to is the signed-in layout's, around the page.
    <InPageStatus>
      <Widgets {...props} />
    </InPageStatus>,
    session,
    { answers },
  );

const pager = () => screen.queryByRole("navigation", { name: "Widget pages" });
const rows = async () => (await screen.findByTestId("rows")).textContent;
const press = (name: string) => userEvent.click(screen.getByRole("button", { name }));

describe("usePagedRead, through the PagedListing it hands its read", () => {
  it("reads the first page, of the page size, for the listing's own arguments", async () => {
    await renderWidgets({ [widgetsAt(1)]: widgetPage(["anvil", "bolt"]) });

    expect(await rows()).toBe("anvil, bolt from 1 of 5");
  });

  it("shows its loading copy while the first read is in flight", async () => {
    await renderWidgets({ [widgetsAt(1)]: deferred().answer });

    expect(screen.getByRole("status")).toHaveTextContent(/^Loading widgets…$/);
    expect(pager()).toBeNull();
  });

  it("sends nothing, and renders nothing, for a session without the read's Permission", async () => {
    const { requests } = await renderWidgets({}, {}, holding([]));

    expect(screen.getByTestId("listing")).toBeEmptyDOMElement();
    expect(requests()).toEqual([]);
  });

  describe("its empty copy", () => {
    it("is the listing's own on an empty first page", async () => {
      await renderWidgets({ [widgetsAt(1)]: widgetPage([], 1, 0) });

      expect(await screen.findByTestId("listing-empty")).toHaveTextContent(/^No widgets exist\.$/);
    });

    it("says only that a later page emptied", async () => {
      await renderWidgets({
        [widgetsAt(1)]: widgetPage(["anvil", "bolt"], 1, 3),
        [widgetsAt(3)]: widgetPage([], 3, 2),
      });
      await rows();

      await press("Next");

      expect(await screen.findByTestId("listing-empty")).toHaveTextContent(
        /^This page is now empty\. Use Previous to go back\.$/,
      );
    });
  });

  describe("its pager", () => {
    it("names the range the read stated", async () => {
      await renderWidgets({ [widgetsAt(1)]: widgetPage(["anvil", "bolt"]) });
      await rows();

      expect(within(pager()!).getByText(/^Showing /).textContent).toBe(
        "Showing 1 to 2 of 5 widgets",
      );
    });

    it("moves on from where the page ended, and back by the page size", async () => {
      const { requests } = await renderWidgets({
        [widgetsAt(1)]: widgetPage(["anvil", "bolt"]),
        [widgetsAt(3)]: widgetPage(["cog", "dowel"], 3),
      });
      await rows();

      await press("Next");
      expect(await screen.findByText("cog, dowel from 3 of 5")).toBeInTheDocument();
      await press("Previous");

      expect(await screen.findByText("anvil, bolt from 1 of 5")).toBeInTheDocument();
      expect(requests().map(({ path }) => `GET ${path}`)).toEqual([
        widgetsAt(1),
        widgetsAt(3),
        widgetsAt(1),
      ]);
    });

    it("holds still while the listing is disabled", async () => {
      await renderWidgets({ [widgetsAt(1)]: widgetPage(["cog", "dowel"], 3) }, { busy: true });
      await rows();

      expect(
        within(pager()!)
          .getAllByRole("button")
          .every((move) => (move as HTMLButtonElement).disabled),
      ).toBe(true);
    });

    it("keeps the page it counts from once a re-read fails", async () => {
      await renderWidgets({ [widgetsAt(1)]: [widgetPage(["anvil", "bolt"]), bare(503)] });
      await rows();

      await press("Refresh");
      await screen.findByRole("button", { name: "Try again" });

      expect(within(pager()!).getByText(/^Showing /).textContent).toBe(
        "Showing 1 to 2 of 5 widgets",
      );
    });
  });

  describe("a read after the first", () => {
    /** Whether the listing's body is marked busy. */
    const busy = () =>
      screen.getByTestId("listing").querySelector("[aria-busy]")?.getAttribute("aria-busy");

    it("holds the page last read, busy and its pager still, until it answers", async () => {
      const next = deferred();
      await renderWidgets({
        [widgetsAt(1)]: widgetPage(["anvil", "bolt"]),
        [widgetsAt(3)]: next.answer,
      });
      await rows();

      await press("Next");

      expect(screen.getByTestId("rows").textContent).toBe("anvil, bolt from 1 of 5");
      expect(screen.getByRole("status")).toHaveTextContent(/^Loading widgets…$/);
      expect(busy()).toBe("true");
      expect(
        within(pager()!)
          .getAllByRole("button")
          .every((move) => (move as HTMLButtonElement).disabled),
      ).toBe(true);

      await next.settle(widgetPage(["cog", "dowel"], 3));

      expect(screen.getByTestId("rows").textContent).toBe("cog, dowel from 3 of 5");
      expect(screen.queryByRole("status")).toBeNull();
      expect(busy()).toBe("false");
      expect(within(pager()!).getByRole("button", { name: "Previous" })).toBeEnabled();
    });

    it("holds no page that had no rows", async () => {
      const back = deferred();
      await renderWidgets({
        [widgetsAt(1)]: [widgetPage(["anvil", "bolt"], 1, 3), back.answer],
        [widgetsAt(3)]: widgetPage([], 3, 2),
      });
      await rows();
      await press("Next");
      await screen.findByTestId("listing-empty");

      await press("Previous");

      expect(screen.queryByTestId("listing-empty")).toBeNull();
      expect(screen.getByRole("status")).toHaveTextContent(/^Loading widgets…$/);
      expect(busy()).toBe("false");
    });

    it("holds nothing once a read failed", async () => {
      await renderWidgets({
        [widgetsAt(1)]: widgetPage(["anvil", "bolt"]),
        [widgetsAt(3)]: bare(503),
        [widgetsAt(1, "nut")]: deferred().answer,
      });
      await rows();
      await press("Next");
      await screen.findByRole("button", { name: "Try again" });

      await press("Show nuts");

      expect(screen.queryByTestId("rows")).toBeNull();
      expect(screen.getByRole("status")).toHaveTextContent(/^Loading widgets…$/);
    });
  });

  // jsdom lays nothing out, so each element reports a height of 40px for each
  // widget it shows.
  describe("its height", () => {
    const ROW = 40;
    const FULL_PAGE = 2 * ROW;
    beforeEach(() => {
      vi.spyOn(HTMLElement.prototype, "offsetHeight", "get").mockImplementation(function (
        this: HTMLElement,
      ) {
        const shown = this.querySelector('[data-testid="rows"]')?.textContent ?? "";
        return shown === "" ? 0 : ROW * shown.split(" from ")[0].split(", ").length;
      });
    });
    afterEach(() => {
      vi.restoreAllMocks();
    });

    /** The least height the listing's body stands at. */
    const minHeight = () =>
      (screen.getByTestId("listing").querySelector("[aria-busy]") as HTMLElement).style.minHeight;

    it("stands a page short of rows as tall as the last full page", async () => {
      await renderWidgets({
        [widgetsAt(1)]: widgetPage(["anvil", "bolt"], 1, 3),
        [widgetsAt(3)]: widgetPage(["cog"], 3, 3),
      });
      await rows();
      expect(minHeight()).toBe(`${FULL_PAGE}px`);

      await press("Next");
      await screen.findByText("cog from 3 of 3");

      expect(minHeight()).toBe(`${FULL_PAGE}px`);
    });

    it("stands a later page that emptied as tall as the last full page", async () => {
      await renderWidgets({
        [widgetsAt(1)]: widgetPage(["anvil", "bolt"], 1, 3),
        [widgetsAt(3)]: widgetPage([], 3, 2),
      });
      await rows();

      await press("Next");
      await screen.findByTestId("listing-empty");

      expect(minHeight()).toBe(`${FULL_PAGE}px`);
    });

    it("sets no height on a listing of one page", async () => {
      await renderWidgets({ [widgetsAt(1)]: widgetPage(["anvil", "bolt"], 1, 2) });
      await rows();

      expect(minHeight()).toBe("");
    });
  });

  it("starts again from the first page for new arguments", async () => {
    const { requests } = await renderWidgets({
      [widgetsAt(1)]: widgetPage(["anvil", "bolt"]),
      [widgetsAt(3)]: widgetPage(["cog", "dowel"], 3),
      [widgetsAt(1, "nut")]: widgetPage(["hex"], 1, 1),
    });
    await rows();
    await press("Next");
    await screen.findByText("cog, dowel from 3 of 5");

    await press("Show nuts");

    expect(await screen.findByText("hex from 1 of 1")).toBeInTheDocument();
    expect(requests().map(({ path }) => `GET ${path}`)).toEqual([
      widgetsAt(1),
      widgetsAt(3),
      widgetsAt(1, "nut"),
    ]);
  });

  describe("a fresh start", () => {
    it("reads the first page once from a later page", async () => {
      const { requests } = await renderWidgets({
        [widgetsAt(1)]: [widgetPage(["anvil", "bolt"]), widgetPage(["axle", "bolt"])],
        [widgetsAt(3)]: widgetPage(["cog", "dowel"], 3),
      });
      await rows();
      await press("Next");
      await screen.findByText("cog, dowel from 3 of 5");

      await press("Start over");

      expect(await screen.findByText("axle, bolt from 1 of 5")).toBeInTheDocument();
      expect(requests().map(({ path }) => `GET ${path}`)).toEqual([
        widgetsAt(1),
        widgetsAt(3),
        widgetsAt(1),
      ]);
    });

    it("reads the first page again, once, while it is the page shown", async () => {
      const { requests } = await renderWidgets({
        [widgetsAt(1)]: [widgetPage(["anvil", "bolt"]), widgetPage(["axle", "bolt"])],
      });
      await rows();

      await press("Start over");

      expect(await screen.findByText("axle, bolt from 1 of 5")).toBeInTheDocument();
      expect(requests().map(({ path }) => `GET ${path}`)).toEqual([widgetsAt(1), widgetsAt(1)]);
    });
  });

  describe("a failed read", () => {
    it("is reported to the page's error line, its body blank and no pager shown", async () => {
      await renderWidgets({ [widgetsAt(1)]: bare(503) });

      expect(await screen.findByRole("button", { name: "Try again" })).toBeEnabled();
      expect(shownLine()).toBe(FAILURE);
      expect(screen.queryByTestId("rows")).toBeNull();
      expect(screen.queryByTestId("listing-empty")).toBeNull();
      expect(pager()).toBeNull();
    });

    it("is read again, the same page, on Try again", async () => {
      const { requests } = await renderWidgets({
        [widgetsAt(1)]: widgetPage(["anvil", "bolt"]),
        [widgetsAt(3)]: [bare(503), widgetPage(["cog", "dowel"], 3)],
      });
      await rows();
      await press("Next");

      await userEvent.click(await screen.findByRole("button", { name: "Try again" }));

      expect(await screen.findByText("cog, dowel from 3 of 5")).toBeInTheDocument();
      expect(screen.queryByRole("button", { name: "Try again" })).toBeNull();
      expect(requests().filter(({ path }) => `GET ${path}` === widgetsAt(3))).toHaveLength(2);
    });

    // The rows a re-read could not confirm are neither acted on nor held as evidence.
    it("hides the rows last read once a re-read fails", async () => {
      await renderWidgets({ [widgetsAt(1)]: [widgetPage(["anvil", "bolt"]), bare(503)] });
      await rows();

      await press("Refresh");

      expect(await screen.findByRole("button", { name: "Try again" })).toBeEnabled();
      expect(screen.queryByTestId("rows")).toBeNull();
    });

    it("holds Try again still while the listing is disabled", async () => {
      await renderWidgets({ [widgetsAt(1)]: bare(503) }, { busy: true });

      expect(await screen.findByRole("button", { name: "Try again" })).toBeDisabled();
    });

    // A page that offers its own read-again control, as the audit trail's
    // Refresh in its card header, has the listing offer none.
    it("offers no Try again where the page reads again itself", async () => {
      await renderWidgets({ [widgetsAt(1)]: bare(503) }, { readAgain: false });
      await screen.findByTestId("listing");

      expect(shownLine()).toBe(FAILURE);
      expect(screen.queryByRole("button", { name: "Try again" })).toBeNull();
    });
  });

  it("offers no Try again once the read succeeded", async () => {
    await renderWidgets({ [widgetsAt(1)]: widgetPage(["anvil"], 1, 1) });
    await rows();

    expect(screen.queryByRole("button", { name: "Try again" })).toBeNull();
  });
});
