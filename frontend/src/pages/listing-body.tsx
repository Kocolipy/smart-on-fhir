import type { ReactNode } from "react";

import type { ReadView } from "@/auth/use-gated-read";

/**
 * What a page shows after a refresh fails: the rows last read, under the
 * error line, or nothing until a read succeeds again. Every listing page names
 * its choice; there is no default.
 */
export type StaleRows = "keep" | "hide";

interface ListingBodyProps<T, R> {
  view: ReadView<T>;
  /** The rows in the read's data — the data itself, for a plain list. */
  rows: (data: T) => readonly R[];
  /** The line shown while the first read is in flight. */
  loading: string;
  /** The line shown when a successful read has no rows. */
  empty: string;
  staleRows: StaleRows;
  /** The rows themselves; called only with at least one. */
  children: (rows: readonly R[]) => ReactNode;
}

const NOTE_CLASS = "text-sm text-muted-foreground";

/**
 * The one listing body every listing page renders: loading, empty and failed
 * look the same on every page. A failure is never shown as empty or as a load
 * still under way — the body is blank, and the page's error line says what
 * went wrong — and a session without the read's Permission sees nothing at all.
 */
export function ListingBody<T, R>({
  children,
  empty,
  loading,
  rows,
  staleRows,
  view,
}: ListingBodyProps<T, R>): ReactNode {
  switch (view.kind) {
    case "not-permitted":
    case "failed":
      return null;
    case "loading":
      return <p className={NOTE_CLASS}>{loading}</p>;
    case "stale": {
      const kept = rows(view.data);
      return staleRows === "keep" && kept.length > 0 ? children(kept) : null;
    }
    case "ready": {
      const read = rows(view.data);
      return read.length > 0 ? children(read) : <p className={NOTE_CLASS}>{empty}</p>;
    }
  }
}
