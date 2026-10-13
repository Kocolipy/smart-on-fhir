package com.example.backend.scim.domain;

/**
 * A submitted attribute value that its column cannot hold, refused before persistence by
 * {@link ScimAttributeLimits} rather than left to the database.
 *
 * <p>Every kind is the caller's mistake and is rendered as {@code 400 invalidValue} naming the
 * attribute, and audited as {@code INVALID_VALUE}. It carries the attribute path and the rule and
 * nothing else: the value never reaches this object, so it cannot reach its message, a log line or
 * a response built from it.
 */
public abstract sealed class ScimAttributeValueException extends ScimRequestRefusedException
        permits ScimValueTooLongException, ScimValueControlCharacterException,
                ScimValueEdgeWhitespaceException {

    private final String attribute;

    protected ScimAttributeValueException(String attribute, String message) {
        super(message);
        this.attribute = attribute;
    }

    /** The SCIM attribute path the value was submitted for, e.g. {@code emails.type}. */
    public String attribute() {
        return attribute;
    }
}
