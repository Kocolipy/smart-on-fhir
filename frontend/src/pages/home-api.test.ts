import { describe, expect, it } from "vitest";

import { COUNTER, INCREMENT_COUNTER, RESET_COUNTER } from "./home-api";

describe("COUNTER", () => {
  it("reads the count at /api/count, under counter:read", () => {
    expect({ path: COUNTER.path(), permission: COUNTER.permission }).toEqual({
      path: "/api/count",
      permission: "counter:read",
    });
  });
});

describe.each([
  ["INCREMENT_COUNTER", INCREMENT_COUNTER, "/api/count/increment"],
  ["RESET_COUNTER", RESET_COUNTER, "/api/count/reset"],
] as const)("%s", (_, write, path) => {
  it("posts, with no body, to its own path under counter:write", () => {
    expect({
      body: write.body,
      method: write.method,
      path: write.path(),
      permission: write.permission,
    }).toEqual({ body: undefined, method: "POST", path, permission: "counter:write" });
  });
});

describe.each([
  ["COUNTER", COUNTER.decode],
  ["INCREMENT_COUNTER", INCREMENT_COUNTER.decode],
  ["RESET_COUNTER", RESET_COUNTER.decode],
] as const)("%s's answer", (_, decode) => {
  it("is read down to the count the backend's body carries", async () => {
    await expect(decode(Response.json({ count: 7 }))).resolves.toBe(7);
  });

  it.each([
    ["a missing count", {}, "CountResponse.count is not an integer"],
    ["a string count", { count: "7" }, "CountResponse.count is not an integer"],
    ["a fractional count", { count: 1.5 }, "CountResponse.count is not an integer"],
    ["a non-object body", [7], "CountResponse is not an object"],
  ])("is refused with %s", async (_, body, message) => {
    await expect(decode(Response.json(body))).rejects.toThrow(
      expect.objectContaining({ message, name: "DecodeError" }),
    );
  });
});
