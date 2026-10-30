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
  revokedAt: null,
  ...overrides,
});

const connector = (overrides: Partial<Connector> = {}): Connector => ({
  createdAt: "2025-12-31T23:30:00Z",
  displayName: "Okta",
  id: CONNECTOR_ID,
  tokens: [],
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

const LISTING = "GET /api/admin/connectors";
const connectorAt = `/api/admin/connectors/${CONNECTOR_ID}`;
const tokenAt = `${connectorAt}/tokens/${TOKEN_ID}`;

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

describe("Connectors by Superuser", () => {
  const oneActiveToken = { [LISTING]: json([connector({ tokens: [token()] })]) };

  it("offers no connector or token action to a User holding every Permission but not the Superuser", async () => {
    await renderConnectors(oneActiveToken, EVERY_PERMISSION, false);

    expect(await screen.findByRole("heading", { name: "Okta" })).toBeInTheDocument();
    expect(screen.queryByLabelText("New connector name")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Create connector" })).not.toBeInTheDocument();
    const okta = section("Okta");
    expect(okta.queryByRole("button", { name: /^Delete/ })).not.toBeInTheDocument();
    expect(okta.queryByRole("button", { name: /^Issue token/ })).not.toBeInTheDocument();
    expect(okta.queryByRole("button", { name: /^Rotate token/ })).not.toBeInTheDocument();
    expect(okta.queryByRole("button", { name: /^Revoke token/ })).not.toBeInTheDocument();
  });

  it("still lists every connector and its tokens to a connector:read holder who is not the Superuser", async () => {
    await renderConnectors(oneActiveToken, ["connector:read"], false);

    expect(await screen.findByRole("heading", { name: "Okta" })).toBeInTheDocument();
    expect(section("Okta").getByRole("rowheader", { name: "a1b2c3d4" })).toBeInTheDocument();
    // The layout's Sign out is the only control on the page.
    expect(screen.getAllByRole("button").map((button) => button.textContent)).toEqual(["Sign out"]);
  });

  it("offers every connector and token action to the Superuser", async () => {
    await renderConnectors(oneActiveToken, ["connector:read"], true);

    expect(await screen.findByRole("heading", { name: "Okta" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Create connector" })).toBeEnabled();
    const okta = section("Okta");
    expect(okta.getByRole("button", { name: "Delete Okta" })).toBeEnabled();
    expect(okta.getByRole("button", { name: /^Rotate token/ })).toBeEnabled();
    expect(okta.getByRole("button", { name: /^Revoke token/ })).toBeEnabled();
    expect(okta.getByRole("button", { name: "Issue token for Okta" })).toBeDisabled();
  });

  it("requests no listing without connector:read", async () => {
    const { requests } = await renderConnectors(oneActiveToken, []);

    expect(requests()).toEqual([]);
  });

  it("renders no panel at all without connector:read", async () => {
    await renderConnectors(oneActiveToken, []);

    // Not its heading, its loading note, its listing or its empty note.
    expect(screen.queryByText(/connector/i)).not.toBeInTheDocument();
  });
});

describe("Connectors", () => {
  it("lists every connector with its tokens' Permissions, dates and status", async () => {
    await renderConnectors({
      [LISTING]: json([
        connector({
          tokens: [
            token(),
            token({ active: false, id: "e0000000-x", permissions: ["user:read"] }),
            token({
              active: false,
              id: "r0000000-x",
              permissions: [],
              revokedAt: "2026-02-02T18:00:00Z",
            }),
          ],
        }),
      ]),
    });

    expect(await screen.findByRole("heading", { name: "Okta" })).toBeInTheDocument();
    const okta = section("Okta");
    expect(okta.getAllByRole("columnheader").map((header) => header.textContent)).toEqual([
      "Token",
      "Permissions",
      "Issued",
      "Expires",
      "Status",
      "Actions",
    ]);
    expect(okta.getByText("Created 2026-01-01")).toBeInTheDocument();
    const active = within(okta.getByRole("rowheader", { name: "a1b2c3d4" }).closest("tr")!);
    expect(active.getByText("group:read, group:write, user:read, user:write")).toBeInTheDocument();
    expect(active.getByText("2026-01-02")).toBeInTheDocument();
    expect(active.getByText("2027-01-02")).toBeInTheDocument();
    expect(active.getByText("Active")).toBeInTheDocument();
    const expired = within(okta.getByRole("rowheader", { name: "e0000000" }).closest("tr")!);
    expect(expired.getByText("user:read")).toBeInTheDocument();
    expect(okta.getByText("Expired")).toBeInTheDocument();
    const revoked = within(okta.getByRole("rowheader", { name: "r0000000" }).closest("tr")!);
    expect(revoked.getByText("No permissions")).toBeInTheDocument();
    expect(okta.getByText("Revoked 2026-02-03")).toBeInTheDocument();
    // Rotate and revoke only for a token that would still be accepted.
    expect(okta.getAllByRole("button", { name: /^Rotate token/ })).toHaveLength(1);
    expect(okta.getAllByRole("button", { name: /^Revoke token/ })).toHaveLength(1);
    // No token value anywhere in the listing: the type has no field for one.
    expect(screen.queryByLabelText("New token value")).not.toBeInTheDocument();
  });

  // A token carrying a Permission no token may hold fails the whole listing on
  // decode, so the panel shows its failure copy rather than a half-read token.
  it("reports a listing whose token carries an unknown Permission, and lists none of it", async () => {
    await renderConnectors({
      [LISTING]: json([{ ...connector(), tokens: [{ ...token(), permissions: ["audit:read"] }] }]),
    });

    expect(await screen.findByRole("alert")).toHaveTextContent(
      /^Unable to load the connectors\. Please try again\.$/,
    );
    expect(screen.queryByRole("heading", { name: "Okta" })).not.toBeInTheDocument();
  });

  it("creates a connector and re-reads the listing", async () => {
    const { requests } = await renderConnectors({
      [LISTING]: [json([]), json([connector()])],
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
    expect(await screen.findByRole("heading", { name: "Okta" })).toBeInTheDocument();
    expect(section("Okta").getByText("No tokens issued.")).toBeInTheDocument();
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

  it("does not submit a blank connector name", async () => {
    const { requests } = await renderConnectors({ [LISTING]: json([]) });
    const user = userEvent.setup();

    await user.type(await screen.findByLabelText("New connector name"), "   ");
    await user.click(screen.getByRole("button", { name: "Create connector" }));

    expect(requests()).toEqual([{ method: "GET", path: "/api/admin/connectors" }]);
  });

  it("deletes a connector", async () => {
    const { requests } = await renderConnectors({
      [LISTING]: [json([connector()]), json([])],
      [`DELETE ${connectorAt}`]: bare(204),
    });
    const user = userEvent.setup();

    await user.click(await screen.findByRole("button", { name: "Delete Okta" }));

    expect(requests()).toContainEqual({ method: "DELETE", path: connectorAt });
    expect(await screen.findByText("No connectors exist.")).toBeInTheDocument();
  });

  /**
   * The one-time disclosure: the plaintext from the issue response is shown, with
   * the warning that it cannot be retrieved again, and nowhere but component
   * state — dismissing it is final, and the re-read listing never carries it.
   */
  it("issues a token and discloses its value once", async () => {
    const { requests } = await renderConnectors({
      [LISTING]: [json([connector()]), json([connector({ tokens: [token()] })])],
      [`POST ${connectorAt}/tokens`]: json(issued()),
    });
    const user = userEvent.setup();

    await screen.findByRole("heading", { name: "Okta" });
    const permissions = within(screen.getByRole("group", { name: "Permissions for Okta" }));
    // Chosen out of order: the request lists them in the backend's order regardless.
    await user.click(permissions.getByRole("checkbox", { name: /user:read/ }));
    await user.click(permissions.getByRole("checkbox", { name: /group:write/ }));
    await user.type(screen.getByLabelText("Lifetime in days for Okta (default 365)"), "30");
    await user.click(screen.getByRole("button", { name: "Issue token for Okta" }));

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

    await user.click(screen.getByRole("button", { name: "Dismiss token" }));
    expect(screen.queryByText("scim_plaintext_value_shown_once")).not.toBeInTheDocument();
  });

  it("issues nothing until a Permission is chosen, then with the default lifetime", async () => {
    const { requests } = await renderConnectors({
      [LISTING]: json([connector()]),
      [`POST ${connectorAt}/tokens`]: json(issued({ permissions: ["group:read"] })),
    });
    const user = userEvent.setup();

    const issue = await screen.findByRole("button", { name: "Issue token for Okta" });
    expect(issue).toBeDisabled();
    const permissions = within(screen.getByRole("group", { name: "Permissions for Okta" }));
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
    await renderConnectors({ [LISTING]: json([connector()]) });

    expect(await screen.findByLabelText("Lifetime in days for Okta (default 365)")).toHaveAttribute(
      "value",
      "",
    );
  });

  /** The Superuser may put any token-grantable Permission on a token, whatever its own session lists. */
  it("offers the Superuser every token Permission", async () => {
    await renderConnectors({ [LISTING]: json([connector()]) }, ["connector:read"]);

    await screen.findByRole("heading", { name: "Okta" });
    const permissions = within(screen.getByRole("group", { name: "Permissions for Okta" }));
    for (const permission of ["group:read", "group:write", "user:read", "user:write"]) {
      expect(permissions.getByRole("checkbox", { name: new RegExp(permission) })).toBeEnabled();
    }
    expect(permissions.getAllByRole("checkbox")).toHaveLength(4);
    expect(permissions.getByText("read Users")).toBeInTheDocument();
    expect(permissions.getByText("create, change and delete Users")).toBeInTheDocument();
    expect(permissions.getByText("read Groups")).toBeInTheDocument();
    expect(permissions.getByText("create, change and delete Groups")).toBeInTheDocument();
    expect(permissions.queryByText(/you do not hold it/)).not.toBeInTheDocument();
  });

  it("rotates a token and discloses the replacement once", async () => {
    const { requests } = await renderConnectors({
      [LISTING]: json([connector({ tokens: [token()] })]),
      [`POST ${tokenAt}/rotate`]: json(issued({ presentedValue: "scim_rotated_value" })),
    });
    const user = userEvent.setup();

    await user.click(await screen.findByRole("button", { name: "Rotate token a1b2c3d4" }));

    expect(requests()).toContainEqual({ body: {}, method: "POST", path: `${tokenAt}/rotate` });
    expect(await screen.findByLabelText("New token value")).toHaveTextContent("scim_rotated_value");
  });

  it("revokes a token and shows the re-read state", async () => {
    const { requests } = await renderConnectors({
      [LISTING]: [
        json([connector({ tokens: [token()] })]),
        json([
          connector({ tokens: [token({ active: false, revokedAt: "2026-05-05T22:00:00Z" })] }),
        ]),
      ],
      [`POST ${tokenAt}/revoke`]: bare(204),
    });
    const user = userEvent.setup();

    await user.click(await screen.findByRole("button", { name: "Revoke token a1b2c3d4" }));

    expect(requests()).toContainEqual({ method: "POST", path: `${tokenAt}/revoke` });
    expect(await screen.findByText("Revoked 2026-05-06")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^Revoke token/ })).not.toBeInTheDocument();
    expect(screen.queryByLabelText("New token value")).not.toBeInTheDocument();
  });

  it("disables every control while a change is in flight", async () => {
    const revoking = deferred();
    await renderConnectors({
      [LISTING]: json([connector({ tokens: [token()] })]),
      [`POST ${tokenAt}/revoke`]: revoking.answer,
    });
    const user = userEvent.setup();

    await user.click(await screen.findByRole("button", { name: "Revoke token a1b2c3d4" }));

    for (const name of [
      "Delete Okta",
      "Issue token for Okta",
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
      [LISTING]: json([connector()]),
      [`POST ${connectorAt}/tokens`]: bare(400),
    });
    const user = userEvent.setup();

    await user.click(await screen.findByRole("checkbox", { name: /user:read/ }));
    await user.click(screen.getByRole("button", { name: "Issue token for Okta" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Refused: Issuing a token for Okta — check the values and try again.",
    );
    expect(screen.queryByLabelText("New token value")).not.toBeInTheDocument();
  });

  it("reports a connector that has since gone", async () => {
    await renderConnectors({
      [LISTING]: json([connector()]),
      [`DELETE ${connectorAt}`]: bare(404),
    });
    const user = userEvent.setup();

    await user.click(await screen.findByRole("button", { name: "Delete Okta" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Deleting Okta failed: it no longer exists. Reload the page.",
    );
  });

  // The page names no 409 of its own, so this pins its own `default` over the
  // hook's generic 409 copy.
  it("reports a 409 refusal with the action's own default copy, not the hook's generic one", async () => {
    await renderConnectors({
      [LISTING]: json([connector({ tokens: [token()] })]),
      [`POST ${tokenAt}/revoke`]: bare(409),
    });
    const user = userEvent.setup();

    await user.click(await screen.findByRole("button", { name: "Revoke token a1b2c3d4" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Revoking the token failed. Please try again.",
    );
  });

  it("reports a rotation refused because the token can no longer be rotated", async () => {
    await renderConnectors({
      [LISTING]: json([connector({ tokens: [token()] })]),
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
      [LISTING]: json([connector({ tokens: [token()] })]),
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

  it("keeps the connectors on screen when the re-read after a change fails, and says so", async () => {
    await renderConnectors({
      [LISTING]: [json([connector()]), bare(503)],
      [`DELETE ${connectorAt}`]: bare(500),
    });
    const user = userEvent.setup();

    await user.click(await screen.findByRole("button", { name: "Delete Okta" }));

    // The re-read is the latest thing to go wrong, so its copy replaces the delete's.
    expect(await screen.findByRole("alert")).toHaveTextContent(
      /^Unable to load the connectors\. Please try again\.$/,
    );
    expect(screen.getByRole("heading", { name: "Okta" })).toBeInTheDocument();
    expect(screen.queryByText("No connectors exist.")).not.toBeInTheDocument();
  });

  it("withdraws a listing failure once a change starts, and shows the change's own", async () => {
    await renderConnectors({
      [LISTING]: [json([connector()]), bare(503), json([connector()])],
      [`DELETE ${connectorAt}`]: bare(500),
    });
    const user = userEvent.setup();

    await user.click(await screen.findByRole("button", { name: "Delete Okta" }));
    // Delete fails (500) and the re-read fails too, so the alert shows the listing copy.
    await screen.findByText(/^Unable to load the connectors/);
    await user.click(screen.getByRole("button", { name: "Delete Okta" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      /^Deleting Okta failed\. Please try again\.$/,
    );
  });
});
