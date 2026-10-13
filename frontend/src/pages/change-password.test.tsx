import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { PasswordChangeOutcome } from "@/auth/api";
import { AuthContext, type AuthContextState } from "@/auth/auth-context-value";
import { authState, CHECKING, signedIn } from "@/auth/auth-context.testHelpers";
import { PASSWORD_LENGTH } from "@/auth/password-policy";

import { ChangePassword } from "./change-password";

/** Distinctive values, so a search of the DOM or a log line for them cannot match by accident. */
const CURRENT = "Current-Value-4f81";
const NEXT = "Next-Value-9c2e-long";

const flaggedAuth = (): AuthContextState =>
  authState(signedIn({ passwordChangeRequired: true }), {
    logout: vi.fn().mockResolvedValue(undefined),
  });

const unflaggedAuth = (): AuthContextState => ({ ...flaggedAuth(), ...signedIn() });

function renderPage(auth: AuthContextState) {
  return render(
    <AuthContext.Provider value={auth}>
      <MemoryRouter>
        <ChangePassword />
      </MemoryRouter>
    </AuthContext.Provider>,
  );
}

const field = (name: string) => screen.getByLabelText(name);
const submit = () => screen.getByRole("button", { name: "Change password" });

async function fillAndSubmit(confirm: string = NEXT) {
  const user = userEvent.setup();
  await user.type(field("Current password"), CURRENT);
  await user.type(field("New password"), NEXT);
  await user.type(field("Confirm new password"), confirm);
  await user.click(submit());
}

function expectFieldsCleared() {
  for (const name of ["Current password", "New password", "Confirm new password"]) {
    expect(field(name)).toHaveValue("");
  }
}

function expectNoValueEchoed(consoleSpies: ReturnType<typeof vi.spyOn>[]) {
  // The whole serialized DOM, attributes included, and every console channel.
  const markup = document.documentElement.outerHTML;
  expect(markup).toContain("Change your password");
  expect(markup).not.toContain(CURRENT);
  expect(markup).not.toContain(NEXT);
  for (const spy of consoleSpies) {
    for (const call of spy.mock.calls) {
      expect(JSON.stringify(call)).not.toContain(CURRENT);
      expect(JSON.stringify(call)).not.toContain(NEXT);
    }
  }
}

