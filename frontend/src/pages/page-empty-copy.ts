/**
 * The empty line for a page of a paged read. A page that is not the first says
 * only that it emptied: a row removed between the read that counted the listing
 * and the read that fetched this page can leave the page shown with none, while
 * the listing is not empty at all. Only the first page, or no page read yet, may
 * make the claim of absence in the listing's own copy.
 */
export function emptyCopyOf(page: { startIndex: number } | null, listingCopy: string): string {
  return page !== null && page.startIndex > 1
    ? "This page is now empty. Use Previous to go back."
    : listingCopy;
}
