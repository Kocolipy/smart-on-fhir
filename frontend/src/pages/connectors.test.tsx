import { act, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { AuthContext, type AuthContextState } from "@/auth/auth-context-value";
import { apiFetch, type ApiDecoder } from "@/lib/http";

import {
  TOKEN_PERMISSIONS,
  type Connector,
  type ConnectorToken,
  type IssuedToken,
  type TokenPermission,
} from "./accounts-api";
import { Connectors } from "./connectors";

vi.mock("@/lib/http", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/http")>()),
  apiFetch: vi.fn(),
}));

const apiFetchMock = vi.mocked(apiFetch);

const auth: AuthContextState = {
  changePassword: vi.fn(),
  expireSession: vi.fn(),
  login: vi.fn(),
  logout: vi.fn(),
  signInReason: null,
  signOutForInactivity: vi.fn(),
  status: "authenticated",
  // connector:read is the precondition: the Accounts page renders this panel only with it.
  // The session also holds what the default props offer (connector:write for
  // connectors, connector:token for tokens), since each write names its own
  // Permission and is never sent without it.
  user: {
    idleTimeoutSeconds: 900,
    passwordChangeRequired: false,
    permissions: ["connector:read", "connector:write", "connector:token"],
    username: "ada",
  },
};

const CONNECTOR_ID = "c0000000-0000-4000-8000-000000000001";
const TOKEN_ID = "a1b2c3d4-0000-4000-8000-000000000001";

const token = (overrides: Partial<ConnectorToken> = {}): ConnectorToken => ({
  active: true,
  expiresAt: "2027-01-02T00:00:00Z",
  id: TOKEN_ID,
  issuedAt: "2026-01-02T00:00:00Z",
  originalExpiresAt: "2027-01-02T00:00:00Z",
  permissions: ["group:read", "group:write", "user:read", "user:write"],
  revokedAt: null,
  ...overrides,
});

const connector = (overrides: Partial<Connector> = {}): Connector => ({
  createdAt: "2026-01-01T00:00:00Z",
  displayName: "Okta",
  id: CONNECTOR_ID,
  tokens: [],
  ...overrides,
});

const issued = (overrides: Partial<IssuedToken> = {}): IssuedToken => ({
  connectorId: CONNECTOR_ID,
  expiresAt: "2027-01-02T00:00:00Z",
  issuedAt: "2026-01-02T00:00:00Z",
  permissions: ["group:read", "group:write", "user:read", "user:write"],
  presentedValue: "scim_plaintext_value_shown_once",
  tokenId: TOKEN_ID,
  ...overrides,
});

/**
 * Answers every listing read from `listings` in turn (the last one repeats) and
 * every other request from `actions`, in order.
 */
function routeApi({ actions = [], listings }: { actions?: object[]; listings: object[] }) {
  const reads = [...listings];
  const queue = [...actions];
  apiFetchMock.mockImplementation(((
    path: string,
    init?: RequestInit,
    decode?: ApiDecoder<unknown>,
  ) => {
    if (path === "/api/admin/connectors" && (init?.method ?? "GET") === "GET") {
      return answer(reads.length > 1 ? reads.shift() : reads[0], decode);
    }
    const next = queue.shift();
    if (next === undefined) throw new Error(`unexpected ${init?.method} ${path}`);
    return answer(next, decode);
  }) as never);
}

/**
 * What `apiFetch` hands the page for a canned result: an `ok` result's `data`
 * is the backend's JSON body, read through the decoder the page passed, and a
 * body that decoder refuses is a plain `failed`, as `apiFetch` makes it — so a
 * write naming the wrong decoder, or none where its answer has a body, fails
 * the test rather than slipping the fixture through undecoded.
 */
