import { afterEach, describe, expect, it, vi } from "vitest";

import type { PatientEvent } from "@/lib/fhircast";

import {
  adoptLaunchLanding,
  clearEhrContext,
  ehrContext,
  followPatientEvent,
  subscribeEhrContext,
} from "./ehr-context";

// The subject is the tab itself: the page's URL, its history entry and its
// sessionStorage, all jsdom's own.

const TAN_WEI_MING = "eq081-VQEgP8drUUqCWzHfw3";

const SITI = "erXuFYUfucBZaryVksYEcMg3";

/** The hub's `event`, naming Patient `patientId` or, with `null`, none within the grammar. */
const hubEvent = (event: PatientEvent["event"], patientId: string | null): PatientEvent => ({
  event,
  patientId,
  timestamp: "2026-10-25T02:00:00Z",
});

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

describe("the EHR context", () => {
  it("keeps the patient and encounter an Epic landing carries, for the tab", () => {
    loadAt(`/#patient=${TAN_WEI_MING}&encounter=enc.42-A`);

    expect(ehrContext()).toEqual({ encounter: "enc.42-A", patient: TAN_WEI_MING });
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

    expect(ehrContext()).toEqual({ encounter: "enc-1" });
  });

  it("is replaced by every new landing, not merged with the one before", () => {
    loadAt(`/#patient=${TAN_WEI_MING}&encounter=enc-1`);
    loadAt("/#patient=other-patient");

    expect(ehrContext()).toEqual({ patient: "other-patient" });
  });

  it("is replaced with none by a landing with the bare marker", () => {
    loadAt(`/#patient=${TAN_WEI_MING}`);
    loadAt("/#launch");

    expect(ehrContext()).toEqual({});
  });

  it("survives a reload, which carries no fragment", () => {
    loadAt(`/#patient=${TAN_WEI_MING}`);
    loadAt("/");

    expect(ehrContext()).toEqual({ patient: TAN_WEI_MING });
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

    expect(ehrContext()).toEqual({ encounter: "enc-1" });
  });

  it("keeps a value of exactly 64 characters", () => {
    loadAt(`/#patient=${"a".repeat(64)}`);

    expect(ehrContext()).toEqual({ patient: "a".repeat(64) });
  });

  it("leaves a fragment that is no Epic landing, and the context, alone", () => {
    loadAt(`/#patient=${TAN_WEI_MING}`);
    loadAt("/#section-2");

    expect({ address: addressBar(), context: ehrContext() }).toEqual({
      address: "/#section-2",
      context: { patient: TAN_WEI_MING },
    });
  });

  it("is absent in a tab no landing ever reached", () => {
    expect(ehrContext()).toBeNull();
  });

  it("is absent once cleared", () => {
    loadAt(`/#patient=${TAN_WEI_MING}`);

    clearEhrContext();

    expect(ehrContext()).toBeNull();
  });

  it("is present, naming nothing, after a landing with the bare marker", () => {
    loadAt("/#launch");

    expect(ehrContext()).toEqual({});
  });

  it("is the same value on every read until it changes", () => {
    loadAt(`/#patient=${TAN_WEI_MING}`);

    expect(ehrContext()).toBe(ehrContext());
  });

  it("tells a subscriber when a landing seeds it", () => {
    const listener = vi.fn();
    const unsubscribe = subscribeEhrContext(listener);

    loadAt(`/#patient=${TAN_WEI_MING}`);
    unsubscribe();

    expect(listener).toHaveBeenCalledOnce();
  });

  it("tells a subscriber when it is cleared", () => {
    loadAt(`/#patient=${TAN_WEI_MING}`);
    const listener = vi.fn();
    const unsubscribe = subscribeEhrContext(listener);

    clearEhrContext();
    unsubscribe();

    expect(listener).toHaveBeenCalledOnce();
  });

  it("tells a subscriber nothing once it has unsubscribed", () => {
    const listener = vi.fn();
    subscribeEhrContext(listener)();

    loadAt(`/#patient=${TAN_WEI_MING}`);
    clearEhrContext();

    expect(listener).not.toHaveBeenCalled();
  });

  it("tells a subscriber nothing of a URL that is no Epic landing", () => {
    const listener = vi.fn();
    const unsubscribe = subscribeEhrContext(listener);

    loadAt("/#section-2");
    unsubscribe();

    expect(listener).not.toHaveBeenCalled();
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
    window.sessionStorage.setItem("ehr-context", stored);

    expect(ehrContext()).toEqual(expected);
  });
});

