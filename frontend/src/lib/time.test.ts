import { describe, expect, it } from "vitest";

import {
  completedYears,
  formatCalendarDate,
  formatDate,
  isCalendarDate,
  isInstant,
  formatInstant,
  parseTypedTime,
  systemClock,
  today,
} from "./time";

describe("formatDate", () => {
  it("shows an instant as its date in Singapore", () => {
    expect(formatDate("2026-03-04T05:06:07Z")).toBe("2026-03-04");
  });

  it("shows an instant late in the UTC day as the next day, Singapore's", () => {
    expect(formatDate("2026-04-01T20:00:00Z")).toBe("2026-04-02");
  });

  it("keeps an instant before Singapore's midnight on its own day", () => {
    expect(formatDate("2026-04-01T15:59:59Z")).toBe("2026-04-01");
  });

  it("carries the year over at Singapore's midnight", () => {
    expect(formatDate("2026-12-31T16:00:00Z")).toBe("2027-01-01");
  });

  it("reads an instant written with an offset, and with a fraction of a second", () => {
    expect(formatDate("2026-04-01T23:30:00.123456-05:00")).toBe("2026-04-02");
  });
});

describe("formatInstant", () => {
  it("shows an instant as its date and minute in Singapore, dropping seconds", () => {
    expect(formatInstant("2026-03-04T05:06:07Z")).toBe("2026-03-04 13:06");
  });

  it("shows an instant near midnight UTC on Singapore's date and minute", () => {
    expect(formatInstant("2026-10-18T23:59:59Z")).toBe("2026-10-19 07:59");
  });

  it("writes Singapore's midnight as 00:00", () => {
    expect(formatInstant("2026-10-18T16:00:00Z")).toBe("2026-10-19 00:00");
  });

  it("writes single-digit months, days, hours and minutes with their leading zero", () => {
    expect(formatInstant("2026-01-04T17:05:00Z")).toBe("2026-01-05 01:05");
  });
});

describe("isInstant", () => {
  it.each(["2026-10-24T02:00:05Z", "2026-10-24T10:00:05.123+08:00"])(
    "takes %s for an instant",
    (text) => {
      expect(isInstant(text)).toBe(true);
    },
  );

  it.each(["", "yesterday", "<b>2026</b>", "2026-13-45T00:00:00Z"])(
    "refuses %j, which names no instant",
    (text) => {
      expect(isInstant(text)).toBe(false);
    },
  );
});

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

describe("today", () => {
  it("is the calendar date in Singapore, eight hours ahead of UTC", () => {
    expect(today(() => new Date("2026-03-11T15:59:59Z"))).toBe("2026-03-11");
    expect(today(() => new Date("2026-03-11T16:00:00Z"))).toBe("2026-03-12");
  });

  it("writes single-digit months and days with their leading zero", () => {
    expect(today(() => new Date("2026-01-05T04:00:00Z"))).toBe("2026-01-05");
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

describe("parseTypedTime", () => {
  /** The instant a typed time is read as, written as UTC; `null` when it is refused. */
  const readAs = (typed: string) => parseTypedTime(typed)?.toISOString() ?? null;

  it("reads a typed date and minute as Singapore time, eight hours ahead of UTC", () => {
    expect(readAs("2026-10-19T09:00")).toBe("2026-10-19T01:00:00.000Z");
  });

  it("reads a time before 08:00 as the previous day in UTC", () => {
    expect(readAs("2026-01-01T00:00")).toBe("2025-12-31T16:00:00.000Z");
  });

  it("reads the time an instant is shown at back as that instant's minute", () => {
    expect(readAs(formatInstant("2026-10-18T23:59:59Z").replace(" ", "T"))).toBe(
      "2026-10-18T23:59:00.000Z",
    );
  });

  it("reads typed seconds and milliseconds", () => {
    expect(readAs("2026-10-19T09:00:30.250")).toBe("2026-10-19T01:00:30.250Z");
  });

  it("reads typed seconds alone", () => {
    expect(readAs("2026-10-19T09:00:30")).toBe("2026-10-19T01:00:30.000Z");
  });

  it("reads the last second of a minute", () => {
    expect(readAs("2026-10-19T09:00:59")).toBe("2026-10-19T01:00:59.000Z");
  });

  it("reads a fraction of a second shorter than three digits as tenths", () => {
    expect(readAs("2026-10-19T09:00:30.5")).toBe("2026-10-19T01:00:30.500Z");
  });

  it("reads a year before 100 as that year, not as one in the 1900s", () => {
    expect(readAs("0099-03-01T08:00")).toBe("0099-03-01T00:00:00.000Z");
  });

  it("reads the last minute of a day, and of a year", () => {
    expect(readAs("2026-12-31T23:59")).toBe("2026-12-31T15:59:00.000Z");
  });

  it.each([
    ["text that is no time at all", "not-a-date"],
    ["nothing", ""],
    ["a date with no time", "2026-01-01"],
    ["a month past December", "2026-13-01T00:00"],
    ["month zero", "2026-00-10T00:00"],
    ["day zero", "2026-01-00T00:00"],
    ["a day past the month's last", "2026-04-31T00:00"],
    ["29 February in a common year", "2026-02-29T00:00"],
    ["hour 24", "2026-01-01T24:00"],
    ["minute 60", "2026-01-01T00:60"],
    ["second 60", "2026-01-01T00:00:60"],
    ["text around the time", " 2026-01-01T00:00"],
    ["text after the time", "2026-01-01T00:00Z"],
    ["a year past what a Date can hold", "275760-12-31T23:59"],
  ])("refuses %s", (_, typed) => {
    expect(readAs(typed)).toBeNull();
  });
});

describe("isCalendarDate", () => {
  it.each([
    ["an ordinary day", "1985-03-12"],
    ["the last day of a 31-day month", "2026-01-31"],
    ["the last day of a 30-day month", "2026-04-30"],
    ["29 February in a leap year", "2024-02-29"],
    ["29 February in a year divisible by 400", "2000-02-29"],
    ["28 February in a common year", "2023-02-28"],
    ["the last day of the year", "1999-12-31"],
  ])("accepts %s", (_, date) => {
    expect(isCalendarDate(date)).toBe(true);
  });

  it.each([
    ["a month past December", "1985-13-12"],
    ["month zero", "1985-00-12"],
    ["day zero", "1985-03-00"],
    ["a day past a 31-day month's last", "1985-03-32"],
    ["31 in a 30-day month", "2026-06-31"],
    ["29 February in a common year", "2023-02-29"],
    ["29 February in a century year not divisible by 400", "1900-02-29"],
    ["a value no date at all", "1985-13-45"],
    ["another notation", "12/03/1985"],
    ["a time after the date", "1985-03-12T00:00:00"],
    ["text before the date", "c. 1985-03-12"],
    ["an interval of two dates", "1985-03-12/1985-03-13"],
    ["a year short of four digits", "985-03-12"],
  ])("refuses %s", (_, date) => {
    expect(isCalendarDate(date)).toBe(false);
  });
});