async function answer(result: object | undefined, decode?: ApiDecoder<unknown>) {
  if (result === undefined || !("kind" in result) || result.kind !== "ok") return result;
  if (decode === undefined) return { kind: "ok", data: undefined };
  try {
    return {
      kind: "ok",
      data: await decode(Response.json("data" in result ? result.data : undefined)),
    };
  } catch {
    return { kind: "failed" };
  }
}

/** Every request the page sent, as path and init — the operation it named. */
const sent = () => apiFetchMock.mock.calls.map(([path, init]) => [path, init]);

function renderConnectors(
  {
    canIssueTokens = true,
    canManageConnectors = true,
    grantablePermissions = TOKEN_PERMISSIONS,
  } = {} as {
    canIssueTokens?: boolean;
    canManageConnectors?: boolean;
    grantablePermissions?: readonly TokenPermission[];
  },
) {
  return render(
    <AuthContext.Provider value={auth}>
      <Connectors
        canIssueTokens={canIssueTokens}
        canManageConnectors={canManageConnectors}
        grantablePermissions={grantablePermissions}
      />
    </AuthContext.Provider>,
  );
}

const section = (name: string) => within(screen.getByRole("region", { name: `Connector ${name}` }));

describe("Connectors by Permission", () => {
  beforeEach(() => {
    apiFetchMock.mockReset();
  });

  const oneActiveToken = () =>
    routeApi({ listings: [{ kind: "ok", data: [connector({ tokens: [token()] })] }] });

  it("offers neither creating nor deleting a connector without connector:write", async () => {
    oneActiveToken();
    renderConnectors({ canManageConnectors: false });

    expect(await screen.findByRole("heading", { name: "Okta" })).toBeInTheDocument();
    expect(screen.queryByLabelText("New connector name")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Create connector" })).not.toBeInTheDocument();
    expect(section("Okta").queryByRole("button", { name: /^Delete/ })).not.toBeInTheDocument();
    // Token actions are connector:token's, held here.
    expect(section("Okta").getByRole("button", { name: /^Rotate token/ })).toBeEnabled();
  });

  it("offers no token issue, rotation or revocation without connector:token", async () => {
    oneActiveToken();
    renderConnectors({ canIssueTokens: false });

    expect(await screen.findByRole("heading", { name: "Okta" })).toBeInTheDocument();
    const okta = section("Okta");
    expect(okta.queryByRole("button", { name: /^Issue token/ })).not.toBeInTheDocument();
    expect(okta.queryByRole("button", { name: /^Rotate token/ })).not.toBeInTheDocument();
    expect(okta.queryByRole("button", { name: /^Revoke token/ })).not.toBeInTheDocument();
    // Connector management is connector:write's, held here.
    expect(okta.getByRole("button", { name: "Delete Okta" })).toBeEnabled();
    expect(screen.getByRole("button", { name: "Create connector" })).toBeEnabled();
  });

  it("still lists every connector and its tokens to a reader holding neither", async () => {
    oneActiveToken();
    renderConnectors({ canIssueTokens: false, canManageConnectors: false });

    expect(await screen.findByRole("heading", { name: "Okta" })).toBeInTheDocument();
    expect(section("Okta").getByRole("rowheader", { name: "a1b2c3d4" })).toBeInTheDocument();
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });

  it("requests no listing without connector:read", async () => {
    oneActiveToken();
    render(
      <AuthContext.Provider value={{ ...auth, user: { ...auth.user!, permissions: [] } }}>
        <Connectors canIssueTokens={false} canManageConnectors={false} grantablePermissions={[]} />
      </AuthContext.Provider>,
    );

    await act(async () => {});
    expect(apiFetchMock).not.toHaveBeenCalled();
  });
});

describe("Connectors", () => {
  beforeEach(() => {
    apiFetchMock.mockReset();
  });

  it("lists every connector with its tokens' Permissions, dates and status", async () => {
    routeApi({
      listings: [
        {
          kind: "ok",
          data: [
            connector({
              tokens: [
                token(),
                token({ active: false, id: "e0000000-x", permissions: ["user:read"] }),
                token({
                  active: false,
                  id: "r0000000-x",
                  permissions: [],
                  revokedAt: "2026-02-03T00:00:00Z",
                }),
              ],
            }),
          ],
        },
      ],
    });
    renderConnectors();

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

  it("creates a connector and re-reads the listing", async () => {
    routeApi({
      actions: [{ kind: "ok", data: connector() }],
      listings: [
        { kind: "ok", data: [] },
        { kind: "ok", data: [connector()] },
      ],
    });
    const user = userEvent.setup();
    renderConnectors();

    expect(await screen.findByText("No connectors exist.")).toBeInTheDocument();
    await user.type(screen.getByLabelText("New connector name"), "  Okta  ");
    await user.click(screen.getByRole("button", { name: "Create connector" }));

    expect(sent()).toContainEqual([
      "/api/admin/connectors",
      {
        body: JSON.stringify({ displayName: "Okta" }),
        headers: { "Content-Type": "application/json" },
        method: "POST",
      },
    ]);
    expect(await screen.findByRole("heading", { name: "Okta" })).toBeInTheDocument();
    expect(section("Okta").getByText("No tokens issued.")).toBeInTheDocument();
    expect(section("Okta").queryByRole("table")).not.toBeInTheDocument();
    expect(screen.getByLabelText("New connector name")).toHaveValue("");
  });

  it("says it is loading until the listing arrives", async () => {
    let finish: ((result: object) => void) | undefined;
    apiFetchMock.mockReturnValueOnce(
      new Promise((resolve) => {
        finish = resolve;
      }) as never,
    );
    renderConnectors();

    expect(screen.getByText("Loading connectors…")).toBeInTheDocument();
    await act(async () => {
      finish?.({ kind: "ok", data: [] });
    });
    expect(await screen.findByText("No connectors exist.")).toBeInTheDocument();
    expect(screen.queryByText("Loading connectors…")).not.toBeInTheDocument();
  });

  it("reports a failed create naming the connector", async () => {
    routeApi({
      actions: [{ kind: "failed", status: 503 }],
      listings: [{ kind: "ok", data: [] }],
    });
    const user = userEvent.setup();
    renderConnectors();

    await user.type(await screen.findByLabelText("New connector name"), "Okta");
    await user.click(screen.getByRole("button", { name: "Create connector" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Creating Okta failed. Please try again.",
    );
    // The name is kept for a retry: only a successful create clears it.
    expect(screen.getByLabelText("New connector name")).toHaveValue("Okta");
  });

  it("reports a create the backend answers with an unreadable connector as failed", async () => {
    routeApi({
      actions: [{ kind: "ok", data: { displayName: "Okta" } }],
      listings: [{ kind: "ok", data: [] }],
    });
    const user = userEvent.setup();
    renderConnectors();

    await user.type(await screen.findByLabelText("New connector name"), "Okta");
    await user.click(screen.getByRole("button", { name: "Create connector" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Creating Okta failed. Please try again.",
    );
  });

  it("does not submit a blank connector name", async () => {
    routeApi({ listings: [{ kind: "ok", data: [] }] });
    const user = userEvent.setup();
    renderConnectors();

    await user.type(await screen.findByLabelText("New connector name"), "   ");
    await user.click(screen.getByRole("button", { name: "Create connector" }));

    expect(apiFetchMock).toHaveBeenCalledTimes(1);
  });

  it("deletes a connector", async () => {
    routeApi({
      actions: [{ kind: "ok", data: undefined }],
      listings: [
        { kind: "ok", data: [connector()] },
        { kind: "ok", data: [] },
      ],
    });
    const user = userEvent.setup();
    renderConnectors();

    await user.click(await screen.findByRole("button", { name: "Delete Okta" }));

    expect(apiFetchMock).toHaveBeenCalledWith(`/api/admin/connectors/${CONNECTOR_ID}`, {
      method: "DELETE",
    });
    expect(await screen.findByText("No connectors exist.")).toBeInTheDocument();
  });

  /**
   * The one-time disclosure: the plaintext from the issue response is shown, with
   * the warning that it cannot be retrieved again, and nowhere but component
   * state — dismissing it is final, and the re-read listing never carries it.
   */
  it("issues a token and discloses its value once", async () => {
    routeApi({
      actions: [{ kind: "ok", data: issued() }],
      listings: [
        { kind: "ok", data: [connector()] },
        { kind: "ok", data: [connector({ tokens: [token()] })] },
      ],
    });
    const user = userEvent.setup();
    renderConnectors();

    await screen.findByRole("heading", { name: "Okta" });
    const permissions = within(screen.getByRole("group", { name: "Permissions for Okta" }));
    // Chosen out of order: the request lists them in the backend's order regardless.
    await user.click(permissions.getByRole("checkbox", { name: /user:read/ }));
    await user.click(permissions.getByRole("checkbox", { name: /group:write/ }));
    await user.type(screen.getByLabelText("Lifetime in days for Okta (default 365)"), "30");
    await user.click(screen.getByRole("button", { name: "Issue token for Okta" }));

    expect(sent()).toContainEqual([
      `/api/admin/connectors/${CONNECTOR_ID}/tokens`,
      {
        body: JSON.stringify({ lifetimeDays: 30, permissions: ["group:write", "user:read"] }),
        headers: { "Content-Type": "application/json" },
        method: "POST",
      },
    ]);
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
    routeApi({
      actions: [{ kind: "ok", data: issued({ permissions: ["group:read"] }) }],
      listings: [{ kind: "ok", data: [connector()] }],
    });
    const user = userEvent.setup();
    renderConnectors();

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

    expect(sent()).toContainEqual([
      `/api/admin/connectors/${CONNECTOR_ID}/tokens`,
      expect.objectContaining({
        body: JSON.stringify({ lifetimeDays: null, permissions: ["group:read"] }),
      }),
    ]);
    expect(await screen.findByText("group:read · expires 2027-01-02")).toBeInTheDocument();
  });

  it("offers the issue form with no lifetime filled in, so the default stands", async () => {
    routeApi({ listings: [{ kind: "ok", data: [connector()] }] });
    renderConnectors();

    expect(await screen.findByLabelText("Lifetime in days for Okta (default 365)")).toHaveAttribute(
      "value",
      "",
    );
  });

  /** A token may carry only what the session holds itself: the rest is shown, not offered. */
  it("offers only the Permissions the session holds", async () => {
    routeApi({ listings: [{ kind: "ok", data: [connector()] }] });
    renderConnectors({ grantablePermissions: ["group:read", "group:write"] });

    await screen.findByRole("heading", { name: "Okta" });
    const permissions = within(screen.getByRole("group", { name: "Permissions for Okta" }));
    for (const permission of ["group:read", "group:write"]) {
      expect(permissions.getByRole("checkbox", { name: new RegExp(permission) })).toBeEnabled();
    }
    for (const permission of ["user:read", "user:write"]) {
      expect(permissions.getByRole("checkbox", { name: new RegExp(permission) })).toBeDisabled();
    }
    expect(permissions.getByText("read Users — you do not hold it")).toBeInTheDocument();
    expect(
      permissions.getByText("create, change and delete Users — you do not hold it"),
    ).toBeInTheDocument();
    expect(permissions.getByText("read Groups")).toBeInTheDocument();
    expect(permissions.getByText("create, change and delete Groups")).toBeInTheDocument();
    expect(permissions.getAllByRole("checkbox")).toHaveLength(4);
  });

  it("rotates a token and discloses the replacement once", async () => {
    routeApi({
      actions: [{ kind: "ok", data: issued({ presentedValue: "scim_rotated_value" }) }],
      listings: [{ kind: "ok", data: [connector({ tokens: [token()] })] }],
    });
    const user = userEvent.setup();
    renderConnectors();

    await user.click(await screen.findByRole("button", { name: "Rotate token a1b2c3d4" }));

    expect(sent()).toContainEqual([
      `/api/admin/connectors/${CONNECTOR_ID}/tokens/${TOKEN_ID}/rotate`,
      { body: "{}", headers: { "Content-Type": "application/json" }, method: "POST" },
    ]);
    expect(await screen.findByLabelText("New token value")).toHaveTextContent("scim_rotated_value");
  });

  it("revokes a token and shows the re-read state", async () => {
    routeApi({
      actions: [{ kind: "ok", data: undefined }],
      listings: [
        { kind: "ok", data: [connector({ tokens: [token()] })] },
        {
          kind: "ok",
          data: [
            connector({ tokens: [token({ active: false, revokedAt: "2026-05-06T00:00:00Z" })] }),
          ],
        },
      ],
    });
    const user = userEvent.setup();
    renderConnectors();

    await user.click(await screen.findByRole("button", { name: "Revoke token a1b2c3d4" }));

    expect(apiFetchMock).toHaveBeenCalledWith(
      `/api/admin/connectors/${CONNECTOR_ID}/tokens/${TOKEN_ID}/revoke`,
      { method: "POST" },
    );
    expect(await screen.findByText("Revoked 2026-05-06")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^Revoke token/ })).not.toBeInTheDocument();
    expect(screen.queryByLabelText("New token value")).not.toBeInTheDocument();
  });

  it("disables every control while a change is in flight", async () => {
    let finish: ((result: object) => void) | undefined;
    apiFetchMock.mockImplementation(((_path: string, init?: RequestInit) => {
      if ((init?.method ?? "GET") === "GET") {
        return Promise.resolve({ kind: "ok", data: [connector({ tokens: [token()] })] });
      }
      return new Promise((resolve) => {
        finish = resolve;
      });
    }) as never);
    const user = userEvent.setup();
    renderConnectors();

    await user.click(await screen.findByRole("button", { name: "Revoke token a1b2c3d4" }));

    for (const name of [
      "Delete Okta",
      "Issue token for Okta",
      "Rotate token a1b2c3d4",
      "Create connector",
    ]) {
      expect(screen.getByRole("button", { name })).toBeDisabled();
    }
    finish?.({ kind: "ok", data: undefined });
    expect(await screen.findByRole("button", { name: "Delete Okta" })).toBeEnabled();
  });

  it("reports an out-of-range request as a refusal", async () => {
    routeApi({
      actions: [{ kind: "failed", status: 400 }],
      listings: [{ kind: "ok", data: [connector()] }],
    });
    const user = userEvent.setup();
    renderConnectors();

    await user.click(await screen.findByRole("checkbox", { name: /user:read/ }));
    await user.click(screen.getByRole("button", { name: "Issue token for Okta" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Refused: Issuing a token for Okta — check the values and try again.",
    );
    expect(screen.queryByLabelText("New token value")).not.toBeInTheDocument();
  });

  it("reports a connector that has since gone", async () => {
    routeApi({
      actions: [{ kind: "failed", status: 404 }],
      listings: [{ kind: "ok", data: [connector()] }],
    });
    const user = userEvent.setup();
    renderConnectors();

    await user.click(await screen.findByRole("button", { name: "Delete Okta" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Deleting Okta failed: it no longer exists. Reload the page.",
    );
  });

  // The page names no 409 of its own, so this pins its own `default` over the
  // hook's generic 409 copy.
  it("reports a 409 refusal with the action's own default copy, not the hook's generic one", async () => {
    routeApi({
      actions: [{ kind: "failed", status: 409 }],
      listings: [{ kind: "ok", data: [connector({ tokens: [token()] })] }],
    });
    const user = userEvent.setup();
    renderConnectors();

    await user.click(await screen.findByRole("button", { name: "Revoke token a1b2c3d4" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Revoking the token failed. Please try again.",
    );
  });

  // The csrf-expired mapping is the seam's own, exercised generically by
  // use-gated-write.test.tsx; this page proves only its own default copy.
  it("reports any other failure with action-specific default copy", async () => {
    routeApi({
      actions: [{ kind: "failed", status: 503 }, { kind: "failed" }],
      listings: [{ kind: "ok", data: [connector({ tokens: [token()] })] }],
    });
    const user = userEvent.setup();
    renderConnectors();

    await user.click(await screen.findByRole("button", { name: "Rotate token a1b2c3d4" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Rotating the token failed. Please try again.",
    );

    await user.click(screen.getByRole("button", { name: "Revoke token a1b2c3d4" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Revoking the token failed. Please try again.",
    );
  });

  it("reports a refused mutation as permission denied and keeps the session", async () => {
    routeApi({
      actions: [{ kind: "forbidden" }],
      listings: [{ kind: "ok", data: [connector({ tokens: [token()] })] }],
    });
    const user = userEvent.setup();
    renderConnectors();

    await user.click(await screen.findByRole("button", { name: "Rotate token a1b2c3d4" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      /^You don't have permission to do this\.$/,
    );
    expect(auth.expireSession).not.toHaveBeenCalled();
    expect(auth.logout).not.toHaveBeenCalled();
  });

  it("reports a refused listing read as permission denied and keeps the session", async () => {
    routeApi({ listings: [{ kind: "forbidden" }] });
    renderConnectors();

    expect(await screen.findByRole("alert")).toHaveTextContent(
      /^You don't have permission to do this\.$/,
    );
    expect(auth.expireSession).not.toHaveBeenCalled();
    expect(auth.logout).not.toHaveBeenCalled();
  });

  it("reports a failed listing read without claiming there are no connectors", async () => {
    routeApi({ listings: [{ kind: "failed", status: 503 }] });
    renderConnectors();

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Unable to load the connectors. Please try again.",
    );
    expect(screen.queryByText("No connectors exist.")).not.toBeInTheDocument();
    expect(screen.queryByText("Loading connectors…")).not.toBeInTheDocument();
  });

  it("keeps the connectors on screen when the re-read after a change fails, and says so", async () => {
    routeApi({
      actions: [{ kind: "failed", status: 500 }],
      listings: [
        { kind: "ok", data: [connector()] },
        { kind: "failed", status: 503 },
      ],
    });
    const user = userEvent.setup();
    renderConnectors();

    await user.click(await screen.findByRole("button", { name: "Delete Okta" }));

    // The re-read is the latest thing to go wrong, so its copy replaces the delete's.
    expect(await screen.findByRole("alert")).toHaveTextContent(
      /^Unable to load the connectors\. Please try again\.$/,
    );
    expect(screen.getByRole("heading", { name: "Okta" })).toBeInTheDocument();
    expect(screen.queryByText("No connectors exist.")).not.toBeInTheDocument();
  });

  it("withdraws a listing failure once a change starts, and shows the change's own", async () => {
    routeApi({
      actions: [{ kind: "failed", status: 500 }],
      listings: [
        { kind: "ok", data: [connector()] },
        { kind: "failed", status: 503 },
        { kind: "ok", data: [connector()] },
      ],
    });
    const user = userEvent.setup();
    renderConnectors();

    await user.click(await screen.findByRole("button", { name: "Delete Okta" }));
    // Delete fails (500) and the re-read fails too, so the alert shows the listing copy.
    await screen.findByText(/^Unable to load the connectors/);
    routeApi({
      actions: [{ kind: "failed", status: 500 }],
      listings: [{ kind: "ok", data: [connector()] }],
    });
    await user.click(screen.getByRole("button", { name: "Delete Okta" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      /^Deleting Okta failed\. Please try again\.$/,
    );
  });
});