describe("the EHR context, as the FHIRcast hub's events move it", () => {
  it("is set to the patient a Patient-open names, the encounter dropped", () => {
    loadAt(`/#patient=${TAN_WEI_MING}&encounter=enc-1`);

    followPatientEvent(hubEvent("Patient-open", SITI));

    expect(ehrContext()).toEqual({ patient: SITI });
  });

  it("drops the encounter on a Patient-open for the patient already in context", () => {
    loadAt(`/#patient=${TAN_WEI_MING}&encounter=enc-1`);

    followPatientEvent(hubEvent("Patient-open", TAN_WEI_MING));

    expect(ehrContext()).toEqual({ patient: TAN_WEI_MING });
  });

  it("is set to the patient a Patient-open names in a context that named none", () => {
    loadAt("/#launch");

    followPatientEvent(hubEvent("Patient-open", TAN_WEI_MING));

    expect(ehrContext()).toEqual({ patient: TAN_WEI_MING });
  });

  it("is left alone by a Patient-open naming no valid id", () => {
    loadAt(`/#patient=${TAN_WEI_MING}&encounter=enc-1`);

    followPatientEvent(hubEvent("Patient-open", null));

    expect(ehrContext()).toEqual({ encounter: "enc-1", patient: TAN_WEI_MING });
  });

  it.each([
    ["the patient in context", TAN_WEI_MING],
    ["another patient", SITI],
    ["no valid id", null],
  ])("is emptied, but kept, by a Patient-close naming %s", (_name, patientId) => {
    loadAt(`/#patient=${TAN_WEI_MING}&encounter=enc-1`);

    followPatientEvent(hubEvent("Patient-close", patientId));

    expect(ehrContext()).toEqual({});
  });

  it("survives a reload once a Patient-open has moved it", () => {
    loadAt(`/#patient=${TAN_WEI_MING}`);
    followPatientEvent(hubEvent("Patient-open", SITI));

    loadAt("/");

    expect(ehrContext()).toEqual({ patient: SITI });
  });

  it("stays empty across a reload once a Patient-close has emptied it", () => {
    loadAt(`/#patient=${TAN_WEI_MING}`);
    followPatientEvent(hubEvent("Patient-close", TAN_WEI_MING));

    loadAt("/");

    expect(ehrContext()).toEqual({});
  });

  it("brings a patient back with a Patient-open after a Patient-close", () => {
    loadAt(`/#patient=${TAN_WEI_MING}`);
    followPatientEvent(hubEvent("Patient-close", TAN_WEI_MING));

    followPatientEvent(hubEvent("Patient-open", TAN_WEI_MING));

    expect(ehrContext()).toEqual({ patient: TAN_WEI_MING });
  });

  it.each([
    ["a Patient-open", hubEvent("Patient-open", TAN_WEI_MING)],
    ["a Patient-close", hubEvent("Patient-close", TAN_WEI_MING)],
  ])("is never created by %s in a tab that holds none", (_name, event) => {
    followPatientEvent(event);

    expect(ehrContext()).toBeNull();
  });

  it.each([
    ["a Patient-open", hubEvent("Patient-open", SITI)],
    ["a Patient-close", hubEvent("Patient-close", null)],
  ])("tells a subscriber when %s moves it", (_name, event) => {
    loadAt(`/#patient=${TAN_WEI_MING}`);
    const listener = vi.fn();
    const unsubscribe = subscribeEhrContext(listener);

    followPatientEvent(event);
    unsubscribe();

    expect(listener).toHaveBeenCalledOnce();
  });

  it("tells a subscriber nothing of a Patient-open it ignores", () => {
    loadAt(`/#patient=${TAN_WEI_MING}`);
    const listener = vi.fn();
    const unsubscribe = subscribeEhrContext(listener);

    followPatientEvent(hubEvent("Patient-open", null));
    unsubscribe();

    expect(listener).not.toHaveBeenCalled();
  });
});
