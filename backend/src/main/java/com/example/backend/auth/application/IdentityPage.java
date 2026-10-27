package com.example.backend.auth.application;

import java.util.List;

/**
 * One page of the Users projection, as the Accounts page reads it: the Users a search matched, in
 * normalized {@code userName} order, a page at a time, and how many it matched in all.
 *
 * <p>The shape the endpoint puts on the wire, serialised as-is as {@link GroupMemberPage} is: the
 * openapi {@code AccountPage} schema is this record's documented counterpart. Each row is an
 * {@link IdentitySummary}, which has no field a password hash could go in.
 *
 * @param totalResults how many Users the search matched — every User when there was none —
 *                     whatever the page
 * @param startIndex   the one-based position of the page's first User
 * @param users        the page's Users, in order; empty past the last one, and when none matched
 */
public record IdentityPage(long totalResults, int startIndex, List<IdentitySummary> users) {

    public IdentityPage {
        users = List.copyOf(users);
    }
}
