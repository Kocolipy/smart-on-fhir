import "@testing-library/jest-dom";
import { afterEach } from "vitest";

import { endFhircast } from "@/lib/fhircast";

// One happy-dom window serves a whole test file, so the tab's sessionStorage —
// where the Launch context lives — would otherwise carry over between tests.
// So would the connections the FHIRcast module registered, which a later
// test's end of the session would tear down at that test's hub; ended here,
// while this test's hub still answers.
afterEach(async () => {
  await endFhircast();
  window.sessionStorage.clear();
});
