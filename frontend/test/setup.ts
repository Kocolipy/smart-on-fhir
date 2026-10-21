import "@testing-library/jest-dom";
import { afterEach } from "vitest";

// One happy-dom window serves a whole test file, so the tab's sessionStorage —
// where the Launch context lives — would otherwise carry over between tests.
afterEach(() => {
  window.sessionStorage.clear();
});
