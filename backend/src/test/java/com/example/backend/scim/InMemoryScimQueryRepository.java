package com.example.backend.scim;

import com.example.backend.scim.domain.ScimQuery;
import com.example.backend.scim.domain.ScimQueryRepository;
import com.example.backend.scim.domain.ScimResourceType;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A {@link ScimQueryRepository} over the in-memory User and Group repositories, for unit tests
 * of the use cases.
 *
 * <p>Evaluates only the unfiltered, unsorted query — the default order and the page. Filtering
 * and sorting are the SQL translation's job and are tested against SQL Server, so this fake
 * refuses them rather than carrying a second evaluator that could agree with a unit test and
 * disagree with the database — over where a missing value sorts, or over SQL Server's
 * trailing-space-insensitive equality on free text, which no evaluator here has to imitate.
 */
public final class InMemoryScimQueryRepository implements ScimQueryRepository {

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
}
