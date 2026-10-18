import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";

import {
  bare,
  deferred,
  holding,
  json,
  STILL_CHECKING,
  type Answers,
  type StartingSession,
} from "@/auth/session-adapter.testHelpers";
import type { Permission } from "@/auth/api";

import { Home } from "./home";
import { renderInLayout, renderSignedIn } from "./signed-in-shell.testHelpers";

const count = () => screen.getByTestId("count");
const increment = () => screen.getByRole("button", { name: "Increment" });
const reset = () => screen.getByRole("button", { name: "Reset" });

/** The counter's `CountResponse`, as the backend answers every counter endpoint. */
const counted = (value: number) => json({ count: value });

const COUNTER_USER = holding(["counter:read", "counter:write"]);

/**
 * The page as `session` sees it, inside the signed-in layout that renders its
 * one error line, the count reading zero unless `answers` says otherwise.
 */
const renderHome = (answers: Answers = {}, session: StartingSession = COUNTER_USER) =>
  renderSignedIn("/", session, { "GET /api/count": counted(0), ...answers });

/** The page for a session holding exactly `permissions`, its links all a test reads. */
const renderHolding = (permissions: Permission[]) => renderHome({}, holding(permissions));

describe("Home", () => {
  it("links a page it is handed only for a session that may open it, with no edit of its own", async () => {
    const extra = {
      homeLink: "View reports",
      path: "/reports",
      permissions: ["ops:read"] as const,
    };
    const routes = [{ element: <Home pages={[extra]} />, path: "/" }];

    const { unmount } = await renderInLayout(routes, "/", holding(["ops:read"]), {});
    expect(screen.getByRole("link", { name: "View reports" })).toHaveAttribute("href", "/reports");
    unmount();

    await renderInLayout(routes, "/", holding(["audit:read"]), {});
    expect(screen.queryByRole("link", { name: "View reports" })).not.toBeInTheDocument();
  });

  it("links no page it is handed without a link label", async () => {
    const unlabelled = { path: "/reports" };
    await renderInLayout([{ element: <Home pages={[unlabelled]} />, path: "/" }], "/", holding([]));

    expect(screen.queryAllByRole("link")).toEqual([]);
  });

  // Each page Home links to, with the Permissions its route guard opens it for
  // (none: every signed-in session), as the registry declares them today.
  it.each([
    ["Manage accounts", "/accounts", ["user:read", "group:read", "connector:read"]],
    ["View audit log", "/audit", ["audit:read"]],
    ["Change password", "/change-password", []],
  ] as const)(
    "links %s exactly for a session its route guard would open it to",
    async (label, path, qualifying) => {
      /** Where the link labelled `label` leads for a session holding `held`, or null if absent. */
      const linkFor = async (held: readonly Permission[]) => {
        const { unmount } = await renderHolding([...held]);
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

  it("lists the pages it links to in registry order, and not itself", async () => {
    await renderHolding(["user:read", "group:read", "connector:read", "audit:read"]);

    expect(screen.getAllByRole("link").map((link) => link.textContent)).toEqual([
      "Manage accounts",
      "View audit log",
      "Change password",
    ]);
  });

  it("styles a page link as an underlined text link", async () => {
    await renderHolding([]);

    expect(screen.getByRole("link", { name: "Change password" })).toHaveClass(
      "text-sm",
      "font-medium",
      "underline",
    );
  });

  it("shows its note about the counter as muted text", async () => {
    await renderHolding([]);

    expect(
      screen.getByText("You are signed in. Your account has no access to the counter."),
    ).toHaveClass("text-sm", "text-muted-foreground");
  });

  it("shows a baseline User no counter, asks for none, and keeps self-service", async () => {
    const { requests } = await renderHolding([]);

    expect(
      screen.getByText("You are signed in. Your account has no access to the counter."),
    ).toBeInTheDocument();
    expect(screen.queryByTestId("count")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Increment" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Reset" })).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Change password" })).toBeInTheDocument();
    expect(requests()).toEqual([]);
  });

  it("shows counter:read the count without the controls that change it", async () => {
    await renderHome({ "GET /api/count": counted(4) }, holding(["counter:read"]));

    expect(await screen.findByText("Clicked 4 times")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Increment" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Reset" })).not.toBeInTheDocument();
  });

  // Without counter:read the count is never read, so nothing offers to change it.
  it("offers counter:write alone no change to a count it cannot read", async () => {
    await renderHolding(["counter:write"]);
    await screen.findByText("You are signed in. Your account has no access to the counter.");

    expect(screen.queryByRole("button", { name: "Increment" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Reset" })).not.toBeInTheDocument();
  });

  it("renders the original home page and signed-in user", async () => {
    await renderHome();
    expect(await screen.findByRole("button", { name: "Increment" })).toBeEnabled();
  });

  it("renders safely while the session's user details are unavailable", async () => {
    const { requests } = await renderHome({}, STILL_CHECKING);
    // No session details, so no Permission: nothing is offered and nothing is asked for.
    expect(screen.queryByRole("button", { name: "Increment" })).not.toBeInTheDocument();
    expect(requests()).toEqual([]);
  });

  it("loads the current count when Home opens", async () => {
    const { requests } = await renderHome({ "GET /api/count": counted(3) });

    expect(await screen.findByText("Clicked 3 times")).toBeInTheDocument();
    expect(requests()).toEqual([{ method: "GET", path: "/api/count" }]);
  });

  it("disables counter actions while the initial count is loading", async () => {
    const loading = deferred();
    await renderHome({ "GET /api/count": loading.answer });

    expect(increment()).toBeDisabled();
    expect(reset()).toBeDisabled();

    await loading.settle(counted(2));
    expect(await screen.findByText("Clicked 2 times")).toBeInTheDocument();
    expect(increment()).toBeEnabled();
    expect(reset()).toBeEnabled();
  });

  it("shows no count while the initial count is loading", async () => {
    await renderHome({ "GET /api/count": deferred().answer });

    expect(screen.getByText("Loading the counter…")).toBeInTheDocument();
    expect(screen.queryByTestId("count")).not.toBeInTheDocument();
  });

  it("reports a failed initial counter read", async () => {
    await renderHome({ "GET /api/count": bare(503) });

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Unable to load the counter. Please try again.",
    );
  });

  // A body that drifted from `CountResponse` fails the read like a 5xx, and
  // shows no count: the page renders nothing it could not decode.
  it("reports a count body that does not decode, and shows no count", async () => {
    await renderHome({ "GET /api/count": json({ count: "3" }) });

    expect(await screen.findByRole("alert")).toHaveTextContent(
      /^Unable to load the counter\. Please try again\.$/,
    );
    expect(screen.queryByTestId("count")).not.toBeInTheDocument();
  });

  // The count was never read, so no count is shown and nothing offers to change it.
  it("shows no count and offers no change after a failed initial counter read", async () => {
    await renderHome({ "GET /api/count": bare(503) });
    await screen.findByRole("alert");

    expect(screen.queryByTestId("count")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Increment" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Reset" })).not.toBeInTheDocument();
  });

  it("reports a refused counter update as permission denied and keeps the session", async () => {
    const { requests, session } = await renderHome({
      "GET /api/count": counted(2),
      "POST /api/count/increment": bare(403),
    });
    const user = userEvent.setup();

    await user.click(await screen.findByRole("button", { name: "Increment" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      /^You don't have permission to do this\.$/,
    );
    // The count from the last successful read stands, and nothing ends the session.
    expect(count()).toHaveTextContent("Clicked 2 times");
    expect(session().status).toBe("authenticated");
    expect(requests()).not.toContainEqual(expect.objectContaining({ path: "/api/auth/logout" }));
  });

  it("expires the auth state when the initial request is unauthenticated", async () => {
    const loading = deferred();
    const { session } = await renderHome({ "GET /api/count": loading.answer });

    await loading.settle(bare(401));

    // The page never sees this case: the session seam ends the session, and the
    // route guard replaces this page with the login route on the same update.
    // Meanwhile the count was never read, so none is shown.
    expect(session()).toEqual({ signInReason: "expired", status: "guest" });
    expect(screen.queryByTestId("count")).not.toBeInTheDocument();
  });

  it("counts each click", async () => {
    const { requests } = await renderHome({
      "POST /api/count/increment": [counted(1), counted(2)],
    });
    const user = userEvent.setup();

    await user.click(increment());
    await user.click(increment());

    expect(count()).toHaveTextContent(/^Clicked 2 times$/);
    expect(requests().slice(-1)).toEqual([{ method: "POST", path: "/api/count/increment" }]);
  });

  it("uses the singular label at exactly one", async () => {
    await renderHome({ "POST /api/count/increment": counted(1) });
    const user = userEvent.setup();

    await user.click(increment());
    expect(count()).toHaveTextContent(/^Clicked 1 time$/);
  });

  it("disables Reset until there is something to reset", async () => {
    await renderHome({ "POST /api/count/increment": counted(1) });
    const user = userEvent.setup();

    expect(reset()).toBeDisabled();
    await user.click(increment());
    expect(reset()).toBeEnabled();
  });

  it("disables counter actions while an update is pending", async () => {
    const incrementing = deferred();
    await renderHome({ "POST /api/count/increment": incrementing.answer });
    const user = userEvent.setup();

    await user.click(increment());
    expect(increment()).toBeDisabled();
    expect(reset()).toBeDisabled();

    await incrementing.settle(counted(1));
    expect(await screen.findByText("Clicked 1 time")).toBeInTheDocument();
    expect(increment()).toBeEnabled();
    expect(reset()).toBeEnabled();
  });

  it("returns the count to zero on reset", async () => {
    const { requests } = await renderHome({
      "POST /api/count/increment": counted(1),
      "POST /api/count/reset": counted(0),
    });
    const user = userEvent.setup();

    await user.click(increment());
    await user.click(reset());

    expect(count()).toHaveTextContent(/^Clicked 0 times$/);
    expect(reset()).toBeDisabled();
    expect(requests().slice(-1)).toEqual([{ method: "POST", path: "/api/count/reset" }]);
  });

  it("keeps the count and reports backend failures", async () => {
    await renderHome({ "POST /api/count/increment": bare(503) });
    const user = userEvent.setup();

    await user.click(increment());

    expect(count()).toHaveTextContent(/^Clicked 0 times$/);
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Unable to update the counter. Please try again.",
    );
  });

  // The page names no status of its own, only a `default`, so this pins that
  // default over the hook's generic 400/404/409 copy for each.
  it.each([400, 404, 409])(
    "reports a %i refusal with the page's own default copy, not the hook's generic one",
    async (status) => {
      await renderHome({ "POST /api/count/increment": bare(status) });
      const user = userEvent.setup();

      await user.click(increment());

      expect(await screen.findByRole("alert")).toHaveTextContent(
        "Unable to update the counter. Please try again.",
      );
    },
  );

  it("expires the auth state when an update is unauthenticated", async () => {
    const { session } = await renderHome({
      "POST /api/count/increment": bare(401),
    });
    const user = userEvent.setup();

    await user.click(increment());

    // The session ends with it, so the route guard replaces the page; nothing
    // here shows a count for a session that no longer stands.
    expect(session()).toEqual({ signInReason: "expired", status: "guest" });
  });
});
