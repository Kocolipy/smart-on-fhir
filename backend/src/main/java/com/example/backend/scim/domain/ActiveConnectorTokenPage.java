package com.example.backend.scim.domain;

import java.util.List;

/**
 * One page of a connector's active tokens, as the token store reads it: the page's tokens, newest
 * issue first, and how many active tokens the connector holds in all.
 *
 * @param totalResults how many of the connector's tokens are active, whatever the page
 * @param tokens       the page's tokens, in order; empty past the last one
 */
public record ActiveConnectorTokenPage(long totalResults, List<ScimConnectorToken> tokens) {

    public ActiveConnectorTokenPage {
        tokens = List.copyOf(tokens);
    }
}
