import { describe, expect, it } from "vitest";

import { emptyCopyOf } from "./page-empty-copy";

const LISTING_COPY = "No widgets are provisioned.";

describe("emptyCopyOf", () => {
  it.each([
    ["on the first page", { startIndex: 1 }],
    ["while no page has been read", null],
  ])("is the listing's own copy %s", (_name, page) => {
    expect(emptyCopyOf(page, LISTING_COPY)).toBe(LISTING_COPY);
  });

  it("says the page emptied, not the listing, from the second row on", () => {
    expect(emptyCopyOf({ startIndex: 2 }, LISTING_COPY)).toBe(
      "This page is now empty. Use Previous to go back.",
    );
  });
});
