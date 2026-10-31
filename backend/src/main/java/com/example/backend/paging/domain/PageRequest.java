package com.example.backend.paging.domain;

/**
 * One page of a paged read, as the {@code startIndex} and {@code count} parameters resolve to:
 * SCIM's collection query, the administrative projections and the audit listing all page by these
 * one set of rules (ADR 0017, ADR 0018).
 *
 * <p>RFC 7644 §3.4.2.4 asks a service provider to COERCE out-of-range paging
 * parameters rather than refuse them, and each coercion below is one of its rules.
 * They live in a domain value object rather than in a controller because the
 * numbers are also what SCIM discovery advertises: {@link #MAX_COUNT} is rendered into
 * {@code filter.maxResults}, so the advertised ceiling and the enforced one are the
 * same constant and cannot drift.
 *
 * <p>In a module of its own because {@code scim} depends on {@code audit}: the audit listing could
 * not reach the rules in {@code scim} without a module cycle.
 *
 * @param startIndex one-based index of the first row to return
 * @param count      how many rows to return, possibly zero
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
     * The page the given parameters resolve to, each absent value defaulted and each
     * out-of-range value coerced.
     *
     * <p>A {@code count} of zero survives coercion, because RFC 7644 gives it a
     * meaning: no rows, but {@code totalResults} still computed. It is
     * how a client asks "how many are there" without transferring any of them, so
     * silently turning it into the default page would answer a question nobody asked.
     *
     * @param startIndex the requested one-based index, or {@code null} when absent;
     *                   anything below 1 becomes 1
     * @param count      the requested page size, or {@code null} when absent;
     *                   negatives become 0 and anything above {@link #MAX_COUNT} is
     *                   capped
     */
    public static PageRequest of(Integer startIndex, Integer count) {
        int resolvedStart = startIndex == null || startIndex < 1 ? 1 : startIndex;
        int resolvedCount = count == null ? DEFAULT_COUNT : Math.clamp(count, 0, MAX_COUNT);
        return new PageRequest(resolvedStart, resolvedCount);
    }

    /** The zero-based offset the persistence adapter pages from. */
    public int offset() {
        return startIndex - 1;
    }
}
