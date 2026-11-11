import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Route, Routes } from "react-router-dom";
import { describe, expect, it } from "vitest";

import type { Permission } from "@/auth/api";
import { EVERY_PERMISSION } from "@/auth/auth-context.testHelpers";
import {
  bare,
  deferred,
  holding,
  json,
  renderAsSession,
  STILL_CHECKING,
  type Answers,
  type StartingSession,
} from "@/auth/session-adapter.testHelpers";

import { SignedInShell } from "./signed-in-shell";
import { renderSignedIn } from "./signed-in-shell.testHelpers";

const LOGOUT = "DELETE /api/auth/logout";

const SIGNED_IN = holding([]);

/** The shell at `path` as `session` sees it, a Sign out answered as `answers` says. */
function renderShell(path = "/", answers: Answers = {}, session: StartingSession = SIGNED_IN) {
  return renderAsSession(
    <Routes>
      <Route element={<SignedInShell />}>
        <Route element={<p>page body</p>} path="/" />
        <Route element={<p>page body</p>} path="/accounts" />
        <Route element={<p>page body</p>} path="/audit" />
        <Route element={<p>page body</p>} path="/change-password" />
        <Route element={<p>page body</p>} path="/settings" />
        <Route element={<p>page body</p>} path="*" />
      </Route>
    </Routes>,
    session,
    { answers, at: path },
  );
}

const STILL_SIGNED_IN = /^Unable to sign out\. Please try again\. You are still signed in\.$/;

const FULL_WIDTH = "flex flex-1 flex-col gap-6 p-8";

/** The Tailwind max-width classes on `element`; none means it fills the content shell. */
const widthCaps = (element: HTMLElement) =>
  [...element.classList].filter((name) => name.startsWith("max-w-"));

const signOut = () => screen.getByRole("button", { name: "Sign out" });

describe("SignedInShell", () => {
  it("shows the signed-in identity, the page title and the page", async () => {
    await renderShell("/accounts");
    expect(screen.getByText("Signed in as ada")).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 1, name: "Accounts" })).toBeInTheDocument();
    expect(screen.getByText("page body")).toBeInTheDocument();
  });

  // The sidebar, or the top bar for Settings, is a link away from every other
  // page, so none of these needs one.
  it.each(["/", "/accounts", "/audit", "/settings"])("offers no back link on %s", async (path) => {
    await renderShell(path);
    expect(screen.queryByRole("link", { name: /^Back/ })).not.toBeInTheDocument();
  });

  it("offers no back link to a session confined to the password change", async () => {
    await renderShell("/change-password", {}, holding([], { passwordChangeRequired: true }));
    expect(screen.queryByRole("link", { name: /^Back/ })).not.toBeInTheDocument();
  });

  it("offers a back link on the password change once unconfined", async () => {
    await renderShell("/change-password");
    expect(screen.getByRole("link", { name: "Back" })).toHaveAttribute("href", "/");
  });

  it("signs out through the auth context", async () => {
    const { requests, session } = await renderShell("/", {
      [LOGOUT]: bare(204),
    });
    await userEvent.setup().click(signOut());
    expect(requests()).toEqual([{ method: "DELETE", path: "/api/auth/logout" }]);
    expect(session()).toEqual({ signInReason: null, status: "guest" });
  });

  // Running the Sign out — pending, which outcome signed out, its copy, the line
  // withdrawn on the next try — is `use-session-action.test.tsx`'s; these prove
  // the shell renders what it hands back.
  it("disables Sign out while the logout is in flight", async () => {
    await renderShell("/", { [LOGOUT]: deferred().answer });
    await userEvent.setup().click(signOut());
    expect(screen.getByRole("button", { name: "Signing out…" })).toBeDisabled();
  });

  it("says a failed Sign out left the user signed in", async () => {
    await renderShell("/", { [LOGOUT]: bare(500) });
    await userEvent.setup().click(signOut());
    expect(await screen.findByRole("alert")).toHaveTextContent(STILL_SIGNED_IN);
    expect(screen.getByRole("button", { name: "Sign out" })).toBeEnabled();
  });

  it("titles each signed-in page", async () => {
    await renderShell("/");
    expect(screen.getByRole("heading", { level: 1, name: "Profile" })).toBeInTheDocument();
  });

  it("keeps Profile's title for screen readers only, the patient banner heading the page", async () => {
    await renderShell("/");
    expect(screen.getByRole("heading", { level: 1, name: "Profile" })).toHaveClass("sr-only");
  });

  it.each([
    ["Accounts", "/accounts"],
    ["Audit", "/audit"],
    ["Settings", "/settings"],
  ])("keeps the %s page's title for screen readers only", async (title, path) => {
    await renderShell(path);
    expect(screen.getByRole("heading", { level: 1, name: title })).toHaveClass("sr-only");
  });

  it("shows the password change's title, where a confined session has no sidebar to go by", async () => {
    await renderShell("/change-password");
    expect(screen.getByRole("heading", { level: 1, name: "Change password" })).not.toHaveClass(
      "sr-only",
    );
  });

  it("titles the password change", async () => {
    await renderShell("/change-password");
    expect(screen.getByRole("heading", { level: 1, name: "Change password" })).toBeInTheDocument();
  });

  it("renders safely while the session's user details are unavailable", async () => {
    await renderShell("/", {}, STILL_CHECKING);
    expect(screen.getByText(/^Signed in as\s*$/)).toBeInTheDocument();
  });

  it.each([
    ["Profile", "/"],
    ["Accounts", "/accounts"],
    ["Audit", "/audit"],
    ["Settings", "/settings"],
    ["Change password", "/change-password"],
  ])("lays the %s page out across the content shell's full width", async (_, path) => {
    await renderShell(path);
    expect(screen.getByRole("main")).toHaveClass(...FULL_WIDTH.split(" "));
    expect(widthCaps(screen.getByRole("main"))).toEqual([]);
  });

  it.each([
    ["Profile", "/"],
    ["Accounts", "/accounts"],
    ["Audit", "/audit"],
    ["Settings", "/settings"],
  ])("top-aligns the %s page", async (_, path) => {
    await renderShell(path);
    expect(screen.getByRole("main")).not.toHaveClass("items-center");
    expect(screen.getByRole("main")).not.toHaveClass("justify-center");
  });

  it("centers the password change vertically", async () => {
    await renderShell("/change-password");
    expect(screen.getByRole("main")).toHaveClass("items-center", "justify-center");
  });

  it("renders the Home layout for a path it has no page for", async () => {
    await renderShell("/nowhere");
    expect(screen.getByRole("heading", { level: 1, name: "Profile" })).toBeInTheDocument();
  });

  it("renders a page's own layout for its trailing-slash path", async () => {
    await renderShell("/accounts/");
    expect(screen.getByRole("heading", { level: 1, name: "Accounts" })).toBeInTheDocument();
  });
});

