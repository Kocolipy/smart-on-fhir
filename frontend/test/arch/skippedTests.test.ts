import { describe, expect, it } from "vitest";

import { readSources } from "./sources";

// Architecture rule `no_skipped_or_focused_tests` (/backend/AGENTS.md, "Test
// suite policy": "A broken test is corrected from source authority or removed
// with `git rm`; `@Disabled` and skip markers stay out of the tree" — the
// monorepo's policy, held on the backend's test sources by
// `arch.TestSourceArchitectureTest` and here on the SPA's).
// `docs/TESTING_GUIDE.md` lists it beside the other `test/arch` rules.
//
// Two markers, with opposite failure modes and the same effect — a suite that
// reports success without running what it names:
//
//   - a skip (`it.skip`, `it.todo`, `xit`) leaves a test in the tree that
//     nothing runs, so the behaviour it names drifts away unnoticed;
//   - a focus (`it.only`, `fdescribe`) runs that test and silently drops every
//     other one in the file.
//
// Neither is caught by the runners here. vitest refuses `.only` only when
// `CI` is set and this repo has no CI (`vitest.config.ts` therefore sets
// `allowOnly: false` outright, and `playwright.config.ts` `forbidOnly: true`),
// and no runner refuses a skip at all.
const RULE_SOURCE = "test/arch/skippedTests.test.ts";

// A marker on a suite or a case: `it.skip(`, `describe.only`, `test.todo`,
// `it.fixme` — and the `x`/`f` prefixed spellings, which take their
// parenthesis so identifiers like `fitFrame` stay out.
const SKIPPED_OR_FOCUSED =
  /\b(?:it|test|describe)\.(?:skip|only|todo|fixme)\b|\b(?:xit|xdescribe|fit|fdescribe)\s*\(/;

// Same blanking as rawNetwork.test.ts: comments may name these freely, and
// line numbers stay true.
const blankComments = (source: string): string =>
  source
    .replace(/\/\*[\s\S]*?\*\//g, (match) => match.replace(/[^\n]/g, " "))
    .replace(/(^|[^:])\/\/.*$/gm, (match, prefix: string) => prefix.padEnd(match.length));

// Every suite in the repo: the colocated unit tests under `src`, and `test`,
// which `readSources` walks recursively — the adapters, the architecture
// suite and the Playwright specs under `test/e2e` alike.
const sources = [...readSources("src", [".test.ts", ".test.tsx"]), ...readSources("test", [".ts"])];

// This file is the one exemption: it names every marker in the patterns and
// the planted cases below, so scanning it would report the rule itself.
const files = sources
  .filter(({ path }) => path !== RULE_SOURCE)
  .map(({ path, code }) => ({ path, code: blankComments(code) }));

/** Each line of `checked` carrying a skip or focus marker, as `path:line: code`. */
const violationsIn = (checked: { path: string; code: string }[]): string[] =>
  checked.flatMap(({ path, code }) =>
    code
      .split("\n")
      .map((line, index) => ({ line, number: index + 1 }))
      .filter(({ line }) => SKIPPED_OR_FOCUSED.test(line))
      .map(({ line, number }) => `${path}:${number}: ${line.trim()}`),
  );

describe("skipped and focused tests", () => {
  it("finds the sources to check", () => {
    expect(files.length).toBeGreaterThan(0);
    expect(files.some(({ path }) => path.startsWith("test/e2e/"))).toBe(true);
    expect(files.some(({ path }) => path.startsWith("src/"))).toBe(true);
  });

  it("keeps itself as the one exempt file", () => {
    // Guards the exemption: if this file is renamed and RULE_SOURCE is not
    // renamed with it, the exemption would cover nothing and the planted
    // strings below would be reported as real violations.
    const self = sources.find(({ path }) => path === RULE_SOURCE);

    expect(self, `${RULE_SOURCE} names every marker, so it exempts itself.`).toBeDefined();
  });

  it("reports every marker, and not a comment or a lookalike", () => {
    // Proves the rule fires: without it the pattern could reject everything
    // and the case below would pass on any suite at all. `it.each` is the
    // table form the suites use, `fitting` an ordinary identifier, and a
    // commented-out marker may name one freely.
    const marker = (name: string): string => `${name}("plants a violation", () => {});`;
    const planted = [
      { path: "src/pages/planted-skip.test.tsx", code: marker("it.skip") },
      { path: "src/pages/planted-only.test.tsx", code: marker("describe.only") },
      { path: "src/pages/planted-todo.test.tsx", code: marker("test.todo") },
      { path: "src/pages/planted-fixme.test.tsx", code: marker("it.fixme") },
      { path: "test/e2e/planted-xit.spec.ts", code: marker("xit") },
      { path: "test/e2e/planted-fdescribe.spec.ts", code: marker("fdescribe") },
      { path: "src/pages/planted-each.test.tsx", code: 'it.each(["a"])("%s", () => {});' },
      { path: "src/pages/planted-identifier.test.tsx", code: "const fitting = frame();" },
      {
        path: "src/pages/planted-comment.test.tsx",
        code: blankComments(`// ${marker("it.only")}`),
      },
    ];

    expect(violationsIn(planted)).toEqual([
      `src/pages/planted-skip.test.tsx:1: ${marker("it.skip")}`,
      `src/pages/planted-only.test.tsx:1: ${marker("describe.only")}`,
      `src/pages/planted-todo.test.tsx:1: ${marker("test.todo")}`,
      `src/pages/planted-fixme.test.tsx:1: ${marker("it.fixme")}`,
      `test/e2e/planted-xit.spec.ts:1: ${marker("xit")}`,
      `test/e2e/planted-fdescribe.spec.ts:1: ${marker("fdescribe")}`,
    ]);
  });

  it("has no no_skipped_or_focused_tests violation", () => {
    const violations = violationsIn(files);

    const why =
      "A test is corrected from source authority or removed with `git rm`: a skip leaves a " +
      "test nothing runs, and a focus drops every other test in its file, both while the " +
      "suite still reports success (/backend/AGENTS.md, \u201cTest suite policy\u201d).\n";

    expect(violations, why + violations.join("\n")).toEqual([]);
  });
});
