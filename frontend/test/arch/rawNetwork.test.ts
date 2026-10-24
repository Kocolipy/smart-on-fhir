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

// The one other module that may reach the network: the FHIRcast module, which
// talks to Epic's local FHIRcast hub on loopback (ADR 0016) — another origin,
// so none of the transport's session rules apply to it.
const FHIRCAST = "src/lib/fhircast.ts";

/** Every module allowed raw network access, and nothing else. */
const NETWORK_MODULES = [TRANSPORT, FHIRCAST];

// The browser's network entry points. `\bfetch\s*\(` rather than `fetch`
// alone, so identifiers like `refetch` and words in strings stay out.
const RAW_NETWORK =
  /(?<![.\w])fetch\s*\(|\bXMLHttpRequest\b|\bEventSource\b|\bnew\s+WebSocket\b|\bsendBeacon\s*\(/;

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

  it("has no no_raw_network_outside_transport violation", () => {
    const violations = files.flatMap(({ path, code }) =>
      code
        .split("\n")
        .map((line, index) => ({ line, number: index + 1 }))
        .filter(({ line }) => RAW_NETWORK.test(line))
        .map(({ line, number }) => `${path}:${number}: ${line.trim()}`),
    );

    const why =
      `Requests go through apiFetch in ${TRANSPORT}, reached via useSessionRequest ` +
      "(in practice useGatedRead / useGatedWrite), so CSRF, the single retry and the " +
      `session's 401 are handled once. Only ${FHIRCAST} reaches the local FHIRcast hub.\n`;

    expect(violations, why + violations.join("\n")).toEqual([]);
  });
});
