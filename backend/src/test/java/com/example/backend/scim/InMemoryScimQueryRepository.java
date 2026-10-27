package com.example.backend.scim;

import com.example.backend.scim.domain.AdministrativeQueryRepository;
import com.example.backend.scim.domain.ConnectorScopedQueryException;
import com.example.backend.scim.domain.ScimFilter;
import com.example.backend.scim.domain.ScimFilterPath;
import com.example.backend.scim.domain.ScimQuery;
import com.example.backend.scim.domain.ScimQueryRepository;
import com.example.backend.scim.domain.ScimResourceType;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A {@link ScimQueryRepository} over the in-memory User and Group repositories, for unit tests
 * of the use cases.
 *
 * <p>Evaluates only the unfiltered, unsorted query — the default order and the page — and, for an
 * administrative caller, the direct-membership filter. Other filtering
 * and sorting are the SQL translation's job and are tested against SQL Server, so this fake
 * refuses them rather than carrying a second evaluator that could agree with a unit test and
 * disagree with the database — over where a missing value sorts, or over SQL Server's
 * trailing-space-insensitive equality on free text, which no evaluator here has to imitate.
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
            users.findAllOrderedByNormalizedUserName()
                    .forEach(user -> all.add(new ScimQuery.Hit(ScimResourceType.USER, user.id())));
        }
        if (query.types().contains(ScimResourceType.GROUP)) {
            groups.findAllOrderedByNormalizedDisplayName()
                    .forEach(group -> all.add(new ScimQuery.Hit(ScimResourceType.GROUP, group.id())));
        }
        List<ScimQuery.Hit> page = all.stream()
                .skip(query.page().offset())
                .limit(query.page().count())
                .toList();
        return new ScimQuery.Result(all.size(), page);
    }

    /**
     * The administrative query: refused when it would need a connector, as the adapter refuses it;
     * otherwise the unfiltered query, or the Users who are direct members of one Group — the one
     * filter an administrative read builds, as a value ({@link ScimFilter#directMemberOf}), and
     * an equality of ids that no SQL Server rule could answer differently.
     */
    @Override
    public ScimQuery.Result query(ScimQuery query) {
        if (query.needsConnector()) {
            throw new ConnectorScopedQueryException();
        }
        if (query.filter() == null) {
            return query(query, null, null);
        }
        if (query.sort() != null || !query.types().equals(Set.of(ScimResourceType.USER))
                || !(query.filter() instanceof ScimFilter.Comparison membership)
                || membership.attribute().path() != ScimFilterPath.GROUPS_VALUE
                || membership.operator() != ScimFilter.Operator.EQ) {
            throw new UnsupportedOperationException(
                    "only the direct-membership filter is evaluated here");
        }
        queries++;
        UUID groupId = UUID.fromString((String) membership.value());
        List<ScimQuery.Hit> all = users.findAllOrderedByNormalizedUserName().stream()
                .filter(user -> groups.findGroupsOfUser(user.id()).stream()
                        .anyMatch(group -> group.id().equals(groupId)))
                .map(user -> new ScimQuery.Hit(ScimResourceType.USER, user.id()))
                .toList();
        return new ScimQuery.Result(all.size(), all.stream()
                .skip(query.page().offset())
                .limit(query.page().count())
                .toList());
    }
}
