import { describe, expect, it } from "vitest";

import { readSource, readSources } from "./sources";

// Architecture rule `e2e_spec_routed_to_project` (docs/E2E_GUIDE.md, "Routing a new spec").
// A spec matched by no project's `testMatch` runs in no project and is
// silently skipped — the suite still reports green, and no other gate notices.
// The routing rules are read out of playwright.config.ts itself rather than
// restated here, so the two cannot drift apart.
//
// The config is read as source rather than imported: importing it would drag
// the real @playwright/test runtime into vitest, which starts browsers and a
// web server the moment it loads.
const configSource = readSource("playwright.config.ts");

// Each project declares its name immediately before its testMatch, so one
// non-greedy pass pairs them up. Anything this cannot parse — a glob string,
// an array, a computed value — is caught by the count assertion below rather
// than silently dropped.
const PROJECT_ROUTE =
  /name:\s*['"]([^'"]+)['"],[\s\S]*?testMatch:\s*\/((?:\\.|[^/\\\n])+)\/([gimsuy]*)/g;

const routes = [...configSource.matchAll(PROJECT_ROUTE)].map(([, name, pattern, flags]) => ({
  name,
  testMatch: new RegExp(pattern, flags),
}));

// Paths only — no spec is loaded by this test, so none of them runs here.
const testFiles = readSources("test/e2e", [".spec.ts", ".setup.ts"]).map(({ path }) => path);

describe("E2E spec routing", () => {
  it("finds the E2E test files to check", () => {
    expect(testFiles.length).toBeGreaterThan(0);
  });

  it("parses a testMatch regex for every project", () => {
    const declared = configSource.match(/testMatch:/g)?.length ?? 0;

    expect(routes.length).toBeGreaterThan(0);
    expect(
      routes.length,
      "playwright.config.ts declares a testMatch this test could not parse as a single regex literal (a glob string, an array, or a computed value). Extend the parser rather than leaving a project unchecked.",
    ).toBe(declared);
  });

  it.each(testFiles.map((file) => [file]))("routes %s to exactly one project", (file) => {
    const matched = routes.filter(({ testMatch }) => testMatch.test(file)).map(({ name }) => name);

    expect(
      matched,
      matched.length === 0
        ? `${file} is matched by no project's testMatch, so it runs in no project and is silently skipped. Add it to a project's testMatch in playwright.config.ts.`
        : `${file} is matched by more than one project (${matched.join(", ")}), so it runs more than once.`,
    ).toHaveLength(1);
  });
});
