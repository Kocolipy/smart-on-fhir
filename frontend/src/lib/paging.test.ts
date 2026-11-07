import { describe, expect, it } from "vitest";

import { DecodeError, readObject } from "./decode";
import { pageDecoder, pagedPath } from "./paging";

const decodeWidget = (value: unknown): string => readObject(value, "Widget").string("name");

describe("pagedPath", () => {
  it("puts the page after the listing's own query", () => {
    expect(pagedPath("/api/x", { count: 20, startIndex: 41 }, "search=ada")).toBe(
      "/api/x?search=ada&startIndex=41&count=20",
    );
  });

  it("carries the page alone for a listing with no query of its own", () => {
    expect(pagedPath("/api/x", { count: 5, startIndex: 1 })).toBe("/api/x?startIndex=1&count=5");
  });
});

describe("pageDecoder", () => {
  const decode = pageDecoder("WidgetPage", "widgets", decodeWidget);

  it("reads the page's rows from their field, beside where it starts and the total", () => {
    expect(decode({ startIndex: 3, totalResults: 4, widgets: [{ name: "anvil" }] })).toEqual({
      rows: ["anvil"],
      startIndex: 3,
      totalResults: 4,
    });
  });

  it.each([
    ["a malformed row", { startIndex: 1, totalResults: 1, widgets: [{ name: 7 }] }],
    ["rows under another field", { rows: [], startIndex: 1, totalResults: 0 }],
    ["a startIndex that is no integer", { startIndex: "1", totalResults: 0, widgets: [] }],
    ["a total that is no integer", { startIndex: 1, totalResults: 0.5, widgets: [] }],
    ["a bare list of rows", [{ name: "anvil" }]],
  ])("refuses %s", (_, body) => {
    expect(() => decode(body)).toThrow(DecodeError);
  });

  // Its fields say where the page sits, so one the page does not count from is refused.
  it("refuses a page carrying a field beside its three", () => {
    expect(() => decode({ itemsPerPage: 2, startIndex: 1, totalResults: 0, widgets: [] })).toThrow(
      "WidgetPage.itemsPerPage is not expected",
    );
  });
});