describe("SignedInShell's navigation sidebar", () => {
  // Each page the sidebar links, with the Permissions its route guard opens it
  // for (none: every signed-in session), as the registry declares them today.
  it.each([
    ["Profile", "/", []],
    ["Accounts", "/accounts", ["user:read", "group:read", "connector:read"]],
    ["Audit logs", "/audit", ["audit:read"]],
  ] as const)(
    "links %s exactly for a session its route guard would open it to",
    async (label, path, qualifying) => {
      /** Where the sidebar's link labelled `label` leads for a session holding `held`, or null if absent. */
      const linkFor = async (held: readonly Permission[]) => {
        const { unmount } = await renderSignedIn("/", holding([...held]));
        const href = screen.queryByRole("link", { name: label })?.getAttribute("href") ?? null;
        unmount();
        return href;
      };
      const forEverySession = qualifying.length === 0 ? path : null;

      // A Monitoring account's Permission opens none of the guarded pages.
      expect(await linkFor([])).toBe(forEverySession);
      expect(await linkFor(["ops:read"])).toBe(forEverySession);
      for (const permission of qualifying) expect(await linkFor([permission])).toBe(path);
      expect(await linkFor([...qualifying])).toBe(path);
    },
  );

  it.each([
    ["/", "Profile"],
    ["/audit", "Audit logs"],
  ])("on %s highlights %s's link, and no other", async (path, current) => {
    await renderShell(path, {}, holding(EVERY_PERMISSION));

    const links = ["Profile", "Accounts", "Audit logs"];
    expect(
      links.map((name) => screen.getByRole("link", { name }).getAttribute("data-active")),
    ).toEqual(links.map((name) => String(name === current)));
  });

  describe("its Admin group", () => {
    const admin = () => screen.queryByRole("button", { name: "Admin" });
    /** The links beneath the Admin button, by name. */
    const adminLinks = () =>
      within(admin()!.closest("li")!)
        .queryAllByRole("link")
        .map((link) => link.textContent);

    it("holds Accounts and Audit logs, open from the start", async () => {
      await renderSignedIn("/", holding(EVERY_PERMISSION));

      expect(admin()!.getAttribute("aria-expanded")).toBe("true");
      expect(adminLinks()).toEqual(["Accounts", "Audit logs"]);
    });

    it("folds its links away and back from its button", async () => {
      await renderSignedIn("/", holding(EVERY_PERMISSION));
      const user = userEvent.setup();

      await user.click(admin()!);
      expect([admin()!.getAttribute("aria-expanded"), adminLinks()]).toEqual(["false", []]);

      await user.click(admin()!);
      expect(adminLinks()).toEqual(["Accounts", "Audit logs"]);
    });

    it("holds only the pages the session may open", async () => {
      await renderSignedIn("/", holding(["audit:read"]));

      expect(adminLinks()).toEqual(["Audit logs"]);
    });

    it("shows for no session that may open none of its pages", async () => {
      await renderSignedIn("/", holding([]));

      expect(admin()).toBeNull();
    });

    it("opens a sidebar folded to its icons out to its links", async () => {
      await renderSignedIn("/", holding(EVERY_PERMISSION));
      const user = userEvent.setup();
      await user.click(admin()!);
      await user.click(screen.getByRole("button", { name: "Collapse sidebar" }));

      await user.click(admin()!);

      expect(screen.getByRole("button", { name: "Collapse sidebar" })).toBeInTheDocument();
      expect(adminLinks()).toEqual(["Accounts", "Audit logs"]);
    });
  });

  it("links no page of its own for the password change, which Settings holds", async () => {
    await renderSignedIn("/settings", holding(EVERY_PERMISSION));

    expect([
      screen.queryByRole("link", { name: "Change password" }),
      screen.getByRole("button", { name: "Change password" }) !== null,
    ]).toEqual([null, true]);
  });
});

