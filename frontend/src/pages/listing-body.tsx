import { Inbox } from "lucide-react";
import type { ReactNode } from "react";

import type { ReadView } from "@/auth/use-gated-read";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";

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
  /** The rows themselves, beside the read they came from; called only with at least one row. */
  children: (rows: readonly R[], data: T) => ReactNode;
}

/** The listing's empty copy under a decorative icon; the copy alone is what a screen reader reads. */
function EmptyNote({ copy }: { copy: string }): ReactNode {
  return (
    <div
      className="flex flex-col items-center gap-2 py-8 text-center text-sm text-muted-foreground"
      data-testid="listing-empty"
    >
      <Inbox aria-hidden="true" className="size-8" />
      <p>{copy}</p>
    </div>
  );
}

/** How many placeholder rows stand in for the listing while its first read is in flight. */
const SKELETON_ROWS = 3;

/**
 * Skeleton rows for sighted users, and the listing's loading copy, visually
 * hidden, in a status region for screen readers, which skip the skeletons.
 */
function LoadingRows({ copy }: { copy: string }): ReactNode {
  return (
    <div role="status">
      <span className="sr-only">{copy}</span>
      <div aria-hidden="true" className="flex flex-col gap-2">
        {Array.from({ length: SKELETON_ROWS }, (_, row) => (
          <Skeleton className="h-9 w-full" key={row} />
        ))}
      </div>
    </div>
  );
}

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
      return <LoadingRows copy={loading} />;
    case "stale": {
      const kept = rows(view.data);
      return staleRows === "keep" && kept.length > 0 ? children(kept, view.data) : null;
    }
    case "ready": {
      const read = rows(view.data);
      return read.length > 0 ? children(read, view.data) : <EmptyNote copy={empty} />;
    }
  }
}

/**
 * The listing's Try again: offered only once the latest read was refused or
 * failed — a successful read is read again by the page's own controls — and
 * reading the current path again through `onReadAgain`. Every listing offers
 * this one control, the paged listing beside its pager.
 */
export function ReadAgain({
  disabled,
  onReadAgain,
  view,
}: {
  /** Holds the control still, as while an action answering onto the listing is in flight. */
  disabled: boolean;
  onReadAgain: () => void;
  view: ReadView<unknown>;
}): ReactNode {
  if (view.kind !== "failed" && view.kind !== "stale") return null;
  return (
    <Button
      className="ml-auto"
      disabled={disabled}
      onClick={onReadAgain}
      size="sm"
      variant="outline"
    >
      Try again
    </Button>
  );
}
