import { describe, expect, it } from "vitest";

import { readSources } from "./sources";

// Architecture rule `no_time_outside_time_module` (docs/ARCHITECTURE.md, "Why
// every time goes through lib/time.ts"). The SPA reads and shows every time in
// Asia/Singapore, and src/lib/time.ts is the one place that knows it. A page
// that builds a `Date` from a typed value reads it in the browser's zone, and
// one that formats through `Intl` or a local getter shows it in the viewer's:
// both import nothing new, so dependency-cruiser never sees them, and the unit
// suite sees them only where a test happens to cross a date boundary.
const TIME_MODULE = "src/lib/time.ts";

// Every way to read a zone, convert an instant or parse a time. `Date.now()`
// is not among them: it is a count of milliseconds, the same in every zone,
// which is all the idle sign-out's timer reads.
const TIME_HANDLING =
  /\bnew\s+Date\b|\bDate\.(?:parse|UTC)\b|\bIntl\.DateTimeFormat\b|\btoLocale\w*String\b|\bgetTimezoneOffset\b|\btimeZone\b|\.get(?:FullYear|Month|Date|Day|Hours|Minutes|Seconds)\s*\(/;

// Same blanking as rawNetwork.test.ts: comments may name these freely, and
// line numbers stay true.
const blankComments = (source: string): string =>
  source
    .replace(/\/\*[\s\S]*?\*\//g, (match) => match.replace(/[^\n]/g, " "))
    .replace(/(^|[^:])\/\/.*$/gm, (match, prefix: string) => prefix.padEnd(match.length));

const sources = readSources("src", [".ts", ".tsx"]).filter(
  ({ path }) => !/\.(test|spec|testHelpers)\.[tj]sx?$/.test(path),
);
const files = sources
  .filter(({ path }) => path !== TIME_MODULE)
  .map(({ path, code }) => ({ path, code: blankComments(code) }));

describe("time handling", () => {
  it("finds the sources to check", () => {
    expect(files.length).toBeGreaterThan(0);
  });

  it("keeps src/lib/time.ts as the time module", () => {
    // Guards the exemption: if the module moves and TIME_MODULE is not moved
    // with it, the exemption would cover a file that no longer handles time.
    const module = sources.find(({ path }) => path === TIME_MODULE);

    expect(module, `${TIME_MODULE} is where every time is read and shown.`).toBeDefined();
    expect(TIME_HANDLING.test(blankComments(module?.code ?? ""))).toBe(true);
  });

  it("has no no_time_outside_time_module violation", () => {
    const violations = files.flatMap(({ path, code }) =>
      code
        .split("\n")
        .map((line, index) => ({ line, number: index + 1 }))
        .filter(({ line }) => TIME_HANDLING.test(line))
        .map(({ line, number }) => `${path}:${number}: ${line.trim()}`),
    );

    const why =
      `Every time is read and shown in Asia/Singapore through ${TIME_MODULE}: ` +
      "formatDate / formatInstant for an instant, parseTypedTime for a typed time, " +
      "isCalendarDate / formatCalendarDate for a date, and today(clock) for today.\n";

    expect(violations, why + violations.join("\n")).toEqual([]);
  });
});
