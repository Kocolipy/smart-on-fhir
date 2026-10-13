package com.example.backend.scim.domain;

/**
 * A query parameter other than the filter that names something this service cannot honour: a
 * {@code sortBy} that is not a sortable attribute, a {@code sortOrder} that is neither
 * direction, a paging value that is not an integer, or a search request whose body is not a
 * {@code SearchRequest}.
 *
 * <p>SCIM's {@code 400 invalidValue}. Like {@link InvalidScimFilterException} it never quotes
 * what the client sent.
 */
public final class InvalidScimQueryException extends ScimRequestRefusedException {

    public InvalidScimQueryException(String message) {
        super(message);
    }
}
