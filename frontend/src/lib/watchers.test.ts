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

  it("keeps each set's watchers to itself", () => {
    const watcher = vi.fn();
    watchers().watch(watcher);

    watchers().changed();

    expect(watcher).not.toHaveBeenCalled();
  });
});
