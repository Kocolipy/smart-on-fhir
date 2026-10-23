package com.example.backend.scim.application;

import com.example.backend.audit.domain.AuditTrail;
import com.example.backend.audit.domain.Audited;
import com.example.backend.scim.domain.AuthenticatedConnector;
import com.example.backend.scim.domain.ScimQuery;
import com.example.backend.scim.domain.ScimQueryRepository;
import com.example.backend.scim.domain.ScimResourceType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The base {@code POST /.search}: one query over Users and Groups together.
 *
 * <p>Its own use case rather than a branch in either resource's service, because it belongs to
 * neither: the order interleaves the two types, the total counts both, and the audit event is
 * one bulk read spanning them. The resources themselves are still assembled by the service that
 * owns each type, so a User in a base search is exactly the User {@code GET /Users} renders.
 */
@Service
public class ScimSearchService {

    private final ScimQueryRepository queries;
    private final ScimUserService users;
    private final ScimGroupService groups;
    private final AuditTrail audit;

    public ScimSearchService(
            ScimQueryRepository queries,
            ScimUserService users,
            ScimGroupService groups,
            AuditTrail audit) {
        this.queries = queries;
        this.users = users;
        this.groups = groups;
        this.audit = audit;
    }

    /**
     * The page of Users and Groups the query selects, in its order, and the total over both —
     * narrowed to the types the connector's token may read (ADR 0015). A {@code user:read}-only
     * token gets Users and no Groups: its answer is the one a directory holding no Groups would
     * give, not a refusal, so a partially permitted connector still gets a conformant response.
     * The authorization rule refuses a token that may read neither before this runs.
     *
     * <p>Audited as exactly one bulk read, on the terms a single-type query is.
     */
    @Transactional
    public ScimSearchListing search(
            AuthenticatedConnector connector, ScimQuery query, String baseUri) {
        ScimQuery readable = query.restrictedTo(connector.permissions().readableTypes());
        ScimQuery.Result result = queries.query(readable, connector.connectorId(), baseUri);
        Map<UUID, ScimListedResource> byId = new HashMap<>();
        users.resources(connector, result.idsOf(ScimResourceType.USER))
                .forEach(user -> byId.put(user.id(), user));
        groups.resources(connector, result.idsOf(ScimResourceType.GROUP))
                .forEach(group -> byId.put(group.id(), group));
        List<ScimListedResource> ordered = new ArrayList<>();
        for (ScimQuery.Hit hit : result.hits()) {
            ScimListedResource resource = byId.get(hit.id());
            if (resource != null) {
                ordered.add(resource);
            }
        }
        audit.append(new Audited.ScimResourcesQueried(
                connector.connectorId(), ordered.size(), ScimAuditFilterShapes.of(query.filter())));
        return new ScimSearchListing(ordered, result.totalResults(), query.page());
    }
}
