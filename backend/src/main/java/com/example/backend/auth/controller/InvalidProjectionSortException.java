package com.example.backend.auth.controller;

import com.example.backend.scim.domain.InvalidScimQueryException;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * A projection listing's {@code sortBy} or {@code sortOrder} the directory's SCIM sort refuses:
 * the caller's error, answered as a malformed {@code startIndex} is — the application API's
 * generic {@code 400}, with its one refusal record (ADR 0017, D5).
 *
 * <p>The refusal travels as the cause, so the record names {@link InvalidScimQueryException} as
 * the reason; it carries no message of its own and nothing the caller sent.
 */
final class InvalidProjectionSortException extends ResponseStatusException {

    private InvalidProjectionSortException(InvalidScimQueryException refused) {
        super(HttpStatus.BAD_REQUEST, null, refused);
    }

    /**
     * The page {@code listing} reads, or this exception in place of the refusal when the SCIM sort
     * refuses the listing's sort: the one translation both projections' listings share.
     */
    static <T> T translating(Supplier<T> listing) {
        try {
            return listing.get();
        } catch (InvalidScimQueryException refused) {
            throw new InvalidProjectionSortException(refused);
        }
    }
}
