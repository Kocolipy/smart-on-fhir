import { renderHook } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { usePatientInContext } from "./patient-in-context";

// What the one read shows, and how often it asks, is held where it is shown:
// `home.test.tsx` (one read per change, for the card and the top bar both),
// `patient-card.test.tsx` and `shell/top-bar.test.tsx`.

describe("usePatientInContext", () => {
  it("has nothing to read outside the signed-in layout's scope", () => {
    const silence = vi.spyOn(console, "error").mockImplementation(() => undefined);
    let thrown: unknown = null;
    try {
      renderHook(() => usePatientInContext());
    } catch (error) {
      thrown = error;
    } finally {
      silence.mockRestore();
    }

    expect(thrown).toEqual(
      new Error("The patient in context is read only inside the signed-in layout."),
    );
  });
});
