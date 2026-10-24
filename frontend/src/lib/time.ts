/**
 * Every time the SPA reads or shows, in the one zone it reads them in:
 * Asia/Singapore, the zone the backend names for an operator. This module is
 * the only place that knows the zone — how an instant from the backend is
 * shown, how a time an administrator types becomes an instant, what counts as
 * a calendar date and how one is shown, and what "today" is.
 *
 * Nothing here goes through `toLocaleString` or the machine's zone: what is
 * shown is the same on every machine, and a test can assert an exact string.
 * The one instant read is "now", through a {@link Clock} the caller is handed,
 * so a test states the day it runs on.
 */

/** Reads the current instant. Injected wherever "today" decides what is shown. */
export type Clock = () => Date;

/** The real clock: the one place the SPA reads the current instant for a date. */
export const systemClock: Clock = () => new Date();

const MONTHS = [
  "Jan",
  "Feb",
  "Mar",
  "Apr",
  "May",
  "Jun",
  "Jul",
  "Aug",
  "Sep",
  "Oct",
  "Nov",
  "Dec",
] as const;

/** The year, month and day of a `YYYY-MM-DD` date. */
const partsOf = (date: string): [year: number, month: number, day: number] => [
  Number(date.slice(0, 4)),
  Number(date.slice(5, 7)),
  Number(date.slice(8, 10)),
];

/**
 * Singapore's offset from UTC, in milliseconds. Asia/Singapore is a fixed
 * +08:00 with no daylight saving, so a constant states it exactly and no
 * reading depends on the machine's zone database.
 */
const SINGAPORE_OFFSET_MS = 8 * 60 * 60 * 1000;

/** A number written with at least two digits: `5` is `05`. */
const twoDigits = (value: number): string => String(value).padStart(2, "0");

/**
 * The wall-clock fields of `instant` in Singapore: the instant moved by the
 * offset, then read as UTC, which has no zone of its own to add.
 */
const singaporeWallClock = (instant: Date) => {
  const shifted = new Date(instant.getTime() + SINGAPORE_OFFSET_MS);
  return {
    date: `${String(shifted.getUTCFullYear())}-${twoDigits(shifted.getUTCMonth() + 1)}-${twoDigits(shifted.getUTCDate())}`,
    minute: `${twoDigits(shifted.getUTCHours())}:${twoDigits(shifted.getUTCMinutes())}`,
  };
};

/** An instant the backend sent, as its date in Singapore, where the day is enough. */
export const formatDate = (instant: string): string => singaporeWallClock(new Date(instant)).date;

/** An instant the backend sent, as its date and minute in Singapore. */
export function formatInstant(instant: string): string {
  const { date, minute } = singaporeWallClock(new Date(instant));
  return `${date} ${minute}`;
}

/**
 * Whether `text` names an instant {@link formatInstant} can show: one another
 * origin sent, such as a FHIRcast notification's `timestamp`.
 */
export const isInstant = (text: string): boolean => !Number.isNaN(Date.parse(text));

/** Whether `year` has a 29 February, by the Gregorian rule. */
const isLeapYear = (year: number): boolean =>
  year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);

/** The number of days in `month` (1–12) of `year`. */
function daysInMonth(year: number, month: number): number {
  if (month === 2) return isLeapYear(year) ? 29 : 28;
  return [4, 6, 9, 11].includes(month) ? 30 : 31;
}

/** Whether `year`, `month` (1–12) and `day` name a day the calendar has. */
const isRealDay = (year: number, month: number, day: number): boolean =>
  month >= 1 && month <= 12 && day >= 1 && day <= daysInMonth(year, month);

/** A calendar date's shape: four-digit year, two-digit month and day. */
const CALENDAR_DATE = /^\d{4}-\d{2}-\d{2}$/;

/**
 * Whether `text` is a calendar date — a day with no time and no zone, as
 * FHIR's `date` to the day is — written `YYYY-MM-DD`, and one the calendar
 * has: `1985-13-45` and `2023-02-29` have the shape and are still refused.
 */
export function isCalendarDate(text: string): boolean {
  return CALENDAR_DATE.test(text) && isRealDay(...partsOf(text));
}

/**
 * What a `datetime-local` control delivers: a date, `T`, and an hour and
 * minute, with seconds and milliseconds only where its step allows them. A
 * year can run past four digits, which the control accepts.
 */
const TYPED_TIME = /^(\d{4,})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,3}))?)?$/;

/**
 * A time an administrator typed, as a `datetime-local` value, read as
 * Singapore time whatever the browser's zone: the instant it names, or `null`
 * for a value that is no time at all, names a day or a time of day the
 * calendar does not have, or lies past the last instant a `Date` can hold.
 * The time an instant is shown at ({@link formatInstant}) reads back as that
 * instant's minute.
 */
export function parseTypedTime(typed: string): Date | null {
  const match = TYPED_TIME.exec(typed);
  if (match === null) return null;
  const [year, month, day, hour, minute, second] = match
    .slice(1, 7)
    .map((field) => Number(field ?? 0));
  if (!isRealDay(year, month, day) || hour > 23 || minute > 59 || second > 59) return null;
  // Built field by field rather than with `Date.UTC`, which reads a year
  // below 100 as one in the 1900s. Past a `Date`'s range the fields go NaN.
  const wallClock = new Date(0);
  wallClock.setUTCFullYear(year, month - 1, day);
  wallClock.setUTCHours(hour, minute, second, Number((match[7] ?? "").padEnd(3, "0")));
  const instant = new Date(wallClock.getTime() - SINGAPORE_OFFSET_MS);
  return Number.isNaN(instant.getTime()) ? null : instant;
}

/** A `YYYY-MM-DD` date as `d MMM yyyy`: `1985-03-12` is `12 Mar 1985`. */
export function formatCalendarDate(date: string): string {
  const [year, month, day] = partsOf(date);
  return `${day} ${MONTHS[month - 1]} ${year}`;
}

/** Today's date in Singapore, as `YYYY-MM-DD`, by `clock`. */
export const today = (clock: Clock): string => singaporeWallClock(clock()).date;

/**
 * Completed years from `birthDate` to `on`, both `YYYY-MM-DD`: a year counts
 * from its birthday on. A 29 February birthday counts from 1 March in a
 * common year, the first day it has certainly passed.
 */
export function completedYears(birthDate: string, on: string): number {
  const [bornYear, bornMonth, bornDay] = partsOf(birthDate);
  const [year, month, day] = partsOf(on);
  const birthdayPassed = month > bornMonth || (month === bornMonth && day >= bornDay);
  return year - bornYear - (birthdayPassed ? 0 : 1);
}
