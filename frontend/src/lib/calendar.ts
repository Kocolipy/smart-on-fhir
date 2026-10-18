/**
 * Calendar dates — a day with no time and no zone, as FHIR's `date` and the
 * backend's birth dates are — and the one clock the SPA reads "today" from.
 *
 * Dates are handled as their `YYYY-MM-DD` text rather than through `Date`, so
 * nothing here depends on the machine's zone or locale and a test can assert
 * an exact string. The one instant read is "now", through a {@link Clock} the
 * caller is handed, so a test states the day it runs on.
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

/** A `YYYY-MM-DD` date as `d MMM yyyy`: `1985-03-12` is `12 Mar 1985`. */
export function formatCalendarDate(date: string): string {
  const [year, month, day] = partsOf(date);
  return `${day} ${MONTHS[month - 1]} ${year}`;
}

/**
 * Today's date in Singapore, as `YYYY-MM-DD`, by `clock`. `en-CA` writes a
 * date as `YYYY-MM-DD`, so the formatter's output is the date's own text.
 */
export function singaporeDate(clock: Clock): string {
  return new Intl.DateTimeFormat("en-CA", {
    day: "2-digit",
    month: "2-digit",
    timeZone: "Asia/Singapore",
    year: "numeric",
  }).format(clock());
}

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
