/**
 * The one paged read every paged listing goes through: the Users and Groups
 * projections, a Group's members, a connector's tokens and the audit trail.
 *
 * A listing's API module declares the read with the paging vocabulary of
 * `@/lib/paging` — its query through `pagedPath`, its page through
 * `pageDecoder` — and the listing hands {@link usePagedRead} that declaration,
 * its own arguments and its page size, and nothing about paging besides. The
 * hook holds where the listing stands, sends the gated read, and hands
 * `PagedListing` everything it renders: the view, the move, the page size and
 * the read again.
 */

import { useState } from "react";

import type { Permission } from "@/auth/api";
import type { ReadDeclaration } from "@/auth/declarations";
import type { ReadRequirement } from "@/auth/permissions";
import { useGatedRead, type ReadView } from "@/auth/use-gated-read";
import type { Page, PageAt } from "@/lib/paging";

/**
 * A paged read's declaration: a gated read answering a {@link Page}, whose last
 * argument is the {@link PageAt} the hook asks for and whose others are the
 * listing's own (a Group's id, a search, a filter set).
 */
export type PagedDeclaration<
  R,
  A extends readonly unknown[],
  P extends ReadRequirement = Permission,
> = ReadDeclaration<Page<R>, [...A, at: PageAt], false, P>;

export interface PagedRead<R> {
  /** What the listing renders. */
  view: ReadView<Page<R>>;
  /** Reads the page `startIndex` starts. */
  move: (startIndex: number) => void;
  /** How many rows a page asks for, and so how far Previous moves back. */
  pageSize: number;
  /** Reads the page shown again: the gated read's own `reload`. */
  reload: () => Promise<void>;
  /** Replaces the page held, from a response that states a row of it, as the gated read's `update`. */
  update: (next: (page: Page<R> | null) => Page<R> | null) => void;
}

/**
 * Reads `declaration` a page of `pageSize` rows at a time, for the listing's
 * own `args`, from the first page. A change of `args` — a new search, sort or
 * filter set — starts again from the first page, in the same render, so the
 * page it had moved to is never read for the new arguments.
 */
export function usePagedRead<R, A extends readonly unknown[], P extends ReadRequirement>(
  declaration: PagedDeclaration<R, A, P>,
  { args, failureMessage, pageSize }: { args: A; failureMessage: string; pageSize: number },
): PagedRead<R> {
  // The declaration with the listing's own arguments bound, addressed by page alone.
  const pathAt = (at: PageAt) => declaration.path(...args, at);
  // The first page's path names the listing's arguments: a new one is a new listing.
  const listing = pathAt({ count: pageSize, startIndex: 1 });
  const [position, setPosition] = useState({ listing, startIndex: 1 });
  // React discards this render and renders again at once from the first page.
  if (position.listing !== listing) setPosition({ listing, startIndex: 1 });
  const { startIndex } = position;

  const read = useGatedRead(
    { decode: declaration.decode, path: pathAt, permission: declaration.permission },
    { args: [{ count: pageSize, startIndex }], failureMessage },
  );
  return {
    move: (next) => setPosition({ listing, startIndex: next }),
    pageSize,
    reload: read.reload,
    update: read.update,
    view: read.view,
  };
}
