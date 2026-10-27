package com.example.backend.scim.domain;

/**
 * A collection query naming {@code externalId}, a {@code $ref} or {@code meta.location} was asked
 * by a caller that is no connector ({@link AdministrativeQueryRepository}). Those values exist
 * only for a connector that called a SCIM base URI, so the query is refused rather than evaluated
 * against nothing — which would answer as though no resource had them.
 *
 * <p>A defect in the caller, never a refusal of a request: an administrative read builds its
 * query as a value, and no request text reaches one.
 */
public class ConnectorScopedQueryException extends RuntimeException {

    public ConnectorScopedQueryException() {
        super("a query naming externalId, a $ref or meta.location needs a connector");
    }
}
