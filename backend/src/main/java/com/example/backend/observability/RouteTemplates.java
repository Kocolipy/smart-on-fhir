package com.example.backend.observability;

import jakarta.servlet.http.HttpServletRequest;
import java.util.function.Supplier;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.ServletRequestPathUtils;

/**
 * The route TEMPLATE a request addresses ({@code /api/admin/accounts/{id}}), for a record
 * written before — or instead of — the dispatcher matching it.
 *
 * <p>The security chain refuses a request before any handler mapping runs, so the template
 * Spring would have matched is not on the request yet. A refusal record still needs to say
 * which operation was refused, and the raw path cannot stand in for it: a path carries the
 * ids and the filter text a caller put there. So the template is looked up here, by asking
 * each of the application's request mappings whether it would match, without dispatching and
 * without leaving anything behind on the request — the request record still files a
 * refused request under {@code unmatched}, as it always has.
 *
 * <p>Once the dispatcher has matched, its own answer is used unchanged.
 */
public class RouteTemplates {

    private final Supplier<RequestMappingHandlerMapping> mappings;

    /**
     * @param mappings the application's {@code requestMappingHandlerMapping}, or a supplier of
     *     {@code null} in a context that has none — where every route is {@code unmatched}
     */
    public RouteTemplates(Supplier<RequestMappingHandlerMapping> mappings) {
        this.mappings = mappings;
    }

    /** The template the request matches, or {@code unmatched} when no handler would serve it. */
    public String of(HttpServletRequest request) {
        if (request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)
                instanceof String matched) {
            return matched;
        }
        RequestMappingHandlerMapping mapping = mappings.get();
        if (mapping == null) {
            return RequestIdFilter.UNMATCHED;
        }
        // Path-pattern matching reads a parsed path from the request. Parsed here only when
        // nothing upstream has, and cleared again, so the lookup leaves the request as it was.
        boolean parsedHere = !ServletRequestPathUtils.hasParsedRequestPath(request);
        if (parsedHere) {
            ServletRequestPathUtils.parseAndCache(request);
        }
        try {
            return template(best(mapping, request));
        } finally {
            if (parsedHere) {
                ServletRequestPathUtils.clearParsedRequestPath(request);
            }
        }
    }

    private static RequestMappingInfo best(
            RequestMappingHandlerMapping mapping, HttpServletRequest request) {
        RequestMappingInfo best = null;
        for (RequestMappingInfo info : mapping.getHandlerMethods().keySet()) {
            RequestMappingInfo matching = info.getMatchingCondition(request);
            if (matching != null && (best == null || matching.compareTo(best, request) < 0)) {
                best = matching;
            }
        }
        return best;
    }

    private static String template(RequestMappingInfo best) {
        if (best == null || best.getPathPatternsCondition() == null) {
            return RequestIdFilter.UNMATCHED;
        }
        return best.getPathPatternsCondition().getFirstPattern().getPatternString();
    }
}
