package com.example.backend.scim.domain;

/**
 * Persistence port for the directory's collection query asked by a caller that is no connector
 * and has no SCIM base URI: an administrative read, such as a Group's direct members a page at a
 * time.
 *
 * <p>The same evaluation as {@link ScimQueryRepository}'s — the filter, the default order, the
 * page and the total, answered with identities — and the same adapter. A port of its own rather
 * than a second method on that one, so that what a connector's query needs, its connector and its
 * base URI, is never a parameter an administrative caller could pass nothing for: here there is
 * none to pass, and a query that would need one is refused.
 *
 * <p>An implementation must bind every literal the filter carries as a statement parameter, as
 * {@link ScimQueryRepository}'s must.
 */
public interface AdministrativeQueryRepository {

    /**
     * The query's total and its page.
     *
     * @throws ConnectorScopedQueryException when the query
     *                                       {@linkplain ScimQuery#needsConnector() needs a
     *                                       connector}: it names {@code externalId}, a
     *                                       {@code $ref} or {@code meta.location}, which have no
     *                                       value here; nothing is run for it
     */
    ScimQuery.Result query(ScimQuery query);
}
