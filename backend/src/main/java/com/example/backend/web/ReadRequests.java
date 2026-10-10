package com.example.backend.web;

import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * The one answer to what a read of a path is, for every filter-chain rule that permits one: the
 * application chain's own rules and the ones Epic Login adds to it.
 *
 * <p>A read is {@code GET}, and the {@code HEAD} the dispatcher answers with the same handler. A
 * rule for {@code GET} alone would leave {@code HEAD} to the chain's deny-all rule, refusing a
 * read the operation itself serves.
 */
public final class ReadRequests {

    private ReadRequests() {
    }

    /** A {@code GET} or {@code HEAD} of {@code path}. */
    public static RequestMatcher read(String path) {
        PathPatternRequestMatcher.Builder paths = PathPatternRequestMatcher.withDefaults();
        return new OrRequestMatcher(
                paths.matcher(HttpMethod.GET, path), paths.matcher(HttpMethod.HEAD, path));
    }
}
