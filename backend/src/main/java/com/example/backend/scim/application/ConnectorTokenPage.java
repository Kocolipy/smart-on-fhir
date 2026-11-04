package com.example.backend.scim.application;

import java.util.List;

/**
 * One page of a connector's active tokens, as an Admin reads them: newest issue first, with how
 * many are active in all (ADR 0017). Active means the token still authenticates — not revoked and
 * not yet expired — so a rotated token inside its overlap window is on it, marked rotated, and a
 * revoked, expired or past-overlap one is not, though its row is kept.
 *
 * <p>It is also the shape the endpoint puts on the wire: the openapi {@code ConnectorTokenPage}
 * schema is this record's documented counterpart.
 *
 * @param totalResults how many of the connector's tokens are active, whatever the page
 * @param startIndex   the one-based position of the page's first token
 * @param tokens       the page's tokens, in order; empty past the last one
 */
public record ConnectorTokenPage(
        long totalResults, int startIndex, List<ConnectorTokenSummary> tokens) {

    public ConnectorTokenPage {
        tokens = List.copyOf(tokens);
    }
}
