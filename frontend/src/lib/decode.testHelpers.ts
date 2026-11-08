import { describe, expect, it } from "vitest";

import { DecodeError } from "./decode";

/** `body` without its `key` field, as a response that left the field out. */
export const without = (body: object, key: string) =>
  Object.fromEntries(Object.entries(body).filter(([field]) => field !== key));

/**
 * How one field is checked: a value of the wrong type for it, what the refusal
 * says that value is not, and what a missing field is not (the same, except a
 * closed set, which is first read as a string).
 */
export type FieldCheck = [wrong: unknown, expected: string, whenMissing?: string];

export const STRING: FieldCheck = [7, "a string"];
export const BOOLEAN: FieldCheck = ["true", "a boolean"];

/** `decode(value)`'s refusal, whether the decoder throws or answers a rejected promise. */
async function refused(decode: (value: unknown) => unknown, value: unknown, message: string) {
  await expect(Promise.resolve().then(() => decode(value))).rejects.toThrow(
    new DecodeError(message),
  );
}

/**
 * The decoder sweep: one decoder's contract, applied to every field so no
 * field's check can be dropped unnoticed — a valid body decodes to itself, an
 * extra field is dropped, every field the type has is checked, and each field
 * missing or wrong-typed, and a body that is no object, is refused. Each
 * refusal names the field that failed, so a case cannot pass on some other
 * field's failure. `decode` may answer a promise, as a decoder of a `Response`
 * does.
 */
export function describeDecoder(
  name: string,
  decode: (value: unknown) => unknown,
  valid: object,
  fields: Record<string, FieldCheck>,
) {
  describe(name, () => {
    it("decodes a valid body to the same value", async () => {
      expect(await decode(valid)).toStrictEqual(valid);
    });

    it("drops nothing the wire did not send and adds nothing", async () => {
      expect(
        Object.keys((await decode({ ...valid, extra: "ignored" })) as object).sort(),
      ).toStrictEqual(Object.keys(valid).sort());
    });

    it("checks every field the type has", () => {
      expect(Object.keys(fields).sort()).toStrictEqual(Object.keys(valid).sort());
    });

    it.each(Object.entries(fields))(
      "refuses a body missing %s",
      async (key, [, expected, missing]) => {
        await refused(decode, without(valid, key), `${name}.${key} is not ${missing ?? expected}`);
      },
    );

    it.each(Object.entries(fields))("refuses a wrong-typed %s", async (key, [value, expected]) => {
      await refused(decode, { ...valid, [key]: value }, `${name}.${key} is not ${expected}`);
    });

    it.each([
      ["an array", [valid]],
      ["null", null],
      ["a string", "body"],
    ])("refuses %s as a body", async (_, value) => {
      await refused(decode, value, `${name} is not an object`);
    });
  });
}
