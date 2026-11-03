import type { ReactNode } from "react";

import type { ReadView } from "@/auth/use-gated-read";
import { Button } from "@/components/ui/button";
import { ListingBody } from "@/pages/listing-body";
import { emptyCopyOf } from "@/pages/page-empty-copy";
import { Pager, type PagedRows } from "@/pages/pager";

/** Where a paged read's data sits among every row it matched, as the backend states it. */
export interface PageCount {
  startIndex: number;
  totalResults: number;
}

/**
 * When the listing offers to read its current page again:
 *
 * - `"after-failure"` — "Try again", only once the latest read was refused or
 *   failed: a successful read is read again by the page's own controls;
 * - `"always"` — "Refresh", whatever the read came to: for a listing whose
 *   rows change under it with nothing on the page to say so, as the audit trail's.
 */
export type ReadAgain = "after-failure" | "always";

/** The copy a paged listing shows, all of it the page's own. */
interface PagedListingCopy {
  /** The line shown while the first read is in flight. */
  loading: string;
  /** The line shown when the first page has no rows; a later empty page says it emptied. */
  empty: string;
  /** The pager's accessible name. */
  pagerLabel: string;
  /** What the rows are, as the pager's position line names them: "Users 1–20 of 45". */
  noun: string;
}

/**
 * The page the latest read answered for the current path, or `null` while
 * there is none: a read still loading, or one that failed with nothing kept,
 * names no range it has not seen. A `stale` read keeps the page it last read
 * for this path — its rows are hidden, but where they sat is still known.
 */
function readPageOf<T>(view: ReadView<T>): T | null {
  return view.kind === "ready" || view.kind === "stale" ? view.data : null;
}

/** Whether the read-again control is shown, as {@link ReadAgain} names its rule. */
const offersReadAgain = (view: ReadView<unknown>, readAgain: ReadAgain): boolean =>
  readAgain === "always" || view.kind === "failed" || view.kind === "stale";

/**
 * The one paged listing every paged read renders — the Users and Groups
 * projections, a Group's members and the audit trail — so these rules are
 * decided once, here, rather than by each page:
 *
 * - **The body** is the shared {@link ListingBody}, and a re-read that failed
 *   hides the rows last read (`staleRows="hide"`): a paged listing's rows are
 *   either acted on or held as evidence, and rows the listing could not
 *   confirm are neither.
 * - **The empty copy** is the listing's own on the first page, and says the
 *   page emptied on a later one ({@link emptyCopyOf}).
 * - **The pager** shows for the page the latest read answered for the current
 *   path, `ready` or `stale` alike, and for nothing else: never while a first
 *   read is in flight, nor once it failed with nothing read.
 * - **Read again** is offered as `readAgain` says, and reads the current path
 *   again — the same page, search, sort and filters — through `onReadAgain`.
 *
 * A session without the read's Permission sees none of it: nothing was read,
 * and reading again could read nothing.
 *
 * The page keeps everything around the listing: its filters, its search box,
 * its toolbar, and the table or list the rows render as.
 */
export function PagedListing<T extends PageCount, R>({
  children,
  copy,
  disabled = false,
  onMove,
  onReadAgain,
  pageSize,
  readAgain,
  rows,
  view,
}: {
  /** The rows of the page shown, which has at least one. */
  children: (page: PagedRows<R>) => ReactNode;
  copy: PagedListingCopy;
  /** Holds the pager and the read-again control still, as while an action answering onto the page is in flight. */
  disabled?: boolean;
  onMove: (startIndex: number) => void;
  /** Reads the current path again: the read's own `reload`. */
  onReadAgain: () => void;
  pageSize: number;
  readAgain: ReadAgain;
  /** The rows in the read's data. */
  rows: (data: T) => readonly R[];
  view: ReadView<T>;
}): ReactNode {
  if (view.kind === "not-permitted") return null;
  const read = readPageOf(view);
  const pager =
    read === null ? null : (
      <Pager
        disabled={disabled}
        label={copy.pagerLabel}
        noun={copy.noun}
        onMove={onMove}
        page={{ ...read, rows: rows(read) }}
        pageSize={pageSize}
      />
    );
  const readAgainControl = offersReadAgain(view, readAgain) ? (
    <Button
      className="ml-auto"
      disabled={disabled}
      onClick={onReadAgain}
      size="sm"
      variant="outline"
    >
      {readAgain === "always" ? "Refresh" : "Try again"}
    </Button>
  ) : null;
  return (
    <>
      {/* The listing scrolls horizontally on its own, scoped to the rows: an
          overflow-x-auto on the container around it (a CardContent) would make
          that a scroll container with no top padding, clipping the focus
          ring/shadow of what sits above the listing, as a search box. */}
      <div className="overflow-x-auto">
        <ListingBody
          empty={emptyCopyOf(read, copy.empty)}
          loading={copy.loading}
          rows={rows}
          staleRows="hide"
          view={view}
        >
          {(listed, data) => children({ ...data, rows: listed })}
        </ListingBody>
      </div>
      {/* The pager and the read-again control, each only where it applies. */}
      <div className="flex items-end justify-between gap-4">
        {pager}
        {readAgainControl}
      </div>
    </>
  );
}
