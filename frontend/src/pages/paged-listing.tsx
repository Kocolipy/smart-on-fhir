import type { ReactNode } from "react";

import type { ReadView } from "@/auth/use-gated-read";
import { ListingBody, ReadAgain } from "@/pages/listing-body";
import { emptyCopyOf } from "@/pages/page-empty-copy";
import type { Page } from "@/lib/paging";
import type { PagedRead } from "@/pages/paged-read";
import { Pager } from "@/pages/pager";

/** The copy a paged listing shows, all of it the page's own. */
interface PagedListingCopy {
  /** The line shown while the first read is in flight. */
  loading: string;
  /** The line shown when the first page has no rows; a later empty page says it emptied. */
  empty: string;
  /** The pager's accessible name. */
  pagerLabel: string;
  /** What the rows are, lower-case and plural, as the pager's position line names them: "Showing 1 to 20 of 45 users". */
  noun: string;
}

/**
 * The page the latest read answered for the current path, or `null` while
 * there is none: a read still loading, or one that failed with nothing kept,
 * names no range it has not seen. A `stale` read keeps the page it last read
 * for this path — its rows are hidden, but where they sat is still known.
 */
function readPageOf<R>(view: ReadView<Page<R>>): Page<R> | null {
  return view.kind === "ready" || view.kind === "stale" ? view.data : null;
}

const rowsOf = <R,>(page: Page<R>) => page.rows;

/**
 * The one paged listing every paged read renders — the Users and Groups
 * projections, a Group's members, a connector's tokens and the audit trail —
 * from the {@link usePagedRead} that reads it, so these rules are decided
 * once, here, rather than by each page:
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
 * - **Try again** ({@link ReadAgain}) reads the current path again — the same
 *   page, search, sort and filters. A page that offers its own read-again
 *   control whatever the read came to, as the audit trail's Refresh, passes
 *   `readAgain={false}`, and the listing offers none.
 *
 * A session without the read's Permission sees none of it: nothing was read,
 * and reading again could read nothing.
 *
 * The page keeps everything around the listing: its filters, its search box,
 * its toolbar, and the table or list the rows render as.
 *
 * @see usePagedRead
 */
export function PagedListing<R>({
  children,
  copy,
  disabled = false,
  read: { move, pageSize, reload, view },
  readAgain = true,
}: {
  /** The rows of the page shown, which has at least one. */
  children: (page: Page<R>) => ReactNode;
  copy: PagedListingCopy;
  /** Holds the pager and the read-again control still, as while an action answering onto the page is in flight. */
  disabled?: boolean;
  read: PagedRead<R>;
  /** Whether the listing offers Try again; `false` for a page offering its own read-again control. */
  readAgain?: boolean;
}): ReactNode {
  if (view.kind === "not-permitted") return null;
  const page = readPageOf(view);
  return (
    <>
      {/* The listing scrolls horizontally on its own, scoped to the rows: an
          overflow-x-auto on the container around it (a CardContent) would make
          that a scroll container with no top padding, clipping the focus
          ring/shadow of what sits above the listing, as a search box. */}
      <div className="overflow-x-auto">
        <ListingBody
          empty={emptyCopyOf(page, copy.empty)}
          loading={copy.loading}
          rows={rowsOf}
          staleRows="hide"
          view={view}
        >
          {(_, data) => children(data)}
        </ListingBody>
      </div>
      {/* The pager and Try again, each only where it applies. */}
      <div className="flex items-end justify-between gap-4">
        {page === null ? null : (
          <Pager
            disabled={disabled}
            label={copy.pagerLabel}
            noun={copy.noun}
            onMove={move}
            page={page}
            pageSize={pageSize}
          />
        )}
        {readAgain ? (
          <ReadAgain disabled={disabled} onReadAgain={() => void reload()} view={view} />
        ) : null}
      </div>
    </>
  );
}
