/**
 * A set of watchers for a module-level store, shaped for React's
 * `useSyncExternalStore`: `watch` is its `subscribe`, and the store calls
 * `changed` whenever what its snapshot reads would change. A set made with a
 * payload type hands each watcher what `changed` is told — the FHIRcast
 * connection's followers, each handed every event the hub sends.
 */
export interface Watchers<Payload = void> {
  /** Calls `watcher` whenever the store changes; answers how to stop. */
  watch(watcher: (payload: Payload) => void): () => void;
  /** Tells every watcher the store changed, handing each `payload`. */
  changed(payload: Payload): void;
}

/** A new, empty set of watchers. */
export function watchers<Payload = void>(): Watchers<Payload> {
  const set = new Set<(payload: Payload) => void>();
  return {
    changed: (payload) => {
      for (const watcher of set) watcher(payload);
    },
    watch: (watcher) => {
      set.add(watcher);
      return () => set.delete(watcher);
    },
  };
}
