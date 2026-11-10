import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";

import { EVERY_PERMISSION } from "@/auth/auth-context.testHelpers";
import {
  bare,
  deferred,
  holding,
  json,
  UNREACHABLE,
  type Answers,
  type WireAnswer,
} from "@/auth/session-adapter.testHelpers";
import type { Permission } from "@/auth/api";

import type { Connector, ConnectorToken, IssuedToken } from "./accounts-api";
import { Connectors } from "./connectors";
import { renderInLayout } from "@/shell/signed-in-shell.testHelpers";

const CONNECTOR_ID = "c0000000-0000-4000-8000-000000000001";
const TOKEN_ID = "a1b2c3d4-0000-4000-8000-000000000001";

const token = (overrides: Partial<ConnectorToken> = {}): ConnectorToken => ({
  active: true,
  expiresAt: "2027-01-01T20:00:00Z",
  id: TOKEN_ID,
  issuedAt: "2026-01-01T16:00:00Z",
  originalExpiresAt: "2027-01-01T20:00:00Z",
  permissions: ["group:read", "group:write", "user:read", "user:write"],
  rotated: false,
  ...overrides,
});

const connector = (overrides: Partial<Connector> = {}): Connector => ({
  createdAt: "2025-12-31T23:30:00Z",
  displayName: "Okta",
  id: CONNECTOR_ID,
  ...overrides,
});

const issued = (overrides: Partial<IssuedToken> = {}): IssuedToken => ({
  connectorId: CONNECTOR_ID,
  expiresAt: "2027-01-01T20:00:00Z",
  issuedAt: "2026-01-01T16:00:00Z",
  permissions: ["group:read", "group:write", "user:read", "user:write"],
  presentedValue: "scim_plaintext_value_shown_once",
  tokenId: TOKEN_ID,
  ...overrides,
});

/** A create's `409` when a live connector already holds the name. */
const NAME_TAKEN = {
  code: "CONNECTOR_NAME_TAKEN",
  detail: "A connector with this name already exists.",
  status: 409,
};

const LISTING = "GET /api/admin/connectors";
const connectorAt = `/api/admin/connectors/${CONNECTOR_ID}`;
const tokenAt = `${connectorAt}/tokens/${TOKEN_ID}`;

/** Okta's token read from the one-based `startIndex`, five at a time. */
const tokensFrom = (startIndex: number) =>
  `GET ${connectorAt}/tokens?startIndex=${startIndex}&count=5`;
const TOKENS = tokensFrom(1);

/** A page of Okta's active tokens, counting `totalResults` of them in all. */
const tokenPage = (
  tokens: ConnectorToken[],
  startIndex = 1,
  totalResults = startIndex - 1 + tokens.length,
): WireAnswer => json({ startIndex, tokens, totalResults });

/** Tokens numbered from `first`, each with an id of its own: `t0000001`, `t0000002`… */
const numberedTokens = (first: number, count: number): ConnectorToken[] =>
  Array.from({ length: count }, (_, index) =>
    token({ id: `t${String(first + index).padStart(7, "0")}-0000-4000-8000-000000000000` }),
  );

/** Okta alone, holding `tokens` on its first page. */
const okta = (tokens: ConnectorToken[] = []): Answers => ({
  [LISTING]: json([connector()]),
  [TOKENS]: tokenPage(tokens),
});

/**
 * The panel, for a session holding `permissions` and, by default, the Superuser —
 * alone on the Accounts path inside the real signed-in layout, which owns the page's
 * one error line. The panel has no line of its own, so its failures show on
 * the layout's. The Accounts suite renders the panel on the real page.
 */
const renderConnectors = (
  answers: Answers,
  permissions: readonly Permission[] = EVERY_PERMISSION,
  superuser = true,
) =>
  renderInLayout(
    [{ element: <Connectors />, path: "/accounts" }],
    "/accounts",
    holding(permissions, { superuser }),
    answers,
  );

const section = (name: string) => within(screen.getByRole("region", { name: `Connector ${name}` }));

