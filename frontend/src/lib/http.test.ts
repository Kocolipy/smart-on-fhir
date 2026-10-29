import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { createElement } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { App } from "@/App";
import { TEST_USER_ID } from "@/auth/auth-context.testHelpers";

import { DecodeError, jsonDecoder, readObject } from "./decode";
import { apiFetch, discardCsrfToken } from "./http";

const CSRF_PATH = "/api/auth/csrf";

/** A `GET /api/auth/csrf` answer, as the backend's session repository names its header. */
const grant = (token: string, headerName = "X-CSRF-TOKEN") => Response.json({ headerName, token });

/** The request the token fetch itself is: a credentialed GET with nothing else on it. */
const TOKEN_FETCH = [CSRF_PATH, { credentials: "include" }] as const;

const decodeExample = async (response: Response): Promise<number> => {
  const body = (await response.json()) as { value: number };
  return body.value;
};

describe("apiFetch", () => {
  beforeEach(discardCsrfToken);

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("sends the session cookie and returns no-content success for a safe request", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(null)));

    await expect(apiFetch("/api/example")).resolves.toEqual({ kind: "ok", data: undefined });
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(fetch).toHaveBeenCalledWith("/api/example", { credentials: "include" });
  });

  it("decodes successful response data", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(Response.json({ value: 3 })));

    await expect(apiFetch("/api/example", {}, decodeExample)).resolves.toEqual({
      kind: "ok",
      data: 3,
    });
  });

  // ---- token fetch ------------------------------------------------------------------

  it("fetches the session's token before the first unsafe request", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(grant("token-1"))
      .mockResolvedValueOnce(new Response(null));
    vi.stubGlobal("fetch", fetchMock);

    await expect(apiFetch("/api/example/change", { method: "POST" })).resolves.toEqual({
      kind: "ok",
      data: undefined,
    });
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(fetchMock).toHaveBeenNthCalledWith(1, ...TOKEN_FETCH);
    expect(fetchMock).toHaveBeenNthCalledWith(2, "/api/example/change", {
      credentials: "include",
      headers: { "X-CSRF-TOKEN": "token-1" },
      method: "POST",
    });
  });

  it("holds the token in memory and reuses it, fetching it once", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(grant("token-1"))
      .mockResolvedValue(new Response(null));
    vi.stubGlobal("fetch", fetchMock);

    await apiFetch("/api/example/change", { method: "POST" });
    await apiFetch("/api/example/reset", { method: "POST" });

    expect(fetchMock.mock.calls.map(([url]) => url as string)).toEqual([
      CSRF_PATH,
      "/api/example/change",
      "/api/example/reset",
    ]);
    expect(fetchMock).toHaveBeenNthCalledWith(3, "/api/example/reset", {
      credentials: "include",
      headers: { "X-CSRF-TOKEN": "token-1" },
      method: "POST",
    });
    // Memory, not storage: nothing the backend handed out reaches a cookie.
    expect(document.cookie).not.toContain("token-1");
  });

  it("fetches again after the token is discarded, as a session change does", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(grant("before"))
      .mockResolvedValueOnce(new Response(null))
      .mockResolvedValueOnce(grant("after"))
      .mockResolvedValueOnce(new Response(null));
    vi.stubGlobal("fetch", fetchMock);

    await apiFetch("/api/auth/login", { method: "POST" });
    discardCsrfToken();
    await apiFetch("/api/example/change", { method: "POST" });

    expect(fetchMock).toHaveBeenNthCalledWith(3, ...TOKEN_FETCH);
    expect(fetchMock).toHaveBeenNthCalledWith(4, "/api/example/change", {
      credentials: "include",
      headers: { "X-CSRF-TOKEN": "after" },
      method: "POST",
    });
  });

  // A sign-in or sign-out can land while a token fetch is in flight; the token
  // it brings back is the old session's, so it is never held for the new one.
  it("holds no token whose fetch a discard overtook, so the next request fetches afresh", async () => {
    let answerToken!: (response: Response) => void;
    const fetchMock = vi
      .fn()
      .mockImplementationOnce(
        () =>
          new Promise<Response>((answer) => {
            answerToken = answer;
          }),
      )
      .mockResolvedValueOnce(new Response(null))
      .mockResolvedValueOnce(grant("after"))
      .mockResolvedValueOnce(new Response(null));
    vi.stubGlobal("fetch", fetchMock);

    const overtaken = apiFetch("/api/example/change", { method: "POST" });
    discardCsrfToken();
    answerToken(grant("before"));
    await overtaken;
    await apiFetch("/api/example/reset", { method: "POST" });

    expect(fetchMock.mock.calls.slice(2)).toEqual([
      TOKEN_FETCH,
      [
        "/api/example/reset",
        { credentials: "include", headers: { "X-CSRF-TOKEN": "after" }, method: "POST" },
      ],
    ]);
  });

  it.each(["GET", "HEAD", "OPTIONS", "get", undefined])(
    "sends safe method %s with no token and without fetching one",
    async (method) => {
      vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(null)));

      await apiFetch("/api/example", method === undefined ? {} : { method });

      expect(fetch).toHaveBeenCalledTimes(1);
      expect(fetch).not.toHaveBeenCalledWith(CSRF_PATH, expect.anything());
      const init = vi.mocked(fetch).mock.calls[0]?.[1];
      expect(init?.headers).toBeUndefined();
    },
  );

  it.each([
    ["answers 500", () => Promise.resolve(new Response(null, { status: 500 }))],
    ["answers 401", () => Promise.resolve(new Response(null, { status: 401 }))],
    ["throws", () => Promise.reject(new TypeError("offline"))],
    ["answers a body that is not JSON", () => Promise.resolve(new Response("<html>"))],
    ["answers no token", () => Promise.resolve(Response.json({ headerName: "X-CSRF-TOKEN" }))],
    ["answers no header name", () => Promise.resolve(Response.json({ token: "token-1" }))],
    ["answers an empty token", () => Promise.resolve(grant(""))],
    ["answers an empty header name", () => Promise.resolve(grant("token-1", ""))],
    [
      "answers a non-string token",
      () => Promise.resolve(Response.json({ headerName: "h", token: 1 })),
    ],
    ["answers a non-object", () => Promise.resolve(Response.json(null))],
    ["answers a bare string", () => Promise.resolve(Response.json("token-1"))],
    // Refused on the status, not merely because the body failed to parse.
    [
      "answers 403 with a token-shaped body",
      () =>
        Promise.resolve(
          Response.json({ headerName: "X-CSRF-TOKEN", token: "token-1" }, { status: 403 }),
        ),
    ],
  ])(
    "returns csrf-expired without sending the request when the token fetch %s",
    async (_, answer) => {
      const fetchMock = vi
        .fn()
        .mockImplementationOnce(answer)
        .mockResolvedValue(new Response(null));
      vi.stubGlobal("fetch", fetchMock);

      await expect(apiFetch("/api/example/change", { method: "POST" })).resolves.toEqual({
        kind: "csrf-expired",
      });
      expect(fetchMock).toHaveBeenCalledTimes(1);
      expect(fetchMock).toHaveBeenCalledWith(...TOKEN_FETCH);
    },
  );

  // ---- header attachment ------------------------------------------------------------

  it("sends the token in whichever header the endpoint names", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValueOnce(grant("token-1", "X-Renamed-Csrf"))
        .mockResolvedValueOnce(new Response(null)),
    );

    await apiFetch("/api/example/change", { method: "DELETE" });

    expect(fetch).toHaveBeenLastCalledWith("/api/example/change", {
      credentials: "include",
      headers: { "X-Renamed-Csrf": "token-1" },
      method: "DELETE",
    });
  });

  it("keeps the caller's own headers alongside the token", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValueOnce(grant("token-1")).mockResolvedValueOnce(new Response(null)),
    );

    await apiFetch("/api/auth/login", {
      body: "{}",
      headers: { "Content-Type": "application/json" },
      method: "POST",
    });

    expect(fetch).toHaveBeenLastCalledWith("/api/auth/login", {
      body: "{}",
      credentials: "include",
      headers: { "Content-Type": "application/json", "X-CSRF-TOKEN": "token-1" },
      method: "POST",
    });
  });

  // ---- re-fetch and retry -----------------------------------------------------------

  it("re-fetches the token and retries once after a 403", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(grant("stale"))
      .mockResolvedValueOnce(new Response(null, { status: 403 }))
      .mockResolvedValueOnce(grant("fresh"))
      .mockResolvedValueOnce(Response.json({ value: 1 }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(
      apiFetch("/api/example/change", { method: "POST" }, decodeExample),
    ).resolves.toEqual({ kind: "ok", data: 1 });
    expect(fetchMock).toHaveBeenCalledTimes(4);
    expect(fetchMock).toHaveBeenNthCalledWith(3, ...TOKEN_FETCH);
    expect(fetchMock).toHaveBeenNthCalledWith(4, "/api/example/change", {
      credentials: "include",
      headers: { "X-CSRF-TOKEN": "fresh" },
      method: "POST",
    });
  });

  it("keeps the re-fetched token for the requests after the retry", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(grant("stale"))
      .mockResolvedValueOnce(new Response(null, { status: 403 }))
      .mockResolvedValueOnce(grant("fresh"))
      .mockResolvedValue(new Response(null));
    vi.stubGlobal("fetch", fetchMock);

    await apiFetch("/api/example/change", { method: "POST" });
    await apiFetch("/api/example/reset", { method: "POST" });

    expect(fetchMock).toHaveBeenCalledTimes(5);
    expect(fetchMock).toHaveBeenLastCalledWith("/api/example/reset", {
      credentials: "include",
      headers: { "X-CSRF-TOKEN": "fresh" },
      method: "POST",
    });
  });

  it("returns forbidden after a second 403, with exactly one retry", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(grant("stale"))
      .mockResolvedValueOnce(new Response(null, { status: 403 }))
      .mockResolvedValueOnce(grant("fresh"))
      .mockResolvedValueOnce(new Response(null, { status: 403 }))
      .mockResolvedValue(new Response(null));
    vi.stubGlobal("fetch", fetchMock);

    await expect(apiFetch("/api/example/change", { method: "POST" })).resolves.toEqual({
      kind: "forbidden",
    });
    expect(fetchMock).toHaveBeenCalledTimes(4);
    expect(fetchMock).toHaveBeenNthCalledWith(4, "/api/example/change", {
      credentials: "include",
      headers: { "X-CSRF-TOKEN": "fresh" },
      method: "POST",
    });
  });

  it.each([
    ["answers 500", () => Promise.resolve(new Response(null, { status: 500 }))],
    ["answers 403", () => Promise.resolve(new Response(null, { status: 403 }))],
    ["throws", () => Promise.reject(new TypeError("offline"))],
    ["answers no token", () => Promise.resolve(Response.json({}))],
  ])("returns csrf-expired without retrying when the re-fetch %s", async (_, refetch) => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(grant("stale"))
      .mockResolvedValueOnce(new Response(null, { status: 403 }))
      .mockImplementationOnce(refetch)
      .mockResolvedValue(new Response(null));
    vi.stubGlobal("fetch", fetchMock);

    await expect(apiFetch("/api/example/change", { method: "POST" })).resolves.toEqual({
      kind: "csrf-expired",
    });
    expect(fetchMock).toHaveBeenCalledTimes(3);
    expect(fetchMock).toHaveBeenNthCalledWith(3, ...TOKEN_FETCH);
  });

  it("forgets a stale token whose re-fetch failed, so the next request fetches again", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(grant("stale"))
      .mockResolvedValueOnce(new Response(null, { status: 403 }))
      .mockResolvedValueOnce(new Response(null, { status: 500 }))
      .mockResolvedValueOnce(grant("fresh"))
      .mockResolvedValueOnce(new Response(null));
    vi.stubGlobal("fetch", fetchMock);

    await apiFetch("/api/example/change", { method: "POST" });
    await expect(apiFetch("/api/example/change", { method: "POST" })).resolves.toEqual({
      kind: "ok",
      data: undefined,
    });

    expect(fetchMock).toHaveBeenNthCalledWith(4, ...TOKEN_FETCH);
    expect(fetchMock).toHaveBeenNthCalledWith(5, "/api/example/change", {
      credentials: "include",
      headers: { "X-CSRF-TOKEN": "fresh" },
      method: "POST",
    });
  });

  it.each(["GET", "HEAD", "OPTIONS", "get", undefined])(
    "returns forbidden for a 403 on safe method %s without touching the CSRF token",
    async (method) => {
      const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 403 }));
      vi.stubGlobal("fetch", fetchMock);

      await expect(
        apiFetch("/api/example", method === undefined ? {} : { method }),
      ).resolves.toEqual({ kind: "forbidden" });
      expect(fetchMock).toHaveBeenCalledTimes(1);
      expect(fetchMock).not.toHaveBeenCalledWith(CSRF_PATH, expect.anything());
    },
  );

  // ---- status classification --------------------------------------------------------

  it("classifies 401 as unauthenticated without retrying", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(grant("token-1"))
      .mockResolvedValue(new Response(null, { status: 401 }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(apiFetch("/api/auth/logout", { method: "DELETE" })).resolves.toEqual({
      kind: "unauthenticated",
    });
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it("classifies other unsuccessful statuses as failed", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(null, { status: 503 })));

    await expect(apiFetch("/api/example")).resolves.toEqual({ kind: "failed", status: 503 });
  });

  it("classifies network and decoding failures as failed", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValueOnce(new TypeError("offline")));
    await expect(apiFetch("/api/example")).resolves.toEqual({ kind: "failed" });

    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("not json")));
    await expect(apiFetch("/api/example", {}, decodeExample)).resolves.toEqual({ kind: "failed" });
  });

  it("reads a successful response whose decoder throws as failed, with no status", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(Response.json({ value: "three" })));
    const refuse = vi.fn(() => {
      throw new DecodeError("ExampleResponse.value is not an integer");
    });

    // Strict: a `status` key would let a page read the `200` as meaningful.
    await expect(apiFetch("/api/example", {}, refuse)).resolves.toStrictEqual({ kind: "failed" });
    expect(refuse).toHaveBeenCalledTimes(1);
  });

  it("reads a successful response whose async decoder rejects as failed, with no status", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(Response.json([])));
    const decode = jsonDecoder((body) => readObject(body, "ExampleResponse").integer("value"));

    await expect(apiFetch("/api/example", {}, decode)).resolves.toStrictEqual({ kind: "failed" });
  });

  const decodeRule = async (response: Response): Promise<string> => {
    const body = (await response.json()) as { rule: string };
    return body.rule;
  };

  it("decodes a failure body only for a caller that asks for one", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn((input: string) =>
        Promise.resolve(
          input === CSRF_PATH
            ? grant("token-1")
            : Response.json({ rule: "TOO_SHORT" }, { status: 400 }),
        ),
      ),
    );

    await expect(
      apiFetch("/api/auth/change-password", { method: "POST" }, undefined, decodeRule),
    ).resolves.toStrictEqual({ kind: "failed", status: 400, detail: "TOO_SHORT" });
    // Without a failure decoder the body is not read, and no `detail` key appears.
    await expect(apiFetch("/api/auth/change-password", { method: "POST" })).resolves.toStrictEqual({
      kind: "failed",
      status: 400,
    });
  });

  it("decodes success data alongside a failure decoder", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(Response.json({ value: 4 })));

    await expect(apiFetch("/api/example", {}, decodeExample, decodeRule)).resolves.toEqual({
      kind: "ok",
      data: 4,
    });
  });

  it("keeps the status when a failure body does not decode", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValueOnce(grant("token-1"))
        .mockResolvedValueOnce(new Response("not json", { status: 400 })),
    );

    await expect(
      apiFetch("/api/auth/change-password", { method: "POST" }, undefined, decodeRule),
    ).resolves.toStrictEqual({ kind: "failed", status: 400, detail: undefined });
  });

  it("decodes a 401 body too, for a caller that asks for one", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn((input: string) =>
        Promise.resolve(
          input === CSRF_PATH
            ? grant("token-1")
            : Response.json({ rule: "ANSWERED" }, { status: 401 }),
        ),
      ),
    );

    await expect(
      apiFetch("/api/example", { method: "POST" }, undefined, decodeRule),
    ).resolves.toStrictEqual({ kind: "unauthenticated", detail: "ANSWERED" });
    // Without a failure decoder the body is not read, and no `detail` key appears.
    await expect(apiFetch("/api/example", { method: "POST" })).resolves.toStrictEqual({
      kind: "unauthenticated",
    });
  });

  it("reads a 401 whose body does not decode as unauthenticated with no detail", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValueOnce(grant("token-1"))
        .mockResolvedValueOnce(new Response(null, { status: 401 })),
    );

    await expect(
      apiFetch("/api/example", { method: "POST" }, undefined, decodeRule),
    ).resolves.toStrictEqual({ kind: "unauthenticated", detail: undefined });
  });
});

