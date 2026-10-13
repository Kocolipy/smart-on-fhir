import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { describe, expect, it } from "vitest";

import { AuthContext, type AuthContextState } from "./auth-context-value";
import { authState, CHECKING, unchecked } from "./auth-context.testHelpers";
import { GuestRoute, ProtectedRoute } from "./route-guards";

/** The guest route at `/` and a protected one at `/showcase`, entered at `path`. */
function renderRoutes(value: AuthContextState, path = "/showcase") {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AuthContext.Provider value={value}>
        <Routes>
          <Route
            element={
              <GuestRoute>
                <p>Login page</p>
              </GuestRoute>
            }
            path="/"
          />
          <Route
            element={
              <ProtectedRoute>
                <p>Protected page</p>
              </ProtectedRoute>
            }
            path="/showcase"
          />
        </Routes>
      </AuthContext.Provider>
    </MemoryRouter>,
  );
}

describe("ProtectedRoute", () => {
  it("waits for the session check with a pending view, rendering nothing protected", () => {
    renderRoutes(authState(CHECKING));

    expect(screen.getByText(/^Checking your session…$/)).toBeInTheDocument();
    expect(screen.queryByText("Protected page")).not.toBeInTheDocument();
  });
});

describe("a session check that did not answer", () => {
  it.each([
    ["a protected route", "/showcase"],
    ["the guest route", "/"],
  ])("says on %s, in one line, that the session could not be checked", (_name, path) => {
    renderRoutes(authState(unchecked()), path);

    expect(screen.getAllByRole("alert").map((line) => line.textContent)).toEqual([
      "Your session could not be checked. Please try again.",
    ]);
  });

  it("neither renders the protected page nor sends the visitor to login", () => {
    renderRoutes(authState(unchecked()));

    expect([screen.queryByText("Protected page"), screen.queryByText("Login page")]).toEqual([
      null,
      null,
    ]);
  });

  it("says why when the seam knows, as every other refusal does", () => {
    renderRoutes(authState(unchecked({ kind: "forbidden" })));

    expect(screen.getByRole("alert")).toHaveTextContent(
      /^Your session could not be checked\. You don't have permission to do this\.$/,
    );
  });

  it("offers to check the session again", async () => {
    const value = authState(unchecked());
    renderRoutes(value);

    await userEvent.setup().click(screen.getByRole("button", { name: "Check again" }));

    expect(value.retrySessionCheck).toHaveBeenCalledOnce();
  });
});
