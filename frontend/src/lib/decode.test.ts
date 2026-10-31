import { describe, expect, it } from "vitest";

import { DecodeError, decodeArray, jsonDecoder, readObject } from "./decode";

const BODY = {
  name: "ada",
  nickname: null,
  admin: false,
  count: 3,
  maybeCount: null,
  scope: "READ_ONLY",
  maybeScope: null,
  tags: ["a", "b"],
};

const SCOPES = ["READ_ONLY", "READ_WRITE"] as const;

/** The refusal `run` throws: a `DecodeError` whose message is exactly `message`. */
function refusal(run: () => unknown) {
  try {
    run();
  } catch (error) {
    expect(error).toBeInstanceOf(DecodeError);
    expect(error).toHaveProperty("name", "DecodeError");
    return (error as Error).message;
  }
  throw new Error("expected a DecodeError, but the read succeeded");
}

const asString = (item: unknown): string => {
  if (typeof item !== "string") throw new DecodeError("not a string");
  return item;
};

describe("readObject", () => {
  const body = () => readObject(BODY, "Body");

  it("accepts an object holding only the fields named", () => {
    expect(() => readObject({ a: 1, b: 2 }, "Body").onlyFields(["a", "b", "c"])).not.toThrow();
  });

  it("refuses an object holding a field not named, and names it", () => {
    expect(refusal(() => readObject({ a: 1, b: 2 }, "Body").onlyFields(["a"]))).toBe(
      "Body.b is not expected",
    );
  });

  it("reads each field type from a well-formed object", () => {
    expect(body().string("name")).toBe("ada");
    expect(body().nullableString("nickname")).toBeNull();
    expect(body().nullableString("name")).toBe("ada");
    expect(body().boolean("admin")).toBe(false);
    expect(body().nullableBoolean("nickname")).toBeNull();
    expect(body().nullableBoolean("admin")).toBe(false);
    expect(body().integer("count")).toBe(3);
    expect(body().nullableInteger("maybeCount")).toBeNull();
    expect(body().nullableInteger("count")).toBe(3);
    expect(body().oneOf("scope", SCOPES)).toBe("READ_ONLY");
    expect(body().nullableOneOf("maybeScope", SCOPES)).toBeNull();
    expect(body().nullableOneOf("scope", SCOPES)).toBe("READ_ONLY");
    expect(body().array("tags", asString)).toStrictEqual(["a", "b"]);
  });

  it.each([
    ["an array", []],
    ["null", null],
    ["a string", "ada"],
    ["a number", 7],
    ["undefined", undefined],
  ])("refuses %s as an object", (_, value) => {
    expect(refusal(() => readObject(value, "Body"))).toBe("Body is not an object");
  });

  it("refuses a missing field, naming it", () => {
    expect(refusal(() => body().string("missing"))).toBe("Body.missing is not a string");
    expect(refusal(() => body().nullableString("missing"))).toBe("Body.missing is not a string");
    expect(refusal(() => body().boolean("missing"))).toBe("Body.missing is not a boolean");
    expect(refusal(() => body().nullableBoolean("missing"))).toBe("Body.missing is not a boolean");
    expect(refusal(() => body().integer("missing"))).toBe("Body.missing is not an integer");
    expect(refusal(() => body().nullableInteger("missing"))).toBe("Body.missing is not an integer");
    expect(refusal(() => body().oneOf("missing", SCOPES))).toBe("Body.missing is not a string");
    expect(refusal(() => body().nullableOneOf("missing", SCOPES))).toBe(
      "Body.missing is not a string",
    );
    expect(refusal(() => body().array("missing", asString))).toBe("Body.missing is not an array");
  });

  it("refuses a field of the wrong type, naming it", () => {
    expect(refusal(() => body().string("count"))).toBe("Body.count is not a string");
    expect(refusal(() => body().string("nickname"))).toBe("Body.nickname is not a string");
    expect(refusal(() => body().nullableString("admin"))).toBe("Body.admin is not a string");
    expect(refusal(() => body().boolean("name"))).toBe("Body.name is not a boolean");
    expect(refusal(() => body().boolean("nickname"))).toBe("Body.nickname is not a boolean");
    expect(refusal(() => body().nullableBoolean("name"))).toBe("Body.name is not a boolean");
    expect(refusal(() => body().integer("name"))).toBe("Body.name is not an integer");
    expect(refusal(() => body().nullableInteger("name"))).toBe("Body.name is not an integer");
    expect(refusal(() => body().array("name", asString))).toBe("Body.name is not an array");
    expect(refusal(() => body().oneOf("maybeScope", SCOPES))).toBe(
      "Body.maybeScope is not a string",
    );
  });

  it("refuses a number that is not an integer", () => {
    for (const count of [1.5, Number.NaN, Number.POSITIVE_INFINITY, "3"]) {
      expect(refusal(() => readObject({ count }, "Body").integer("count"))).toBe(
        "Body.count is not an integer",
      );
    }
  });

  it("refuses a string outside the allowed set, rather than passing it through", () => {
    for (const scope of ["ADMIN", "read_only", ""]) {
      expect(refusal(() => readObject({ scope }, "Body").oneOf("scope", SCOPES))).toBe(
        "Body.scope is not READ_ONLY | READ_WRITE",
      );
      expect(refusal(() => readObject({ scope }, "Body").nullableOneOf("scope", SCOPES))).toBe(
        "Body.scope is not READ_ONLY | READ_WRITE",
      );
    }
  });

  it("reads a nested object's fields", () => {
    expect(
      readObject({ owner: { name: "ada" } }, "Body")
        .object("owner")
        .string("name"),
    ).toBe("ada");
  });

  it.each([
    ["missing", {}],
    ["null", { owner: null }],
    ["an array", { owner: [] }],
    ["a string", { owner: "ada" }],
  ])("refuses a nested object that is %s, naming it under its parent", (_, value) => {
    expect(refusal(() => readObject(value, "Body").object("owner"))).toBe(
      "Body.owner is not an object",
    );
  });

  it("names a nested object's field under its parent's name", () => {
    expect(refusal(() => readObject({ owner: {} }, "Body").object("owner").string("name"))).toBe(
      "Body.owner.name is not a string",
    );
  });

  it("names a nested element's field under its parent", () => {
    expect(refusal(() => readObject({ tags: ["a", 1] }, "Body").array("tags", asString))).toBe(
      "not a string",
    );
    expect(refusal(() => readObject({ tags: {} }, "Body").array("tags", asString))).toBe(
      "Body.tags is not an array",
    );
  });
});

