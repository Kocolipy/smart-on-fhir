package com.example.backend.auth.application;

import java.util.List;

/**
 * One page of the Groups projection, as the Accounts page reads it: the Groups a search matched,
 * in normalized {@code displayName} order, a page at a time, and how many it matched in all.
 *
 * <p>The shape the endpoint puts on the wire, serialised as-is as {@link GroupMemberPage} is: the
 * openapi {@code GroupPage} schema is this record's documented counterpart.
 *
 * @param totalResults how many Groups the search matched — every Group when there was none —
 *                     whatever the page
 * @param startIndex   the one-based position of the page's first Group
 * @param groups       the page's Groups, in order; empty past the last one, and when none matched
 */
public record GroupPage(long totalResults, int startIndex, List<GroupSummary> groups) {

    public GroupPage {
        groups = List.copyOf(groups);
    }
}
