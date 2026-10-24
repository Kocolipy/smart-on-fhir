/**
 * The primitives a response decoder is built from.
 *
 * A successful body is the backend's claim about its contract, not a guarantee:
 * a renamed field, a `null` where a string was promised, or a proxy's HTML
 * error page all parse (or fail to) long before anything renders them. So every
 * body is read through a hand-written decoder that takes `unknown` and either
 * returns the typed value or throws `DecodeError`, and `apiFetch` turns that
 * throw into a plain `failed` result. A cast (`as T`) would let the drift
 * through to show up later as a blank cell or a crash mid-render.
 *
 * Deliberately not a schema library: the shapes are few and flat, and adding
 * one is a dependency decision of its own.
 */

/** A body that does not have the shape its decoder expects. */
export class DecodeError extends Error {
  override name = "DecodeError";
}

type JsonObject = Readonly<Record<string, unknown>>;

const isObject = (value: unknown): value is JsonObject =>
  typeof value === "object" && value !== null && !Array.isArray(value);

const isInteger = (value: unknown): value is number => Number.isInteger(value);

/**
 * Typed reads of one object's fields. Every read is strict: a missing field is
 * a wrong type like any other, and a nullable read accepts `null` but not an
 * absent key, because the backend serialises its `null`s rather than dropping
 * them.
 */
export interface ObjectReader {
  string(key: string): string;
  nullableString(key: string): string | null;
  boolean(key: string): boolean;
  nullableBoolean(key: string): boolean | null;
  integer(key: string): number;
  nullableInteger(key: string): number | null;
  /** A string that must be one of `allowed`; anything else is refused, not passed through. */
  oneOf<T extends string>(key: string, allowed: readonly T[]): T;
  nullableOneOf<T extends string>(key: string, allowed: readonly T[]): T | null;
  array<T>(key: string, decode: (item: unknown) => T): T[];
  /** A nested object, read the same way; its errors name it under this one. */
  object(key: string): ObjectReader;
}

/** An array, each element decoded by `decode`; `what` names it in the error. */
export function decodeArray<T>(value: unknown, decode: (item: unknown) => T, what: string): T[] {
  if (!Array.isArray(value)) throw new DecodeError(`${what} is not an array`);
  return value.map((item: unknown) => decode(item));
}

/** Reads `value` as an object; `what` names it in every error a read throws. */
export function readObject(value: unknown, what: string): ObjectReader {
  if (!isObject(value)) throw new DecodeError(`${what} is not an object`);

  const fail = (key: string, expected: string): never => {
    throw new DecodeError(`${what}.${key} is not ${expected}`);
  };
  const string = (key: string): string => {
    const field = value[key];
    return typeof field === "string" ? field : fail(key, "a string");
  };
  const oneOf = <T extends string>(key: string, allowed: readonly T[]): T => {
    const field = string(key);
    return allowed.find((candidate) => candidate === field) ?? fail(key, allowed.join(" | "));
  };
  const boolean = (key: string): boolean => {
    const field = value[key];
    return typeof field === "boolean" ? field : fail(key, "a boolean");
  };
  const integer = (key: string): number => {
    const field = value[key];
    return isInteger(field) ? field : fail(key, "an integer");
  };

  return {
    string,
    nullableString: (key) => (value[key] === null ? null : string(key)),
    boolean,
    nullableBoolean: (key) => (value[key] === null ? null : boolean(key)),
    integer,
    nullableInteger: (key) => (value[key] === null ? null : integer(key)),
    oneOf,
    nullableOneOf: (key, allowed) => (value[key] === null ? null : oneOf(key, allowed)),
    array: (key, decode) => decodeArray(value[key], decode, `${what}.${key}`),
    object: (key) => readObject(value[key], `${what}.${key}`),
  };
}

/**
 * Lifts a body decoder to the `Response` decoder `apiFetch` takes. A body that
 * is not JSON at all — an HTML error page — throws from `json()` the same way a
 * wrong shape throws from `decode`.
 */
export const jsonDecoder =
  <T>(decode: (body: unknown) => T) =>
  async (response: Response): Promise<T> => {
    const body: unknown = await response.json();
    return decode(body);
  };