describe("SignedInShell's sidebar collapse", () => {
  /** The element carrying the sidebar's expanded or collapsed state. */
  const sidebarState = () =>
    document.querySelector('[data-slot="sidebar"]')?.getAttribute("data-state");

  it("starts open, with a control to collapse it", async () => {
    await renderShell("/");

    expect([
      screen.getByRole("button", { name: "Collapse sidebar" }).getAttribute("aria-expanded"),
      sidebarState(),
    ]).toEqual(["true", "expanded"]);
  });

  it("folds to its icons and opens out again from the same control", async () => {
    await renderShell("/");
    const user = userEvent.setup();

    await user.click(screen.getByRole("button", { name: "Collapse sidebar" }));
    const expand = screen.getByRole("button", { name: "Expand sidebar" });
    expect([expand.getAttribute("aria-expanded"), sidebarState()]).toEqual(["false", "collapsed"]);

    await user.click(expand);
    expect([
      screen.getByRole("button", { name: "Collapse sidebar" }).getAttribute("aria-expanded"),
      sidebarState(),
    ]).toEqual(["true", "expanded"]);
  });

  it("opens as a sheet from the top bar on a phone, and closes on following a link", async () => {
    const width = window.innerWidth;
    window.innerWidth = 400;
    try {
      await renderSignedIn("/settings", holding(EVERY_PERMISSION));
      const user = userEvent.setup();

      await user.click(screen.getByRole("button", { name: "Open navigation" }));
      const sheet = await screen.findByRole("dialog");
      await user.click(within(sheet).getByRole("link", { name: "Profile" }));

      expect([
        screen.queryByRole("dialog"),
        await screen.findByRole("heading", { level: 1, name: "Profile" }),
      ]).toEqual([null, expect.anything()]);
    } finally {
      window.innerWidth = width;
    }
  });

  it("keeps every link's and Sign out's name while folded", async () => {
    await renderSignedIn("/", holding(EVERY_PERMISSION));

    await userEvent.setup().click(screen.getByRole("button", { name: "Collapse sidebar" }));

    expect([
      screen.getByRole("link", { name: "Profile" }) !== null,
      screen.getByRole("button", { name: "Sign out" }) !== null,
    ]).toEqual([true, true]);
  });
});

/**
 * The page's one error line belongs to the layout: the page, its panels and
 * Sign out all report to it, so however many of them fail there is one line,
 * showing the latest failure — and it starts empty on every page.
 */
