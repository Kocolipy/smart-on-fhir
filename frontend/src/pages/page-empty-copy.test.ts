import { describe, expect, it } from "vitest";

import { emptyCopyOf } from "./page-empty-copy";

const LISTING_COPY = "No widgets are provisioned.";

describe("emptyCopyOf", () => {
  it("is the listing's own copy on the first page", () => {
    expect(emptyCopyOf({ startIndex: 1 }, LISTING_COPY)).toBe(LISTING_COPY);
  });

  it("is the listing's own copy while no page has been read", () => {
    expect(emptyCopyOf(null, LISTING_COPY)).toBe(LISTING_COPY);
  });

  it("says the page emptied, not the listing, from the second row on", () => {
    expect(emptyCopyOf({ startIndex: 2 }, LISTING_COPY)).toBe(
      "This page is now empty. Use Previous to go back.",
    );
  });
});
