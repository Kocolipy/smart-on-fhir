import { describe, expect, it } from "vitest";

import { completedYears, formatCalendarDate, singaporeDate, systemClock } from "./calendar";

describe("formatCalendarDate", () => {
  it.each([
    ["1985-03-12", "12 Mar 1985"],
    ["2000-01-01", "1 Jan 2000"],
    ["1999-12-31", "31 Dec 1999"],
    ["2001-02-05", "5 Feb 2001"],
    ["2002-04-15", "15 Apr 2002"],
    ["2003-05-15", "15 May 2003"],
    ["2004-06-15", "15 Jun 2004"],
    ["2005-07-15", "15 Jul 2005"],
    ["2006-08-15", "15 Aug 2006"],
    ["2007-09-15", "15 Sep 2007"],
    ["2008-10-15", "15 Oct 2008"],
    ["2009-11-15", "15 Nov 2009"],
  ])("writes %s as %s", (date, written) => {
    expect(formatCalendarDate(date)).toBe(written);
  });
});

describe("singaporeDate", () => {
  it("is the calendar date in Singapore, eight hours ahead of UTC", () => {
    expect(singaporeDate(() => new Date("2026-03-11T15:59:59Z"))).toBe("2026-03-11");
    expect(singaporeDate(() => new Date("2026-03-11T16:00:00Z"))).toBe("2026-03-12");
  });

  it("writes single-digit months and days with their leading zero", () => {
    expect(singaporeDate(() => new Date("2026-01-05T04:00:00Z"))).toBe("2026-01-05");
  });
});

describe("systemClock", () => {
  it("reads the current instant", () => {
    const before = Date.now();
    const read = systemClock().getTime();

    expect(read).toBeGreaterThanOrEqual(before);
    expect(read).toBeLessThanOrEqual(Date.now());
  });
});

describe("completedYears", () => {
  it("does not count the year whose birthday is tomorrow", () => {
    expect(completedYears("1985-03-12", "2026-03-11")).toBe(40);
  });

  it("counts the year on the birthday itself", () => {
    expect(completedYears("1985-03-12", "2026-03-12")).toBe(41);
  });

  it("does not count the year a month before the birthday's month", () => {
    expect(completedYears("1985-03-12", "2026-02-28")).toBe(40);
  });

  it("counts the year in the month after the birthday's", () => {
    expect(completedYears("1985-03-12", "2026-04-01")).toBe(41);
  });

  it("is zero on the day of birth", () => {
    expect(completedYears("2026-03-12", "2026-03-12")).toBe(0);
  });

  it("counts a 29 February birthday from 1 March in a common year", () => {
    expect(completedYears("2024-02-29", "2027-02-28")).toBe(2);
    expect(completedYears("2024-02-29", "2027-03-01")).toBe(3);
  });
});