/**
 * Logout on a session that already expired, through the whole SPA: the token
 * held died with the session, so the backend refuses the logout `403`; the
 * transport re-fetches a token (for a fresh, anonymous session) and retries
 * once, and that is refused too — `401`, or `403` again. The User asked to be
 * signed out and is: local state is cleared and login shown, with no error and
 * no "session ended" notice, because nothing went wrong.
 */
describe("logout on an expired session", () => {
  beforeEach(discardCsrfToken);

  afterEach(() => {
    vi.unstubAllGlobals();
    window.history.replaceState(null, "", "/");
  });

  it.each([401, 403])(
    "clears the session and routes to login silently when the retry answers %i",
    async (retryStatus) => {
      window.history.replaceState(null, "", "/");
      let tokens = 0;
      let logouts = 0;
      const fetchMock = vi.fn((input: string, init?: RequestInit) => {
        if (input === CSRF_PATH) return Promise.resolve(grant(`token-${++tokens}`));
        if (input === "/api/auth/logout" && init?.method === "DELETE") {
          logouts += 1;
          return Promise.resolve(new Response(null, { status: logouts === 1 ? 403 : retryStatus }));
        }
        if (input === "/api/auth/me")
          return Promise.resolve(
            Response.json({
              id: TEST_USER_ID,
              idleTimeoutSeconds: 900,
              passwordChangeRequired: false,
              superuser: false,
              permissions: [],
              username: "ada",
            }),
          );
        return Promise.resolve(Response.json({ value: 0 }));
      });
      vi.stubGlobal("fetch", fetchMock);
      const user = userEvent.setup();
      render(createElement(App));

      await user.click(await screen.findByRole("button", { name: "Sign out" }));

      expect(await screen.findByRole("heading", { name: "Welcome back" })).toBeInTheDocument();
      expect(window.location.pathname).toBe("/login");
      expect(screen.queryByText("Signed in as ada")).not.toBeInTheDocument();
      expect(screen.queryByRole("alert")).not.toBeInTheDocument();
      expect(screen.queryByRole("status")).not.toBeInTheDocument();
      const logoutCalls = fetchMock.mock.calls.filter(([url]) => url === "/api/auth/logout");
      expect(logoutCalls).toEqual([
        [
          "/api/auth/logout",
          { credentials: "include", headers: { "X-CSRF-TOKEN": "token-1" }, method: "DELETE" },
        ],
        [
          "/api/auth/logout",
          { credentials: "include", headers: { "X-CSRF-TOKEN": "token-2" }, method: "DELETE" },
        ],
      ]);
    },
  );

  it("fetches a new token for the next login instead of reusing the dead one", async () => {
    window.history.replaceState(null, "", "/");
    let tokens = 0;
    let logouts = 0;
    const fetchMock = vi.fn((input: string, init?: RequestInit) => {
      if (input === CSRF_PATH) return Promise.resolve(grant(`token-${++tokens}`));
      if (input === "/api/auth/logout" && init?.method === "DELETE") {
        logouts += 1;
        return Promise.resolve(new Response(null, { status: logouts === 1 ? 403 : 401 }));
      }
      if (input === "/api/auth/login")
        return Promise.resolve(
          Response.json({
            id: TEST_USER_ID,
            idleTimeoutSeconds: 900,
            passwordChangeRequired: false,
            superuser: false,
            permissions: [],
            username: "ada",
          }),
        );
      if (input === "/api/auth/me")
        return Promise.resolve(
          Response.json({
            id: TEST_USER_ID,
            idleTimeoutSeconds: 900,
            passwordChangeRequired: false,
            superuser: false,
            permissions: [],
            username: "ada",
          }),
        );
      return Promise.resolve(Response.json({ value: 0 }));
    });
    vi.stubGlobal("fetch", fetchMock);
    const user = userEvent.setup();
    render(createElement(App));

    await user.click(await screen.findByRole("button", { name: "Sign out" }));
    await user.type(await screen.findByLabelText("Username"), "ada");
    await user.type(screen.getByLabelText("Password"), "correct-password");
    await user.click(screen.getByRole("button", { name: "Sign in" }));

    expect(await screen.findByRole("heading", { name: "Home" })).toBeInTheDocument();
    const urls = fetchMock.mock.calls.map(([url]) => url);
    // The token fetched just before the login, not the one the dead session held.
    expect(urls[urls.indexOf("/api/auth/login") - 1]).toBe(CSRF_PATH);
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/auth/login",
      expect.objectContaining({
        headers: { "Content-Type": "application/json", "X-CSRF-TOKEN": "token-3" },
      }),
    );
  });
});
