import { describe, expect, it } from "vitest";

import { blankComments, readSources, violationsIn } from "./sources";

// Architecture rule `no_cookie_outside_the_sidebar` (docs/BACKEND_CONTRACT.md,
// "CSRF" and "Logout"; ADR 0009). The session cookie is the backend's and
// HttpOnly, and the CSRF token never travels in a cookie, so the SPA writes one
// cookie of its own: the vendored sidebar's open state, which carries nothing
// about the session. `document.cookie` and `cookieStore` are globals, so a page
// that reaches them imports nothing, and dependency-cruiser never sees it. Web
// storage is webStorage.test.ts's rule.
const SIDEBAR = "src/components/ui/sidebar.tsx";

// `cookieStore`, the Cookie Store API, as well as `document.cookie`.
const COOKIES = /\bdocument\.cookie\b|\bcookieStore\b/;

const sources = readSources("src", [".ts", ".tsx"]).filter(
  ({ path }) => !/\.(test|spec|testHelpers)\.[tj]sx?$/.test(path),
);
const files = sources
  .filter(({ path }) => path !== SIDEBAR)
  .map(({ path, code }) => ({ path, code: blankComments(code) }));

describe("cookies", () => {
  it("finds the sources to check", () => {
    expect(files.length).toBeGreaterThan(0);
  });

  it("keeps the sidebar as the one that writes a cookie", () => {
    // Guards the exemption: if the sidebar moves or stops keeping its state in
    // a cookie, the exemption would cover a file that no longer needs it.
    const module = sources.find((source) => source.path === SIDEBAR);

    expect(module, `${SIDEBAR} is the exempt cookie writer.`).toBeDefined();
    expect(
      violationsIn([{ code: blankComments(module?.code ?? ""), path: SIDEBAR }], COOKIES),
    ).toEqual([expect.stringContaining("document.cookie = `${SIDEBAR_COOKIE_NAME}")]);
  });

  it("reports document.cookie and the Cookie Store API, and not in a comment", () => {
    const planted = [
      { path: "src/pages/planted-read.ts", code: "const all = document.cookie;" },
      { path: "src/pages/planted-window.ts", code: 'window.document.cookie = "csrf=x";' },
      { path: "src/pages/planted-store.ts", code: 'await cookieStore.set("csrf", value);' },
      { path: "src/pages/planted-word.ts", code: 'const label = "cookie policy";' },
      {
        path: "src/pages/planted-comment.ts",
        code: blankComments("// document.cookie is not ours"),
      },
    ];

    expect(violationsIn(planted, COOKIES)).toEqual([
      "src/pages/planted-read.ts:1: const all = document.cookie;",
      'src/pages/planted-window.ts:1: window.document.cookie = "csrf=x";',
      'src/pages/planted-store.ts:1: await cookieStore.set("csrf", value);',
    ]);
  });

  it("has no no_cookie_outside_the_sidebar violation", () => {
    const violations = violationsIn(files, COOKIES);

    const why =
      "The session cookie is the backend's and the CSRF token never travels in a cookie, so " +
      `the SPA writes one cookie of its own, the sidebar's open state (${SIDEBAR}). Anything ` +
      "else the tab keeps gets a keeper of its own, added here and to " +
      "docs/BACKEND_CONTRACT.md together.\n";

    expect(violations, why + violations.join("\n")).toEqual([]);
  });
});
