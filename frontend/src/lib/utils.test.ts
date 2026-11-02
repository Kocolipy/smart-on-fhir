import { readFileSync } from "node:fs";
import { join } from "node:path";

import { describe, expect, it } from "vitest";

import { cn } from "./utils";

// Every `text-<level>` the token file's `--text-<level>: <size>` tokens
// generate (its `--text-<level>--<property>` sub-keys excluded). cwd is the
// frontend root under vitest and in a Stryker sandbox alike.
const TYPE_SCALE_LEVELS = [
  ...readFileSync(join(process.cwd(), "src/index.css"), "utf8").matchAll(
    /^\s*--text-([a-z]+(?:-[a-z]+)*):/gm,
  ),
].map(([, level]) => `text-${String(level)}`);

describe("cn", () => {
  it("joins class names", () => {
    expect(cn("a", "b")).toBe("a b");
  });

  it("drops falsy values so a conditional class can be inlined", () => {
    const isActive = Boolean(0);

    expect(cn("a", isActive && "b", undefined, null, "c")).toBe("a c");
  });

  it("flattens arrays and object maps", () => {
    expect(cn(["a", "b"], { c: true, d: false })).toBe("a b c");
  });

  it("lets the later of two conflicting Tailwind utilities win", () => {
    // The point of twMerge over plain clsx: both classes set padding-inline,
    // and CSS source order — not argument order — would otherwise decide.
    expect(cn("px-4", "px-8")).toBe("px-8");
  });

  it("reads the type-scale levels out of the token file", () => {
    // A guard on the reading itself: an empty list would run no case below.
    expect(TYPE_SCALE_LEVELS).toEqual(expect.arrayContaining(["text-body-sm", "text-headline-lg"]));
  });

  // Each level is a font size, not a colour, so a caller's
  // `text-muted-foreground` must not drop it. The levels come from the token
  // file itself, so one added there and missed in utils.ts fails here.
  it.each(TYPE_SCALE_LEVELS)("keeps the type-scale level %s beside a text colour", (level) => {
    expect(cn(level, "text-muted-foreground")).toBe(`${level} text-muted-foreground`);
  });

  it("lets a later type-scale level replace an earlier font size", () => {
    expect(cn("text-sm", "text-body-lg")).toBe("text-body-lg");
  });

  it("keeps utilities that set different properties", () => {
    expect(cn("px-4", "py-8")).toBe("px-4 py-8");
  });

  it("returns an empty string when given nothing", () => {
    expect(cn()).toBe("");
  });
});
