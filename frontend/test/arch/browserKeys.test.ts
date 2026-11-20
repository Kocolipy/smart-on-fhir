import { describe, expect, it } from "vitest";

import { readSources } from "./sources";

// Architecture rule `no_browser_keys_outside_the_key_module` (ADR 0016, "The
// dynamic client and the hub token"; ADR 0011, "FHIRcast in the browser").
// The tab's dynamic client at the FHIRcast Hub is a private key the browser
// profile keeps: generated non-extractable in WebCrypto, kept in IndexedDB,
// and registered under a Web Locks lock. One module does all three, so the
// private key is only ever a `CryptoKey` that signs, and what is kept is
// decoded in one place. `crypto.subtle`, `indexedDB` and `navigator.locks` are
// globals, so a page that reaches them imports nothing, and
// dependency-cruiser never sees it.
const KEY_MODULE = "src/lib/fhircast/client-key.ts";

// `subtle` alone, so `const { subtle } = crypto` is caught as well as
// `crypto.subtle`; `navigator.locks` and `locks.request`, the Web Locks API.
const BROWSER_KEYS = /\bsubtle\b|\bindexedDB\b|\bnavigator\.locks\b|\blocks\.request\s*\(/;

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
  .filter(({ path }) => path !== KEY_MODULE)
  .map(({ path, code }) => ({ path, code: blankComments(code) }));

/** Each line of `checked` that reaches WebCrypto, IndexedDB or Web Locks, as `path:line: code`. */
const violationsIn = (checked: { path: string; code: string }[]): string[] =>
  checked.flatMap(({ path, code }) =>
    code
      .split("\n")
      .map((line, index) => ({ line, number: index + 1 }))
      .filter(({ line }) => BROWSER_KEYS.test(line))
      .map(({ line, number }) => `${path}:${number}: ${line.trim()}`),
  );

describe("browser keys", () => {
  it("finds the sources to check", () => {
    expect(files.length).toBeGreaterThan(0);
  });

  it("keeps the key module as the one that reaches them", () => {
    // Guards the exemption: if the module moves and its entry is not moved
    // with it, the exemption would cover a file that no longer reaches them.
    const module = sources.find((source) => source.path === KEY_MODULE);

    expect(module, `${KEY_MODULE} is the exempt key module.`).toBeDefined();
    expect(violationsIn([{ code: blankComments(module?.code ?? ""), path: KEY_MODULE }])).toEqual([
      expect.stringContaining("crypto.subtle.digest"),
      expect.stringContaining("crypto.subtle.generateKey"),
      expect.stringContaining("crypto.subtle.exportKey"),
      expect.stringContaining("crypto.subtle.sign"),
      expect.stringContaining("indexedDB.open"),
      expect.stringContaining("navigator.locks"),
    ]);
  });

  it("reports each of WebCrypto, IndexedDB and Web Locks, and not in a comment", () => {
    const planted = [
      { path: "src/pages/planted-subtle.ts", code: "crypto.subtle.sign(alg, key, data);" },
      { path: "src/pages/planted-destructured.ts", code: "const { subtle } = crypto;" },
      { path: "src/pages/planted-idb.ts", code: 'indexedDB.open("db");' },
      { path: "src/pages/planted-window-idb.ts", code: 'window.indexedDB.open("db");' },
      { path: "src/pages/planted-locks.ts", code: 'navigator.locks.request("x", task);' },
      { path: "src/pages/planted-held-locks.ts", code: 'locks.request("x", task);' },
      { path: "src/pages/planted-random.ts", code: "crypto.randomUUID();" },
      { path: "src/pages/planted-word.ts", code: 'const tone = "subtler";' },
      { path: "src/pages/planted-comment.ts", code: blankComments("// crypto.subtle is not ours") },
    ];

    expect(violationsIn(planted)).toEqual([
      "src/pages/planted-subtle.ts:1: crypto.subtle.sign(alg, key, data);",
      "src/pages/planted-destructured.ts:1: const { subtle } = crypto;",
      'src/pages/planted-idb.ts:1: indexedDB.open("db");',
      'src/pages/planted-window-idb.ts:1: window.indexedDB.open("db");',
      'src/pages/planted-locks.ts:1: navigator.locks.request("x", task);',
      'src/pages/planted-held-locks.ts:1: locks.request("x", task);',
    ]);
  });

  it("has no no_browser_keys_outside_the_key_module violation", () => {
    const violations = violationsIn(files);

    const why =
      `The tab's dynamic client key is generated, kept and registered under its lock in ` +
      `${KEY_MODULE} alone: WebCrypto keeps the private key non-extractable there, and ` +
      "what IndexedDB hands back is decoded there.\n";

    expect(violations, why + violations.join("\n")).toEqual([]);
  });
});
