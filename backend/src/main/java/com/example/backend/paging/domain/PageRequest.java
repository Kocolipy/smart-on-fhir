package com.example.backend.paging.domain;

import java.math.BigInteger;

/**
 * One page of a paged read, as the {@code startIndex} and {@code count} parameters resolve to:
 * SCIM's collection query, the administrative projections and the audit listing all page by these
 * one set of rules (ADR 0017, D1 and D7).
 *
 * <p>RFC 7644 §3.4.2.4 asks a service provider to COERCE out-of-range paging
 * parameters rather than refuse them, and each coercion in {@link #of} is one of its rules. Every
 * read reaches a page only through it, so a page holds only what the rules produce: the
 * constructor refuses anything else. The rules live in a domain value object rather than in a
 * controller because the numbers are also what SCIM discovery advertises: {@link #MAX_COUNT} is
 * rendered into {@code filter.maxResults}, so the advertised ceiling and the enforced one are the
 * same constant and cannot drift.
 *
 * <p>In a module of its own because {@code scim} depends on {@code audit}: the audit listing could
 * not reach the rules in {@code scim} without a module cycle.
 *
 * @param startIndex one-based index of the first row to return, at least 1
 * @param count      how many rows to return, from 0 to {@link #MAX_COUNT}
 */
public record PageRequest(int startIndex, int count) {

    /** The page size a request that asked for none gets. */
    public static final int DEFAULT_COUNT = 100;

    /** The largest page this service will return, however large a count is asked for. */
    public static final int MAX_COUNT = 200;

    /** The first page at the default size: what an unparameterized collection GET means. */
    public static final PageRequest FIRST_PAGE =
            new PageRequest(1, DEFAULT_COUNT);

    /**
     * @throws IllegalArgumentException for a page the rules never produce: a {@code startIndex}
     *     below 1, or a {@code count} below 0 or above {@link #MAX_COUNT}
     */
    public PageRequest {
        if (startIndex < 1) {
            throw new IllegalArgumentException("A page starts at index 1 or later.");
        }
        if (count < 0 || count > MAX_COUNT) {
            throw new IllegalArgumentException("A page holds from 0 to " + MAX_COUNT + " rows.");
        }
    }

    /**
     * The page the given parameters resolve to, each absent value defaulted and each
     * out-of-range value coerced, however far out of range it is.
     *
     * <p>A {@code count} of zero survives coercion, because RFC 7644 gives it a
     * meaning: no rows, but {@code totalResults} still computed. It is
     * how a client asks "how many are there" without transferring any of them, so
     * silently turning it into the default page would answer a question nobody asked.
     *
     * @param startIndex the requested one-based index, or {@code null} when absent;
     *                   anything below 1 becomes 1, and anything above
     *                   {@link Integer#MAX_VALUE} becomes that
     * @param count      the requested page size, or {@code null} when absent;
     *                   negatives become 0 and anything above {@link #MAX_COUNT} is
     *                   capped
     */
    public static PageRequest of(PageParameter startIndex, PageParameter count) {
        int resolvedStart = startIndex == null
                ? 1
                : clamp(startIndex.value(), 1, Integer.MAX_VALUE);
        int resolvedCount = count == null
                ? DEFAULT_COUNT
                : clamp(count.value(), 0, MAX_COUNT);
        return new PageRequest(resolvedStart, resolvedCount);
    }

    private static int clamp(BigInteger value, int min, int max) {
        return value.max(BigInteger.valueOf(min)).min(BigInteger.valueOf(max)).intValueExact();
    }

    /** The zero-based offset the persistence adapter pages from. */
    public int offset() {
        return startIndex - 1;
    }

    /**
     * Whether this page of a read matching {@code totalResults} rows holds any row, and so whether
     * the read reads it at all. A {@code count} of zero holds none, and nor does a page starting
     * past the last row; each is answered with the total alone and no page statement. Every paged
     * read asks this, so none answers either case differently from another.
     */
    public boolean readsAny(long totalResults) {
        return count > 0 && offset() < totalResults;
    }
}
