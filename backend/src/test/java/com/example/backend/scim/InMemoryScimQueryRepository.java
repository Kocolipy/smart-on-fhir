package com.example.backend.scim;

import com.example.backend.scim.domain.AdministrativeQueryRepository;
import com.example.backend.scim.domain.ConnectorScopedQueryException;
import com.example.backend.scim.domain.ScimFilter;
import com.example.backend.scim.domain.ScimFilterPath;
import com.example.backend.scim.domain.ScimQuery;
import com.example.backend.scim.domain.ScimQueryRepository;
import com.example.backend.scim.domain.ScimResourceType;
import com.example.backend.scim.domain.ScimUser;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * A {@link ScimQueryRepository} over the in-memory User and Group repositories, for unit tests
 * of the use cases.
 *
 * <p>Evaluates only the unfiltered, unsorted query — the default order and the page — and, for an
 * administrative caller, the two filters an administrative read builds as values: direct
 * membership of one Group, and a name search. Other filtering and sorting are the SQL
 * translation's job and are tested against SQL Server, so this fake refuses them rather than
 * carrying a second evaluator that could agree with a unit test and disagree with the database —
 * over where a missing value sorts, or over SQL Server's trailing-space-insensitive equality on
 * free text, which no evaluator here has to imitate. The name search is answered as a plain
 * case-insensitive substring match, which is what the translation's {@code LIKE} over lower-cased
 * text gives for the plain names unit tests search; what it gives for a wildcard, a quote or a
 * mixed-case name is {@code AdministrativeListingIntegrationTests}' to say, against SQL Server.
 */
public final class InMemoryScimQueryRepository
        implements ScimQueryRepository, AdministrativeQueryRepository {

    private final InMemoryScimUserRepository users;

    private final InMemoryScimGroupRepository groups;

    /** How many queries were run, so a test can assert a refused request ran none. */
    private int queries;

    public InMemoryScimQueryRepository(
            InMemoryScimUserRepository users, InMemoryScimGroupRepository groups) {
        this.users = users;
        this.groups = groups;
    }

    public int queries() {
        return queries;
    }

    @Override
    public ScimQuery.Result query(ScimQuery query, UUID connectorId, String baseUri) {
        if (query.filter() != null || query.sort() != null) {
            throw new UnsupportedOperationException(
                    "filtering and sorting are tested against SQL Server");
        }
        queries++;
        List<ScimQuery.Hit> all = new ArrayList<>();
        if (query.types().contains(ScimResourceType.USER)) {
            users.inQueryOrder()
                    .forEach(user -> all.add(new ScimQuery.Hit(ScimResourceType.USER, user.id())));
        }
        if (query.types().contains(ScimResourceType.GROUP)) {
            groups.inQueryOrder()
                    .forEach(group -> all.add(new ScimQuery.Hit(ScimResourceType.GROUP, group.id())));
        }
        return pageOf(query, all);
    }

    /**
     * The administrative query: refused when it would need a connector, as the adapter refuses it;
     * otherwise the unfiltered query, or one resource type filtered by one of the two filters an
     * administrative read builds — the Users who are direct members of one Group
     * ({@link ScimFilter#directMemberOf}), an equality of ids that no SQL Server rule could answer
     * differently, or the resources whose name contains a text ({@link ScimFilter#nameContains}).
     */
    @Override
    public ScimQuery.Result query(ScimQuery query) {
        if (query.needsConnector()) {
            throw new ConnectorScopedQueryException();
        }
        if (query.filter() == null) {
            return query(query, null, null);
        }
        if (query.sort() != null || query.types().size() != 1) {
            throw new UnsupportedOperationException(
                    "only one type's administrative filters are evaluated here");
        }
        ScimResourceType type = query.types().iterator().next();
        queries++;
        List<ScimQuery.Hit> all = type == ScimResourceType.USER
                ? users.inQueryOrder().stream()
                        .filter(userMatcher(query.filter()))
                        .map(user -> new ScimQuery.Hit(ScimResourceType.USER, user.id()))
                        .toList()
                : groups.inQueryOrder().stream()
                        .filter(group -> contains(group.displayName(),
                                searchedText(ScimResourceType.GROUP, query.filter())))
                        .map(group -> new ScimQuery.Hit(ScimResourceType.GROUP, group.id()))
                        .toList();
        return pageOf(query, all);
    }

    private Predicate<ScimUser> userMatcher(ScimFilter filter) {
        if (filter instanceof ScimFilter.Comparison membership
                && membership.attribute().path() == ScimFilterPath.GROUPS_VALUE
                && membership.operator() == ScimFilter.Operator.EQ) {
            UUID groupId = UUID.fromString((String) membership.value());
            return user -> groups.findGroupsOfUser(user.id()).stream()
                    .anyMatch(group -> group.id().equals(groupId));
        }
        String text = searchedText(ScimResourceType.USER, filter);
        return user -> contains(user.profile().userName(), text)
                || contains(user.profile().displayName(), text);
    }

    /**
     * The text a name search over {@code type} looks for — recognised by being exactly the filter
     * {@link ScimFilter#nameContains} builds for it — or a refusal of any other filter.
     */
    private static String searchedText(ScimResourceType type, ScimFilter filter) {
        ScimFilter first = filter instanceof ScimFilter.Or or ? or.left() : filter;
        if (first instanceof ScimFilter.Comparison comparison
                && comparison.value() instanceof String text
                && filter.equals(ScimFilter.nameContains(type, text))) {
            return text;
        }
        throw new UnsupportedOperationException(
                "only the administrative filters are evaluated here");
    }

    private static boolean contains(String name, String text) {
        return name != null
                && name.toLowerCase(Locale.ROOT).contains(text.toLowerCase(Locale.ROOT));
    }

    private static ScimQuery.Result pageOf(ScimQuery query, List<ScimQuery.Hit> all) {
        return new ScimQuery.Result(all.size(), all.stream()
                .skip(query.page().offset())
                .limit(query.page().count())
                .toList());
    }
}
