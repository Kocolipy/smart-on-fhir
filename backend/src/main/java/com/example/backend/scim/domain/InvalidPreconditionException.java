package com.example.backend.scim.domain;

/**
 * The {@code If-Match} precondition was not exactly one entity tag — a wildcard, a list, a
 * repeated header, or a value that is not an entity tag.
 *
 * <p>A wildcard is refused rather than honoured because it would switch the check off, and a
 * list because "any of these versions" is not the exact match this profile requires. Rendered
 * as {@code 400 invalidValue}. Carries nothing the caller sent.
 */
public final class InvalidPreconditionException extends ScimRequestRefusedException {

    public InvalidPreconditionException() {
        super("an If-Match precondition must be exactly one strong entity tag");
    }
}
