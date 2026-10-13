package com.example.backend.scim.domain;

/**
 * A submitted key — {@code userName}, a User's or a Group's {@code displayName}, an email's
 * {@code value} or {@code type}, or an {@code externalId} — begins or ends with whitespace, as
 * {@link EdgeWhitespace} defines it.
 *
 * <p>Refused rather than trimmed, because a trim would store something other than what the
 * connector sent, and the connector would then hold a key this service does not (ADR 0015).
 * Rendered as {@code 400 invalidValue} naming the attribute; the value never reaches this object.
 */
public final class ScimValueEdgeWhitespaceException extends ScimAttributeValueException {

    public ScimValueEdgeWhitespaceException(String attribute) {
        super(attribute, attribute + " must not begin or end with whitespace.");
    }
}