describe("decodeArray", () => {
  it("decodes every element in order", () => {
    expect(decodeArray(["a", "b"], asString, "Tags")).toStrictEqual(["a", "b"]);
    expect(decodeArray([], asString, "Tags")).toStrictEqual([]);
  });

  it.each([
    ["an object", { 0: "a" }],
    ["null", null],
    ["a string", "ab"],
  ])("refuses %s as an array", (_, value) => {
    expect(refusal(() => decodeArray(value, asString, "Tags"))).toBe("Tags is not an array");
  });

  it("refuses an array with one bad element", () => {
    expect(refusal(() => decodeArray(["a", 2], asString, "Tags"))).toBe("not a string");
  });
});

describe("jsonDecoder", () => {
  const decodeName = jsonDecoder((body) => readObject(body, "Body").string("name"));

  it("parses the body and hands it to the body decoder", async () => {
    await expect(decodeName(Response.json({ name: "ada" }))).resolves.toBe("ada");
  });

  it("rejects a body of the wrong shape with the body decoder's error", async () => {
    await expect(decodeName(Response.json({ name: 1 }))).rejects.toThrow(
      new DecodeError("Body.name is not a string"),
    );
  });

  it("rejects a body that is not JSON at all", async () => {
    await expect(decodeName(new Response("<html>502 Bad Gateway</html>"))).rejects.toThrow(
      SyntaxError,
    );
  });
});
