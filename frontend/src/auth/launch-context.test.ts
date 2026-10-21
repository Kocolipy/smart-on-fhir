import { afterEach, describe, expect, it } from "vitest";

import { adoptLaunchLanding, clearLaunchContext, launchContext } from "./launch-context";

// The subject is the tab itself: the page's URL, its history entry and its
// sessionStorage, all jsdom's own.

const TAN_WEI_MING = "eq081-VQEgP8drUUqCWzHfw3";

/** The page loads at `url`, as the browser follows the backend's Epic landing redirect. */
function loadAt(url: string) {
  window.history.replaceState(null, "", url);
  adoptLaunchLanding();
}

/** Where the tab now is, as its address bar shows it. */
const addressBar = () =>
  `${window.location.pathname}${window.location.search}${window.location.hash}`;

afterEach(() => {
  window.history.replaceState(null, "", "/");
});

describe("the Launch context", () => {
  it("keeps the patient and encounter an Epic landing carries, for the tab", () => {
    loadAt(`/#patient=${TAN_WEI_MING}&encounter=enc.42-A`);

    expect(launchContext()).toEqual({ encounter: "enc.42-A", patient: TAN_WEI_MING });
  });

  it("strips the landing's fragment from the URL, leaving the path", () => {
    loadAt(`/#patient=${TAN_WEI_MING}&encounter=enc.42-A`);

    expect(addressBar()).toBe("/");
  });

  it("strips the bare marker from the URL too", () => {
    loadAt("/#launch");

    expect(addressBar()).toBe("/");
  });

  it("keeps whichever of the two a landing carries", () => {
    loadAt("/#encounter=enc-1");

    expect(launchContext()).toEqual({ encounter: "enc-1" });
  });

  it("is replaced by every new landing, not merged with the one before", () => {
    loadAt(`/#patient=${TAN_WEI_MING}&encounter=enc-1`);
    loadAt("/#patient=other-patient");

    expect(launchContext()).toEqual({ patient: "other-patient" });
  });

  it("is replaced with none by a landing with the bare marker", () => {
    loadAt(`/#patient=${TAN_WEI_MING}`);
    loadAt("/#launch");

    expect(launchContext()).toEqual({});
  });

  it("survives a reload, which carries no fragment", () => {
    loadAt(`/#patient=${TAN_WEI_MING}`);
    loadAt("/");

    expect(launchContext()).toEqual({ patient: TAN_WEI_MING });
  });

  it.each([
    ["a slash", "a%2Fb"],
    ["a space", "a%20b"],
    ["markup", "%3Cb%3Ex%3C%2Fb%3E"],
    ["65 characters", "a".repeat(65)],
    ["nothing", ""],
    ["an underscore", "a_b"],
  ])("ignores a value with %s, keeping the other", (_name, value) => {
    loadAt(`/#patient=${value}&encounter=enc-1`);

    expect(launchContext()).toEqual({ encounter: "enc-1" });
  });

  it("keeps a value of exactly 64 characters", () => {
    loadAt(`/#patient=${"a".repeat(64)}`);

    expect(launchContext()).toEqual({ patient: "a".repeat(64) });
  });

  it("leaves a fragment that is no Epic landing, and the context, alone", () => {
    loadAt(`/#patient=${TAN_WEI_MING}`);
    loadAt("/#section-2");

    expect({ address: addressBar(), context: launchContext() }).toEqual({
      address: "/#section-2",
      context: { patient: TAN_WEI_MING },
    });
  });

  it("is none in a tab no landing ever reached", () => {
    expect(launchContext()).toEqual({});
  });

  it("is none once cleared", () => {
    loadAt(`/#patient=${TAN_WEI_MING}`);

    clearLaunchContext();

    expect(launchContext()).toEqual({});
  });

  // The tab's storage is the page's to write, so what is read back is decoded
  // rather than trusted.
  it.each([
    ["not JSON", "{patient", {}],
    ["not an object", "42", {}],
    ["null", "null", {}],
    [
      "a value outside the grammar",
      '{"patient":"a/b","encounter":"enc-1"}',
      { encounter: "enc-1" },
    ],
    ["a value that is no string", '{"patient":7,"encounter":"enc-1"}', { encounter: "enc-1" }],
  ])("reads only what the grammar allows from storage holding %s", (_name, stored, expected) => {
    window.sessionStorage.setItem("launch-context", stored);

    expect(launchContext()).toEqual(expected);
  });
});
