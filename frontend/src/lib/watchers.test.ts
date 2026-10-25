import { describe, expect, it, vi } from "vitest";

import { watchers } from "./watchers";

describe("watchers", () => {
  it("tells every watcher when told something changed", () => {
    const set = watchers();
    const first = vi.fn();
    const second = vi.fn();
    set.watch(first);
    set.watch(second);

    set.changed();

    expect([first.mock.calls.length, second.mock.calls.length]).toEqual([1, 1]);
  });

  it("tells a watcher nothing once it stops watching", () => {
    const set = watchers();
    const watcher = vi.fn();
    set.watch(watcher)();

    set.changed();

    expect(watcher).not.toHaveBeenCalled();
  });

  it("hands every watcher what it was told changed", () => {
    const set = watchers<string>();
    const heard: string[] = [];
    set.watch((news) => heard.push(`first:${news}`));
    set.watch((news) => heard.push(`second:${news}`));

    set.changed("Patient-open");

    expect(heard).toEqual(["first:Patient-open", "second:Patient-open"]);
  });

  it("keeps each set's watchers to itself", () => {
    const watcher = vi.fn();
    watchers().watch(watcher);

    watchers().changed();

    expect(watcher).not.toHaveBeenCalled();
  });
});