describe("SignedInShell's error line", () => {
  const USERS = "GET /api/admin/accounts?startIndex=1&count=20";
  const CONNECTORS = "GET /api/admin/connectors";
  const GROUPS = "GET /api/admin/groups?startIndex=1&count=20";
  const CHANGE = "POST /api/auth/change-password";
  const MISMATCH = "The new password and its confirmation do not match.";
  const SIGN_OUT_FAILED = "Unable to sign out. Please try again. You are still signed in.";

  const lines = () => screen.queryAllByRole("alert").map((line) => line.textContent);

  /** Fills the password change, its confirmation `confirm`, and submits it. */
  async function submitChange(confirm: string) {
    const user = userEvent.setup();
    await user.type(screen.getByLabelText("Current password"), "Current-Value-4f81");
    await user.type(screen.getByLabelText("New password"), "Next-Value-9c2e-long");
    await user.type(screen.getByLabelText("Confirm new password"), confirm);
    await user.click(screen.getByRole("button", { name: "Change password" }));
  }

  it("shows one line on Accounts, the latest, when the Users, the Connectors and Sign out all fail", async () => {
    await renderSignedIn("/accounts", holding(EVERY_PERMISSION), {
      [CONNECTORS]: bare(503),
      [GROUPS]: json({ groups: [], startIndex: 1, totalResults: 0 }),
      [LOGOUT]: bare(500),
      [USERS]: bare(503),
    });
    await screen.findByRole("alert");

    await userEvent.setup().click(signOut());

    expect(lines()).toEqual([SIGN_OUT_FAILED]);
  });

  it("shows a page's failure on the one line in place of an earlier failed Sign out", async () => {
    const users = deferred();
    await renderSignedIn("/accounts", holding(["user:read"]), {
      [LOGOUT]: bare(500),
      [USERS]: users.answer,
    });
    await userEvent.setup().click(signOut());
    await screen.findByRole("alert");

    await users.settle(bare(503));

    expect(lines()).toEqual(["Unable to load the users. Please try again."]);
  });

  it("starts the next page with no line after a failed Sign out", async () => {
    await renderSignedIn("/", SIGNED_IN, { [LOGOUT]: bare(500) });
    const user = userEvent.setup();
    await user.click(signOut());
    await screen.findByRole("alert");

    await user.click(screen.getByRole("link", { name: "Open settings" }));

    expect(lines()).toEqual([]);
  });

  it("starts the next page with no line after the page's own failure", async () => {
    await renderSignedIn("/accounts", holding(["user:read"]), { [USERS]: bare(503) });
    await screen.findByRole("alert");

    await userEvent.setup().click(screen.getByRole("link", { name: "Profile" }));

    expect(lines()).toEqual([]);
  });

  it("keeps a write the user left behind off the next page's line", async () => {
    const unlock = deferred();
    await renderSignedIn("/accounts", holding(["user:read", "account:unlock"]), {
      "POST /api/admin/accounts/00000000-0000-4000-8000-000000000001/unlock": unlock.answer,
      [USERS]: json({
        startIndex: 1,
        totalResults: 1,
        users: [
          {
            active: true,
            admin: false,
            bootstrapAdmin: false,
            createdAt: "2026-01-02T19:04:05Z",
            displayName: null,
            groups: [],
            hasPassword: true,
            id: "00000000-0000-4000-8000-000000000001",
            lastAuthenticatedAt: null,
            lockCause: "FAILURES",
            locked: true,
            passwordChangeRequired: false,
            userName: "grace",
          },
        ],
      }),
    });
    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "Unlock grace" }));
    await user.click(screen.getByRole("link", { name: "Profile" }));

    await unlock.settle(bare(500));

    expect(lines()).toEqual([]);
  });

  it("shows the password change's mismatch on the one line, in place of a failed Sign out", async () => {
    await renderSignedIn("/change-password", SIGNED_IN, { [LOGOUT]: bare(500) });
    await userEvent.setup().click(signOut());
    await screen.findByRole("alert");

    await submitChange("Something-Else-123");

    expect(lines()).toEqual([MISMATCH]);
  });

  it("shows a refused password change on the one line, in place of a failed Sign out", async () => {
    await renderSignedIn("/change-password", SIGNED_IN, {
      [CHANGE]: json({ message: "The new password must be at least 12 characters long" }, 400),
      [LOGOUT]: bare(500),
    });
    await userEvent.setup().click(signOut());
    await screen.findByRole("alert");

    await submitChange("Next-Value-9c2e-long");

    expect(lines()).toEqual(["The new password must be at least 12 characters long"]);
  });

  it("shows a failed Sign out on the one line, in place of the password change's mismatch", async () => {
    await renderSignedIn("/change-password", SIGNED_IN, { [LOGOUT]: bare(500) });
    await submitChange("Something-Else-123");

    await userEvent.setup().click(signOut());

    expect(lines()).toEqual([SIGN_OUT_FAILED]);
  });
});
