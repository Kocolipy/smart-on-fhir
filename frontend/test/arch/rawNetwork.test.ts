import { describe, expect, it } from "vitest";

import { readSources } from "./sources";

// Architecture rule `no_raw_network_outside_transport` (docs/ARCHITECTURE.md,
// "Why every request goes through lib/http.ts"). apiFetch is the one place that
// holds the CSRF token, retries a stale one once and classifies a 401 as the
// session ending. mb-transport-is-behind-the-session-seam holds that for
// imports, but `fetch` is a global: a page that calls it directly imports
// nothing, so dependency-cruiser never sees it, and the request silently
// skips all three.
const TRANSPORT = "src/lib/http.ts";

// The only others that may reach the network: the two FHIRcast modules that
// talk to Epic's local FHIRcast hub on loopback (ADR 0016) — another origin,
// so none of the transport's session rules apply to it. The hub protocol makes
// every HTTP request of the hub; the notifications module opens its socket.
const FHIRCAST_PROTOCOL = "src/lib/fhircast/protocol.ts";
const FHIRCAST_NOTIFICATIONS = "src/lib/fhircast/notifications.ts";

/** Every module allowed raw network access, and nothing else. */
const NETWORK_MODULES = [TRANSPORT, FHIRCAST_PROTOCOL, FHIRCAST_NOTIFICATIONS];

// The browser's network entry points. `fetch\s*\(` rather than `fetch` alone,
// so identifiers like `refetch` and words in strings stay out.
//
// `fetch` is matched in two spellings, because the global has two: bare
// `fetch(` — nothing before it but whitespace or punctuation — and `fetch(`
// reached through the global object it hangs off (`window.fetch(u)`,
// `globalThis.fetch(u)`, `self.fetch(u)`), which is the same global and the
// same request. Without the three prefixes the lookbehind that keeps
// `client.fetch(` and `query.refetch(` out would reject those too, and a page
// could skip the transport by writing `window.` in front of the call.
const RAW_NETWORK =
  /(?:(?<![.\w])|(?<=\b(?:window|globalThis|self)\.))fetch\s*\(|\bXMLHttpRequest\b|\bEventSource\b|\bnew\s+WebSocket\b|\bsendBeacon\s*\(/;

// Same blanking as designTokens.test.ts: comments may name these freely, and
// line numbers stay true.
const blankComments = (source: string): string =>
  source
    .replace(/\/\*[\s\S]*?\*\//g, (match) => match.replace(/[^\n]/g, " "))
    .replace(/(^|[^:])\/\/.*$/gm, (match, prefix: string) => prefix.padEnd(match.length));

const sources = readSources("src", [".ts", ".tsx"]).filter(
  ({ path }) => !/\.(test|spec|testHelpers)\.[tj]sx?$/.test(path),
);
const files = sources
  .filter(({ path }) => !NETWORK_MODULES.includes(path))
  .map(({ path, code }) => ({ path, code: blankComments(code) }));

/** Each line of `checked` that reaches the network, as `path:line: code`. */
const violationsIn = (checked: { path: string; code: string }[]): string[] =>
  checked.flatMap(({ path, code }) =>
    code
      .split("\n")
      .map((line, index) => ({ line, number: index + 1 }))
      .filter(({ line }) => RAW_NETWORK.test(line))
      .map(({ line, number }) => `${path}:${number}: ${line.trim()}`),
  );

describe("raw network access", () => {
  it("finds the sources to check", () => {
    expect(files.length).toBeGreaterThan(0);
  });

  it.each(NETWORK_MODULES)("keeps %s as a network module", (path) => {
    // Guards the exemption: if the module moves and its entry is not moved
    // with it, the exemption would cover a file that no longer reaches the network.
    const module = sources.find((source) => source.path === path);

    expect(module, `${path} is an exempt network module.`).toBeDefined();
    expect(RAW_NETWORK.test(blankComments(module?.code ?? ""))).toBe(true);
  });

  it("reports every network entry point, and not in a comment", () => {
    // Proves the rule fires: without it the regex could reject everything and
    // the case above would pass on any source at all. Each entry point gets a
    // line, including the two global-object spellings of `fetch`; the three
    // that must stay out are a `refetch` identifier, a method named `fetch` on
    // something that is not the global, and a commented-out call.
    const planted = [
      { path: "src/pages/planted-fetch.tsx", code: 'fetch("/api/x");' },
      { path: "src/pages/planted-window.tsx", code: "window.fetch(u);" },
      { path: "src/pages/planted-global.tsx", code: "globalThis.fetch(u);" },
      { path: "src/pages/planted-socket.tsx", code: "new WebSocket(u);" },
      { path: "src/pages/planted-beacon.tsx", code: "navigator.sendBeacon(u);" },
      { path: "src/pages/planted-xhr.tsx", code: "new XMLHttpRequest();" },
      { path: "src/pages/planted-events.tsx", code: "new EventSource(u);" },
      { path: "src/pages/planted-refetch.tsx", code: "refetch();" },
      { path: "src/pages/planted-method.tsx", code: "client.fetch(u);" },
      { path: "src/pages/planted-comment.tsx", code: blankComments("// fetch(u) is not ours") },
    ];

    expect(violationsIn(planted)).toEqual([
      'src/pages/planted-fetch.tsx:1: fetch("/api/x");',
      "src/pages/planted-window.tsx:1: window.fetch(u);",
      "src/pages/planted-global.tsx:1: globalThis.fetch(u);",
      "src/pages/planted-socket.tsx:1: new WebSocket(u);",
      "src/pages/planted-beacon.tsx:1: navigator.sendBeacon(u);",
      "src/pages/planted-xhr.tsx:1: new XMLHttpRequest();",
      "src/pages/planted-events.tsx:1: new EventSource(u);",
    ]);
  });

  it("has no no_raw_network_outside_transport violation", () => {
    const violations = violationsIn(files);

    const why =
      `Requests go through apiFetch in ${TRANSPORT}, reached via useSessionRequest ` +
      "(in practice useGatedRead / useGatedWrite), so CSRF, the single retry and the " +
      `session's 401 are handled once. Only ${FHIRCAST_PROTOCOL} and ${FHIRCAST_NOTIFICATIONS} ` +
      "reach the local FHIRcast hub.\n";

    expect(violations, why + violations.join("\n")).toEqual([]);
  });
});