describe("ChangePassword", () => {
  let consoleSpies: ReturnType<typeof vi.spyOn>[];

  beforeEach(() => {
    consoleSpies = (["debug", "error", "info", "log", "warn"] as const).map((method) =>
      vi.spyOn(console, method),
    );
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("tells a flagged User the change is required", () => {
    renderPage(flaggedAuth());

    expect(screen.getByRole("heading", { name: "Change your password" })).toBeInTheDocument();
    expect(
      screen.getByText(/^Your password must be replaced before you can continue\. /),
    ).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("renders safely while the session's user details are unavailable", () => {
    renderPage({ ...unflaggedAuth(), ...CHECKING });

    // No flag to read means no confinement: the unflagged copy and a way back.
    expect(screen.getByText(/^Choose a new password for your account\. /)).toBeInTheDocument();
  });

  it("tells an unflagged User it is a voluntary change", () => {
    renderPage(unflaggedAuth());

    expect(screen.getByText(/^Choose a new password for your account\. /)).toBeInTheDocument();
  });

  it("keeps every field a masked password field with the matching autocomplete hint", () => {
    renderPage(flaggedAuth());

    expect(field("Current password")).toHaveAttribute("type", "password");
    expect(field("Current password")).toHaveAttribute("autocomplete", "current-password");
    expect(field("Current password")).toBeRequired();
    for (const name of ["New password", "Confirm new password"]) {
      expect(field(name)).toHaveAttribute("type", "password");
      expect(field(name)).toHaveAttribute("autocomplete", "new-password");
      expect(field(name)).toBeRequired();
    }
  });

  it("states the password requirements before submission and links them to the new-password field", () => {
    renderPage(flaggedAuth());

    const requirements = screen.getByRole("list");
    expect(requirements).toHaveAttribute("id", "newPasswordRequirements");
    expect(Array.from(requirements.querySelectorAll("li"), (item) => item.textContent)).toEqual([
      "12 to 256 characters long",
      "Must not contain your user name",
      "Must not reuse your current or recent passwords",
    ]);
    expect(field("New password")).toHaveAttribute("aria-describedby", "newPasswordRequirements");
    expect(field("New password")).toHaveAccessibleDescription(
      "12 to 256 characters long Must not contain your user name Must not reuse your current or recent passwords",
    );
    // Shown before anything is submitted: no refusal is needed to learn the rules.
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("bounds the new password and its confirmation by the backend's length policy", () => {
    renderPage(flaggedAuth());

    // Literal values, not the constant: PasswordPolicy.java is the authority,
    // and a drift in the SPA's mirror of it must fail here.
    expect(PASSWORD_LENGTH).toEqual({ min: 12, max: 256 });
    for (const name of ["New password", "Confirm new password"]) {
      expect(field(name)).toHaveAttribute("minlength", "12");
      expect(field(name)).toHaveAttribute("maxlength", "256");
    }
    // The current password is whatever it already is; no bound applies to it.
    expect(field("Current password")).not.toHaveAttribute("minlength");
    expect(field("Current password")).not.toHaveAttribute("maxlength");
  });

  it.each([
    ["a success", { kind: "changed" }],
    // The lockout ended the session too, so the login page says it, not this one.
    ["a lockout", { kind: "locked" }],
  ] as [string, PasswordChangeOutcome][])(
    "submits current and new password and shows nothing on %s",
    async (_name, outcome) => {
      const auth = flaggedAuth();
      vi.mocked(auth.changePassword).mockResolvedValue(outcome);
      renderPage(auth);

      await fillAndSubmit();

      expect(auth.changePassword).toHaveBeenCalledTimes(1);
      expect(auth.changePassword).toHaveBeenCalledWith(CURRENT, NEXT);
      expect(screen.queryByRole("alert")).not.toBeInTheDocument();
      // Only a refusal the User can correct clears the form; an ended session
      // has the route guard replace the page, so this one does nothing further.
      expect(field("Current password")).toHaveValue(CURRENT);
      expect(field("Current password")).toBeEnabled();
    },
  );

  it("refuses a mismatched confirmation without submitting, and clears the fields", async () => {
    const auth = flaggedAuth();
    renderPage(auth);

    await fillAndSubmit("Something-Else-123");

    expect(auth.changePassword).not.toHaveBeenCalled();
    expect(screen.getByRole("alert")).toHaveTextContent(
      /^The new password and its confirmation do not match\.$/,
    );
    expectFieldsCleared();
  });

  // The copy for every refusal is `use-session-action.test.tsx`'s and
  // `session-action-copy.test.ts`'s; these prove the page clears the fields
  // after one and that its line echoes neither value — the backend's own
  // statement of a rule included.
  const refusals: [string, PasswordChangeOutcome, RegExp][] = [
    [
      "a 400 by the rule the backend names",
      { kind: "policy-violation", message: "The new password must be at least 12 characters long" },
      /^The new password must be at least 12 characters long$/,
    ],
    [
      "a 401 as a wrong current password",
      { kind: "current-password-rejected" },
      /^The current password is incorrect\.$/,
    ],
  ];

  it.each(refusals)(
    "shows %s, clears the fields and echoes neither value",
    async (_name, outcome, copy) => {
      const auth = flaggedAuth();
      vi.mocked(auth.changePassword).mockResolvedValue(outcome);
      renderPage(auth);

      await fillAndSubmit();

      expect(await screen.findByRole("alert")).toHaveTextContent(copy);
      expectFieldsCleared();
      expectNoValueEchoed(consoleSpies);
      // A refusal the User can correct leaves the form usable.
      expect(field("Current password")).toBeEnabled();
      expect(submit()).toBeEnabled();
    },
  );

  it("clears an earlier refusal when the next submission starts", async () => {
    const auth = flaggedAuth();
    let finish: ((outcome: PasswordChangeOutcome) => void) | undefined;
    vi.mocked(auth.changePassword)
      .mockResolvedValueOnce({ kind: "current-password-rejected" })
      .mockReturnValueOnce(
        new Promise((resolve) => {
          finish = resolve;
        }),
      );
    renderPage(auth);

    await fillAndSubmit();
    expect(await screen.findByRole("alert")).toBeInTheDocument();

    await fillAndSubmit();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Changing password…" })).toBeDisabled();

    finish?.({ kind: "changed" });
    expect(await screen.findByRole("button", { name: "Change password" })).toBeEnabled();
  });

  it("says the confirmation does not match in place of an earlier refusal", async () => {
    const auth = flaggedAuth();
    vi.mocked(auth.changePassword).mockResolvedValue({ kind: "current-password-rejected" });
    renderPage(auth);
    await fillAndSubmit();
    await screen.findByRole("alert");

    await fillAndSubmit("Something-Else-123");

    expect(screen.getByRole("alert").textContent).toBe(
      "The new password and its confirmation do not match.",
    );
  });

  it("withdraws the mismatch line when a matching submission starts", async () => {
    const auth = flaggedAuth();
    vi.mocked(auth.changePassword).mockReturnValue(new Promise(() => undefined));
    renderPage(auth);
    await fillAndSubmit("Something-Else-123");
    // user-event keeps its own record of what it typed, which the page's
    // `form.reset()` does not touch; clearing through it keeps the next fill matched.
    const user = userEvent.setup();
    for (const name of ["Current password", "New password", "Confirm new password"]) {
      await user.clear(field(name));
    }

    await fillAndSubmit();

    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });
});
