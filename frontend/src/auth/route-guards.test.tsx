import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";

import { AuthContext, type AuthContextState } from "./auth-context-value";
import { ProtectedRoute } from "./route-guards";

describe("ProtectedRoute", () => {
  it("waits for the session check with a pending view, rendering nothing protected", () => {
    const value: AuthContextState = {
      changePassword: vi.fn(),
      expireSession: vi.fn(),
      login: vi.fn(),
      logout: vi.fn(),
      signInReason: null,
      signOutForInactivity: vi.fn(),
      status: "checking",
      user: null,
    };
    render(
      <MemoryRouter initialEntries={["/showcase"]}>
        <AuthContext.Provider value={value}>
          <ProtectedRoute>
            <p>Protected page</p>
          </ProtectedRoute>
        </AuthContext.Provider>
      </MemoryRouter>,
    );

    expect(screen.getByText(/^Checking your session…$/)).toBeInTheDocument();
    expect(screen.queryByText("Protected page")).not.toBeInTheDocument();
  });
});
