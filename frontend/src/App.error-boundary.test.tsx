import { render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { App } from "./App";
import { bare, fakeBackend } from "./lib/fake-backend.testHelpers";

// Its own file because the page has to be replaced at module level: the point is
// to prove App's real tree catches a page that throws, not a hand-built one.
vi.mock("@/pages/login", () => ({
  Login: () => {
    throw new Error("login-page-internal-failure");
  },
}));

describe("App error boundary", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("shows the generic fallback when a routed page throws", async () => {
    vi.spyOn(console, "error").mockImplementation(() => {});
    fakeBackend({ "GET /api/auth/me": bare(401) });
    render(<App />);

    expect(
      await screen.findByRole("heading", { name: "Something went wrong" }),
    ).toBeInTheDocument();
    expect(document.body.textContent).not.toContain("login-page-internal-failure");
  });
});
