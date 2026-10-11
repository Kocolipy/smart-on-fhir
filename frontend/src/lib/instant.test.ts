import { describe, expect, it } from "vitest";

import { formatDate, formatInstant } from "./instant";

describe("formatDate", () => {
  it("renders the calendar date alone, dropping the time and the zone", () => {
    expect(formatDate("2026-03-04T05:06:07Z")).toBe("2026-03-04");
  });
});

describe("formatInstant", () => {
  it("renders the date and minute, dropping seconds and the zone", () => {
    expect(formatInstant("2026-03-04T05:06:07Z")).toBe("2026-03-04 05:06");
  });
});
