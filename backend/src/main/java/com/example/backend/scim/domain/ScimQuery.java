package com.example.backend.scim.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * One collection query — a filter, a sort and a page — over one or both resource types.
 *
 * <p>The same value whether it arrived as query parameters on a {@code GET} or as the body of a
 * {@code POST .search}, which is what makes the two paths return the same resources: there is
 * one query, and two ways of writing it down.
 *
 * @param types  the resource types being queried; both for a base search
 * @param filter the filter, or {@code null} for every resource
 * @param sort   the requested order, or {@code null} for this service's default order
 * @param page   which slice of the ordered result to return
 */
public record ScimQuery(
        Set<ScimResourceType> types, ScimFilter filter, ScimSort sort, ScimPageRequest page) {

    public ScimQuery {
        if (types == null || types.isEmpty()) {
            throw new IllegalArgumentException("a query names at least one resource type");
        }
        types = Set.copyOf(types);
        page = page == null ? ScimPageRequest.FIRST_PAGE : page;
    }

    /**
     * The query the textual parameters describe.
     *
     * @throws InvalidScimFilterException when the filter is not one this service evaluates
     * @throws InvalidScimQueryException  when the sort is not
     */
    public static ScimQuery of(
            Set<ScimResourceType> types,
            String filter,
            String sortBy,
            String sortOrder,
            ScimPageRequest page) {
        ScimFilter parsed = filter == null ? null : ScimFilterParser.parse(filter, types);
        return new ScimQuery(types, parsed, ScimSort.of(sortBy, sortOrder, types), page);
    }

    /**
     * This query over only those of its types that are also in {@code permitted} — how a base
     * search returns only what the token may read. The filter and sort stay as parsed against
     * every type the request named, so a path valid for a type the token may not read still
     * means "no value" rather than becoming a malformed filter: the answer is the one the
     * service would give if the unreadable type held no resources.
     *
     * @throws IllegalArgumentException when no type is left, which the caller's authorization
     *                                  rule exists to prevent
     */
    public ScimQuery restrictedTo(Set<ScimResourceType> permitted) {
        Set<ScimResourceType> kept = new HashSet<>(types);
        kept.retainAll(permitted);
        return new ScimQuery(kept, filter, sort, page);
    }

    /**
     * Whether this query names, in its filter or its sort, a path whose value depends on who asks
     * and where: {@code externalId}, which is the calling connector's own alias, or a
     * {@code $ref} or {@code meta.location}, which are built from the SCIM base URI the connector
     * called. Such a query is evaluated only for a connector
     * ({@link ScimQueryRepository}); a caller that is none — an administrative read — is refused
     * it rather than having it evaluated against nothing ({@link AdministrativeQueryRepository}).
     */
    public boolean needsConnector() {
        return (filter != null && filter.testsAnyPath(ScimQuery::connectorScoped))
                || (sort != null && connectorScoped(sort.attribute().path()));
    }

    /**
     * The four paths of {@link ScimFilterPath} whose value is the calling connector's or its base
     * URI's: {@code externalId}, a {@code $ref} of either multi-valued attribute, and
     * {@code meta.location}. A closed set stated as identity comparisons rather than as a
     * {@code switch}, which would compile to a null check and an unmatched-value branch that no
     * caller can reach — a path always arrives from the parser or from {@link ScimSort} — and so
     * to behavior no test can state.
     */
    private static boolean connectorScoped(ScimFilterPath path) {
        return path == ScimFilterPath.EXTERNAL_ID
                || path == ScimFilterPath.GROUPS_REF
                || path == ScimFilterPath.MEMBERS_REF
                || path == ScimFilterPath.META_LOCATION;
    }

    /**
     * What one query found: the total that matched, and the identities of the page, in order.
     *
     * @param totalResults how many resources matched, irrespective of the page
     * @param hits         the page's resources, in the query's order
     */
    public record Result(long totalResults, List<Hit> hits) {

        public Result {
            hits = List.copyOf(hits);
        }

        /** The ids of the hits of one resource type, in order. */
        public List<UUID> idsOf(ScimResourceType type) {
            return hits.stream().filter(hit -> hit.type() == type).map(Hit::id).toList();
        }
    }

    /** One matched resource, identified. */
    public record Hit(ScimResourceType type, UUID id) {
    }
}
