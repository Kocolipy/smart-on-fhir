/**
 * How the SPA writes an instant the backend sent, for every page that shows one.
 *
 * Timestamps are rendered from the ISO instant itself rather than through
 * `toLocaleString`, so what an administrator reads does not depend on the
 * machine's locale and a test can assert an exact string.
 */

/** The calendar date alone, where the day is enough. */
export const formatDate = (instant: string): string => instant.slice(0, 10);

/** Date and minute, for the instants where the day is not enough. */
export const formatInstant = (instant: string): string =>
  `${formatDate(instant)} ${instant.slice(11, 16)}`;
