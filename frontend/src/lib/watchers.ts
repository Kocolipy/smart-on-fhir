/**
 * A set of watchers for a module-level store, shaped for React's
 * `useSyncExternalStore`: `watch` is its `subscribe`, and the store calls
 * `changed` whenever what its snapshot reads would change.
 */
export interface Watchers {
  /** Calls `watcher` whenever the store changes; answers how to stop. */
  watch(watcher: () => void): () => void;
  /** Tells every watcher the store changed. */
  changed(): void;
}

/** A new, empty set of watchers. */
export function watchers(): Watchers {
  const set = new Set<() => void>();
  return {
    changed: () => {
      for (const watcher of set) watcher();
    },
    watch: (watcher) => {
      set.add(watcher);
      return () => set.delete(watcher);
    },
  };
}