/** The row a token's short id heads, once its page has been read. */
const tokenRow = async (shortId: string) =>
  within((await (await oktaSection()).findByRole("rowheader", { name: shortId })).closest("tr")!);

/** Okta's section, once the listing that renders it has answered. */
async function oktaSection() {
  return within(await screen.findByRole("region", { name: "Connector Okta" }));
}

/** Asks to delete Okta and confirms it in the dialog — the one path that sends the request. */
const deleteOkta = async (user: ReturnType<typeof userEvent.setup>) => {
  await user.click(await screen.findByRole("button", { name: "Delete Okta" }));
  const confirmation = await screen.findByRole("alertdialog", { name: "Delete Okta?" });
  await user.click(within(confirmation).getByRole("button", { name: "Delete" }));
};

describe("Connectors by Superuser", () => {
  const oneActiveToken = okta([token()]);

  it("offers no connector or token action to a User holding every Permission but not the Superuser", async () => {
    await renderConnectors(oneActiveToken, EVERY_PERMISSION, false);

    await tokenRow("a1b2c3d4");
    expect(screen.queryByLabelText("New connector name")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Create connector" })).not.toBeInTheDocument();
    const oktaSection = section("Okta");
    expect(oktaSection.queryByRole("button", { name: /^Delete/ })).not.toBeInTheDocument();
    expect(oktaSection.queryByRole("button", { name: /^Issue token/ })).not.toBeInTheDocument();
    expect(oktaSection.queryByRole("button", { name: /^Rotate token/ })).not.toBeInTheDocument();
    expect(oktaSection.queryByRole("button", { name: /^Revoke token/ })).not.toBeInTheDocument();
  });

  it("still lists every connector and its active tokens to a connector:read holder who is not the Superuser", async () => {
    await renderConnectors(oneActiveToken, ["connector:read"], false);

    await tokenRow("a1b2c3d4");
    expect(screen.getByRole("heading", { name: "Okta" })).toBeInTheDocument();
    // The page offers no control: its one token fits one page, so there is no
    // pager either; the layout's own controls sit outside it.
    expect(within(screen.getByRole("main")).queryAllByRole("button")).toEqual([]);
  });

  it("offers every connector and token action to the Superuser", async () => {
    await renderConnectors(oneActiveToken, ["connector:read"], true);

    await tokenRow("a1b2c3d4");
    expect(screen.getByRole("button", { name: "Create connector" })).toBeEnabled();
    const oktaSection = section("Okta");
    const remove = oktaSection.getByRole("button", { name: "Delete Okta" });
    expect(remove).toBeEnabled();
    expect(remove).toHaveTextContent(/^Delete$/);
    expect(oktaSection.getByRole("button", { name: /^Rotate token/ })).toBeEnabled();
    expect(oktaSection.getByRole("button", { name: /^Revoke token/ })).toBeEnabled();
    expect(oktaSection.getByRole("button", { name: "Issue token" })).toBeDisabled();
  });

  it("requests no listing and renders no panel at all without connector:read", async () => {
    const { requests } = await renderConnectors(oneActiveToken, []);

    expect(requests()).toEqual([]);
    // Not its heading, its loading note, its listing or its empty note.
    expect(screen.queryByText(/connector/i)).not.toBeInTheDocument();
  });
});

describe("Connectors", () => {
  it("lists every active token with its Permissions, dates and status, a rotated one marked so", async () => {
    await renderConnectors(
      okta([
        token(),
        token({
          expiresAt: "2026-01-08T16:00:00Z",
          id: "r0000000-x",
          permissions: [],
          rotated: true,
        }),
      ]),
    );

    const active = await tokenRow("a1b2c3d4");
    const oktaSection = section("Okta");
    expect(oktaSection.getAllByRole("columnheader").map((header) => header.textContent)).toEqual([
      "Token",
      "Permissions",
      "Issued",
      "Expires",
      "Status",
      "Actions",
    ]);
    expect(oktaSection.getByText("Created 2026-01-01")).toBeInTheDocument();
    expect(active.getByText("group:read, group:write, user:read, user:write")).toBeInTheDocument();
    expect(active.getByText("2026-01-02")).toBeInTheDocument();
    expect(active.getByText("2027-01-02")).toBeInTheDocument();
    expect(active.getByText("Active")).toBeInTheDocument();
    const rotated = await tokenRow("r0000000");
    expect(rotated.getByText("No permissions")).toBeInTheDocument();
    expect(rotated.getByText("2026-01-09")).toBeInTheDocument();
    expect(rotated.getByText("Rotated")).toBeInTheDocument();
    // A rotated token still authenticates, so it may be revoked; it is never rotated twice.
    expect(rotated.getByRole("button", { name: "Revoke token r0000000" })).toBeEnabled();
    expect(rotated.queryByRole("button", { name: /^Rotate token/ })).not.toBeInTheDocument();
    expect(active.getByRole("button", { name: "Rotate token a1b2c3d4" })).toBeEnabled();
    // No token value anywhere in the listing: the type has no field for one.
    expect(screen.queryByLabelText("New token value")).not.toBeInTheDocument();
  });

  it("reads each connector's own first page of five tokens", async () => {
    const { requests } = await renderConnectors(okta([token()]));

    await tokenRow("a1b2c3d4");
    expect(requests()).toEqual([
      { method: "GET", path: "/api/admin/connectors" },
      { method: "GET", path: `${connectorAt}/tokens?startIndex=1&count=5` },
    ]);
  });

  it("pages a connector's tokens five at a time, with the shared pager", async () => {
    await renderConnectors({
      ...okta(),
      [TOKENS]: tokenPage(numberedTokens(1, 5), 1, 7),
      [tokensFrom(6)]: tokenPage(numberedTokens(6, 2), 6, 7),
    });
    const user = userEvent.setup();

    await tokenRow("t0000001");
    const pager = section("Okta").getByRole("navigation", { name: "Pages of the tokens of Okta" });
    expect(within(pager).getByText("Showing 1 to 5 of 7 tokens")).toBeInTheDocument();
    await user.click(within(pager).getByRole("button", { name: "Next" }));

    await tokenRow("t0000006");
    expect(
      section("Okta")
        .getAllByRole("rowheader")
        .map((header) => header.textContent),
    ).toEqual(["t0000006", "t0000007"]);
    expect(section("Okta").getByText("Showing 6 to 7 of 7 tokens")).toBeInTheDocument();
  });

  it("shows no pager when a connector's active tokens fit on one page", async () => {
    await renderConnectors(okta([token()]));

    await tokenRow("a1b2c3d4");
    expect(screen.queryByRole("navigation", { name: /tokens/i })).not.toBeInTheDocument();
  });

  // A token carrying a Permission no token may hold fails the section's read on
  // decode, so it shows the failure copy rather than a half-read token.
  it("reports a token page carrying an unknown Permission, and lists none of it", async () => {
    await renderConnectors({
      ...okta(),
      [TOKENS]: json({
        startIndex: 1,
        tokens: [{ ...token(), permissions: ["audit:read"] }],
        totalResults: 1,
      }),
    });

    expect(await screen.findByRole("alert")).toHaveTextContent(
      /^Unable to load the tokens of Okta\. Please try again\.$/,
    );
    expect(screen.getByRole("heading", { name: "Okta" })).toBeInTheDocument();
    expect(section("Okta").queryByRole("rowheader")).not.toBeInTheDocument();
  });

  // Try again, which reads a section's page again, is the paged read's (`paged-read.test.tsx`).
  it("reports a failed token read without claiming the connector has none", async () => {
    await renderConnectors({ ...okta(), [TOKENS]: bare(503) });

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Unable to load the tokens of Okta. Please try again.",
    );
    expect(section("Okta").queryByText("No active tokens.")).not.toBeInTheDocument();
  });

  it("creates a connector and re-reads the listing", async () => {
    const { requests } = await renderConnectors({
      [LISTING]: [json([]), json([connector()])],
      [TOKENS]: tokenPage([]),
      "POST /api/admin/connectors": json(connector()),
    });
    const user = userEvent.setup();

    expect(await screen.findByText("No connectors exist.")).toBeInTheDocument();
    await user.type(screen.getByLabelText("New connector name"), "  Okta  ");
    await user.click(screen.getByRole("button", { name: "Create connector" }));

    expect(requests()).toContainEqual({
      body: { displayName: "Okta" },
      method: "POST",
      path: "/api/admin/connectors",
    });
    await screen.findByRole("heading", { name: "Okta" });
    expect(await section("Okta").findByText("No active tokens.")).toBeInTheDocument();
    expect(section("Okta").queryByRole("table")).not.toBeInTheDocument();
    expect(screen.getByLabelText("New connector name")).toHaveValue("");
  });

  it("says it is loading until the listing arrives", async () => {
    const listing = deferred();
    await renderConnectors({ [LISTING]: listing.answer });

    expect(screen.getByText("Loading connectors…")).toBeInTheDocument();
    await listing.settle(json([]));
    expect(await screen.findByText("No connectors exist.")).toBeInTheDocument();
    expect(screen.queryByText("Loading connectors…")).not.toBeInTheDocument();
  });

  it("says a section's tokens are loading until its page arrives", async () => {
    const tokens = deferred();
    await renderConnectors({ ...okta(), [TOKENS]: tokens.answer });

    expect(await (await oktaSection()).findByText("Loading tokens…")).toBeInTheDocument();
    await tokens.settle(tokenPage([token()]));
    await tokenRow("a1b2c3d4");
    expect(section("Okta").queryByText("Loading tokens…")).not.toBeInTheDocument();
  });

  it("reports a failed create naming the connector", async () => {
    await renderConnectors({ [LISTING]: json([]), "POST /api/admin/connectors": bare(503) });
    const user = userEvent.setup();

    await user.type(await screen.findByLabelText("New connector name"), "Okta");
    await user.click(screen.getByRole("button", { name: "Create connector" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Creating Okta failed. Please try again.",
    );
    // The name is kept for a retry: only a successful create clears it.
    expect(screen.getByLabelText("New connector name")).toHaveValue("Okta");
  });

  it("shows a name a live connector holds beside the name field, keeping the name", async () => {
    await renderConnectors({
      ...okta(),
      "POST /api/admin/connectors": json(NAME_TAKEN, 409),
    });
    const user = userEvent.setup();

    await user.type(await screen.findByLabelText("New connector name"), "Okta");
    await user.click(screen.getByRole("button", { name: "Create connector" }));

    const name = screen.getByLabelText("New connector name");
    await vi.waitFor(() =>
      expect(name).toHaveAccessibleDescription("A connector with this name already exists"),
    );
    expect(name).toBeInvalid();
    expect(name).toHaveValue("Okta");
    // Shown where it applies, so not on the page's error line as well.
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("withdraws the name-taken error once the name is changed", async () => {
    await renderConnectors({
      ...okta(),
      "POST /api/admin/connectors": json(NAME_TAKEN, 409),
    });
    const user = userEvent.setup();
    const name = await screen.findByLabelText("New connector name");
    await user.type(name, "Okta");
    await user.click(screen.getByRole("button", { name: "Create connector" }));
    await vi.waitFor(() => expect(name).toHaveAttribute("aria-invalid", "true"));

    await user.type(name, " 2");

    expect({
      description: name.getAttribute("aria-describedby"),
      invalid: name.getAttribute("aria-invalid"),
    }).toEqual({
      description: null,
      invalid: null,
    });
    expect(screen.queryByText("A connector with this name already exists")).not.toBeInTheDocument();
  });

  it("withdraws the name-taken error once the same name is accepted on a retry", async () => {
    await renderConnectors({
      ...okta(),
      [LISTING]: [json([connector()]), json([]), json([connector()])],
      "POST /api/admin/connectors": [json(NAME_TAKEN, 409), json(connector())],
    });
    const user = userEvent.setup();
    const name = await screen.findByLabelText("New connector name");
    await user.type(name, "Okta");
    await user.click(screen.getByRole("button", { name: "Create connector" }));
    await vi.waitFor(() => expect(name).toHaveAttribute("aria-invalid", "true"));

    // The holder was deleted elsewhere meanwhile; the same name, unchanged, is now free.
    await user.click(screen.getByRole("button", { name: "Create connector" }));

    await vi.waitFor(() => expect(name).toHaveValue(""));
    expect(screen.queryByText("A connector with this name already exists")).not.toBeInTheDocument();
  });

  it("shows no name error before any create is refused", async () => {
    await renderConnectors({ [LISTING]: json([]) });

    expect(await screen.findByLabelText("New connector name")).not.toHaveAttribute("aria-invalid");
  });

  it("does not submit a blank connector name", async () => {
    const { requests } = await renderConnectors({ [LISTING]: json([]) });
    const user = userEvent.setup();

    await user.type(await screen.findByLabelText("New connector name"), "   ");
    await user.click(screen.getByRole("button", { name: "Create connector" }));

    expect(requests()).toEqual([{ method: "GET", path: "/api/admin/connectors" }]);
  });

  it("deletes a connector", async () => {
    const { requests } = await renderConnectors({
      ...okta(),
      [LISTING]: [json([connector()]), json([])],
      [`DELETE ${connectorAt}`]: bare(204),
    });
    const user = userEvent.setup();

    await deleteOkta(user);

    expect(requests()).toContainEqual({ method: "DELETE", path: connectorAt });
    expect(await screen.findByText("No connectors exist.")).toBeInTheDocument();
    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
  });

  it("asks before deleting a connector, saying what deleting it does", async () => {
    const { requests } = await renderConnectors(okta());
    const user = userEvent.setup();

    await user.click(await screen.findByRole("button", { name: "Delete Okta" }));

    const confirmation = await screen.findByRole("alertdialog", { name: "Delete Okta?" });
    expect(confirmation).toHaveTextContent("Every token it holds is revoked");
    expect(confirmation).toHaveTextContent("This cannot be undone.");
    expect(requests().filter((request) => request.method !== "GET")).toEqual([]);
  });

  it("sends nothing when the deletion is cancelled or dismissed", async () => {
    const { requests } = await renderConnectors(okta());
    const user = userEvent.setup();

    await user.click(await screen.findByRole("button", { name: "Delete Okta" }));
    await user.click(
      within(await screen.findByRole("alertdialog")).getByRole("button", { name: "Cancel" }),
    );
    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Delete Okta" }));
    await screen.findByRole("alertdialog");
    await user.keyboard("{Escape}");
    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();

    expect(requests().filter((request) => request.method !== "GET")).toEqual([]);
    expect(screen.getByRole("heading", { name: "Okta" })).toBeInTheDocument();
  });

  /**
   * The one-time disclosure: the plaintext from the issue response is shown, with
   * the warning that it cannot be retrieved again, and nowhere but component
   * state — dismissing it is final, and the re-read section never carries it.
   */
  it("issues a token, discloses its value once and re-reads the section", async () => {
    const { requests } = await renderConnectors({
      ...okta(),
      [TOKENS]: [tokenPage([]), tokenPage([token()])],
      [`POST ${connectorAt}/tokens`]: json(issued()),
    });
    const user = userEvent.setup();

    await (await oktaSection()).findByText("No active tokens.");
    const permissions = within(screen.getByRole("group", { name: "Permissions" }));
    // Chosen out of order: the request lists them in the backend's order regardless.
    await user.click(permissions.getByRole("checkbox", { name: /user:read/ }));
    await user.click(permissions.getByRole("checkbox", { name: /group:write/ }));
    await user.type(screen.getByLabelText("Lifetime in days (default 365)"), "30");
    await user.click(screen.getByRole("button", { name: "Issue token" }));

    expect(requests()).toContainEqual({
      body: { lifetimeDays: 30, permissions: ["group:write", "user:read"] },
      method: "POST",
      path: `${connectorAt}/tokens`,
    });
    const disclosure = await screen.findByRole("region", { name: "New token for Okta" });
    expect(within(disclosure).getByLabelText("New token value")).toHaveTextContent(
      "scim_plaintext_value_shown_once",
    );
    expect(disclosure).toHaveTextContent("cannot be retrieved again");
    expect(
      within(disclosure).getByText(
        "group:read, group:write, user:read, user:write · expires 2027-01-02",
      ),
    ).toBeInTheDocument();
    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
    await tokenRow("a1b2c3d4");

    await user.click(screen.getByRole("button", { name: "Dismiss token" }));
    expect(screen.queryByText("scim_plaintext_value_shown_once")).not.toBeInTheDocument();
  });

  it("issues nothing until a Permission is chosen, then with the default lifetime", async () => {
    const { requests } = await renderConnectors({
      ...okta(),
      [`POST ${connectorAt}/tokens`]: json(issued({ permissions: ["group:read"] })),
    });
    const user = userEvent.setup();

    const issue = await screen.findByRole("button", { name: "Issue token" });
    expect(issue).toBeDisabled();
    const permissions = within(screen.getByRole("group", { name: "Permissions" }));
    const groupRead = permissions.getByRole("checkbox", { name: /group:read/ });
    const userWrite = permissions.getByRole("checkbox", { name: /user:write/ });
    await user.click(groupRead);
    await user.click(userWrite);
    // Unchecking takes a Permission back off the request.
    await user.click(userWrite);
    expect(groupRead).toBeChecked();
    expect(userWrite).not.toBeChecked();
    expect(issue).toBeEnabled();
    await user.click(issue);

    expect(requests()).toContainEqual({
      body: { lifetimeDays: null, permissions: ["group:read"] },
      method: "POST",
      path: `${connectorAt}/tokens`,
    });
    expect(await screen.findByText("group:read · expires 2027-01-02")).toBeInTheDocument();
  });

  it("offers the issue form with no lifetime filled in, so the default stands", async () => {
    await renderConnectors(okta());

    expect(await screen.findByLabelText("Lifetime in days (default 365)")).toHaveAttribute(
      "value",
      "",
    );
  });

  /** The Superuser may put any token-grantable Permission on a token, whatever its own session lists. */
  it("offers the Superuser every token Permission, Users before Groups", async () => {
    await renderConnectors(okta(), ["connector:read"]);

    await screen.findByRole("heading", { name: "Okta" });
    const permissions = within(screen.getByRole("group", { name: "Permissions" }));
    const offered = ["user:read", "user:write", "group:read", "group:write"];
    for (const permission of offered) {
      expect(permissions.getByRole("checkbox", { name: new RegExp(permission) })).toBeEnabled();
    }
    expect(
      permissions.getAllByRole("checkbox").map((box) => box.closest("label")?.textContent),
    ).toEqual(offered.map((permission) => expect.stringContaining(permission)));
    expect(permissions.getByText("read Users")).toBeInTheDocument();
    expect(permissions.getByText("create, change and delete Users")).toBeInTheDocument();
    expect(permissions.getByText("read Groups")).toBeInTheDocument();
    expect(permissions.getByText("create, change and delete Groups")).toBeInTheDocument();
  });

  it("rotates a token, discloses the replacement once and re-reads the section", async () => {
    const replacementId = "b2c3d4e5-0000-4000-8000-000000000002";
    const { requests } = await renderConnectors({
      ...okta(),
      [TOKENS]: [tokenPage([token()]), tokenPage([token({ id: replacementId })])],
      [`POST ${tokenAt}/rotate`]: json(
        issued({ presentedValue: "scim_rotated_value", tokenId: replacementId }),
      ),
    });
    const user = userEvent.setup();

    await user.click(await screen.findByRole("button", { name: "Rotate token a1b2c3d4" }));

    expect(requests()).toContainEqual({ body: {}, method: "POST", path: `${tokenAt}/rotate` });
    expect(await screen.findByLabelText("New token value")).toHaveTextContent("scim_rotated_value");
    // The rotated token ended at once, so the re-read lists the replacement alone.
    await tokenRow("b2c3d4e5");
    expect(section("Okta").queryByRole("rowheader", { name: "a1b2c3d4" })).not.toBeInTheDocument();
  });

  it("revokes a token, which leaves the section on its re-read", async () => {
    const { requests } = await renderConnectors({
      ...okta(),
      [TOKENS]: [tokenPage([token()]), tokenPage([])],
      [`POST ${tokenAt}/revoke`]: bare(204),
    });
    const user = userEvent.setup();

    await user.click(await screen.findByRole("button", { name: "Revoke token a1b2c3d4" }));

    expect(requests()).toContainEqual({ method: "POST", path: `${tokenAt}/revoke` });
    expect(await section("Okta").findByText("No active tokens.")).toBeInTheDocument();
    expect(section("Okta").queryByRole("rowheader")).not.toBeInTheDocument();
    expect(
      requests().filter((request) => request.path.startsWith(`${connectorAt}/tokens?`)),
    ).toHaveLength(2);
    expect(screen.queryByLabelText("New token value")).not.toBeInTheDocument();
  });

  it("says a later page emptied by a revoke is empty, and offers Previous back", async () => {
    const last = token({ id: "t0000006-0000-4000-8000-000000000000" });
    await renderConnectors({
      ...okta(),
      [TOKENS]: tokenPage(numberedTokens(1, 5), 1, 6),
      [tokensFrom(6)]: [tokenPage([last], 6, 6), tokenPage([], 6, 5)],
      [`POST ${connectorAt}/tokens/${last.id}/revoke`]: bare(204),
    });
    const user = userEvent.setup();
    await tokenRow("t0000001");
    await user.click(screen.getByRole("button", { name: "Next" }));

    await user.click(await screen.findByRole("button", { name: "Revoke token t0000006" }));

    expect(
      await section("Okta").findByText("This page is now empty. Use Previous to go back."),
    ).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Previous" }));
    await tokenRow("t0000001");
  });

  it("disables every control while a change is in flight", async () => {
    const revoking = deferred();
    await renderConnectors({
      ...okta([token()]),
      [`POST ${tokenAt}/revoke`]: revoking.answer,
    });
    const user = userEvent.setup();

    await user.click(await screen.findByRole("button", { name: "Revoke token a1b2c3d4" }));

    for (const name of [
      "Delete Okta",
      "Issue token",
      "Rotate token a1b2c3d4",
      "Create connector",
    ]) {
      expect(screen.getByRole("button", { name })).toBeDisabled();
    }
    await revoking.settle(bare(204));
    // Enabled again once the re-read after the change has answered.
    await vi.waitFor(() =>
      expect(screen.getByRole("button", { name: "Delete Okta" })).toBeEnabled(),
    );
  });

  it("reports an out-of-range request as a refusal", async () => {
    await renderConnectors({
      ...okta(),
      [`POST ${connectorAt}/tokens`]: bare(400),
    });
    const user = userEvent.setup();

    await user.click(await screen.findByRole("checkbox", { name: /user:read/ }));
    await user.click(screen.getByRole("button", { name: "Issue token" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Refused: Issuing a token for Okta — check the values and try again.",
    );
    expect(screen.queryByLabelText("New token value")).not.toBeInTheDocument();
  });

  it("reports a connector that has since gone", async () => {
    await renderConnectors({
      ...okta(),
      [`DELETE ${connectorAt}`]: bare(404),
    });
    const user = userEvent.setup();

    await deleteOkta(user);

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Deleting Okta failed: it no longer exists. Reload the page.",
    );
  });

  it("reports a rotation refused because the token can no longer be rotated", async () => {
    await renderConnectors({
      ...okta([token()]),
      [`POST ${tokenAt}/rotate`]: bare(409),
    });
    const user = userEvent.setup();

    await user.click(await screen.findByRole("button", { name: "Rotate token a1b2c3d4" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Refused: the token can no longer be rotated — it was revoked, has expired or was already rotated.",
    );
  });

  // The forbidden and csrf-expired mappings are the seam's own, exercised
  // generically by use-gated-read.test.tsx and use-gated-write.test.tsx; this
  // page proves only its own default copy.
  it("reports any other failure with action-specific default copy", async () => {
    await renderConnectors({
      ...okta([token()]),
      [`POST ${tokenAt}/revoke`]: UNREACHABLE,
      [`POST ${tokenAt}/rotate`]: bare(503),
    });
    const user = userEvent.setup();

    await user.click(await screen.findByRole("button", { name: "Rotate token a1b2c3d4" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Rotating the token failed. Please try again.",
    );

    await user.click(screen.getByRole("button", { name: "Revoke token a1b2c3d4" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Revoking the token failed. Please try again.",
    );
  });

  it("reports a failed listing read without claiming there are no connectors", async () => {
    await renderConnectors({ [LISTING]: bare(503) });

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Unable to load the connectors. Please try again.",
    );
    expect(screen.queryByText("No connectors exist.")).not.toBeInTheDocument();
    expect(screen.queryByText("Loading connectors…")).not.toBeInTheDocument();
  });

  // A connector:read holder who is not the Superuser has no change that re-reads
  // the listing, so Try again is their one way on short of reloading the page.
  it("reads the listing again on Try again after its first read failed, for a connector:read holder", async () => {
    const { requests } = await renderConnectors(
      { ...okta([token()]), [LISTING]: [bare(503), json([connector()])] },
      ["connector:read"],
      false,
    );
    const user = userEvent.setup();
    await screen.findByRole("alert");

    await user.click(screen.getByRole("button", { name: "Try again" }));

    await tokenRow("a1b2c3d4");
    expect(screen.queryByRole("button", { name: "Try again" })).not.toBeInTheDocument();
    expect(requests().filter((request) => `GET ${request.path}` === LISTING)).toHaveLength(2);
  });

  it("offers Try again beside the connectors kept when a re-read fails", async () => {
    await renderConnectors({
      ...okta(),
      [LISTING]: [
        json([connector()]),
        bare(503),
        json([connector(), connector({ id: "c2", displayName: "Entra" })]),
      ],
      [`DELETE ${connectorAt}`]: bare(500),
      [`GET /api/admin/connectors/c2/tokens?startIndex=1&count=5`]: tokenPage([]),
    });
    const user = userEvent.setup();
    await deleteOkta(user);
    await screen.findByText(/^Unable to load the connectors/);

    await user.click(screen.getByRole("button", { name: "Try again" }));

    expect(await screen.findByRole("region", { name: "Connector Entra" })).toBeInTheDocument();
    expect(screen.getByRole("region", { name: "Connector Okta" })).toBeInTheDocument();
  });

  it("offers no Try again while the listing reads as it should", async () => {
    await renderConnectors(okta());
    await oktaSection();

    expect(screen.queryByRole("button", { name: "Try again" })).not.toBeInTheDocument();
  });

  it("keeps the connectors on screen when the re-read after a change fails, and says so", async () => {
    await renderConnectors({
      ...okta(),
      [LISTING]: [json([connector()]), bare(503)],
      [`DELETE ${connectorAt}`]: bare(500),
    });
    const user = userEvent.setup();

    await deleteOkta(user);

    // The re-read is the latest thing to go wrong, so its copy replaces the delete's.
    expect(await screen.findByRole("alert")).toHaveTextContent(
      /^Unable to load the connectors\. Please try again\.$/,
    );
    expect(screen.getByRole("heading", { name: "Okta" })).toBeInTheDocument();
    expect(screen.queryByText("No connectors exist.")).not.toBeInTheDocument();
  });

  it("withdraws a listing failure once a change starts, and shows the change's own", async () => {
    await renderConnectors({
      ...okta(),
      [LISTING]: [json([connector()]), bare(503), json([connector()])],
      [`DELETE ${connectorAt}`]: bare(500),
    });
    const user = userEvent.setup();

    await deleteOkta(user);
    // Delete fails (500) and the re-read fails too, so the alert shows the listing copy.
    await screen.findByText(/^Unable to load the connectors/);
    await deleteOkta(user);

    expect(await screen.findByRole("alert")).toHaveTextContent(
      /^Deleting Okta failed\. Please try again\.$/,
    );
  });
});
