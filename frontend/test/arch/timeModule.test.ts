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

/** Each line of `checked` that handles a time, as `path:line: code`. */
const violationsIn = (checked: { path: string; code: string }[]): string[] =>
  checked.flatMap(({ path, code }) =>
    code
      .split("\n")
      .map((line, index) => ({ line, number: index + 1 }))
      .filter(({ line }) => TIME_HANDLING.test(line))
      .map(({ line, number }) => `${path}:${number}: ${line.trim()}`),
  );

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

  it("reports every way to read a zone, and not Date.now or a comment", () => {
    // Proves the rule fires: without it the pattern could reject everything
    // and the case above would pass on any source at all. One planted line per
    // kind of time handling, plus the two that must stay out — `Date.now()`,
    // the zone-free millisecond count the idle sign-out reads, and a
    // commented-out construction.
    const planted = [
      { path: "src/pages/planted-date.tsx", code: "const at = new Date(value);" },
      { path: "src/pages/planted-parse.tsx", code: "const at = Date.parse(s);" },
      { path: "src/pages/planted-intl.tsx", code: 'new Intl.DateTimeFormat("en");' },
      { path: "src/pages/planted-locale.tsx", code: "d.toLocaleDateString();" },
      { path: "src/pages/planted-getter.tsx", code: "d.getHours();" },
      { path: "src/pages/planted-zone.tsx", code: 'format(d, { timeZone: "UTC" });' },
      { path: "src/pages/planted-now.tsx", code: "const ms = Date.now();" },
      { path: "src/pages/planted-comment.tsx", code: blankComments("// new Date() is not ours") },
    ];

    expect(violationsIn(planted)).toEqual([
      "src/pages/planted-date.tsx:1: const at = new Date(value);",
      "src/pages/planted-parse.tsx:1: const at = Date.parse(s);",
      'src/pages/planted-intl.tsx:1: new Intl.DateTimeFormat("en");',
      "src/pages/planted-locale.tsx:1: d.toLocaleDateString();",
      "src/pages/planted-getter.tsx:1: d.getHours();",
      'src/pages/planted-zone.tsx:1: format(d, { timeZone: "UTC" });',
    ]);
  });

  it("has no no_time_outside_time_module violation", () => {
    const violations = violationsIn(files);

    const why =
      `Every time is read and shown in Asia/Singapore through ${TIME_MODULE}: ` +
      "formatDate / formatInstant for an instant, parseTypedTime for a typed time, " +
      "isCalendarDate / formatCalendarDate for a date, and today(clock) for today.\n";

    expect(violations, why + violations.join("\n")).toEqual([]);
  });
});
