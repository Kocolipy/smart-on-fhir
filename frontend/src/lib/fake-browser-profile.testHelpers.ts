/**
 * The browser profile the FHIRcast module keeps its dynamic client in, faked
 * at its two browser APIs: an `indexedDB` holding its databases' object
 * stores in memory, as strict as a browser's about a store no upgrade made
 * and a transaction's mode, and a Web Locks `LockManager` that runs the tasks
 * under one name one at a time. happy-dom has neither. The fake hub installs
 * a fresh one for every hub (`fake-hub.testHelpers.ts`).
 */
import { vi } from "vitest";

type Handler = (() => void) | null;

/** An `IDBRequest` as far as the key module reads one: its result, then `onsuccess`, or `onerror`. */
class FakeRequest<T> {
  result: T | undefined;
  error: DOMException | null = null;
  onsuccess: Handler = null;
  onerror: Handler = null;
  onupgradeneeded: Handler = null;

  /** Settles on a microtask, never a timer, so a fake clock stays where a test put it. */
  settle(result: T | undefined, refused: boolean) {
    queueMicrotask(() => {
      if (refused) {
        this.error = new DOMException("refused", "UnknownError");
        this.onerror?.();
        return;
      }
      this.result = result;
      this.onsuccess?.();
    });
  }
}

/** Runs each task under a name only once every task before it under that name has settled. */
export function fakeLocks() {
  const queues = new Map<string, Promise<unknown>>();
  const requested: string[] = [];
  return {
    /** Every lock name a task was run under, in order. */
    requested,
    request<T>(name: string, task: () => Promise<T>): Promise<T> {
      requested.push(name);
      const before = queues.get(name) ?? Promise.resolve();
      const running = before.then(() => task());
      queues.set(
        name,
        running.catch(() => undefined),
      );
      return running;
    },
  };
}

/** How the fake profile behaves: by default its IndexedDB answers every request. */
export interface ProfileOptions {
  /** Whether opening its IndexedDB fails, as a profile that refuses storage does. */
  refusesStorage?: boolean;
  /** How a write's transaction fails: with an error, or aborted. */
  failsWrites?: "error" | "abort";
}

export interface FakeProfile {
  /** The records of the object store `store` of the database `database`, by key, as written; `undefined` for none made. */
  store: (database: string, store: string) => Map<IDBValidKey, unknown> | undefined;
  /** How many connections to its databases are open now. */
  openConnections: () => number;
  /** The profile's Web Locks, for a navigator stub to carry. */
  locks: ReturnType<typeof fakeLocks>;
}

/**
 * A fresh browser profile: its `indexedDB` stubbed for the rest of the test,
 * and its locks, which the caller hands the navigator it stubs.
 */
export function fakeBrowserProfile(options: ProfileOptions = {}): FakeProfile {
  /** database -> its object stores -> their records. */
  const databases = new Map<string, Map<string, Map<IDBValidKey, unknown>>>();
  let open = 0;

  /** A connection to `stores`, as a browser hands one over. */
  function connection(stores: Map<string, Map<IDBValidKey, unknown>>) {
    const storeNamed = (name: string) => {
      const found = stores.get(name);
      if (found === undefined) throw new DOMException(`no object store ${name}`, "NotFoundError");
      return found;
    };
    return {
      close: () => {
        open -= 1;
      },
      createObjectStore: (name: string) => {
        stores.set(name, new Map());
      },
      transaction: (name: string, mode: string) => {
        // A transaction over a store no upgrade made, or in no mode IndexedDB
        // names, fails as a browser's does.
        storeNamed(name);
        if (mode !== "readonly" && mode !== "readwrite") throw new TypeError(`no mode ${mode}`);
        /** A write: `apply` once the transaction completes, unless the profile fails its writes. */
        const write = (apply: () => void) => {
          if (mode === "readonly") throw new DOMException("read-only", "ReadOnlyError");
          queueMicrotask(() => {
            if (options.failsWrites === "error") {
              transaction.error = new DOMException("refused", "QuotaExceededError");
              transaction.onerror?.();
            } else if (options.failsWrites === "abort") {
              transaction.onabort?.();
            } else {
              apply();
              transaction.oncomplete?.();
            }
          });
          return new FakeRequest<undefined>();
        };
        const transaction = {
          error: null as DOMException | null,
          objectStore: (named: string) => {
            const from = storeNamed(named);
            return {
              delete: (key: IDBValidKey) => write(() => from.delete(key)),
              get: (key: IDBValidKey) => {
                const request = new FakeRequest<unknown>();
                request.settle(from.get(key), false);
                return request;
              },
              put: (value: unknown, key: IDBValidKey) => write(() => from.set(key, value)),
            };
          },
          onabort: null as Handler,
          oncomplete: null as Handler,
          onerror: null as Handler,
        };
        return transaction;
      },
    };
  }

  vi.stubGlobal("indexedDB", {
    open: (name: string) => {
      const request = new FakeRequest<ReturnType<typeof connection>>();
      queueMicrotask(() => {
        if (options.refusesStorage === true) {
          request.settle(undefined, true);
          return;
        }
        const made = databases.has(name);
        const stores = databases.get(name) ?? new Map<string, Map<IDBValidKey, unknown>>();
        databases.set(name, stores);
        open += 1;
        request.result = connection(stores);
        if (!made) request.onupgradeneeded?.();
        request.settle(request.result, false);
      });
      return request;
    },
  });
  return {
    locks: fakeLocks(),
    openConnections: () => open,
    store: (database, store) => databases.get(database)?.get(store),
  };
}
